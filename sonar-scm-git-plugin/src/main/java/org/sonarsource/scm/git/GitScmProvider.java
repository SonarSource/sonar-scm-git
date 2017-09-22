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
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.ScmProvider;
import org.sonar.api.utils.MessageException;

public class GitScmProvider extends ScmProvider {

  private static final Logger LOG = LoggerFactory.getLogger(GitScmProvider.class);

  private final JGitBlameCommand jgitBlameCommand;

  public GitScmProvider(JGitBlameCommand jgitBlameCommand) {
    this.jgitBlameCommand = jgitBlameCommand;
  }

  @Override
  public String key() {
    return "git";
  }

  @Override
  public boolean supports(File baseDir) {
    RepositoryBuilder builder = new RepositoryBuilder().findGitDir(baseDir);
    return builder.getGitDir() != null;
  }

  @Override
  public BlameCommand blameCommand() {
    return this.jgitBlameCommand;
  }

  @Nullable
  @Override
  public Set<Path> branchChangedFiles(String targetBranchName, Path rootBaseDir) {
    try (Repository repo = getVerifiedRepositoryBuilder(rootBaseDir).build()) {
      Ref targetRef = repo.exactRef("refs/heads/" + targetBranchName);
      if (targetRef == null) {
        LOG.warn("Could not find ref: {}", targetBranchName);
        return null;
      }

      try (Git git = new Git(repo)) {
        return git.diff().setShowNameAndStatusOnly(true).setOldTree(prepareTreeParser(repo, targetRef)).call().stream()
          .filter(diffEntry -> diffEntry.getChangeType() == DiffEntry.ChangeType.ADD || diffEntry.getChangeType() == DiffEntry.ChangeType.MODIFY)
          .map(diffEntry -> repo.getWorkTree().toPath().resolve(diffEntry.getNewPath()))
          .collect(Collectors.toSet());
      }
    } catch (IOException | GitAPIException e) {
      LOG.warn(e.getMessage(), e);
    }
    return null;
  }

  private static AbstractTreeIterator prepareTreeParser(Repository repo, Ref targetRef) throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      walk.markStart(walk.parseCommit(targetRef.getObjectId()));
      walk.markStart(walk.parseCommit(repo.exactRef("HEAD").getObjectId()));
      walk.setRevFilter(RevFilter.MERGE_BASE);
      RevCommit base = walk.parseCommit(walk.next());

      CanonicalTreeParser treeParser = new CanonicalTreeParser();
      try (ObjectReader objectReader = repo.newObjectReader()) {
        treeParser.reset(objectReader, base.getTree());
      }

      walk.dispose();

      return treeParser;
    }
  }

  public static RepositoryBuilder getVerifiedRepositoryBuilder(Path basedir) {
    RepositoryBuilder builder = new RepositoryBuilder()
      .findGitDir(basedir.toFile())
      .setMustExist(true);

    if (builder.getGitDir() == null) {
      throw MessageException.of("Not inside a Git work tree: " + basedir);
    }
    return builder;
  }
}
