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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import org.eclipse.jgit.api.DiffCommand;
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
import static org.mockito.Matchers.anyBoolean;
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
  private Git git;

  @Before
  public void before() throws IOException, GitAPIException {
    worktree = temp.newFolder().toPath();
    Repository repo = FileRepositoryBuilder.create(worktree.resolve(".git").toFile());
    repo.create();

    git = new Git(repo);

    createAndCommitFile("file-in-first-commit.xoo");
  }

  @Test
  public void sanityCheck() {
    assertThat(newGitScmProvider().key()).isEqualTo("git");
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
    assertThat(newGitScmProvider().supports(baseDirEmpty)).isFalse();

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
    createAndCommitFile("file-m1.xoo");
    createAndCommitFile("file-m2.xoo");
    createAndCommitFile("file-m3.xoo");
    ObjectId forkPoint = git.getRepository().exactRef("HEAD").getObjectId();

    appendToAndCommitFile("file-m3.xoo");
    createAndCommitFile("file-m4.xoo");

    git.branchCreate().setName("b1").setStartPoint(forkPoint.getName()).call();
    git.checkout().setName("b1").call();
    createAndCommitFile("file-b1.xoo");
    appendToAndCommitFile("file-m1.xoo");
    deleteAndCommitFile("file-m2.xoo");

    assertThat(newScmProvider().branchChangedFiles("master", worktree))
      .containsExactlyInAnyOrder(
        worktree.resolve("file-b1.xoo"),
        worktree.resolve("file-m1.xoo"));
  }

  @Test
  public void branchChangedFiles_from_merged_and_diverged() throws IOException, GitAPIException {
    createAndCommitFile("file-m1.xoo");
    createAndCommitFile("file-m2.xoo");
    ObjectId forkPoint = git.getRepository().exactRef("HEAD").getObjectId();

    createAndCommitFile("file-m3.xoo");
    ObjectId mergePoint = git.getRepository().exactRef("HEAD").getObjectId();

    appendToAndCommitFile("file-m3.xoo");
    createAndCommitFile("file-m4.xoo");

    git.branchCreate().setName("b1").setStartPoint(forkPoint.getName()).call();
    git.checkout().setName("b1").call();
    createAndCommitFile("file-b1.xoo");
    appendToAndCommitFile("file-m1.xoo");
    deleteAndCommitFile("file-m2.xoo");

    git.merge().include(mergePoint).call();
    createAndCommitFile("file-b2.xoo");

    assertThat(newScmProvider().branchChangedFiles("master", worktree))
      .containsExactlyInAnyOrder(
        worktree.resolve("file-m1.xoo"),
        worktree.resolve("file-b1.xoo"),
        worktree.resolve("file-b2.xoo"));
  }

  @Test
  public void branchChangedFiles_when_git_work_tree_is_above_project_basedir() throws IOException, GitAPIException {
    git.branchCreate().setName("b1").call();
    git.checkout().setName("b1").call();
    
    Path projectDir = worktree.resolve("project");
    Files.createDirectory(projectDir);
    createAndCommitFile("project/file-b1");
    assertThat(newScmProvider().branchChangedFiles("master", projectDir))
      .containsOnly(projectDir.resolve("file-b1"));
  }

  @Test
  public void branchChangedFiles_falls_back_to_origin_when_local_branch_does_not_exist() throws IOException, GitAPIException {
    git.branchCreate().setName("b1").call();
    git.checkout().setName("b1").call();
    createAndCommitFile("file-b1");

    Path worktree2 = temp.newFolder().toPath();
    Git.cloneRepository()
      .setURI(worktree.toString())
      .setDirectory(worktree2.toFile())
      .call();

    assertThat(newScmProvider().branchChangedFiles("master", worktree2))
      .containsOnly(worktree2.resolve("file-b1"));
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
  public void branchChangedFiles_should_throw_when_dir_nonexistent() {
    thrown.expect(MessageException.class);
    thrown.expectMessage("Not inside a Git work tree: ");
    newScmProvider().branchChangedFiles("master", temp.getRoot().toPath().resolve("nonexistent"));
  }

  @Test
  public void branchChangedFiles_should_return_null_on_io_errors_of_repo_builder() {
    GitScmProvider provider = new GitScmProvider(mockCommand()) {
      @Override
      Repository buildRepo(Path basedir) throws IOException {
        throw new IOException();
      }
    };
    assertThat(provider.branchChangedFiles("branch", worktree)).isNull();
  }

  @Test
  public void branchChangedFiles_should_return_null_on_io_errors_of_repo_exactref() {
    GitScmProvider provider = new GitScmProvider(mockCommand()) {
      @Override
      Repository buildRepo(Path basedir) {
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

  @Test
  public void branchChangedFiles_should_return_null_on_git_api_errors() throws GitAPIException {
    DiffCommand diffCommand = mock(DiffCommand.class);
    when(diffCommand.setShowNameAndStatusOnly(anyBoolean())).thenReturn(diffCommand);
    when(diffCommand.setOldTree(any())).thenReturn(diffCommand);
    when(diffCommand.call()).thenThrow(mock(GitAPIException.class));

    Git git = mock(Git.class);
    when(git.diff()).thenReturn(diffCommand);

    GitScmProvider provider = new GitScmProvider(mockCommand()) {
      @Override
      Git newGit(Repository repo) {
        return git;
      }
    };
    assertThat(provider.branchChangedFiles("branch", worktree)).isNull();
  }

  @Test
  public void relativePathFromScmRoot_should_return_dot_project_root() {
    assertThat(newGitScmProvider().relativePathFromScmRoot(worktree)).isEqualTo(Paths.get(""));
  }

  private GitScmProvider newGitScmProvider() {
    return new GitScmProvider(mock(JGitBlameCommand.class));
  }

  @Test
  public void relativePathFromScmRoot_should_return_filename_for_file_in_project_root() throws IOException {
    Path filename = Paths.get("somefile.xoo");
    Path path = worktree.resolve(filename);
    Files.createFile(path);
    assertThat(newGitScmProvider().relativePathFromScmRoot(path)).isEqualTo(filename);
  }

  @Test
  public void relativePathFromScmRoot_should_return_relative_path_for_file_in_project_subdir() throws IOException {
    Path relpath = Paths.get("sub/dir/to/somefile.xoo");
    Path path = worktree.resolve(relpath);
    Files.createDirectories(path.getParent());
    Files.createFile(path);
    assertThat(newGitScmProvider().relativePathFromScmRoot(path)).isEqualTo(relpath);
  }

  @Test
  public void revisionId_should_return_different_sha1_after_commit() throws IOException, GitAPIException {
    Path projectDir = worktree.resolve("project");
    Files.createDirectory(projectDir);

    GitScmProvider provider = newGitScmProvider();

    String sha1before = provider.revisionId(projectDir);
    assertThat(sha1before).hasSize(40);

    createAndCommitFile("project/file1");
    String sha1after = provider.revisionId(projectDir);
    assertThat(sha1after).hasSize(40);

    assertThat(sha1after).isNotEqualTo(sha1before);
    assertThat(provider.revisionId(projectDir)).isEqualTo(sha1after);
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

  private void createAndCommitFile(String relativePath) throws IOException, GitAPIException {
    Path newFile = worktree.resolve(relativePath);
    Files.write(newFile, randomizedContent(relativePath).getBytes(), StandardOpenOption.CREATE_NEW);
    commit(relativePath);
  }

  private void appendToAndCommitFile(String relativePath) throws IOException, GitAPIException {
    Files.write(worktree.resolve(relativePath), randomizedContent(relativePath).getBytes(), StandardOpenOption.APPEND);
    commit(relativePath);
  }

  private void deleteAndCommitFile(String relativePath) throws GitAPIException {
    git.rm().addFilepattern(relativePath).call();
    commit(relativePath);
  }

  private void commit(String relativePath) throws GitAPIException {
    git.add().addFilepattern(relativePath).call();
    git.commit().setAuthor("joe", "joe@example.com").setMessage(relativePath).call();
  }

  private GitScmProvider newScmProvider() {
    return new GitScmProvider(mockCommand());
  }
}
