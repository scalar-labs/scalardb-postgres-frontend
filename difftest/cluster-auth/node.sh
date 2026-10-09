#!/bin/sh
# The standalone Cluster node, with authentication on, on the PostgreSQL at 15440 (database clusterdb).
# node/ is a local build (see README.md, Setup); JDK 21.
cd "$(dirname "$0")" || exit 1
export JAVA_HOME=${JAVA21_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
exec node/bin/scalardb-cluster-node --config node.properties
