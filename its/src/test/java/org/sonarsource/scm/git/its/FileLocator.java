/*
 * Git Plugin Integration Tests
 * Copyright (C) 2014-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scm.git.its;

import com.google.common.collect.Iterables;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.CheckForNull;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import static java.lang.String.format;
import static org.apache.commons.io.filefilter.FileFilterUtils.and;
import static org.apache.commons.io.filefilter.FileFilterUtils.notFileFilter;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class FileLocator {

  private final List<FileReference> references = new ArrayList<>();

  public FileLocator by(FileReference ref) {
    references.add(ref);
    return this;
  }

  public FileLocator bySystemProperty(String systemPropertyKey) {
    return by(new SystemPropertyRef(systemPropertyKey));
  }

  public FileLocator byEnvVariable(String varKey) {
    return by(new EnvVariableRef(varKey));
  }

  public FileLocator byPath(Path path) {
    return by(new PathRef(path));
  }

  public FileLocator byWildcardFilename(Path dir, String wildcardFilename) {
    return by(new WildcardFilenameRef(dir, wildcardFilename));
  }

  /**
   * Same as {@link #byWildcardFilename(Path, String)} except that some Maven files
   * are excluded from search:
   * <ul>
   *   <li>files matching *-sources.jar</li>
   *   <li>files matching *-tests.jar</li>
   * </ul>
   * Example: {@code byWildcardMavenArtifactFilename(Paths.get("../target"), "sonar-scm-git-plugin-*.jar")}
   */
  public FileLocator byWildcardMavenArtifactFilename(Path dir, String wildcardFilename) {
    return by(new WildcardMavenArtifactFilenameRef(dir, wildcardFilename));
  }

  public File get() {
    for (FileReference reference : references) {
      File file = reference.get();
      if (file != null && file.exists()) {
        return file;
      }
    }
    throw new IllegalArgumentException(format("File not found, check %s", references));
  }

  public interface FileReference {
    @CheckForNull
    File get();
  }

  private static class SystemPropertyRef implements FileReference {

    private final String systemPropertyKey;

    private SystemPropertyRef(String systemPropertyKey) {
      this.systemPropertyKey = systemPropertyKey;
    }

    @Override
    public File get() {
      String val = System.getProperty(systemPropertyKey);
      if (isNotBlank(val)) {
        return new File(val);
      }
      return null;
    }

    @Override
    public String toString() {
      return format("system property [%s]", systemPropertyKey);
    }
  }

  private static class EnvVariableRef implements FileReference {

    private final String variableKey;

    private EnvVariableRef(String variableKey) {
      this.variableKey = variableKey;
    }

    @Override
    public File get() {
      String val = System.getenv(variableKey);
      if (isNotBlank(val)) {
        return new File(val);
      }
      return null;
    }

    @Override
    public String toString() {
      return format("env variable [%s]", variableKey);
    }
  }

  private static class PathRef implements FileReference {

    private final Path path;

    private PathRef(Path path) {
      this.path = path;
    }

    @Override
    public File get() {
      if (Files.exists(path)) {
        return path.toFile();
      }
      return null;
    }

    @Override
    public String toString() {
      return format("path [%s]", path);
    }
  }

  private static class WildcardFilenameRef implements FileReference {

    private final Path dir;
    private final String filename;

    private WildcardFilenameRef(Path dir, String filename) {
      this.dir = dir;
      this.filename = filename;
    }

    @Override
    public File get() {
      if (Files.exists(dir)) {
        WildcardFileFilter filter = new WildcardFileFilter(filename, IOCase.SENSITIVE);
        Collection<File> files = new ArrayList<>(FileUtils.listFiles(dir.toFile(), filter, null));
        return Iterables.getOnlyElement(files, null);
      }
      return null;
    }

    @Override
    public String toString() {
      return format("file matching name [%s] in dir [%s]", filename, dir);
    }
  }

  private static class WildcardMavenArtifactFilenameRef implements FileReference {

    private final Path dir;
    private final String filename;

    private WildcardMavenArtifactFilenameRef(Path dir, String filename) {
      this.dir = dir;
      this.filename = filename;
    }

    @Override
    public File get() {
      if (Files.exists(dir)) {
        IOFileFilter artifactFilter = new WildcardFileFilter(filename, IOCase.SENSITIVE);
        IOFileFilter sourcesFilter = notFileFilter(new WildcardFileFilter("*-sources.jar"));
        IOFileFilter testsFilter = notFileFilter(new WildcardFileFilter("*-tests.jar"));
        IOFileFilter filters = and(artifactFilter, sourcesFilter, testsFilter);
        Collection<File> files = new ArrayList<>(FileUtils.listFiles(dir.toFile(), filters, null));
        return Iterables.getOnlyElement(files, null);
      }
      return null;
    }

    @Override
    public String toString() {
      return format("maven artifact matching name [%s] in dir [%s]", filename, dir);
    }
  }

}
