#!/bin/bash

set -euo pipefail

BUILD_ID=$1
CURRENT_VERSION=`mvn help:evaluate -Dexpression=project.version | grep -v '^\[\|Download\w\+\:'`
RELEASE_VERSION=`echo $CURRENT_VERSION | sed "s/-.*//g"`
NEW_VERSION="$RELEASE_VERSION-build$BUILD_ID"

echo "Replacing version $CURRENT_VERSION with $NEW_VERSION"

mvn org.codehaus.mojo:versions-maven-plugin:2.2:set -DnewVersion=$NEW_VERSION
