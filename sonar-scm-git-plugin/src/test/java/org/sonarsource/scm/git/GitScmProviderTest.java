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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.MessageException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonarsource.scm.git.JGitBlameCommandTest.javaUnzip;

public class GitScmProviderTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static final Random random = new Random();

  private Path worktree;
  private Repository repo;
  private Git git;

  @Before
  public void before() throws IOException, GitAPIException {
    worktree = temp.newFolder().toPath();
    repo = FileRepositoryBuilder.create(worktree.resolve(".git").toFile());
    repo.create();

    git = new Git(repo);

    createAndCommitFile(worktree, "file-in-first-commit.xoo");
  }

  @Test
  public void sanityCheck() {
    assertThat(new GitScmProvider(mock(JGitBlameCommand.class)).key()).isEqualTo("git");
  }

  @Test
  public void returnImplem() {
    JGitBlameCommand jblameCommand = new JGitBlameCommand(new PathResolver());
    GitScmProvider gitScmProvider = new GitScmProvider(jblameCommand);

    assertThat(gitScmProvider.blameCommand()).isEqualTo(jblameCommand);
  }

  @Test
  public void testAutodetection() throws IOException {
    File baseDirEmpty = temp.newFolder();
    assertThat(new GitScmProvider(mock(JGitBlameCommand.class)).supports(baseDirEmpty)).isFalse();

    File projectDir = temp.newFolder();
    javaUnzip(new File("test-repos/dummy-git.zip"), projectDir);
    File baseDir = new File(projectDir, "dummy-git");
    assertThat(newScmProvider().supports(baseDir)).isTrue();
  }

  private static JGitBlameCommand mockCommand() {
    return mock(JGitBlameCommand.class);
  }

  @Test
  public void branchChangedFiles_from_diverged() throws IOException, GitAPIException {
    createAndCommitFile(worktree, "file-m1.xoo");
    createAndCommitFile(worktree, "file-m2.xoo");
    createAndCommitFile(worktree, "file-m3.xoo");
    ObjectId forkPoint = git.getRepository().exactRef("HEAD").getObjectId();

    appendToAndCommitFile(worktree, "file-m3.xoo");
    createAndCommitFile(worktree, "file-m4.xoo");

    git.branchCreate().setName("b1").setStartPoint(forkPoint.getName()).call();
    git.checkout().setName("b1").call();
    createAndCommitFile(worktree, "file-b1.xoo");
    appendToAndCommitFile(worktree, "file-m1.xoo");
    deleteAndCommitFile("file-m2.xoo");

    assertThat(newScmProvider().branchChangedFiles("master", worktree))
      .containsExactlyInAnyOrder(
        worktree.resolve("file-b1.xoo"),
        worktree.resolve("file-m1.xoo"));
  }

  @Test
  public void branchChangedFiles_from_merged_and_diverged() throws IOException, GitAPIException {
    createAndCommitFile(worktree, "file-m1.xoo");
    createAndCommitFile(worktree, "file-m2.xoo");
    ObjectId forkPoint = git.getRepository().exactRef("HEAD").getObjectId();

    createAndCommitFile(worktree, "file-m3.xoo");
    ObjectId mergePoint = git.getRepository().exactRef("HEAD").getObjectId();

    appendToAndCommitFile(worktree, "file-m3.xoo");
    createAndCommitFile(worktree, "file-m4.xoo");

    git.branchCreate().setName("b1").setStartPoint(forkPoint.getName()).call();
    git.checkout().setName("b1").call();
    createAndCommitFile(worktree, "file-b1.xoo");
    appendToAndCommitFile(worktree, "file-m1.xoo");
    deleteAndCommitFile("file-m2.xoo");

    git.merge().include(mergePoint).call();
    createAndCommitFile(worktree, "file-b2.xoo");

    assertThat(newScmProvider().branchChangedFiles("master", worktree))
      .containsExactlyInAnyOrder(
        worktree.resolve("file-m1.xoo"),
        worktree.resolve("file-b1.xoo"),
        worktree.resolve("file-b2.xoo"));
  }

  @Test
  public void branchChangedFiles_when_git_work_tree_is_above_project_basedir() throws IOException, GitAPIException {
    Path projectDir = worktree.resolve("project");
    Files.createDirectory(projectDir);

    git.branchCreate().setName("b1").call();
    git.checkout().setName("b1").call();
    createAndCommitFile(projectDir, "file-b1");

    assertThat(newScmProvider().branchChangedFiles("master", projectDir))
      .containsOnly(projectDir.resolve("file-b1"));
  }

  @Test
  public void branchChangedFiles_should_return_null_when_branch_nonexistent() {
    assertThat(newScmProvider().branchChangedFiles("nonexistent", worktree)).isNull();
  }

  @Test
  public void branchChangedFiles_should_throw_when_repo_nonexistent() throws IOException {
    thrown.expect(MessageException.class);
    thrown.expectMessage("Not inside a Git work tree: ");
    newScmProvider().branchChangedFiles("master", temp.newFolder().toPath());
  }

  @Test
  public void branchChangedFiles_should_throw_when_dir_nonexistent() throws IOException {
    thrown.expect(MessageException.class);
    thrown.expectMessage("Not inside a Git work tree: ");
    newScmProvider().branchChangedFiles("master", temp.getRoot().toPath().resolve("nonexistent"));
  }

  @Test
  public void branchChangedFiles_should_return_null_on_io_errors_of_repo_builder() throws IOException {
    GitScmProvider provider = new GitScmProvider(mockCommand()) {
      @Override
      Repository buildRepo(Path basedir) throws IOException {
        throw new IOException();
      }
    };
    assertThat(provider.branchChangedFiles("branch", worktree)).isNull();
  }

  @Test
  public void branchChangedFiles_should_return_null_on_io_errors_of_repo_exactref() throws IOException {
    GitScmProvider provider = new GitScmProvider(mockCommand()) {
      @Override
      Repository buildRepo(Path basedir) throws IOException {
        return mock(Repository.class);
      }
    };
    assertThat(provider.branchChangedFiles("branch", worktree)).isNull();
  }

  @Test
  public void branchChangedFiles_should_return_null_on_io_errors_of_RevWalk() throws IOException {
    RevWalk walk = mock(RevWalk.class);
    when(walk.parseCommit(any())).thenThrow(new IOException());

    GitScmProvider provider = new GitScmProvider(mockCommand()) {
      @Override
      RevWalk newRevWalk(Repository repo) {
        return walk;
      }
    };
    assertThat(provider.branchChangedFiles("branch", worktree)).isNull();
  }

  private String randomizedContent(String prefix) {
    StringBuilder sb = new StringBuilder(prefix);
    for (int i = 0; i < 4; i++) {
      sb.append(' ');
      for (int j = 0; j < prefix.length(); j++) {
        sb.append((char)('a' + random.nextInt(26)));
      }
    }
    return sb.append("\n").toString();
  }

  private void createAndCommitFile(Path worktree, String filename) throws IOException, GitAPIException {
    Path newFile = worktree.resolve(filename);
    Files.write(newFile, randomizedContent(filename).getBytes(), StandardOpenOption.CREATE_NEW);
    commit(filename);
  }

  private void appendToAndCommitFile(Path worktree, String filename) throws IOException, GitAPIException {
    Files.write(worktree.resolve(filename), randomizedContent(filename).getBytes(), StandardOpenOption.APPEND);
    commit(filename);
  }

  private void deleteAndCommitFile(String filename) throws IOException, GitAPIException {
    git.rm().addFilepattern(filename).call();
    commit(filename);
  }

  private void commit(String filename) throws GitAPIException {
    git.add().addFilepattern(filename).call();
    git.commit().setAuthor("joe", "joe@example.com").setMessage(filename).call();
  }

  private GitScmProvider newScmProvider() {
    return new GitScmProvider(mockCommand());
  }
}
