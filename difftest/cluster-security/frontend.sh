#!/bin/sh
# The frontend in cluster mode with userpass authentication and TLS, on port 15447 (or $1).
cd "$(dirname "$0")" || exit 1
JAVA=${JAVA17_HOME:-/Users/hiroyuki/Library/Java/JavaVirtualMachines/temurin-17.0.12/Contents/Home}/bin/java
exec "$JAVA" -cp "../../server/build/libs/scalardb-postgres-frontend.jar:sdk-classpath/lib/*" \
     com.scalar.db.frontend.postgres.server.Main frontend.properties "${1:-15447}"
