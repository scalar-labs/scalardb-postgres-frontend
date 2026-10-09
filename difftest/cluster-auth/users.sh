#!/bin/sh
# Users.java with the classpath filled in: ./users.sh list | create <user> <password> [superuser] | grant <user> <namespace> <PRIVS> | revoke ... | drop <user>
cd "$(dirname "$0")" || exit 1
JAVA=${JAVA17_HOME:-/Users/hiroyuki/Library/Java/JavaVirtualMachines/temurin-17.0.12/Contents/Home}/bin/java
exec "$JAVA" -cp "../../server/build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar:sdk-classpath/lib/*" Users.java admin.properties "$@" 2>&1 | grep -v ' INFO '
