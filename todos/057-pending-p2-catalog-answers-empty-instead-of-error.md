---
status: pending
priority: p2
issue_id: "057"
tags: [catalog, psql, compatibility]
dependencies: []
---

# Catalog queries the frontend cannot answer come back empty instead of failing

Found on 2026-10-16 while checking psql's meta-commands against a live frontend (main 850ca38, psql 18).
Principle agreed with the user: a catalog query returns the truth or an error, never a plausible empty answer.

## Problem Statement

Three ways a catalog query answers "nothing" when that is not true:

1. **Any unplannable read that mentions `pg_catalog.` returns no rows** (`QueryParser`, the `catch
   (IllegalArgumentException e)` after `plan(sql)`, "for the tables involved the answer is always
   nothing"). It catches tables the frontend does not emulate at all, including ones that do not exist:

   ```sql
   SELECT count(*) FROM pg_catalog.pg_cast;      -- (0 rows): not even a row with 0
   SELECT count(*) FROM pg_catalog.pg_foo_bar;   -- (0 rows), where PostgreSQL says relation does not exist
   SELECT count(*) FROM pg_cast;                 -- ERROR: Table not found: mtest.pg_cast
   ```

   This is why `\dC`, `\do`/`\doS`, `\dc`/`\dcS`, `\dx`, `\dF*`, `\dy` and others look like they work. Todo 024
   narrowed it (only reads; not unknown or ambiguous columns), but the tables themselves are still guessed.
   It also hides queries over emulated tables that fail for an unsupported construct (UNION, table
   functions, arrays): their answer is not necessarily empty.

2. **`pg_proc` is emulated as empty** (`Catalog`, "empty: ScalarDB has no functions, ..."), but the frontend
   implements many built-in functions, so `\dfS`, `SELECT ... FROM pg_proc WHERE proname = 'upper'`, the
   JDBC driver's `getFunctions` and tab completion all say they do not exist. The same holds for operators
   (`pg_operator`, `\doS`) and casts (`pg_cast`, `\dC`), which come only from case 1.

3. **Commonly read catalog views are missing** and fail with a misleading message: `pg_tables`, `pg_views`,
   `pg_user`, `pg_sequences`, `pg_matviews`, `pg_stat_activity`, `information_schema.views`,
   `information_schema.routines` (unqualified: `Table not found: <db>.pg_tables`, naming the user's
   namespace instead of saying the relation does not exist).

Empty answers that are true stay as they are: `pg_trigger`, `pg_rewrite`, `pg_publication*`, `pg_depend`,
`pg_inherits`, `pg_policy`, user functions in `pg_proc`. ScalarDB has none of these objects, PostgreSQL
answers empty for a database without them, and drivers, DBeaver, pgAdmin and ORM introspection read them
on connect, so an error there would break working clients.

Separately, four meta-commands fail on a missing column (the right behaviour, but fixable): `\df+`
(`pg_proc.prolang`), `\dT+` (`pg_type.typacl`), `\dL` (`pg_language.lanplcallfoid`), `\dconfig` without a
pattern (`pg_settings.source`). `\sf` needs `pg_get_functiondef`.

## Audit (2026-10-17)

Run with `difftest/catalog` (harness and raw results there): every psql 18 meta-command, every pgjdbc
42.7.14 `DatabaseMetaData` method, DBeaver's recorded catalog queries, every SQLAlchemy 2.0 `Inspector`
method, and `count(*)` over 50 catalog and `information_schema` tables, each against PostgreSQL 18 and the
frontend with the same two tables. The audited build logged every query the fallback answered (44 distinct).

**A. Made-up answers from the fallback (the bug).** Grouped by what the fallback hides:

| Hidden cause | Who gets a wrong answer |
|---|---|
| Unknown `pg_catalog` table: `pg_sequence` | SQLAlchemy `get_columns` raises **NoSuchTableError** for an existing table, and `get_multi_columns` returns `{}`: reflection and Alembic autogenerate see no columns |
| `generate_series(1, array_upper(current_schemas(false), 1))` | pgjdbc's type lookup by name (`TypeInfoCache`): `getTypeInfo` reports `_interval` as OTHER, not ARRAY; also `getVersionColumns` |
| Unknown tables `pg_cast`, `pg_operator`, `pg_conversion`, `pg_extension`, `pg_ts_*`, `pg_amop`, `pg_amproc`, `pg_event_trigger`, `pg_default_acl`, `pg_db_role_setting`, `pg_subscription`, `pg_largeobject_metadata`, foreign-data tables | `\dC`, `\do`, `\dc`, `\dx`, `\dF*`, `\dAo`, `\dAp`, `\dy`, `\ddp`, `\drds`, `\dRs`, `\dl`, `\des`... show "none" |
| Unknown `pg_parameter_acl` | `\dconfig+ work_mem` says the setting does not exist (PostgreSQL: 1 row) |
| Any qualified unknown view | `count(*)` returns **no row at all** for `pg_tables`, `pg_views`, `pg_user`, `pg_stat_activity`, `pg_stat_user_tables`, `pg_locks`, `pg_timezone_names`, `pg_available_extensions`, `pg_aggregate`, `pg_stats`... |
| UNION with `generate_series` over an array (publications footer of `\d table`) | Empty is the true answer here, by luck |

Unqualified, the same tables fail with `Table not found: <database>.pg_tables`, where PostgreSQL finds them
in `pg_catalog` first. `information_schema` has no fallback: its 14 missing views (`views`, `routines`,
`parameters`, `referential_constraints`, `constraint_column_usage`, `check_constraints`, `sequences`,
`triggers`, `table_privileges`, `role_table_grants`, `character_sets`, `domains`, `element_types`,
`applicable_roles`) fail honestly.

**B. Missing columns: honest errors that break common clients.**

| Column or function | Breaks |
|---|---|
| `pg_index.indnkeyatts`, `indclass` | `\d <index>`; SQLAlchemy `get_pk_constraint`, `get_indexes`, `get_unique_constraints`, `get_multi_pk_constraint`, `get_multi_indexes` |
| `pg_proc.pronargs`, `prolang` | `\da`, `\daS`, `\df+` |
| `pg_type.typacl` | `\dT+`, `\dD+` |
| `pg_language.lanplcallfoid`, `lanowner` | `\dL`, `\dLS` |
| `pg_settings.source` | `\dconfig` |
| `pg_opclass.opcintype`, `opcfamily` | `\dAc`, `\dAf` |
| `pg_tablespace_size()`, `pg_get_functiondef()` | `\db+`, `\sf` |

**C. Incomplete catalog: objects that exist but are not listed.** Native vs frontend rows: `pg_proc` 3413 / 0
(the frontend implements many functions), `pg_operator` 799 / none, `pg_cast` 235 / none, `pg_aggregate`
161 / none, `pg_type` 625 / 39, `pg_class` 422 / 5 (no catalog tables, so `\dS`, `\dtS`, `\dvS`,
`getTables(SYSTEM TABLE)` and `information_schema.tables` show none), `pg_attribute` (no system columns:
DBeaver's column list 11 / 5), `pg_depend` 1743 / 0 (DBeaver's Dependencies tab), `pg_collation` 1286 / 0,
`pg_opclass` 177 / 0, `pg_am` 7 / 2. JDBC `getFunctions` 3237 / 0, `getProcedureColumns` 10103 / 0.

**D. Visibility ignores `search_path`.** `pg_table_is_visible` is true for every table: `\dt`, `\di`, `\dp`
and SQLAlchemy's `get_table_names()` list `sales.orders` although `sales` is not on the path.

**E. Outside the catalog, found on the way.** `current_setting('server_version_num')` fails although
`pg_settings` has it (one line in `QueryProcessor`'s defaults); Prisma 6.19's schema engine reads it on
connect. With it added, Prisma's engine still aborts (Rust stack overflow) right after that probe, whose
answer matches PostgreSQL's types: a separate investigation. pgjdbc's `ResultSetMetaData.isNullable` is
`columnNullableUnknown` (2) for a table column where PostgreSQL gives 1.

What already tells the truth: `\d table`, `\dt`, `\di`, `\dn`, `\l`, `\du`, `\dp` contents for user objects;
JDBC `getColumns`, `getPrimaryKeys`, `getIndexInfo` and `getTypeInfo` (apart from `_interval`); DBeaver's
navigator, keys, indexes and DDL queries (no fallback hits).

## Proposed Solutions

1. **Audit, then narrow the fallback to a list.** With the fallback disabled, run every psql meta-command
   (with and without `S` and `+`) and the catalog queries of the JDBC driver, DBeaver, SQLAlchemy and
   Prisma; classify each failing table as true-empty, populate, or error. Replace the catch-all with
   explicitly emulated empty tables (their columns declared, like `pg_trigger`), so an unknown table fails
   with `42P01` as in PostgreSQL. Medium.
2. **Populate `pg_proc`, `pg_operator` and `pg_cast`** from what `Evaluator`/`Operators` implement (name,
   argument and result types), so `\dfS`, `\doS`, `\dC` and JDBC metadata list them. Medium; needs the
   function table to carry types, overlaps todo 040 (type tables duplicated).
3. **Add the missing views** (`pg_tables`, `pg_views` (empty), `pg_user`, `pg_sequences` (empty),
   `pg_stat_activity` (this frontend's sessions), `information_schema.views`/`routines`) and the four
   missing columns. Small each.

## Recommended Action

All three, in order: 1 decides the scope of 2 and 3.

## Acceptance Criteria

- [ ] `SELECT * FROM pg_catalog.pg_foo_bar` fails with `42P01`; unqualified, the message names `pg_foo_bar`,
      not the user's namespace
- [ ] No catalog read answers empty where the frontend has the objects: `\dfS`, `\doS`, `\dC` list what is
      implemented; `count(*)` over an empty catalog table returns one row
- [ ] Every psql 18 meta-command either shows the truth (true-empty included) or errors; the audit's table
      is recorded in LIMITATIONS.md
- [ ] `\df+`, `\dT+`, `\dL` and `\dconfig` work
- [ ] JDBC `DatabaseMetaData` (`getTables`, `getColumns`, `getFunctions`, `getProcedures`) and the difftest
      `compat` runs still pass

## Work Log

- 2026-10-16: found while answering "how many meta-commands are supported"; row counts against PostgreSQL 18:
  `\dfS` 3402 vs 0, `\doS` 799 vs 0, `\dC` 235 vs 0, `\dTS` 107 vs 20 (the supported types; fine).
- 2026-10-17: audit run (above; `difftest/catalog`). Worst harm is A's SQLAlchemy `get_columns`
  NoSuchTableError; B blocks SQLAlchemy keys and indexes and `\d <index>`. Priority stays P2, but A and B
  first. Next: `/ce:plan` with this audit as input.
