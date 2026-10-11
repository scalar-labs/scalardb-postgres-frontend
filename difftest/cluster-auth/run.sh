#!/bin/sh
# The authentication scenario against a frontend in cluster mode; see README.md for the setup.
# Every check prints PASS or FAIL; the exit code is 1 if any failed. Connection-time refusals are matched
# on their message, since psql shows no SQLSTATE before a session exists (pgjdbc does: see the unit tests).
set -u
export LC_ALL=C # English psql messages, whatever the locale
PORT=${PORT:-15445}
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
as() { # user, password, sql
  PGPASSWORD=$2 $PSQL "sslmode=require user=$1 dbname=authdemo" -c "$3" 2>&1
}
as alice alicepw 'DELETE FROM items WHERE id = 3' >/dev/null 2>&1 # a leftover of an earlier run
check "alice reads" '^2$' "$(as alice alicepw 'SELECT count(*) FROM items')"
check "alice writes" 'INSERT 0 1' "$(as alice alicepw "INSERT INTO items VALUES (3, 'c')")"
check "alice deletes" 'DELETE 1' "$(as alice alicepw 'DELETE FROM items WHERE id = 3')"
check "current_user is alice" '^alice$' "$(as alice alicepw 'SELECT current_user')"
check "a lookup join (pool reads)" '^2$' "$(as alice alicepw 'SELECT count(*) FROM items a JOIN items b ON b.id = a.id')"
check "a transaction" 'COMMIT' "$(as alice alicepw "BEGIN; UPDATE items SET name = 'a' WHERE id = 1; SELECT name FROM items WHERE id = 1; COMMIT")"
check "DDL as alice" 'CREATE TABLE|42501' "$(as alice alicepw 'CREATE TABLE IF NOT EXISTS scratch (id INT, PRIMARY KEY (id))')"
check "bob cannot read" '42501' "$(as bob bobpw 'SELECT count(*) FROM items')"
check "bob cannot write" '42501' "$(as bob bobpw "INSERT INTO items VALUES (9, 'z')")"
check "bob cannot create" '42501' "$(as bob bobpw 'CREATE TABLE forbidden (id INT, PRIMARY KEY (id))')"
check "bob's session goes on after 42501" '^1$' "$(PGPASSWORD=bobpw $PSQL "sslmode=require user=bob dbname=authdemo" -c 'SELECT count(*) FROM items' -c 'SELECT 1' 2>&1 | tail -1)"
check "SET ROLE is refused" '0A000' "$(as alice alicepw 'SET ROLE bob')"
start=$(date +%s)
out=$(as alice wrong 'SELECT 1')
took=$(( $(date +%s) - start ))
check "a wrong password is refused" 'password authentication failed for user "alice"' "$out"
check "a wrong password waited about a second" '^[1-9]' "$took"
check "an unknown user is refused the same way" 'password authentication failed for user "nobody"' "$(as nobody x 'SELECT 1')"
check "plaintext is refused" 'accepts only SSL connections' "$(PGPASSWORD=alicepw $PSQL "sslmode=disable user=alice dbname=authdemo" -c 'SELECT 1' 2>&1)"
check "TLS is in use" 'TLSv1' "$(PGPASSWORD=alicepw psql -X -h localhost -p $PORT "sslmode=require user=alice dbname=authdemo" -c '\conninfo' 2>&1)"
# Users, roles and privileges in SQL, as the node's superuser (admin), onto Cluster's AuthAdmin
su() { as admin admin "$1"; }
su 'DROP ROLE IF EXISTS carol, auditors' >/dev/null 2>&1 # leftovers of an earlier run
check "CREATE USER" 'CREATE ROLE' "$(su "CREATE USER carol WITH PASSWORD 'carolpw'")"
check "a new user logs in" '^1$' "$(as carol carolpw 'SELECT 1')"
check "a new user has no privilege" '42501' "$(as carol carolpw 'SELECT count(*) FROM items')"
check "CREATE USER twice" '42710' "$(su "CREATE USER carol WITH PASSWORD 'x'")"
check "GRANT SELECT" '^GRANT$' "$(su 'GRANT SELECT ON items TO carol')"
check "the grant reads" '^2$' "$(as carol carolpw 'SELECT count(*) FROM items')"
check "the grant does not write" '42501' "$(as carol carolpw "INSERT INTO items VALUES (8, 'y')")"
check "INSERT without UPDATE" '0LP01' "$(su 'GRANT INSERT ON items TO carol')"
check "a user cannot create users" '42501' "$(as carol carolpw "CREATE USER eve WITH PASSWORD 'evepw'")"
check "REVOKE SELECT" '^REVOKE$' "$(su 'REVOKE SELECT ON items FROM carol')"
check "the revoke holds" '42501' "$(as carol carolpw 'SELECT count(*) FROM items')"
check "CREATE ROLE" 'CREATE ROLE' "$(su 'CREATE ROLE auditors')"
check "GRANT ON ALL TABLES IN SCHEMA" '^GRANT$' "$(su 'GRANT SELECT ON ALL TABLES IN SCHEMA authdemo TO auditors')"
check "GRANT a role" 'GRANT ROLE' "$(su 'GRANT auditors TO carol')"
check "the role's grant reads" '^2$' "$(as carol carolpw 'SELECT count(*) FROM items')"
check "ALTER USER PASSWORD" 'ALTER ROLE' "$(su "ALTER USER carol PASSWORD 'carolpw2'")"
check "the new password logs in" '^1$' "$(as carol carolpw2 'SELECT 1')"
check "an unknown grantee" '42704' "$(su 'GRANT SELECT ON items TO ghost')"
check "dropping yourself" '55006' "$(su 'DROP USER admin')"
check "DROP ROLE" 'DROP ROLE' "$(su 'DROP ROLE carol, auditors')"
check "a dropped user cannot log in" 'password authentication failed for user "carol"' "$(as carol carolpw2 'SELECT 1')"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
