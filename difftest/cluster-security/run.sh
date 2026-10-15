#!/bin/sh
# The encryption and ABAC scenario against a frontend in cluster mode; see README.md for the setup.
# Every check prints PASS or FAIL; the exit code is 1 if any failed.
set -u
export LC_ALL=C # English psql messages, whatever the locale
PORT=${PORT:-15447}
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
as() { # user, sql: passwords are the user name plus pw, and admin's is admin
  pw=$1pw; [ "$1" = admin ] && pw=admin
  PGPASSWORD=$pw $PSQL "sslmode=require user=$1 dbname=secdemo" -c "$2" 2>&1
}
row() { # a query's rows on one line
  as "$1" "$2" | tr '\n' ' '
}
# leftovers of an earlier run: the superuser bypasses ABAC
as admin 'DROP TABLE IF EXISTS people' >/dev/null
as admin 'DELETE FROM docs WHERE id > 2' >/dev/null

# Encryption: a table declared in SQL, its values stored as ciphertext, compared in the frontend
check "CREATE TABLE ... WITH (encrypted)" 'CREATE TABLE' "$(as alice "CREATE TABLE people (id int PRIMARY KEY, name text, ssn text, card text, age int) WITH (encrypted = 'ssn, card')")"
check "an encrypted key is refused" '0A000' "$(as alice "CREATE TABLE bad (id int PRIMARY KEY) WITH (encrypted = 'id')")"
check "an unknown encrypted column is refused" '42703' "$(as alice "CREATE TABLE bad (id int PRIMARY KEY) WITH (encrypted = 'nope')")"
check "\\d+ shows the encrypted columns" 'Options: encrypted=ssn,card' "$(PGPASSWORD=alicepw psql -X -h localhost -p $PORT "sslmode=require user=alice dbname=secdemo" -c '\d+ people' 2>&1)"
check "inserts" 'INSERT 0 3' "$(as alice "INSERT INTO people VALUES (1, 'ann', '111-11-1111', '4111', 30), (2, 'ben', '222-22-2222', '4222', 40), (3, 'cy', '333-33-3333', NULL, 50)")"
check "reads decrypt" '^1\|ann\|111-11-1111\|4111\|30 2\|ben' "$(row alice 'SELECT * FROM people ORDER BY id')"
check "the database holds ciphertext" '^vault:v1:' "$(psql -X -A -t -h localhost -p ${BACKEND_PORT:-15450} -U postgres -d clustersec -c 'SELECT convert_from(ssn, '"'UTF8'"') FROM secdemo.people WHERE id = 1' 2>&1)"
check "= on an encrypted column" '^2 $' "$(row alice "SELECT id FROM people WHERE ssn = '222-22-2222'")"
check "= on the key and an encrypted column" '^2 $' "$(row alice "SELECT id FROM people WHERE id = 2 AND ssn = '222-22-2222'")"
check "LIKE and IS NULL in an OR" '^3 $' "$(row alice "SELECT id FROM people WHERE ssn LIKE '3%' OR card IS NULL")"
check "a bound parameter" '^1 $' "$(printf '%s\n' "SELECT id FROM people WHERE ssn = \$1 \\bind '111-11-1111' \\g" | PGPASSWORD=alicepw $PSQL "sslmode=require user=alice dbname=secdemo" -f - 2>&1 | tr '\n' ' ')"
check "ORDER BY an encrypted column" '^3 2 1 $' "$(row alice 'SELECT id FROM people ORDER BY ssn DESC')"
check "a join on an encrypted column" '^3 $' "$(row alice 'SELECT count(*) FROM people p JOIN people q ON p.ssn = q.ssn')"
check "a keyed UPDATE with an encrypted condition" 'UPDATE 1' "$(as alice "UPDATE people SET age = 41 WHERE id = 2 AND ssn = '222-22-2222'")"
check "... and one that does not match" 'UPDATE 0' "$(as alice "UPDATE people SET age = 99 WHERE id = 2 AND ssn = 'wrong'")"
check "DELETE by an encrypted column" 'DELETE 1' "$(as alice "DELETE FROM people WHERE ssn = '333-33-3333'")"
check "the writes landed" '^1\|30 2\|41 $' "$(row alice 'SELECT id, age FROM people ORDER BY id')"
check "an index on an encrypted column is refused" '0A000.*DB-ENCRYPTION-10003' "$(as alice 'CREATE INDEX people_ssn ON people (ssn)')"

# ABAC: rows above the user's level are invisible, writes are tagged with the user's level
check "alice (CONF) sees both rows" '^1 2 $' "$(row alice 'SELECT id FROM docs ORDER BY id')"
check "bob (PUB) sees the public row only" '^1 $' "$(row bob 'SELECT id FROM docs ORDER BY id')"
check "bob's Get of the CONF row is empty" '^0 $' "$(row bob 'SELECT count(*) FROM docs WHERE id = 2')"
check "SELECT * shows the data tag" '^1\|public note\|PUB::' "$(row bob 'SELECT * FROM docs')"
check "the data tag column in WHERE is refused" '0A000.*DB-ABAC-10037' "$(as bob "SELECT * FROM docs WHERE lv_data_tag = 'PUB'")"
check "alice's insert takes her level" 'CONF::' "$(as alice "INSERT INTO docs (id, body) VALUES (3, 'alice default')"; row alice 'SELECT lv_data_tag FROM docs WHERE id = 3')"
check "alice may write a lower level" 'PUB::' "$(as alice "INSERT INTO docs (id, body, lv_data_tag) VALUES (4, 'alice public', 'PUB')"; row alice 'SELECT lv_data_tag FROM docs WHERE id = 4')"
check "bob may not write a higher level" '42501.*DB-ABAC-10036' "$(as bob "INSERT INTO docs (id, body, lv_data_tag) VALUES (5, 'bob conf', 'CONF')")"
check "bob cannot update a row he cannot see" 'UPDATE 0' "$(as bob "UPDATE docs SET body = 'x' WHERE id = 2")"
check "bob cannot delete a row he cannot see" 'DELETE 0' "$(as bob 'DELETE FROM docs WHERE id = 3')"
check "bob's scan-driven UPDATE skips it too" 'UPDATE 0' "$(as bob "UPDATE docs SET body = 'x' WHERE body = 'secret plan'")"
check "the CONF rows are untouched" '^2\|secret plan 3\|alice default $' "$(row alice "SELECT id, body FROM docs WHERE id IN (2, 3) ORDER BY id")"
check "the superuser bypasses ABAC" '^4 $' "$(row admin 'SELECT count(*) FROM docs')"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
