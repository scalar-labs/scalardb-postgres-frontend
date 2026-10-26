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
# The kind of secret, which a client may state with the scalardb.auth startup option: a node (auth=auto)
# then checks alice's password as a token and refuses it; a standalone frontend forces one kind (28000)
check "a client may state its secret is a token" 'password authentication failed|is not allowed' "$(PGPASSWORD=alicepw PGOPTIONS='-c scalardb.auth=oidc_jwt' $PSQL "sslmode=require user=alice dbname=authdemo" -c 'SELECT 1' 2>&1)"
check "a client may state its secret is a password" '^1$' "$(PGPASSWORD=alicepw PGOPTIONS='-c scalardb.auth=userpass' $PSQL "sslmode=require user=alice dbname=authdemo" -c 'SELECT 1' 2>&1)"
check "an unknown kind is refused" 'invalid value for parameter "scalardb.auth"' "$(PGPASSWORD=alicepw PGOPTIONS='-c scalardb.auth=ldap' $PSQL "sslmode=require user=alice dbname=authdemo" -c 'SELECT 1' 2>&1)"
# Users, roles and privileges in SQL, as the node's superuser (admin), onto Cluster's AuthAdmin
su() { as admin admin "$1"; }
su 'DROP ROLE IF EXISTS carol, dave, erin, frank, auditors, leads' >/dev/null 2>&1 # leftovers of an earlier run
su 'DROP TABLE IF EXISTS davetab' >/dev/null 2>&1
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
check "the old password no longer logs in" 'password authentication failed for user "carol"' "$(as carol carolpw 'SELECT 1')"

# User options: SUPERUSER at creation, NOSUPERUSER later, and who may change them
check "CREATE ROLE ... LOGIN SUPERUSER" 'CREATE ROLE' "$(su "CREATE ROLE dave LOGIN SUPERUSER PASSWORD 'davepw'")"
check "a superuser at login" '^on$' "$(as dave davepw 'SHOW is_superuser')"
check "ALTER USER NOSUPERUSER" 'ALTER ROLE' "$(su 'ALTER USER dave NOSUPERUSER')"
check "no longer a superuser" '^off$' "$(as dave davepw 'SHOW is_superuser')"
check "a user cannot make itself superuser" '42501' "$(as dave davepw 'ALTER USER CURRENT_USER SUPERUSER')"
check "a user changes its own password" 'ALTER ROLE' "$(as dave davepw "ALTER USER CURRENT_USER PASSWORD 'davepw2'")"
check "its new password logs in" '^1$' "$(as dave davepw2 'SELECT 1')"

# Roles in roles, and table-level grants to a role
check "REVOKE a role from a user" 'REVOKE ROLE' "$(su 'REVOKE auditors FROM carol')"
check "the role's grant is gone" '42501' "$(as carol carolpw2 'SELECT count(*) FROM items')"
su "CREATE USER erin WITH PASSWORD 'erinpw'" >/dev/null
check "CREATE ROLE leads" 'CREATE ROLE' "$(su 'CREATE ROLE leads')"
check "GRANT on a table to a role" '^GRANT$' "$(su 'GRANT SELECT ON items TO leads')"
check "\\dp lists the owner first, as PostgreSQL does" '^public\|items\|table\|scalardb=arwdD/scalardb$' "$(as carol carolpw2 '\dp items')"
check "\\dp shows a user's grants" '^alice=arwd/scalardb$' "$(as carol carolpw2 '\dp items')"
check "\\dp shows a schema grant on the table" '^auditors=r/scalardb$' "$(as carol carolpw2 '\dp items')"
check "\\dp shows a role's grant" '^leads=r/scalardb' "$(as carol carolpw2 '\dp items')"
check "GRANT a role to a role" 'GRANT ROLE' "$(su 'GRANT leads TO auditors')"
check "REVOKE ON ALL TABLES IN SCHEMA from a role" '^REVOKE$' "$(su 'REVOKE SELECT ON ALL TABLES IN SCHEMA authdemo FROM auditors')"
su 'GRANT auditors TO erin' >/dev/null
check "a grant through two roles reads" '^2$' "$(as erin erinpw 'SELECT count(*) FROM items')"
check "a role cycle" '0LP01' "$(su 'GRANT auditors TO leads')"
check "\\du shows a superuser" '^admin\|Superuser$' "$(as carol carolpw2 '\du')"
check "\\du shows a role" '^leads\|Cannot login$' "$(as carol carolpw2 '\du')"
check "\\drg shows a role granted to a user" '^erin\|auditors\|INHERIT, SET\|$' "$(as carol carolpw2 '\drg')"
check "\\drg shows a role granted to a role" '^auditors\|leads\|INHERIT, SET\|$' "$(as carol carolpw2 '\drg')"
check "REVOKE a role from a role" 'REVOKE ROLE' "$(su 'REVOKE leads FROM auditors')"
check "the inner role's grant is gone" '42501' "$(as erin erinpw 'SELECT count(*) FROM items')"

# The grant option: a user passes on what it holds WITH GRANT OPTION, and only that
check "GRANT ... WITH GRANT OPTION" '^GRANT$' "$(su 'GRANT SELECT ON items TO carol WITH GRANT OPTION')"
check "a user grants with its option" '^GRANT$' "$(as carol carolpw2 'GRANT SELECT ON items TO dave')"
check "the passed-on grant reads" '^2$' "$(as dave davepw2 'SELECT count(*) FROM items')"
check "a user cannot grant what it lacks" '42501' "$(as carol carolpw2 'GRANT INSERT, UPDATE ON items TO dave')"
check "REVOKE GRANT OPTION FOR" '^REVOKE$' "$(su 'REVOKE GRANT OPTION FOR SELECT ON items FROM carol')"
check "the privilege stays" '^2$' "$(as carol carolpw2 'SELECT count(*) FROM items')"
check "the option is gone" '42501' "$(as carol carolpw2 'GRANT SELECT ON items TO erin')"

# The admin option on a role
check "GRANT a role WITH ADMIN OPTION" 'GRANT ROLE' "$(su 'GRANT auditors TO carol WITH ADMIN OPTION')"
check "a user grants a role with its admin option" 'GRANT ROLE' "$(as carol carolpw2 'GRANT auditors TO dave')"
check "REVOKE ADMIN OPTION FOR" 'REVOKE ROLE' "$(su 'REVOKE ADMIN OPTION FOR auditors FROM carol')"
check "the admin option is gone" '42501' "$(as carol carolpw2 'REVOKE auditors FROM dave')"

# DELETE, and CREATE on a schema
check "GRANT DELETE" '^GRANT$' "$(su 'GRANT DELETE ON items TO dave')"
check "the DELETE grant deletes" 'DELETE 0' "$(as dave davepw2 'DELETE FROM items WHERE id = 99')"
check "GRANT USAGE, CREATE ON SCHEMA" 'USAGE has no ScalarDB counterpart' "$(su 'GRANT USAGE, CREATE ON SCHEMA authdemo TO dave')"
check "the CREATE grant creates" 'CREATE TABLE' "$(as dave davepw2 'CREATE TABLE davetab (id INT, PRIMARY KEY (id))')"
check "\\dn+ shows who may create in a schema" '^public\|scalardb\|dave=C/scalardb\|' "$(as carol carolpw2 '\dn+')"
su 'DROP TABLE IF EXISTS davetab' >/dev/null 2>&1

# PASSWORD NULL, IF EXISTS, a leading comment
check "ALTER USER PASSWORD NULL" 'ALTER ROLE' "$(su 'ALTER USER erin PASSWORD NULL')"
check "no password, no login" 'password authentication failed for user "erin"' "$(as erin erinpw 'SELECT 1')"
check "DROP ROLE IF EXISTS on a missing role" 'does not exist, skipping' "$(su 'DROP ROLE IF EXISTS ghost')"
check "a commented CREATE USER" 'CREATE ROLE' "$(su "/* note */ CREATE USER frank WITH PASSWORD 'frankpw'")"
check "the commented user logs in" '^1$' "$(as frank frankpw 'SELECT 1')"
check "an unknown grantee" '42704' "$(su 'GRANT SELECT ON items TO ghost')"
check "dropping yourself" '55006' "$(su 'DROP USER admin')"
check "DROP ROLE" 'DROP ROLE' "$(su 'DROP ROLE carol, dave, erin, frank, auditors, leads')"
check "a dropped user cannot log in" 'password authentication failed for user "carol"' "$(as carol carolpw2 'SELECT 1')"
# The node caches table metadata too (node.properties sets it to 1 s, as the frontend does)
su 'DROP TABLE IF EXISTS renamed' >/dev/null 2>&1
check "a read after RENAME COLUMN sees the new name" '^5$' "$(PGPASSWORD=admin $PSQL "sslmode=require user=admin dbname=authdemo" \
  -c 'CREATE TABLE renamed (id INT PRIMARY KEY, amount INT)' -c 'INSERT INTO renamed VALUES (1, 5)' \
  -c 'ALTER TABLE renamed RENAME COLUMN amount TO total' -c 'SELECT total FROM renamed' -c 'DROP TABLE renamed' 2>&1)"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
