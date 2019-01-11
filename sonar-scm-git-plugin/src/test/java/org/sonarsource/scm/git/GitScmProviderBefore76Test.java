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
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.internal.google.common.collect.ImmutableMap;
import org.sonar.api.internal.google.common.collect.ImmutableSet;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.MessageException;

import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.sonarsource.scm.git.Utils.javaUnzip;

public class GitScmProviderBefore76Test {

  // Sample content for unified diffs
  // http://www.gnu.org/software/diffutils/manual/html_node/Example-Unified.html#Example-Unified
  private static final String CONTENT_LAO = "The Way that can be told of is not the eternal Way;\n"
    + "The name that can be named is not the eternal name.\n"
    + "The Nameless is the origin of Heaven and Earth;\n"
    + "The Named is the mother of all things.\n"
    + "Therefore let there always be non-being,\n"
    + "  so we may see their subtlety,\n"
    + "And let there always be being,\n"
    + "  so we may see their outcome.\n"
    + "The two are the same,\n"
    + "But after they are produced,\n"
    + "  they have different names.\n";

  private static final String CONTENT_TZU = "The Nameless is the origin of Heaven and Earth;\n"
    + "The named is the mother of all things.\n"
    + "\n"
    + "Therefore let there always be non-being,\n"
    + "  so we may see their subtlety,\n"
    + "And let there always be being,\n"
    + "  so we may see their outcome.\n"
    + "The two are the same,\n"
    + "But after they are produced,\n"
    + "  they have different names.\n"
    + "They both may be called deep and profound.\n"
    + "Deeper and more profound,\n"
    + "The door of all subtleties!";

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static final Random random = new Random();

  private Path worktree;
  private Git git;
  private final AnalysisWarningsWrapper analysisWarnings = mock(AnalysisWarningsWrapper.class);

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
    JGitBlameCommand jblameCommand = new JGitBlameCommand(new PathResolver(), analysisWarnings);
    GitScmProviderBefore76 gitScmProvider = new GitScmProviderBefore76(jblameCommand, analysisWarnings);

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
    createAndCommitFile("lao.txt", CONTENT_LAO);
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

    createAndCommitFile("file-m5.xoo");
    deleteAndCommitFile("file-m5.xoo");

    Set<Path> changedFiles = newScmProvider().branchChangedFiles("master", worktree);
    assertThat(changedFiles)
      .containsExactlyInAnyOrder(
        worktree.resolve("file-m1.xoo"),
        worktree.resolve("file-b1.xoo"),
        worktree.resolve("file-b2.xoo"));

    // use a subset of changed files for .branchChangedLines to verify only requested files are returned
    assertThat(changedFiles.remove(worktree.resolve("file-b1.xoo"))).isTrue();

    // generate common sample diff
    createAndCommitFile("lao.txt", CONTENT_TZU);
    changedFiles.add(worktree.resolve("lao.txt"));

    // a file that should not yield any results
    changedFiles.add(worktree.resolve("nonexistent"));

    assertThat(newScmProvider().branchChangedLines("master", worktree, changedFiles))
      .isEqualTo(
        ImmutableMap.of(
          worktree.resolve("lao.txt"), ImmutableSet.of(2, 3, 11, 12, 13),
          worktree.resolve("file-m1.xoo"), ImmutableSet.of(4),
          worktree.resolve("file-b2.xoo"), ImmutableSet.of(1, 2, 3)));

    assertThat(newScmProvider().branchChangedLines("master", worktree, Collections.singleton(worktree.resolve("nonexistent"))))
      .isEmpty();
  }

  @Test
  public void branchChangedLines_should_be_correct_when_change_is_not_committed() throws GitAPIException, IOException {
    String fileName = "file-in-first-commit.xoo";
    git.branchCreate().setName("b1").call();
    git.checkout().setName("b1").call();

    // this line is committed
    addLineToFile(fileName, 3);
    commit(fileName);

    // this line is not committed
    addLineToFile(fileName, 1);

    Path filePath = worktree.resolve(fileName);
    Map<Path, Set<Integer>> changedLines = newScmProvider().branchChangedLines("master", worktree, Collections.singleton(filePath));

    // both lines appear correctly
    assertThat(changedLines).containsExactly(entry(filePath, new HashSet<>(Arrays.asList(1, 4))));
  }

  @Test
  public void branchChangedLines_uses_relative_paths_from_project_root() throws GitAPIException, IOException {
    String fileName = "project1/file-in-first-commit.xoo";
    createAndCommitFile(fileName);

    git.branchCreate().setName("b1").call();
    git.checkout().setName("b1").call();

    // this line is committed
    addLineToFile(fileName, 3);
    commit(fileName);

    // this line is not committed
    addLineToFile(fileName, 1);

    Path filePath = worktree.resolve(fileName);
    Map<Path, Set<Integer>> changedLines = newScmProvider().branchChangedLines("master",
      worktree.resolve("project1"), Collections.singleton(filePath));

    // both lines appear correctly
    assertThat(changedLines).containsExactly(entry(filePath, new HashSet<>(Arrays.asList(1, 4))));
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
    verifyZeroInteractions(analysisWarnings);
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
    GitScmProviderBefore76 provider = new GitScmProviderBefore76(mockCommand(), analysisWarnings) {
      @Override
      Repository buildRepo(Path basedir) throws IOException {
        throw new IOException();
      }
    };
    assertThat(provider.branchChangedFiles("branch", worktree)).isNull();
    verifyZeroInteractions(analysisWarnings);
  }

  @Test
  public void branchChangedFiles_should_return_null_if_repo_exactref_is_null() throws IOException {
    Repository repository = mock(Repository.class);
    RefDatabase refDatabase = mock(RefDatabase.class);
    when(repository.getRefDatabase()).thenReturn(refDatabase);
    when(refDatabase.getRef("branch")).thenReturn(null);

    GitScmProviderBefore76 provider = new GitScmProviderBefore76(mockCommand(), analysisWarnings) {
      @Override
      Repository buildRepo(Path basedir) throws IOException {
        return repository;
      }
    };
    assertThat(provider.branchChangedFiles("branch", worktree)).isNull();

    String warning = "Could not find ref 'branch' in refs/heads or refs/remotes/origin."
      + " You may see unexpected issues and changes. Please make sure to fetch this ref before pull request analysis.";
    verify(analysisWarnings).addUnique(warning);
  }

  @Test
  public void branchChangedFiles_should_return_null_on_io_errors_of_RevWalk() throws IOException {
    RevWalk walk = mock(RevWalk.class);
    when(walk.parseCommit(any())).thenThrow(new IOException());

    GitScmProviderBefore76 provider = new GitScmProviderBefore76(mockCommand(), analysisWarnings) {
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
    when(diffCommand.setNewTree(any())).thenReturn(diffCommand);
    when(diffCommand.call()).thenThrow(mock(GitAPIException.class));

    Git git = mock(Git.class);
    when(git.diff()).thenReturn(diffCommand);

    GitScmProviderBefore76 provider = new GitScmProviderBefore76(mockCommand(), analysisWarnings) {
      @Override
      Git newGit(Repository repo) {
        return git;
      }
    };
    assertThat(provider.branchChangedFiles("master", worktree)).isNull();
    verify(diffCommand).call();
  }

  @Test
  public void branchChangedLines_returns_null_when_branch_doesnt_exist() {
    assertThat(newScmProvider().branchChangedLines("nonexistent", worktree, emptySet())).isNull();
  }

  @Test
  public void branchChangedLines_omits_files_with_git_api_errors() throws GitAPIException {
    DiffEntry diffEntry = mock(DiffEntry.class);
    when(diffEntry.getChangeType()).thenReturn(DiffEntry.ChangeType.MODIFY);

    DiffCommand diffCommand = mock(DiffCommand.class);
    when(diffCommand.setOutputStream(any())).thenReturn(diffCommand);
    when(diffCommand.setOldTree(any())).thenReturn(diffCommand);
    when(diffCommand.setPathFilter(any())).thenReturn(diffCommand);
    when(diffCommand.call())
      .thenReturn(Collections.singletonList(diffEntry))
      .thenThrow(mock(GitAPIException.class));

    Git git = mock(Git.class);
    when(git.diff()).thenReturn(diffCommand);

    GitScmProviderBefore76 provider = new GitScmProviderBefore76(mockCommand(), analysisWarnings) {
      @Override
      Git newGit(Repository repo) {
        return git;
      }
    };
    assertThat(provider.branchChangedLines("master", worktree,
      ImmutableSet.of(worktree.resolve("foo"), worktree.resolve("bar"))))
        .isEqualTo(ImmutableMap.of(worktree.resolve("foo"), emptySet()));
    verify(diffCommand, times(2)).call();
  }

  @Test
  public void branchChangedLines_returns_null_on_io_errors_of_repo_builder() {
    GitScmProviderBefore76 provider = new GitScmProviderBefore76(mockCommand(), analysisWarnings) {
      @Override
      Repository buildRepo(Path basedir) throws IOException {
        throw new IOException();
      }
    };
    assertThat(provider.branchChangedLines("branch", worktree, emptySet())).isNull();
  }

  @Test
  public void relativePathFromScmRoot_should_return_dot_project_root() {
    assertThat(newGitScmProvider().relativePathFromScmRoot(worktree)).isEqualTo(Paths.get(""));
  }

  private GitScmProviderBefore76 newGitScmProvider() {
    return new GitScmProviderBefore76(mock(JGitBlameCommand.class), analysisWarnings);
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

    GitScmProviderBefore76 provider = newGitScmProvider();

    String sha1before = provider.revisionId(projectDir);
    assertThat(sha1before).hasSize(40);

    createAndCommitFile("project/file1");
    String sha1after = provider.revisionId(projectDir);
    assertThat(sha1after).hasSize(40);

    assertThat(sha1after).isNotEqualTo(sha1before);
    assertThat(provider.revisionId(projectDir)).isEqualTo(sha1after);
  }

  private String randomizedContent(String prefix, int numLines) {
    StringBuilder sb = new StringBuilder();
    for (int line = 0; line < numLines; line++) {
      sb.append(randomizedLine(prefix));
      sb.append("\n");
    }
    return sb.toString();
  }

  private String randomizedLine(String prefix) {
    StringBuilder sb = new StringBuilder(prefix);
    for (int i = 0; i < 4; i++) {
      sb.append(' ');
      for (int j = 0; j < prefix.length(); j++) {
        sb.append((char) ('a' + random.nextInt(26)));
      }
    }
    return sb.toString();
  }

  private void createAndCommitFile(String relativePath) throws IOException, GitAPIException {
    createAndCommitFile(relativePath, randomizedContent(relativePath, 3));
  }

  private void createAndCommitFile(String relativePath, String content) throws IOException, GitAPIException {
    Path newFile = worktree.resolve(relativePath);
    Files.createDirectories(newFile.getParent());
    Files.write(newFile, content.getBytes(), StandardOpenOption.CREATE);
    commit(relativePath);
  }

  private void addLineToFile(String relativePath, int lineNumber) throws IOException {
    Path filePath = worktree.resolve(relativePath);
    List<String> lines = Files.readAllLines(filePath);
    lines.add(lineNumber - 1, randomizedLine(relativePath));
    Files.write(filePath, lines, StandardOpenOption.TRUNCATE_EXISTING);

  }

  private void appendToAndCommitFile(String relativePath) throws IOException, GitAPIException {
    Files.write(worktree.resolve(relativePath), randomizedContent(relativePath, 1).getBytes(), StandardOpenOption.APPEND);
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

  private GitScmProviderBefore76 newScmProvider() {
    return new GitScmProviderBefore76(mockCommand(), analysisWarnings);
  }
}
