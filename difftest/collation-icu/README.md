# The frontend under ScalarDB's ICU collation, against PostgreSQL

The frontend runs with `scalar.db.collation=ICU` (locale `und-u-ks-level2`: case-insensitive,
accent-sensitive) on a backing PostgreSQL whose character columns are altered to the matching
nondeterministic ICU collation. The reference is a PostgreSQL before 18 with the same collation on
every text column: 14 to 17 refuse pattern matching on a nondeterministic collation, as the frontend
does, while 18 allows it.

`cases.sql` (40 cases) covers ordering, equality and ranges both pushed down and in memory, index and
key lookups by another spelling, clustering order, `GROUP BY`, `DISTINCT`, `IN`, joins, set operations,
windows, duplicate keys, the pattern-matching refusals and their exemptions (literals, numbers cast
to text, catalog names). `expected.txt` is the baseline; `results-20261016.txt` is a run. Two statuses
are not `OK` on purpose: `collate_c_refused` (`FE_ERR`: under ICU the frontend refuses `COLLATE "C"`),
and `literals_compare` (`DIFF`: two literals compare in ScalarDB's collation here and in the database's
default in PostgreSQL).

## Setup

Ports: the backing PostgreSQL at 15440, the reference at 15460, the frontend at 15472.

```sh
# the reference: PostgreSQL 14 to 17 with ICU, C locale
initdb -D pg14 -U postgres --auth=trust -E UTF8 --locale=C && pg_ctl -D pg14 -o "-p 15460 -k ''" start
psql -h localhost -p 15460 -U postgres -c "create database icu"
psql -h localhost -p 15460 -U postgres -d icu -f native.sql -f data.sql
# the backing database (any PostgreSQL with ICU) and the frontend
psql -h localhost -p 15440 -U postgres -c "create database sdbicu"
java -jar ../../server/build/libs/scalardb-postgres-frontend.jar frontend.properties 15472 &
psql -h localhost -p 15472 -U postgres -d sdb -f ../fe_init.sql -f schema.sql
psql -h localhost -p 15440 -U postgres -d sdbicu -f backend.sql   # after every CREATE TABLE
psql -h localhost -p 15472 -U postgres -d sdb -f data.sql
./run.sh
```

The cases that write roll back or fail, so the set can run again without reloading.
