#!/bin/bash

set -euo pipefail

function installTravisTools {
  mkdir ~/.local
  curl -sSL https://github.com/SonarSource/travis-utils/tarball/v21 | tar zx --strip-components 1 -C ~/.local                                                    
  source ~/.local/bin/install                                                                                                                                    
}
installTravisTools

function strongEcho {
  echo ""
  echo "================ $1 ================="
}

if [ "${TRAVIS_BRANCH}" == "master" ] && [ "$TRAVIS_PULL_REQUEST" == "false" ]; then
  strongEcho 'Build, deploy and analyze master'

  # Do not deploy a SNAPSHOT version but the release version related to this build
  ./set_maven_build_version.sh $TRAVIS_BUILD_NUMBER

  export MAVEN_OPTS="-Xmx1G -Xms128m"
  mvn org.jacoco:jacoco-maven-plugin:prepare-agent deploy sonar:sonar \
      -Pcoverage-per-test,sonarsource-public-repo,deploy-sonarsource \
      -Dmaven.test.redirectTestOutputToFile=false \
      -Dartifactory.user=$REPOX_QA_DEPLOY_USERNAME \
      -Dartifactory.password=$REPOX_QA_DEPLOY_PASSWORD \
      -Dsonar.host.url=$SONAR_HOST_URL \
      -Dsonar.login=$SONAR_TOKEN \
      -s settings.xml \
      -B -e -V

elif [ "$TRAVIS_PULL_REQUEST" != "false" ] && [ -n "${GITHUB_TOKEN-}" ]; then
  strongEcho 'Build and analyze pull request, no deploy'

  # No need for Maven phase "install" as the generated JAR file does not need to be installed
  # in Maven local repository. Phase "verify" is enough.

  mvn org.jacoco:jacoco-maven-plugin:prepare-agent verify sonar:sonar \
      -Psonarsource-public-repo \
      -Dmaven.test.redirectTestOutputToFile=false \
      -Dsonar.analysis.mode=issues \
      -Dsonar.github.pullRequest=$TRAVIS_PULL_REQUEST \
      -Dsonar.github.repository=$TRAVIS_REPO_SLUG \
      -Dsonar.github.oauth=$GITHUB_TOKEN \
      -Dsonar.host.url=$SONAR_HOST_URL \
      -Dsonar.login=$SONAR_TOKEN \
      -s settings.xml \
      -B -e -V

else
  strongEcho 'Build, no analysis, no deploy'

  # No need for Maven phase "install" as the generated JAR file does not need to be installed
  # in Maven local repository. Phase "verify" is enough.

  mvn verify \
      -Psonarsource-public-repo \
      -Dmaven.test.redirectTestOutputToFile=false \
      -s settings.xml \
      -B -e -V
fi


strongEcho 'Run integration tests on minimal supported version of SonarQube'

# Min supported version of SQ (5.0) can't be tested as it already embeds git 5.0.
# Orchestrator does not allow to uninstall it.
./run_integration_tests.sh "5.1" "-s settings.xml -Psonarsource-public-repo"
