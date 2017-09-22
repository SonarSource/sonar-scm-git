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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.MessageException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.sonarsource.scm.git.JGitBlameCommandTest.javaUnzip;

public class GitScmProviderTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private Path worktree;
  private Repository repo;
  private Git git;

  @Before
  public void before() throws IOException, GitAPIException {
    worktree = temp.newFolder().toPath();
    repo = FileRepositoryBuilder.create(worktree.resolve(".git").toFile());
    repo.create();

    git = new Git(repo);

    createAndCommitNewFile(worktree, "file-in-first-commit.xoo");
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
    createAndCommitNewFile(worktree, "file-m1.xoo");
    createAndCommitNewFile(worktree, "file-m2.xoo");
    createAndCommitNewFile(worktree, "file-m3.xoo");
    ObjectId forkPoint = git.getRepository().exactRef("HEAD").getObjectId();

    appendToAndCommitFile(worktree, "file-m3.xoo");
    createAndCommitNewFile(worktree, "file-m4.xoo");

    git.branchCreate().setName("b1").setStartPoint(forkPoint.getName()).call();
    git.checkout().setName("b1").call();
    createAndCommitNewFile(worktree, "file-b1.xoo");
    appendToAndCommitFile(worktree, "file-m1.xoo");
    deleteAndCommitFile("file-m2.xoo");

    assertThat(newScmProvider().branchChangedFiles("master", worktree))
      .containsExactlyInAnyOrder(
        worktree.resolve("file-b1.xoo"),
        worktree.resolve("file-m1.xoo"));
  }

  @Test
  public void branchChangedFiles_from_merged_and_diverged() throws IOException, GitAPIException {
    createAndCommitNewFile(worktree, "file-m1.xoo");
    createAndCommitNewFile(worktree, "file-m2.xoo");
    ObjectId forkPoint = git.getRepository().exactRef("HEAD").getObjectId();

    createAndCommitNewFile(worktree, "file-m3.xoo");
    ObjectId mergePoint = git.getRepository().exactRef("HEAD").getObjectId();

    appendToAndCommitFile(worktree, "file-m3.xoo");
    createAndCommitNewFile(worktree, "file-m4.xoo");

    git.branchCreate().setName("b1").setStartPoint(forkPoint.getName()).call();
    git.checkout().setName("b1").call();
    createAndCommitNewFile(worktree, "file-b1.xoo");
    appendToAndCommitFile(worktree, "file-m1.xoo");
    deleteAndCommitFile("file-m2.xoo");

    git.merge().include(mergePoint).call();
    createAndCommitNewFile(worktree, "file-b2.xoo");

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
    createAndCommitNewFile(projectDir, "file-b1");

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

  private void createAndCommitNewFile(Path worktree, String filename) throws IOException, GitAPIException {
    File newFile = worktree.resolve(filename).toFile();
    assertThat(newFile.createNewFile()).isTrue();
    commit(filename);
  }

  private void appendToAndCommitFile(Path worktree, String filename) throws IOException, GitAPIException {
    Files.write(worktree.resolve(filename), "foo".getBytes(), StandardOpenOption.APPEND);
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
