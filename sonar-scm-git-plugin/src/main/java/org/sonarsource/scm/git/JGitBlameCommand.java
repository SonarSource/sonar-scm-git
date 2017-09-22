/*
 * SonarQube :: Plugins :: SCM :: Git
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
package org.sonarsource.scm.git;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameGenerator;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.MessageException;

public class JGitBlameCommand extends BlameCommand {

  private static final Logger LOG = LoggerFactory.getLogger(JGitBlameCommand.class);

  private final PathResolver pathResolver;

  public JGitBlameCommand(PathResolver pathResolver) {
    this.pathResolver = pathResolver;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    File basedir = input.fileSystem().baseDir();
    try (Repository repo = buildRepository(basedir); Git git = Git.wrap(repo)) {
      File gitBaseDir = repo.getWorkTree();
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
    RepositoryBuilder repoBuilder = new RepositoryBuilder()
      .findGitDir(basedir)
      .setMustExist(true);
    if (repoBuilder.getGitDir() == null) {
      throw MessageException.of(basedir + " doesn't seem to be contained in a Git repository");
    }
    try {
      Repository repo = repoBuilder.build();
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

    try (BlameGenerator gen = BlameGeneratorFactory.newBlameGenerator(filename, git.getRepository())) {
      RawText contents = gen.getResultContents();
      if (contents == null) {
        gen.close();
        LOG.debug("Unable to blame file {}. It is probably a symlink.", inputFile.relativePath());
        return;
      }
      List<BlameLine> lines = getBlameLines(inputFile, gen);
      output.blameResult(inputFile, lines);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to blame file " + inputFile.relativePath(), e);
    }
  }

  @Nonnull
  private List<BlameLine> getBlameLines(InputFile inputFile, BlameGenerator gen) throws IOException {
    int size = gen.getResultContents().size();

    // SONARPLUGINS-3097 Git do not report blame on last empty line
    boolean duplicateLastLine = size > 0 && (size == inputFile.lines() - 1);
    if (duplicateLastLine) {
      size++;
    }
    BlameLine[] lines = new BlameLine[size];

    while (gen.next()) {
      for (int i = gen.getResultStart(); i < gen.getResultEnd(); i++) {
        RevCommit sourceCommit = gen.getSourceCommit();
        PersonIdent sourceAuthor = gen.getSourceAuthor();
        if (sourceAuthor == null || sourceCommit == null) {
          LOG.debug("Unable to blame file {}. No blame info at line {}. Is file committed? [Author: {} Source commit: {}]", inputFile.relativePath(), i + 1,
                  gen.getSourceAuthor(), sourceCommit);
          return Collections.emptyList();
        }
        // BlameGenerator#next() does not process files from start to end, so lines may come in any order
        // and we need to store BlameLine by its index, not just add it to list
        lines[i] = new BlameLine()
                .date(gen.getSourceCommitter().getWhen())
                .revision(sourceCommit.getName())
                .author(gen.getSourceAuthor().getEmailAddress());
      }
    }

    if (duplicateLastLine) {
      lines[size - 1] = lines[size - 2];
    }

    return Arrays.asList(lines);
  }

}
