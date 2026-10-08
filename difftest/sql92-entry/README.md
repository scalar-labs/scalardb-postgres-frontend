# SQL-92 Entry conformance cases

317 cases contributed by the verification of 2026-10-07, organised by SQL-92 (ISO/IEC 9075:1992) section, after the
NIST SQL Test Suite's STAFF/PROJ/WORKS schema. Each case is the same SQL sent to native PostgreSQL and to the
frontend, diffed by difftest's `run.py` (`-- @name [ordered]` format). `BOTH_ERR` means both sides rejected the
statement, which counts as conformance; statement atomicity was checked by reading the outputs.

The base schema uses `VARCHAR(n)` rather than `CHAR(n)`, so the blank-padding difference of `CHAR` shows up only
in the S1 cases that test it. DDL and DML cases are self-contained (create a scratch table `t92_w*`, act, read,
drop) and run in autocommit, so no state carries between cases. Section numbers are from memory and should be
checked against the standard before being cited.

```sh
cd postgres-frontend/difftest
psql -h localhost -p 15440 -U postgres -d native_c -f sql92-entry/setup.sql
psql -h localhost -p 15444 -U postgres -d sdb      -f sql92-entry/setup.sql
export PGOPTIONS='-c client_min_messages=warning'   # keeps NOTICEs (DROP IF EXISTS, CREATE TABLE) out of the diff
for f in sql92-entry/s[0-9]*.sql; do STOP=0 FEPORT=15444 python3 run.py $f > /tmp/$(basename $f .sql).out; done
```

Results on 2026-10-08 (frontend after the fixes that this suite prompted; backing PostgreSQL with the C locale):

| Set | Cases | OK | Both error | Frontend error | Differs | Why the rest differ |
|---|---|---|---|---|---|---|
| S1 data types | 26 | 6 | 3 | 0 | 17 | `CHAR(n)` padding, `NUMERIC(p,s)` kept as double, `SMALLINT` range: announced by NOTICE, not enforced (5 of the 17 are PostgreSQL-side errors) |
| S2 constraints | 30 | 5 | 5 | 8 | 12 | constraints announced by NOTICE, not enforced; a DEFAULT other than NULL is refused (2026-10-08); no views, no GRANT, a table needs a key (all 12 are PostgreSQL-side errors) |
| S3 query | 45 | 38 | 7 | 0 | 0 | |
| S4 predicates | 56 | 54 | 2 | 0 | 0 | |
| S5 subqueries | 18 | 16 | 2 | 0 | 0 | |
| S6 values, aggregates | 37 | 30 | 6 | 1 | 0 | `- -x` does not parse |
| S7 NULL logic | 22 | 22 | 0 | 0 | 0 | |
| S8 DML | 39 | 30 | 8 | 1 | 0 | a primary key column cannot be updated |
| S9 transactions | 21 | 8 | 5 | 7 | 1 | scan after write (DB-CORE-10106, 4), ROLLBACK TO after a write, DDL not rolled back, SERIALIZABLE refused on a SNAPSHOT server (2) |
| S10 identifiers | 17 | 13 | 2 | 2 | 0 | columns differing only by case refused; a quote inside a name fails in core's `enclose` |
| S11 cursors | 6 | 1 | 0 | 5 | 0 | cursors are not supported |

Every difference is an error the frontend raises on purpose, a limitation announced with a NOTICE, a ScalarDB
core limitation (see `core-todo.md` at the repository root), or a PostgreSQL-side error; none is a silently
different result.
