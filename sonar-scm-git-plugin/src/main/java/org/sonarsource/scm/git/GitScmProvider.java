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
import java.nio.file.Paths;
import java.util.Collection;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectReader;
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
import org.sonar.api.batch.scm.ScmBranchProvider;

public class GitScmProvider extends ScmBranchProvider {

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
  public Collection<Path> branchChangedFiles(String targetBranchName, Path rootBaseDir) {
    try {
      Repository repo = new RepositoryBuilder().findGitDir(rootBaseDir.toFile()).build();
      Git git = new Git(repo);
      String exactRef = "refs/heads/" + targetBranchName;
      return git.diff().setShowNameAndStatusOnly(true).setOldTree(prepareTreeParser(repo, exactRef)).call().stream()
        .map(diffEntry -> rootBaseDir.resolve(diffEntry.getNewPath()))
        .collect(Collectors.toList());
    } catch (IOException | GitAPIException e) {
      LOG.warn(e.getMessage(), e);
    }
    return null;
  }

  private static AbstractTreeIterator prepareTreeParser(Repository repo, String targetExactRef) throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      walk.markStart(walk.parseCommit(repo.exactRef(targetExactRef).getObjectId()));
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
}
