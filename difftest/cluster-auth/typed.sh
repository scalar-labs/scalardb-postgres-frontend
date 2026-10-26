#!/bin/sh
# Declared types through the in-node frontend (Cluster branch postgres-frontend), whose metadata table is
# read and written in Cluster's privileged mode: CREATE FRONTEND METADATA TABLES creates no service user,
# a user with no grant on column_types uses typed columns, and nobody reaches the table through SQL.
# Runs after run.sh against the same node (PORT, default 15445); see README.md, "The in-node frontend".
# Needs users.sh (the SDK classpath) for the namespace grant, and the backing database for row counts.
set -u
export LC_ALL=C
PORT=${PORT:-15445}
AUTH=$(cd "$(dirname "$0")" && pwd)
# users.sh defaults to a JDK 17 path; on a machine without it, point it at the JDK 21 when that exists
if [ -z "${JAVA17_HOME:-}" ] && [ -x /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java ]; then
  export JAVA17_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
fi
PSQL="psql -X -A -t -h localhost -p $PORT -v VERBOSITY=verbose -v ON_ERROR_STOP=0"
pass=0
fail=0
check() { # name, expected (grep -E pattern), actual
  if printf '%s' "$3" | grep -qE "$2"; then
    echo "PASS $1"; pass=$((pass + 1))
  else
    echo "FAIL $1: expected /$2/, got: $3"; fail=$((fail + 1))
  fi
}
as() { # user, password, db, sql
  PGPASSWORD=$2 $PSQL "sslmode=require user=$1 dbname=$3" -c "$4" 2>&1
}
su() { as admin admin authdemo "$1"; }
# The frontend hides its metadata schema from SQL and the catalog, so its rows are read in the backing database
BACKEND=${BACKEND:-"-h localhost -p 15440 -U postgres -d clusterdb"}
rows() { psql -X -A -t $BACKEND -c "SELECT count(*) FROM scalardb_postgres_frontend.column_types WHERE full_table_name IN ($1)" 2>&1; }
# leftovers of an earlier run that failed before its cleanup
su 'DROP TABLE IF EXISTS tessdb.inv' >/dev/null 2>&1
su 'DROP USER IF EXISTS tess' >/dev/null 2>&1
su 'DROP SCHEMA IF EXISTS tessdb' >/dev/null 2>&1

check "CREATE FRONTEND METADATA TABLES prints no properties" '^CREATE FRONTEND METADATA TABLES$' "$(su 'CREATE FRONTEND METADATA TABLES')"
check "the metadata table exists in the backing database" '^1$' "$(psql -X -A -t $BACKEND -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'scalardb_postgres_frontend' AND table_name = 'column_types'" 2>&1)"
check "no service user was created" '^0$' "$(su "SELECT count(*) FROM pg_roles WHERE rolname = 'scalardb_postgres_frontend'")"
check "running it again changes nothing" '^CREATE FRONTEND METADATA TABLES$' "$(su 'CREATE FRONTEND METADATA TABLES')"

su 'CREATE SCHEMA IF NOT EXISTS tessdb' >/dev/null 2>&1
check "CREATE USER tess" 'CREATE ROLE' "$(su "CREATE USER tess WITH PASSWORD 'tesspw'")"
check "tess gets her own namespace (Cluster API)" '^granted ' "$("$AUTH/users.sh" grant tess tessdb READ,WRITE,DELETE,CREATE,DROP,ALTER 2>&1 | tail -1)"
check "a typed CREATE TABLE with no grant on column_types" '^CREATE TABLE$' "$(as tess tesspw tessdb 'CREATE TABLE inv (id INT PRIMARY KEY, amount NUMERIC(10,2), memo VARCHAR(5))')"
check "the value reads back exact" '^19\.90\|first$' "$(as tess tesspw tessdb "INSERT INTO inv VALUES (1, 19.90, 'first'); SELECT amount, memo FROM inv" | tail -1)"
check "\\d shows the declared types" 'numeric\(10,2\)' "$(as tess tesspw tessdb '\d inv')"
check "ADD COLUMN SMALLINT on her own table" '^ALTER TABLE$' "$(as tess tesspw tessdb 'ALTER TABLE inv ADD COLUMN qty SMALLINT')"
check "current_user stays the session user after typed reads" '^tess$' "$(as tess tesspw tessdb 'SELECT amount FROM inv; SELECT current_user' | tail -1)"
check "ADD COLUMN on another user's table is refused" '42501' "$(as tess tesspw authdemo 'ALTER TABLE items ADD COLUMN x NUMERIC(5,2)')"
check "a typed CREATE TABLE in another schema is refused" '42501' "$(as tess tesspw authdemo 'CREATE TABLE t2 (id INT PRIMARY KEY, a NUMERIC(5,2))')"
check "no row was written for the refused DDL" '^0$' "$(rows "'authdemo.items', 'authdemo.t2'")"
check "a user cannot read column_types" '42501' "$(as tess tesspw tessdb 'SELECT count(*) FROM scalardb_postgres_frontend.column_types')"
check "a user cannot write column_types" '42501' "$(as tess tesspw tessdb "UPDATE scalardb_postgres_frontend.column_types SET type_scale = 3 WHERE full_table_name = 'tessdb.inv'")"
check "the superuser cannot reach column_types through SQL either" '42501' "$(su 'SELECT count(*) FROM scalardb_postgres_frontend.column_types')"
check "the backing database holds the three rows" '^3$' "$(rows "'tessdb.inv'")"
check "DROP TABLE removes them" '^0$' "$(as tess tesspw tessdb 'DROP TABLE inv' >/dev/null; rows "'tessdb.inv'")"
# leave the node as it was: the user (with her grant) and the schema
check "DROP USER tess" '^DROP ROLE$' "$(su 'DROP USER tess')"
check "DROP SCHEMA tessdb" '^DROP SCHEMA$' "$(su 'DROP SCHEMA tessdb')"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
