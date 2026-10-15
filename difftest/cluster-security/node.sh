#!/bin/sh
# The node, with node.properties; node/ is the build cluster-auth uses (see ../cluster-auth/README.md). JDK 21.
cd "$(dirname "$0")" || exit 1
export JAVA_HOME=${JAVA21_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
exec node/bin/scalardb-cluster-node --config node.properties
