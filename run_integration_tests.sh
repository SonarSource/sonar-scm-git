#!/bin/bash
# Run integration tests with a given version of SonarQube
# Usage: run_integration_tests.sh "5.2"

set -euo pipefail

# required version of SonarQube
SONARQUBE_VERSION=$1
shift

export JAVA_HOME=/opt/sonarsource/jvm/java-1.9.0-sun-x64
export PATH=$JAVA_HOME/bin:$PATH

cd its
mvn verify -Dsonar.runtimeVersion=$SONARQUBE_VERSION -e -B -V -U $*
