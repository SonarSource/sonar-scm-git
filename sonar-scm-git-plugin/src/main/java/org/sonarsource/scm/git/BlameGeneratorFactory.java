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
/*
 * This file is based {@link org.eclipse.jgit.api.BlameCommand}.
 * Here is the original license:
 *
 * Copyright (C) 2011, GitHub Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sonarsource.scm.git;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.eclipse.jgit.blame.BlameGenerator;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;
import org.eclipse.jgit.util.io.AutoLFInputStream;
import org.sonar.api.internal.apachecommons.io.IOUtils;

/**
 * This class is based on {@link org.eclipse.jgit.api.BlameCommand}.
 * BlameCommand is not extendable so I had to copy the code.
 */
class BlameGeneratorFactory {
  static BlameGenerator newBlameGenerator(String path, Repository repo) throws IOException {
    BlameGenerator gen = new BlameGenerator(repo, path);
    gen.push(null, repo.resolve(Constants.HEAD));
    if (repo.isBare()) {
      return gen;
    }

    DirCache dc = repo.readDirCache();
    int entry = dc.findEntry(path);
    if (0 <= entry)
      gen.push(null, dc.getEntry(entry).getObjectId());

    File inTree = new File(repo.getWorkTree(), path);
    if (repo.getFS().isFile(inTree)) {
      RawText rawText = getRawText(inTree, repo);
      gen.push(null, rawText);
    }
    return gen;
  }

  private static RawText getRawText(File inTree, Repository repo) throws IOException {
    RawText rawText;

    WorkingTreeOptions workingTreeOptions = repo.getConfig()
            .get(WorkingTreeOptions.KEY);
    CoreConfig.AutoCRLF autoCRLF = workingTreeOptions.getAutoCRLF();
    switch (autoCRLF) {
      case FALSE:
      case INPUT:
        // Git used the repo format on checkout, but other tools
        // may change the format to CRLF. We ignore that here.
        rawText = new RawText(inTree);
        break;
      case TRUE:
        try (AutoLFInputStream in = new AutoLFInputStream(
                new FileInputStream(inTree), true)) {
          // Canonicalization should lead to same or shorter length
          // (CRLF to LF), so the file size on disk is an upper size bound
          rawText = new RawText(IOUtils.toByteArray(in));
        }
        break;
      default:
        throw new IllegalArgumentException(
                "Unknown autocrlf option " + autoCRLF); //$NON-NLS-1$
    }
    return rawText;
  }

}
