package org.sonarsource.scm.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFileFilter;
import org.sonar.api.utils.MessageException;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class GitInputFileFilter implements InputFileFilter {

  private final Repository repository;
  private final Set<String> ignoredFiles = new HashSet<>();
  private static final Logger LOG = Loggers.get(GitInputFileFilter.class);

  public GitInputFileFilter(/*ProjectReactor projectReactor*/ File baseDir) throws IOException {
    //baseDir = projectReactor.getRoot().getBaseDir();
    repository = getRepository(baseDir.toPath());
    FileTreeIterator fileTreeIterator = new FileTreeIterator(repository);

    check(fileTreeIterator);
  }

  private void check(FileTreeIterator fileTreeIterator) {
    try {
      while (!fileTreeIterator.eof()) {
        File entryFile = fileTreeIterator.getEntryFile();
        System.out.println(entryFile.getAbsolutePath());

        if (entryFile.isFile()) {
          if (fileTreeIterator.isEntryIgnored()) {
            ignoredFiles.add(entryFile.getAbsolutePath());
            System.out.println("Ignored  " + entryFile.getAbsolutePath() );
          }
        } else {
          FileTreeIterator subtreeIterator = (FileTreeIterator) fileTreeIterator.createSubtreeIterator(repository.newObjectReader());
          check(subtreeIterator);
        }

        fileTreeIterator.next(1);
      }
    } catch (CorruptObjectException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  @Override
  public boolean accept(InputFile inputFile) {
    if (ignoredFiles.contains(inputFile.absolutePath())) {
      LOG.debug("File {} was ignored by git", inputFile.toString());
      return false;
    }

    return true;
  }

  static Repository getRepository(Path basedir) throws IOException {
    RepositoryBuilder builder = new RepositoryBuilder()
      .findGitDir(basedir.toFile())
      .setMustExist(true);

    if (builder.getGitDir() == null) {
      throw MessageException.of("Not inside a Git work tree: " + basedir);
    }
    return builder.build();
  }
}
