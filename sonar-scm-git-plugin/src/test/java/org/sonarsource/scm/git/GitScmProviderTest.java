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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.scan.filesystem.PathResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.sonarsource.scm.git.JGitBlameCommandTest.javaUnzip;

public class GitScmProviderTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

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
    assertThat(new GitScmProvider(mockCommand()).supports(baseDir)).isTrue();
  }

  private static JGitBlameCommand mockCommand() {
    return mock(JGitBlameCommand.class);
  }

  @Test
  public void test_branchChangedFiles_from_merged_and_diverged() throws IOException, GitAPIException {
    Path worktree = temp.newFolder().toPath();

    Repository repo = FileRepositoryBuilder.create(worktree.resolve(".git").toFile());
    repo.create();

    Git git = new Git(repo);

    createAndCommitNewFile(worktree, git, "file-0");

    git.branchCreate().setName("b1").call();
    git.checkout().setName("b1").call();
    createAndCommitNewFile(worktree, git, "file-b1-1");

    git.branchCreate().setName("b2").setStartPoint("master").call();
    git.checkout().setName("b2").call();
    createAndCommitNewFile(worktree, git, "file-b2");

    git.branchCreate().setName("b3").call();
    git.checkout().setName("b3").call();
    git.merge().include(repo.findRef("b1")).call();

    git.checkout().setName("b1").call();
    createAndCommitNewFile(worktree, git, "file-b1-2");
    appendToAndCommitFile(worktree, git, "file-0");

    git.checkout().setName("b3").call();
    createAndCommitNewFile(worktree, git, "file-b3");

    assertThat(new GitScmProvider(mockCommand()).branchChangedFiles("b1", worktree))
      .containsExactlyInAnyOrder(
        worktree.resolve("file-b2"),
        worktree.resolve("file-b3"));
  }

  // TODO test correct absolute path when git dir is above project basedir

  // TODO test correct null result for missing branch or missing dir or other interesting error

  private void createAndCommitNewFile(Path worktree, Git git, String filename) throws IOException, GitAPIException {
    File newFile = worktree.resolve(filename).toFile();
    assertThat(newFile.createNewFile()).isTrue();
    commit(git, filename);
  }

  private void appendToAndCommitFile(Path worktree, Git git, String filename) throws IOException, GitAPIException {
    Files.write(worktree.resolve(filename), "foo".getBytes(), StandardOpenOption.APPEND);
    commit(git, filename);
  }

  private void commit(Git git, String filename) throws GitAPIException {
    git.add().addFilepattern(filename).call();
    git.commit().setAuthor("joe", "joe@example.com").setMessage(filename).call();
  }
}
