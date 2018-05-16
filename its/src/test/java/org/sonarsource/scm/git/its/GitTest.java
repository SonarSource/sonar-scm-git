/*
 * Git Plugin Integration Tests
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
package org.sonarsource.scm.git.its;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.BuildResult;
import com.sonar.orchestrator.build.MavenBuild;
import com.sonar.orchestrator.locator.FileLocation;
import com.sonar.orchestrator.util.ZipUtils;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.wsclient.jsonsimple.JSONArray;
import org.sonar.wsclient.jsonsimple.JSONObject;
import org.sonar.wsclient.jsonsimple.JSONValue;

import static org.assertj.core.api.Assertions.assertThat;

public class GitTest {
  public static final File PROJECTS_DIR = new File("target/projects");
  public static final File SOURCES_DIR = new File("scm-repo");

  @ClassRule
  public static Orchestrator orchestrator = Orchestrator.builderEnv()
    .addPlugin(FileLocation.byWildcardMavenFilename(new File("../sonar-scm-git-plugin/target"), "sonar-scm-git-plugin-*.jar"))
    .setOrchestratorProperty("javaVersion", "LATEST_RELEASE")
    .addPlugin("java")
    .build();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void deleteData() {
    orchestrator.resetData();
  }

  /**
   * SONARSCGIT-7 Use Git commit date instead of author date"
   */
  @Test
  public void sample_git_project_commit_date() throws Exception {
    unzip("dummy-git.zip");

    runSonar("dummy-git");

    assertThat(getScmData("dummy-git:dummy:src/main/java/org/dummy/Dummy.java"))
      .contains(
        MapEntry.entry(1, new LineData("6b3aab35a3ea32c1636fee56f996e677653c48ea", "2012-07-17T16:12:48+0200", "david@gageot.net")),
        MapEntry.entry(2, new LineData("6b3aab35a3ea32c1636fee56f996e677653c48ea", "2012-07-17T16:12:48+0200", "david@gageot.net")),
        MapEntry.entry(3, new LineData("6b3aab35a3ea32c1636fee56f996e677653c48ea", "2012-07-17T16:12:48+0200", "david@gageot.net")),

        MapEntry.entry(26, new LineData("0d269c1acfb8e6d4d33f3c43041eb87e0df0f5e7", "2015-05-19T13:31:09+0200", "duarte.meneses@sonarsource.com")),
        MapEntry.entry(27, new LineData("0d269c1acfb8e6d4d33f3c43041eb87e0df0f5e7", "2015-05-19T13:31:09+0200", "duarte.meneses@sonarsource.com")),
        MapEntry.entry(28, new LineData("0d269c1acfb8e6d4d33f3c43041eb87e0df0f5e7", "2015-05-19T13:31:09+0200", "duarte.meneses@sonarsource.com")));
  }

  @Test
  public void dont_fail_on_uncommited_files() throws Exception {
    unzip("dummy-git.zip");

    // Edit file
    FileUtils.write(new File(project("dummy-git"), "src/main/java/org/dummy/Dummy.java"), "\n", Charsets.UTF_8, true);
    // New file
    FileUtils.write(new File(project("dummy-git"), "src/main/java/org/dummy/Dummy2.java"), "package org.dummy;\npublic class Dummy2 {}", Charsets.UTF_8, false);

    BuildResult result = runSonar("dummy-git");
    assertThat(result.getLogs()).contains("Missing blame information for the following files:");
    assertThat(result.getLogs()).contains("src/main/java/org/dummy/Dummy.java");
    assertThat(result.getLogs()).contains("src/main/java/org/dummy/Dummy2.java");

    if (orchestrator.getServer().version().isGreaterThanOrEquals("7.1")) {
      assertThat(getScmData("dummy-git:dummy:src/main/java/org/dummy/Dummy.java")).hasSize(31);
      assertThat(getScmData("dummy-git:dummy:src/main/java/org/dummy/Dummy2.java")).hasSize(2);
    } else {
      assertThat(getScmData("dummy-git:dummy:src/main/java/org/dummy/Dummy.java")).isEmpty();
      assertThat(getScmData("dummy-git:dummy:src/main/java/org/dummy/Dummy2.java")).isEmpty();
    }
  }

  public static void unzip(String zipName) {
    try {
      FileUtils.deleteQuietly(PROJECTS_DIR);
      FileUtils.forceMkdir(PROJECTS_DIR);
      ZipUtils.unzip(new File(SOURCES_DIR, zipName), PROJECTS_DIR);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  public static BuildResult runSonar(String projectName, String... keyValues) {
    File pom = new File(project(projectName), "pom.xml");

    MavenBuild install = MavenBuild.create(pom).setGoals("clean install");
    MavenBuild sonar = MavenBuild.create(pom).setGoals("sonar:sonar");
    sonar.setProperty("sonar.scm.disabled", "false");
    sonar.setProperties(keyValues);
    orchestrator.executeBuild(install);
    return orchestrator.executeBuild(sonar);
  }

  public static File project(String name) {
    return new File(PROJECTS_DIR, name);
  }

  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
  private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

  private class LineData {

    final String revision;
    final Date date;
    final String author;

    public LineData(String revision, String datetime, String author) throws ParseException {
      this.revision = revision;
      this.date = DATETIME_FORMAT.parse(datetime);
      this.author = author;
    }

    public LineData(String date, String author) throws ParseException {
      this.revision = null;
      this.date = DATE_FORMAT.parse(date);
      this.author = author;
    }

    @Override
    public boolean equals(Object obj) {
      return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
      return new HashCodeBuilder().append(revision).append(date).append(author).toHashCode();
    }

    @Override
    public String toString() {
      return ToStringBuilder.reflectionToString(this, ToStringStyle.SIMPLE_STYLE);
    }
  }

  private Map<Integer, LineData> getScmData(String fileKey) throws ParseException {
    Map<Integer, LineData> result = new HashMap<>();
    String json = orchestrator.getServer().adminWsClient().get("api/sources/scm", "commits_by_line", "true", "key", fileKey);
    JSONObject obj = (JSONObject) JSONValue.parse(json);
    JSONArray array = (JSONArray) obj.get("scm");
    for (Object anArray : array) {
      JSONArray item = (JSONArray) anArray;
      String dateOrDatetime = (String) item.get(2);
      result.put(((Long) item.get(0)).intValue(), new LineData((String) item.get(3),
        dateOrDatetime, (String) item.get(1)));
    }
    return result;
  }

}
