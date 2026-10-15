---
title: "refactor: Keep declared types in a frontend metadata table instead of column names"
type: refactor
status: draft
date: 2026-10-15
revises: docs/plans/2026-10-14-001-feat-keep-type-modifiers-exact-numeric-plan.md
---

# Keep declared types in a frontend metadata table

## Why this revision

On 2026-10-14 the user proposed a frontend-owned metadata table, settled on one table for the whole ScalarDB
deployment, and accepted it with a one-command setup ("If it can be one step, then it's also good"). The plan
recorded the column-name encoding (`amount__pg_n10_2`) as preferred instead, which the user never chose, and
Phases 2-5 were built on it. This revision moves the declared types into the metadata table and removes the
encoding from column names.

**What stays:** everything about values. `NUMERIC(p,s)` up to 18 digits is a scaled `BIGINT`, wider or unbounded
`NUMERIC` is exact decimal `TEXT`, `CHAR(n)` is stored trimmed and padded on read, `SMALLINT` is a range-checked
`INT`. Rounding, precision, length and range checks, the bpchar comparison rules, key and range pushdown in
stored units, catalog and RowDescription types, and `ALTER COLUMN TYPE` refused with `0A000` are all unchanged.
`ColumnType` keeps its kinds and value rules; only where it learns a column's kind changes.

**What goes:** the `__pg_` suffix, its 56-byte budget (`42622` for long names), the `__pg_` ban in client names
(`42602`), the name translation in `ColumnMap` (`storageByClient`, `clientByStorage`, `toStorage` rewriting),
`UNKNOWN_ENCODING`, and the duplicate-client-name handling. Backend column names are the client's names again.

## The table

One table for the deployment, in a reserved namespace that the catalog hides and user DDL may not target (as
`coordinator` and `scalardb` are today). ScalarDB namespaces are flat, and `scalardb` is ScalarDB's own schema
on the JDBC storage, so the table gets a namespace of its own.

ScalarDB core has no plan for type data in its own metadata table, so this table defines the format, and it
may become the baseline for core (decided 2026-10-15). It is therefore laid out like core's metadata table
(one row per column, keyed by `full_table_name` and `column_name`), uses standard SQL type names rather than
PostgreSQL's or this frontend's codes, and keeps the declaration separate from how the value is stored:

```
scalardb_postgres_frontend.column_types
  full_table_name  TEXT  partition key    namespace.table, as in core's metadata table
  column_name      TEXT  clustering key
  sql_type         TEXT  NUMERIC | VARCHAR | CHAR | SMALLINT
  type_precision   INT   NUMERIC digits; null for NUMERIC without a precision
  type_scale       INT   NUMERIC scale; null when type_precision is null
  type_length      INT   VARCHAR and CHAR length
  encoding         TEXT  how the value is stored: SCALED_BIGINT (value * 10^scale), DECIMAL_TEXT (exact
                         decimal string), TRIMMED_TEXT (CHAR without trailing spaces), TEXT, INT
  data_type        TEXT  the ScalarDB type the encoding needs (BIGINT, TEXT, INT): a guard, see "Applying a row"
  created_by       TEXT  a random id per DDL statement, so a failed DDL deletes only its own rows; frontend
                         bookkeeping, not part of the format core would adopt
```

- `precision`, `scale` and `length` are reserved or awkward words in the backends, hence the `type_` prefix.
- Only columns with a declared type have a row. A table with none has no rows and costs one empty scan.
- `encoding` is the storage contract for core to take over later (decision 4 of the original plan): core can
  read these rows into its own metadata without rewriting values. `ColumnType` maps a row to its kind;
  the short codes (`n10_2`, `t`, ...) disappear from storage.
- One partition per table, like core's: a table's types are one scan, and the catalog reads one partition per
  table it lists, next to the `getTableMetadata` call it already makes per table.

## Identity

| Deployment | Who reads and writes `column_types` |
|---|---|
| `scalar.db.postgres.auth=none` (ScalarDB, or Cluster with a configured user) | The configured ScalarDB user, as every other call does today |
| `auth=userpass` or `oidc_jwt` | A service user, `scalardb_postgres_frontend`, through the same transaction manager and admin: each call is wrapped in `UserpassHolder.executeWithUserpass(serviceUser, password, call)`, the way `ClusterIdentity.run` wraps a session's calls. No second `TransactionFactory`. |

New properties, required only with auth on: `scalar.db.postgres.metadata_user` (default `scalardb_postgres_frontend`) and
`scalar.db.postgres.metadata_password`. `Main.checkedAuth` keeps refusing `scalar.db.username`/`password` with
auth on; these two are different names on purpose, and are used for nothing but `column_types`. Startup with auth
on and no `metadata_password` fails with a message naming the setup command.

To verify before building: that a userpass call works on a manager whose sessions log in with OIDC tokens
(`oidc_jwt`), and that Cluster table privileges can be granted on `scalardb_postgres_frontend.column_types` alone.

## The one command

A SQL statement, run through the frontend, alongside `CREATE COORDINATOR TABLES`:

```sql
CREATE FRONTEND METADATA TABLES [IF NOT EXISTS];
```

- **Auth off:** creates the namespace and the table. Returns no rows.
- **Auth on, superuser only (`42501` otherwise):** creates the namespace and table, creates the user
  `scalardb_postgres_frontend` with a random 32-byte password, grants it `SELECT, INSERT, UPDATE, DELETE` on that one
  table and nothing else, and returns the two properties to add:

```
            property              |               value
----------------------------------+----------------------------------
 scalar.db.postgres.metadata_user     | scalardb_postgres_frontend
 scalar.db.postgres.metadata_password | 8Jx...  (shown once)
```

Running it again creates only what is missing: an existing user keeps its password, and only the grant is
made sure of, so a rerun never breaks running frontends (decided 2026-10-15). The password is rotated with the
existing `ALTER USER scalardb_postgres_frontend PASSWORD '...'`, then `metadata_password` is updated and each frontend
restarted; frontends still on the old password cannot read types until restarted. Two passwords during a
rotation are not supported until someone needs them.

The table is never created implicitly, with or without auth (decided 2026-10-15). DDL that declares a type
while it is missing fails with `55000` and the hint "run CREATE FRONTEND METADATA TABLES".
DDL that declares no type, and every query, work without it (an absent table means no declared types).

## DDL: write before, delete after

ScalarDB DDL is not transactional, and neither is a DDL plus a row write. Every DDL orders its two writes so a
crash between them leaves a harmless leftover row, never a column without its type: a scaled `BIGINT` read
without its row would show 1990 for 19.90, and a write would store 19.90 as 19 or 20.

| Statement | Order |
|---|---|
| `CREATE TABLE` | Check the table does not exist; write the rows (one transaction, as the service identity); `admin.createTable`. If `createTable` fails, delete the rows with this statement's `created_by`. |
| `ALTER TABLE ADD COLUMN` | Check the column does not exist; write the row; `addNewColumnToTable`; on failure delete it. |
| `ALTER TABLE RENAME COLUMN` | Write the row under the new name; `renameColumn`; delete the old row. |
| `DROP TABLE`, `DROP SCHEMA ... CASCADE` | Drop; then delete the table's rows (or the namespace's partition). |
| `CREATE TABLE` over leftover rows | Rows with the same table name are replaced in the same transaction that writes the new ones. |

**Applying a row.** A row is used only if its column exists and has the row's `storage_type`. A leftover row for a
dropped table, or for a column since recreated as another type by another ScalarDB client, is ignored. The
remaining hole: rows written by a `CREATE TABLE` whose process died before `createTable` failed, for a table
another client created in that same window with the same column names and storage types. Documented, not
defended.

**Privileges.** The rows are written by the service identity, but only after the frontend's checks and before
the user's own `createTable`, which Cluster authorizes. A user who may not create the table gets an error, and
their rows are deleted (or, after a crash, are leftovers that the existence check and the guard keep inert).

## Reading and caching

- `QueryParser.columnMap(namespace, table)` reads the table's rows with the `TableMetadata` and builds the
  `ColumnMap` from both. Same per-session cache, same invalidation (`DDL_GENERATION` in the process, ScalarDB's
  metadata cache for other processes, todo 011): no second cache with its own expiry.
- `Catalog` reads each listed table's partition next to its `getTableMetadata` call.
- The frontend reads rows only for tables whose metadata the session could fetch, so a user does not learn the
  types of tables they cannot see.
- Cost: one extra small scan when a session first touches a table, and one per namespace when the catalog is
  built. Measured in the TPC-C rerun.

## Mixed versions and other clients

- An older frontend, or another ScalarDB client, now sees `amount BIGINT` holding 1990 with nothing in the name
  to warn it. The rolling-upgrade rule in RELEASE-NOTES.md stays (finish upgrading before the first typed
  DDL), and docs/type-encoding.md becomes the contract for reading `column_types`.
- Nothing has shipped with the column-name encoding, so there is no migration from it. Tables created on this
  branch for testing are dropped and recreated.

## Work

1. `CREATE FRONTEND METADATA TABLES`, the reserved namespace, the properties and the service-identity wrapper; startup
   checks. Tests with auth off; the difftest cluster-auth harness for auth on.
2. `ColumnMap` builds from metadata plus rows; drop the name translation. `ColumnType.parse(name, type)` becomes
   `ColumnType.of(row, type)`; drop `MARKER`, `storageName`, `checkClientName`, `UNKNOWN_ENCODING`.
3. DDL paths in `QueryParser` (`CREATE TABLE`, `ADD COLUMN`, `RENAME COLUMN`, `DROP TABLE`, `DROP SCHEMA`) with
   the ordering above; crash tests that stop between the two writes.
4. Catalog reads each listed table's rows.
5. Tests: the six test classes that assert `__pg_` names (about 120 assertions) move to asserting rows. Difftest
   baselines should not change, since clients never saw storage names; any change is a bug.
6. Docs: rewrite docs/type-encoding.md, RELEASE-NOTES.md (setup command, no `__pg_` columns), LIMITATIONS.md
   (drop the 56-byte and `__pg_` rules), integration-design.md, getting-started (add the command to
   schema.sql, after `CREATE COORDINATOR TABLES`), the original plan's decision text.
7. TPC-C rerun: native, frontend DOUBLE DDL, frontend typed DDL.

Rough size: one phase, similar to Phase 5.

## Decisions (2026-10-15)

1. The table is created only by `CREATE FRONTEND METADATA TABLES`, never implicitly.
2. A rerun of the command keeps an existing password; rotation is `ALTER USER ... PASSWORD`.
3. Namespace and service user `scalardb_postgres_frontend`, table `column_types`.
4. The row format is ours to define and may become core's baseline: core's key layout, standard SQL type
   names, declaration separate from encoding.
