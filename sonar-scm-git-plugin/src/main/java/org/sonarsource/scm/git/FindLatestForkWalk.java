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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.DateRevQueue;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Walk the graph starting from the HEAD of all references (branches).
 * The idea is to find the most recent commit that can be reached from the current head and from another branch.
 *
 * This is a special walk that is not supported by RevWalk in JGit because we have the following requirements:
 * 1) Might involve very high number of references (MergeBase detection in RevWalk can only involve limited number of branches)
 * 2) We want any merge base involving the current branch and any other branch (MergeBase would try to detect a n-way merge base involving all branches)
 * 3) We are not interested in the case where the head is merged into another branch (is a parent)
 */
public class FindLatestForkWalk {
  private final DateRevQueue queue = new DateRevQueue();
  private final Map<RevCommit, Integer> distancesFromHead = new HashMap<>();
  private final Repository repo;

  public FindLatestForkWalk(Repository repo) {
    this.repo = repo;
  }

  public ForkPoint find(Ref otherBranch) throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      walk.setRetainBody(false);
      List<RevCommit> otherBranches = Collections.singletonList(walk.parseCommit(otherBranch.getObjectId()));
      RevCommit currentBranch = walk.parseCommit(repo.exactRef("HEAD").getObjectId());
      return internalFind(walk, currentBranch, otherBranches);
    }
  }

  @CheckForNull
  public ForkPoint find() throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      walk.setRetainBody(false);
      RevCommit currentBranch = walk.parseCommit(repo.exactRef("HEAD").getObjectId());
      List<RevCommit> otherBranches = findOtherBranches(repo, walk, currentBranch);
      return internalFind(walk, currentBranch, otherBranches);
    }
  }

  private ForkPoint internalFind(RevWalk walk, RevCommit currentBranch, List<RevCommit> otherBranches) throws IOException {
    if (otherBranches.isEmpty()) {
      return null;
    }
    init(walk, currentBranch, otherBranches);

    RevCommit next;
    while ((next = queue.next()) != null) {
      // TODO finish early if no more nodes from current branch or no more nodes from any other branch
      Integer distanceFromHead = distancesFromHead.get(next);

      for (RevCommit p : next.getParents()) {
        if (p.equals(currentBranch)) {
          // we are not interested in branches where the current branch is part of
          continue;
        }

        Integer parentDistanceFromHead = distancesFromHead.get(p);
        boolean parentSeen = p.has(RevFlag.SEEN);

        if (distanceFromHead == null) {
          if (parentDistanceFromHead != null) {
            return new ForkPoint(p.getName(), parentDistanceFromHead);
          }
        } else {
          if (parentDistanceFromHead == null && parentSeen) {
            return new ForkPoint(p.getName(), distanceFromHead + 1);
          }

          int newValue = distanceFromHead + 1;
          distancesFromHead.compute(p, (k, v) -> v == null || v > newValue ? newValue : v);
        }

        if (!parentSeen) {
          // parse now to have commit time for the queue
          parse(walk, p);
          p.add(RevFlag.SEEN);
          queue.add(p);
        }
      }

      distancesFromHead.remove(next);
    }

    return null;
  }

  private Optional<RevCommit> refToCommit(RevWalk walk, Ref ref) throws IOException {
    try {
      return Optional.of(walk.parseCommit(ref.getObjectId()));
    } catch (IncorrectObjectTypeException e) {
      return Optional.empty();
    }
  }

  private List<RevCommit> findOtherBranches(Repository repo, RevWalk walk, ObjectId currentObjectId) throws IOException {
    List<RevCommit> branches = new ArrayList<>();

    Set<String> currentBranchNames = repo.getRefDatabase().getRefs().stream()
      .filter(r -> r.getObjectId().equals(currentObjectId))
      .map(r -> Repository.shortenRefName(r.getName()))
      .collect(Collectors.toSet());

    for (Ref ref : repo.getRefDatabase().getRefs()) {
      if (ref.getObjectId().equals(currentObjectId) || "HEAD".equals(ref.getName())) {
        continue;
      }

      String remoteName = repo.shortenRemoteBranchName(ref.getName());
      if (remoteName != null && currentBranchNames.contains(remoteName)) {
        // this is the case where the remote ref is not up to date (local ref is pointing to commit added to the remote ref). We don't want to detect the remote ref as a fork point
        continue;
      }

      Optional<RevCommit> commit = refToCommit(walk, ref);
      commit.ifPresent(branches::add);
    }

    return branches;
  }

  private void init(RevWalk walk, RevCommit current, List<RevCommit> otherRefs) throws IOException {
    current.add(RevFlag.SEEN);
    parse(walk, current);
    queue.add(current);
    distancesFromHead.put(current, 0);

    for (RevCommit c : otherRefs) {
      parse(walk, c);
      if (!c.has(RevFlag.SEEN)) {
        // we might have multiple references pointing to the same commit
        c.add(RevFlag.SEEN);
        queue.add(c);
      }
    }
  }

  /**
   * Parse commit's headers so that we have access to its parents and commit time
   */
  private void parse(RevWalk walk, RevCommit c) throws IOException {
    walk.parseHeaders(c);
  }
}
