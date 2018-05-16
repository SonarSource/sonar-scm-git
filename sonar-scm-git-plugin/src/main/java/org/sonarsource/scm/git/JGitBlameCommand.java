/*
 * SonarQube :: Plugins :: SCM :: Git
 * Copyright (C) 2014-2018 SonarSource SA
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
import java.util.ArrayList;
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

  public JGitBlameCommand(PathResolver pathResolver) {
    this.pathResolver = pathResolver;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    File basedir = input.fileSystem().baseDir();
    try (Repository repo = buildRepository(basedir); Git git = Git.wrap(repo)) {
      File gitBaseDir = repo.getWorkTree();
      if (Files.isRegularFile(gitBaseDir.toPath().resolve(".git/shallow"))) {
        LOG.warn("Shallow clone detected, no blame information will be provided. "
          + "You can convert to non-shallow with 'git fetch --unshallow'.");
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

}
