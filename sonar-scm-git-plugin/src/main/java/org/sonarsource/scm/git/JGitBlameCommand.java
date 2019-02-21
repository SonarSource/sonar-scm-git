/*
 * SonarQube :: Plugins :: SCM :: Git
 * Copyright (C) 2014-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
package org.sonarsource.scm.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class JGitBlameCommand extends BlameCommand {

  private static final Logger LOG = Loggers.get(JGitBlameCommand.class);

  private final PathResolver pathResolver;
  private final AnalysisWarningsWrapper analysisWarnings;
  private static HashMap<File, List<Submodule>> submodules;

  static {
    submodules = new HashMap<>();
  }

  public JGitBlameCommand(PathResolver pathResolver, AnalysisWarningsWrapper analysisWarnings) {
    this.pathResolver = pathResolver;
    this.analysisWarnings = analysisWarnings;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    File basedir = input.fileSystem().baseDir();
    try (Repository repo = buildRepository(basedir); Git git = Git.wrap(repo)) {
      File gitBaseDir = repo.getWorkTree();
      if (!submodules.containsKey(gitBaseDir)) {
        List<Submodule> submoduleList = new ArrayList<>();
        submodules.put(gitBaseDir, submoduleList);
        Path gitmodulesPath = Paths.get(basedir.getPath() + "/.gitmodules");
        if (Files.exists(gitmodulesPath)) {
          try {
            List<String> lines = Files.readAllLines(gitmodulesPath);
            final String pathToken = "path = ";
            final int pathTokenLength = pathToken.length();
            for (int i = 0; i < lines.size(); i++) {
              if (lines.get(i).contains("[submodule")) {
                String pathLine = lines.get(i + 1);
                String submodulePath = pathLine.substring(pathLine.indexOf(pathToken) + pathTokenLength);
                File subBaseDir = new File(basedir.getAbsolutePath() + "/" +submodulePath);
                Repository subRepo = buildRepository(subBaseDir);
                Git subGit = Git.wrap(subRepo);
                submoduleList.add(new Submodule(subGit, subBaseDir, submodulePath));
                i = i + 2;
              }
            }
          }
          catch (IOException e) {
            LOG.info("Could not read .gitmodules file");
          }
        }
      }
      if (Files.isRegularFile(gitBaseDir.toPath().resolve(".git/shallow"))) {
        LOG.warn("Shallow clone detected, no blame information will be provided. "
                + "You can convert to non-shallow with 'git fetch --unshallow'.");
        analysisWarnings.addUnique("Shallow clone detected during the analysis. "
                + "Some files will miss SCM information. This will affect features like auto-assignment of issues. "
                + "Please configure your build to disable shallow clone.");
        return;
      }
      Stream<InputFile> stream = StreamSupport.stream(input.filesToBlame().spliterator(), true);
      ForkJoinPool forkJoinPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors(), new GitThreadFactory(), null, false);
      forkJoinPool.submit(() -> stream.forEach(inputFile -> blame(output, git, gitBaseDir, inputFile)));
      try {
        forkJoinPool.shutdown();
        forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        LOG.info("Git blame interrupted");
      }
    }
  }

  private static Repository buildRepository(File basedir) {
    try {
      Repository repo = GitScmProvider.getVerifiedRepositoryBuilder(basedir.toPath()).build();
      // SONARSCGIT-2 Force initialization of shallow commits to avoid later concurrent modification issue
      repo.getObjectDatabase().newReader().getShallowCommits();
      return repo;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to open Git repository", e);
    }
  }

  private void blame(BlameOutput output, Git git, File gitBaseDir, InputFile inputFile) {
    String filename = pathResolver.relativePath(gitBaseDir, inputFile.file());
    LOG.debug("Blame file {}", filename);
    BlameResult blameResult;
    try {
      blameResult = git.blame()
              // Equivalent to -w command line option
              .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
              .setFilePath(filename).call();
    } catch (Exception e) {
      throw new IllegalStateException("Unable to blame file " + inputFile.relativePath(), e);
    }
    List<BlameLine> lines = new ArrayList<>();
    if (blameResult == null) {
      LOG.debug("Unable to blame file {}. It is probably a symlink.", inputFile.relativePath());
      return;
    }

    boolean noBlameInfo = (blameResult.getResultContents().size() > 0
            && (blameResult.getSourceAuthor(0) == null || blameResult.getSourceCommit(0) == null));

    if (noBlameInfo && submodules.get(gitBaseDir).size() > 0) {
      for (Submodule sub : submodules.get(gitBaseDir)) {
        if (filename.contains(sub.path)) {
          String subFilename = pathResolver.relativePath(sub.baseDir, inputFile.file());
          try {
            blameResult = sub.git.blame()
                    // Equivalent to -w command line option
                    .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
                    .setFilePath(subFilename).call();
          } catch (Exception e2) {
            throw new IllegalStateException("Unable to blame file " + inputFile.relativePath(), e2);
          }
        }
      }

      if (blameResult == null) {
        LOG.debug("Unable to blame file {}. It is probably a symlink.", inputFile.relativePath());
        return;
      }
    }

    for (int i = 0; i < blameResult.getResultContents().size(); i++) {
      if (blameResult.getSourceAuthor(i) == null || blameResult.getSourceCommit(i) == null) {
        LOG.debug("Unable to blame file {}. No blame info at line {}. Is file committed? [Author: {} Source commit: {}]", inputFile.relativePath(), i + 1,
                blameResult.getSourceAuthor(i), blameResult.getSourceCommit(i));
        return;
      }
      lines.add(new BlameLine()
              .date(blameResult.getSourceCommitter(i).getWhen())
              .revision(blameResult.getSourceCommit(i).getName())
              .author(blameResult.getSourceAuthor(i).getEmailAddress()));
    }
    if (lines.size() == inputFile.lines() - 1) {
      // SONARPLUGINS-3097 Git do not report blame on last empty line
      lines.add(lines.get(lines.size() - 1));
    }
    output.blameResult(inputFile, lines);
  }

  class Submodule {
    public Git git;
    public File baseDir;
    public String path;

    public Submodule(Git git, File baseDir, String path) {
      this.git = git;
      this.baseDir = baseDir;
      this.path = path;
    }
  }

}