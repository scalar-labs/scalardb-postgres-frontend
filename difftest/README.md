# Differential tests: native PostgreSQL vs the frontend on ScalarDB-on-PostgreSQL

Same schema and data on both sides; every case runs through psql on both and the outputs are diffed.

```sh
initdb -D pgdata -U postgres --auth=trust -E UTF8 --locale=C && pg_ctl -D pgdata -o "-p 15440 -k ''" start
psql -h localhost -p 15440 -U postgres -c "alter system set timezone = 'UTC'" -c "select pg_reload_conf()"
psql -h localhost -p 15440 -U postgres -c "create database native_c template template0 encoding 'UTF8' lc_collate 'C' lc_ctype 'C'" -c "create database sdb"
java -jar ../server/build/libs/scalardb-postgres-frontend.jar scalardb.properties 15441 &   # the jar needs Java 17
python3 gen.py > data.sql
psql -h localhost -p 15440 -U postgres -d native_c -f schema.sql -f data.sql
psql -h localhost -p 15441 -U postgres -d sdb -f fe_init.sql -f schema.sql -f data.sql
python3 run.py cases_read.sql                         # read queries
STOP=0 python3 run.py cases_types.sql               # time, timestamptz, bytea and real columns (table types)
STOP=0 python3 run.py cases_write.sql                 # DML inside BEGIN ... ROLLBACK
STOP=0 SNAP=1 python3 run.py cases_write_ac.sql       # autocommit DML, compares all tables, reloads after each case
STOP=0 python3 run.py cases_bind.sql cases_bind2.sql  # extended protocol (psql \bind), plan-cache reuse
python3 leak.py cases_read.sql                        # reports cases that leave a backend idle in transaction
python3 fuzz.py 7 300 > fuzz_cases.sql && STOP=0 python3 run.py fuzz_cases.sql   # 300 random read queries, seed 7
```

`FEPORT` overrides the frontend port (default 15441) for `run.py` and `leak.py`.

`cases_session.sql` covers session semantics (`SET TIME ZONE`, `search_path`, `SET LOCAL`) and the
statements the frontend refuses on purpose; its `refuse_*` cases are expected `FE_ERR`.
The boundary cases of 2026-10-08 (float overflow, sub-millisecond rounding, negative `LIMIT`, name and
type-modifier limits) are in `cases_types.sql` and `cases_session.sql`; `results/2026-10-08-boundary/summary.txt`
explains their statuses.

`results/baseline/*.txt` record the expected status of every case (`name status` per line).
`EXPECT=results/baseline/read.txt python3 run.py cases_read.sql` exits 1 and prints `CHANGED` for any
case whose status moved, so a run is a pass/fail test and not only a report; `WRITE_EXPECT=<file>`
records the statuses of a run as the new baseline once a change is understood. The expected
non-OK statuses are explained in `results/<date>/summary.txt` of the run that set the baseline.

`sql92-entry/` holds a 317-case SQL-92 Entry conformance suite contributed by the 2026-10-07 verification
(see its README); it runs through the same `run.py`.

`fuzz.py SEED COUNT` prints random read queries over the same schema (joins of every kind, subqueries,
aggregates, windows, set operations, CTEs) in `run.py`'s case format. Expressions are typed, so errors are
rare, and every output column is aliased, so NAMES cannot hide a DIFF; with native PostgreSQL as the
oracle, every DIFF is a wrong result. The run of 2026-10-08 (20 seeds x 300 queries) found eight
wrong-result bugs, fixed and covered by cases in `cases_read.sql`; see `results/2026-10-08-fuzz/summary.txt`
for them and for what remains (a JSQLParser limit: `substr(CASE ... IS DISTINCT FROM ... END, n)` does not
parse). The loose comparison behind NUM_FMT folds -0 into 0, since set operations and DISTINCT may keep
either representative.

The cluster must be UTF-8 with a C locale and a UTC time zone, or `string_funcs` (an `e` with an
accent) and the `types` snapshots (`timestamptz` output) differ for reasons unrelated to the frontend.
The **backing** database of the frontend needs the C locale as well: on a default `en_US` database
`text_cmp_case` (`WHERE name < 'a'`) returns no rows, because the range condition is pushed down to
the database and evaluated under its collation. `leak.py` checks the backing cluster; set
`BACKEND_PORT` when it is not the one on 15440.
Run the sets one at a time, and reload both sides (`trunc.sql` then `data.sql`) before a read run that
follows the write sets: the write and bind sets leave the two sides slightly different.

Statuses: OK, NAMES (only result column names differ), NUM_FMT (numbers differ only in formatting, e.g. 2500 vs 2500.0),
DIFF (wrong result), FE_ERR / PG_ERR (only one side errors), BOTH_ERR (both fail with the same SQLSTATEs),
CODE (both fail, with different SQLSTATEs). psql runs with `VERBOSITY=verbose` so that each error line
carries its SQLSTATE. Two cases are CODE on purpose: `s02_11_1_create_schema_authorization`, where the
frontend refuses `AUTHORIZATION` (`0A000`) and PostgreSQL reports the existing schema, and
`refuse_table_name_over_63`, where PostgreSQL only truncates the name with a NOTICE (no SQLSTATE in
the comparison) and the frontend refuses it (`42622`).

## Concurrency

`Concurrency.java` drives a frontend with 8 pgjdbc clients (prepared statements, as BenchBase does).
It needs only the frontend's fat jar, which bundles pgjdbc:

```sh
java -cp ../server/build/libs/scalardb-postgres-frontend.jar Concurrency.java 15441 conc_si SNAPSHOT
# a second frontend whose properties add scalar.db.consensus_commit.isolation_level=SERIALIZABLE
java -cp ../server/build/libs/scalardb-postgres-frontend.jar Concurrency.java 15443 conc_ser SERIALIZABLE
```

It fails on lost updates, duplicate keys, a changed bank total, unexpected SQLSTATEs (conflicts must be
40001) or a broken session after an error, and under SERIALIZABLE on any read skew, write skew or
phantom insert. Under SNAPSHOT those anomalies are only counted, since ScalarDB's SNAPSHOT allows them.
