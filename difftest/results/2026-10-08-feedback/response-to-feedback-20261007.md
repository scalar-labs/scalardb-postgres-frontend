# Response to the PostgreSQL frontend verification feedback of 2026-10-07

Reviewed against branch `postgres-frontend` at `1fe8373aa`, the same tree the feedback was written on.
Every reproducible finding was re-run on that tree first; all reproduced. Fixes below are in the working
tree on top of `1fe8373aa`. The reviewer's SQL-92 Entry suite (`verification/sql92-entry`, 317 cases) was
re-run with `difftest/run.py` against native PostgreSQL 18 (C locale, UTC) and the frontend on a C-locale
PostgreSQL backend; outputs are in `sql92_*.out` next to this file.

## Summary

| Set | Cases | Reviewer: OK / frontend passes something it should reject | Now: OK / silently wrong |
|---|---|---|---|
| S1 data types | 26 | 4 / 5 | 6 / 0 (type modifiers are reported with a NOTICE, see F-003) |
| S2 constraints | 30 | 5 / 13 | 5 / 0 (constraints are reported with a NOTICE, see F-002) |
| S3 query | 45 | 38 / 2 | 38 / 0 |
| S4 predicates | 56 | 51 / 0 | 54 / 0 |
| S5 subqueries | 18 | 16 / 0 | 16 / 0 |
| S6 value expressions, aggregates | 37 | 29 / 2 | 30 / 0 |
| S7 NULL and three-valued logic | 22 | 22 / 0 | 22 / 0 |
| S8 DML | 39 | 30 / 0 | 30 / 0 |
| S9 transactions | 21 | 6 / 1 | 8 / 0 |
| S10 identifiers | 17 | 12 / 0 | 13 / 0 |
| S11 cursors | 6 | 1 / 0 | 1 / 0 |
| Total | 317 | 214 / 23 | 223 / 0 |

"Silently wrong" counts the cases the reviewer flagged as "frontend accepts what PostgreSQL rejects" plus
the wrong-result DIFFs attributable to the frontend. Every remaining difference is now one of: an error the
frontend raises on purpose (unsupported feature, or a guarantee it cannot give), a NOTICE-announced type or
constraint limitation, a ScalarDB core limitation, or a PostgreSQL-side error.

## Findings

| ID | Severity | Status | What changed |
|---|---|---|---|
| F-002 constraints dropped | high | partly fixed, rest announced | Duplicate column names are refused (42701). `DEFAULT` in VALUES means NULL instead of failing. `NOT NULL` (non-key), `UNIQUE`, `CHECK`, `REFERENCES`, `DEFAULT`, table-level constraints and `serial` are accepted with a `NOTICE` per item ("... is not enforced by ScalarDB") rather than silently. Enforcing them needs persistent column metadata that ScalarDB does not have; refusing them would break every ORM's `create_table`. |
| F-003 type modifiers dropped | high | announced | `CHAR(n)`, `VARCHAR(n)` length, `NUMERIC/DECIMAL(p,s)`, `SMALLINT` get a `NOTICE` at `CREATE TABLE` stating exactly what is lost. `FLOAT(p)` with p up to 24 is now `real`. `DEC` is accepted. A decimal written to an integer column is rounded as PostgreSQL does. Exact NUMERIC needs core DECIMAL (`core-todo.md` item 4). |
| F-010 / F-011 invalid aggregates accepted | high | fixed | PostgreSQL's 42803 rules: an aggregate in WHERE, a nested aggregate, and an ungrouped column outside an aggregate are errors. The functional-dependency rule (whole primary key grouped) is honored. |
| F-012 `int > 12.5` fails | high | fixed | A non-integer literal against an integer column is compared in memory instead of being converted for pushdown. `id = 'abc'` still fails as PostgreSQL does. |
| F-013 isolation level ignored | high | fixed | `BEGIN ISOLATION LEVEL ...`, `SET TRANSACTION ...`, `SET SESSION CHARACTERISTICS ...` and `SET [default_]transaction_isolation` are honored: a level stronger than the engine's is refused with `0A000`; equal or weaker is accepted and shown. `SHOW transaction_isolation` reports ScalarDB `SNAPSHOT` as `read committed`, as suggested (no snapshot across records), and `serializable` when the server runs SERIALIZABLE. |
| F-014 `SET TRANSACTION READ ONLY` ignored | high | fixed | The ScalarDB transaction now starts with the first statement after BEGIN, so `SET TRANSACTION READ ONLY` shapes it; after a statement it fails with `25001` as in PostgreSQL. `default_transaction_read_only` is honored for autocommit statements too. |
| F-021 MySQL collation | high | core; documented | Known core issue, collation support is being merged into core. The getting-started limitations now say that TEXT comparison follows the backing database's collation and recommend a binary collation. |
| F-023 uncorrelated subquery per outer row | high | fixed | An uncorrelated subquery is evaluated once per statement execution and cached; correlated ones stay per row. The 10-level nested `IN` that took 17 s now takes 0.04 s. |
| F-005 range comparison vs backend collation | medium | documented | difftest README now requires the C locale on the backing cluster as well; getting-started limitations cover it. |
| F-009 `cached plan must not change result type` | medium | core | Added to `core-todo.md` (item 8): retry once on that SQLSTATE in the JDBC adapter, or `prepareThreshold=0` on the metadata pool. |
| F-015 UNION type mismatch | high | fixed | Columns of a set operation must agree in type category, else `42804` "UNION types X and Y cannot be matched". An untyped literal takes the other members' type, so `SELECT '7' UNION SELECT 7` is one row and the result advertises one type. |
| F-016 delimited identifiers | medium | (a) refused + core, (b) fixed, (c) frontend fixed + core | (a) pgjdbc resolves column names case-insensitively inside core's JDBC adapter, so `"a"` and `"A"` read each other; the frontend now refuses such a `CREATE TABLE` with a message, and the core fix (read by index) is `core-todo.md` item 7. (b) A quoted qualifier with spaces or capitals now survives re-resolution. (c) `""` inside a quoted identifier is one quote; core's `enclose` does not escape it, so such a table fails at CREATE (core item 10) rather than reading a wrong column. |
| F-020 DDL not transactional, scan-after-write | medium | core; documented | Both are now in the getting-started limitations; scan-after-write is `core-todo.md` item 1. |
| F-001 SAVEPOINT docs | low | documented | getting-started now matches compat: `ROLLBACK TO SAVEPOINT` works only before a write, and after a write the whole transaction aborts, losing earlier writes. |
| F-004 load time | low | documented | Wording now says the time depends on the database's location. |
| F-006 leak.py port | low | fixed | `BACKEND_PORT` selects the backing cluster. |
| F-008 / F-019 / F-024 raw ScalarDB errors | low | fixed where the frontend can | PK update on the keyed path: clear error. Duplicate key in `INSERT ... SELECT`: `23505`. Invalid LIKE escape: `22025`. Write in a read-only transaction: `25006`. Empty-string key: `22023`. |
| F-017 cursors, F-018 `- -x`, USER, views, GRANT | low | partly | `USER` now works and returns the connecting user. The rest stay clear errors and are listed in the limitations; `- -x` is a JSQLParser 5.4 parse limit. |
| F-022 MySQL DDL error passthrough | low | core | Noted with item 7 in `core-todo.md`. |
| F-025 duplicate column names in core | medium | core | `core-todo.md` item 9. |
| F-007 SERIALIZABLE reader starvation | info | core | Already `core-todo.md` item 5. |

## Verification

- Unit tests: 116 of 116 (108 before).
- difftest on the frontend's own cases: read 240 OK of 246 (the same 6 known cases as before), types 14 of 14,
  write, autocommit and bind sets identical to the previous baseline.
- Driver scenarios (pgx, psycopg and SQLAlchemy, node-postgres and Sequelize, pg and ActiveRecord): unchanged.
- SQL-92 Entry: the table above; outputs in this directory.

## Not done, by decision

- Enforcing constraints and keeping type modifiers needs persistent declared-column metadata, the same
  gap as JSON and uuid column OIDs and `serial`. That is a design item (a frontend metadata table, or a
  core column annotation), not a quick fix; the NOTICEs make the gap visible meanwhile.
- `SHOW transaction_isolation` naming follows the reviewer's suggestion; if the product wants
  `repeatable read` for SNAPSHOT, it is one string in `PostgresServer.main` and `QueryProcessor`.
