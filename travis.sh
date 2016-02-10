#!/bin/bash

set -euo pipefail

function configureTravis {
  mkdir ~/.local
  curl -sSL https://github.com/SonarSource/travis-utils/tarball/v23 | tar zx --strip-components 1 -C ~/.local
  source ~/.local/bin/install                                                                                                                                    
}
configureTravis

function strongEcho {
  echo ""
  echo "================ $1 ================="
}

  strongEcho 'Build, deploy and analyze master'

  SONAR_PROJECT_VERSION=`maven_expression "project.version"`

  # Do not deploy a SNAPSHOT version but the release version related to this build
  set_maven_build_version $TRAVIS_BUILD_NUMBER

  export MAVEN_OPTS="-Xmx1G -Xms128m"
  mvn org.jacoco:jacoco-maven-plugin:prepare-agent deploy \
      -Pcoverage-per-test,deploy-sonarsource \
      -Dmaven.test.redirectTestOutputToFile=false \
      -Dsonar.host.url=$SONAR_HOST_URL \
      -Dsonar.login=$SONAR_TOKEN \
      -Dsonar.projectVersion=$SONAR_PROJECT_VERSION \
      -B -e -V
