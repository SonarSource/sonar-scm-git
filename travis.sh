#!/bin/bash
set -euo pipefail

function configureTravis {
  mkdir ~/.local
  curl -sSL https://github.com/SonarSource/travis-utils/tarball/v26 | tar zx --strip-components 1 -C ~/.local
  source ~/.local/bin/install                                                                                                                                    
}
configureTravis


regular_mvn_build_deploy_analyze


# Min supported version of SQ (5.0) can't be tested as it already embeds git 5.0.
# Orchestrator does not allow to uninstall it.
MIN_SQ_VERSION="5.1"
echo '======= Run integration tests on minimal supported version of SonarQube ($MIN_SQ_VERSION)'
./run_integration_tests.sh "$MIN_SQ_VERSION"
