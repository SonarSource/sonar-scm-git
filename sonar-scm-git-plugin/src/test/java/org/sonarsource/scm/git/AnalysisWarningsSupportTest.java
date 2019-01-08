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

import org.junit.*;
import org.sonar.api.SonarRuntime;
import org.sonar.api.utils.Version;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.sonarsource.scm.git.AnalysisWarningsSupport.getAnalysisWarningsWrapper;

public class AnalysisWarningsSupportTest {
  @Test
  public void getAnalysisWarningsWrapper_returns_default_implementation_for_supported_runtime() {
    SonarRuntime supportedRuntime = mock(SonarRuntime.class);
    when(supportedRuntime.getApiVersion()).thenReturn(Version.create(7, 4));
    assertThat(getAnalysisWarningsWrapper(supportedRuntime)).isEqualTo(DefaultAnalysisWarningsWrapper.class);
  }

  @Test
  public void getAnalysisWarningsWrapper_returns_noop_implementation_for_unsupported_runtime() {
    SonarRuntime unsupportedRuntime = mock(SonarRuntime.class);
    when(unsupportedRuntime.getApiVersion()).thenReturn(Version.create(7, 3));
    assertThat(getAnalysisWarningsWrapper(unsupportedRuntime)).isEqualTo(NoOpAnalysisWarningsWrapper.class);
  }
}
