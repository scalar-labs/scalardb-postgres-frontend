#!/bin/sh
# The frontend in cluster mode with userpass authentication and TLS, on port 15445 (or $1).
# Needs the fat jar (./gradlew :server:shadowJar) and sdk-classpath/lib (./gradlew -p difftest/cluster-auth/sdk-classpath lib).
cd "$(dirname "$0")" || exit 1
JAVA=${JAVA17_HOME:-/Users/hiroyuki/Library/Java/JavaVirtualMachines/temurin-17.0.12/Contents/Home}/bin/java
exec "$JAVA" -cp "../../server/build/libs/scalardb-postgres-frontend.jar:sdk-classpath/lib/*" \
     com.scalar.db.frontend.postgres.server.Main frontend.properties "${1:-15445}"
