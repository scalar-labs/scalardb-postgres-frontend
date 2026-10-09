#!/bin/sh
# The authentication scenario against a frontend in cluster mode; see README.md for the setup.
# Every check prints PASS or FAIL; the exit code is 1 if any failed.
set -u
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
check "bob's session goes on after 42501" '^1$' "$(as bob bobpw 'SELECT count(*) FROM items; SELECT 1' | tail -1)"
check "SET ROLE is refused" '0A000' "$(as alice alicepw 'SET ROLE bob')"
start=$(date +%s)
out=$(as alice wrong 'SELECT 1')
took=$(( $(date +%s) - start ))
check "a wrong password is 28P01" '28P01' "$out"
check "a wrong password waited about a second" '^[1-9]' "$took"
check "an unknown user is 28P01" '28P01' "$(as nobody x 'SELECT 1')"
check "plaintext is refused" '28000' "$(PGPASSWORD=alicepw $PSQL "sslmode=disable user=alice dbname=authdemo" -c 'SELECT 1' 2>&1)"
check "TLS is in use" 'SSL connection' "$(PGPASSWORD=alicepw psql -X -h localhost -p $PORT "sslmode=require user=alice dbname=authdemo" -c '\conninfo' 2>&1)"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
