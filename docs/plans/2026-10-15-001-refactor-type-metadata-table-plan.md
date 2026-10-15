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
| `auth=userpass` or `oidc_jwt` | A service user, `scalardb_postgres_frontend`, through the same transaction manager: each transaction on `column_types` is begun with the Cluster SDK's per-transaction credentials, `manager.begin(Map.of("auth-type", "userpass", "auth-userpass-username", user, "auth-userpass-password", password))`, which the SDK honours whatever the client's own auth type. No second `TransactionFactory`. |

New properties, required only with auth on: `scalar.db.postgres.metadata_user` (default `scalardb_postgres_frontend`) and
`scalar.db.postgres.metadata_password`. `Main.checkedAuth` keeps refusing `scalar.db.username`/`password` with
auth on; these two are different names on purpose, and are used for nothing but `column_types`. Startup with auth
on and no `metadata_password` fails with a message naming the setup command.

**Verified 2026-10-15** (Cluster `main` 6e2cb011 node, standalone, auth on; a probe through the SDK):

- `UserpassHolder` does not work under `oidc_jwt`: the SDK's default token manager is chosen by
  `scalar.db.cluster.client.auth.type`, and the OIDC one reads only `OidcJwtAccessTokenHolder`
  (`DB-CLUSTER-10066`). Hence the per-transaction attributes above (`AuthOperationAttributes`), which
  `AuthTokenManagerResolver` applies before the default manager.
- On a client configured `auth.type=oidc_jwt` with no token at all, a transaction begun with the service user's
  attributes wrote and read `column_types`, and a one-shot `get` carrying them read it too.
- A table grant (`admin.grant(user, namespace, table, READ, WRITE, DELETE)`) is enough and is all the service
  user gets: reading another table in the same namespace was refused (`DB-AUTH-10008`, READ), and so was
  `createTable` (`DB-AUTH-10008`, CREATE).
- Not exercised: a real OIDC session alongside, for want of an identity provider. The node picks the token type
  per request (`AuthService.getAuthTokenInfo`), so a session's JWT and the service user's login token do not
  interact.

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

## Progress

**2026-10-15: implemented** (items 1-6 of "Work"; the TPC-C rerun is pending). Where the code differs from the
text above:

- **A missing password fails lazily, not at startup.** With auth on and no `metadata_password`, the frontend
  starts; once `column_types` exists, reading any table's types fails with `55000` naming the property. A
  deployment that never declares such types needs no setup, and an existing auth deployment keeps starting
  after the upgrade.
- **`IF NOT EXISTS` is the frontend's.** `CREATE TABLE` checks `tableExists` first and leaves an existing
  table's rows alone; otherwise it writes the rows, then calls ScalarDB without `ifNotExists`, and on a failure
  takes its rows back, or, when the table turns out to exist (another session's), puts back the rows it
  replaced and, with `IF NOT EXISTS`, succeeds. `ADD COLUMN` and `RENAME COLUMN` follow the same helper
  (`QueryParser.withTypes`) with their column as the scope, so a leftover row under the new name is removed.
- **The catalog reads one partition per table**, next to the `getTableMetadata` it already does per table,
  since the partition key is the table (core's layout) rather than the namespace.
- **The command prints rows only with auth**; without, it returns just its tag.
- `ColumnMap` keeps only types and the write guard for rows it cannot read: no name translation is left, and
  `UNKNOWN_ENCODING` now means a row with an encoding this frontend does not know.

Checked: `./gradlew check` (315 tests; `MemoryTypeStore` runs the real `TypeStore` over an in-memory table;
removing the restore, or running the DDL before writing the rows, fails the DDL tests). Live against a Cluster
node with auth on: alice's typed DDL before setup is `55000`; alice running the command is refused by Cluster
(superuser only); admin's run prints the password; without it configured, reads are `55000`; with it, alice
writes 19.995 and reads 20.00, `\d` shows `numeric(10,2)`, `character(3)`, `character varying(5)`,
`smallint`, the backend holds 2000 under `amount` and four rows in `column_types`; RENAME moves the row; a
rerun keeps the password; DROP TABLE deletes the rows. A read right after RENAME COLUMN fails on the node's
stale metadata cache, on `main` too (todo 056). Getting-started schema and tour: as before.
Difftest on fresh databases (PostgreSQL 18, C locale): every set as its baseline (read, types, numeric,
character, session, write, write_ac, bind, SQL-92 s01-s11), except `s02_11_1_create_schema_authorization`
CODE -> FE_ERR, the fresh-database effect noted since Phase 0; fuzz seeds 7, 11, 23 x 300: no DIFF.

**2026-10-15: review fixes.** A correctness and a security review of the change found: a DROP TABLE or RENAME
COLUMN deleting rows another session wrote between the DDL and its cleanup (fixed: the cleanup deletes only rows
still carrying the token they had before, `TypeStore.tokens` / `deleteUnchanged`); a cleanup failure after a DDL
that took effect reported as the statement's error (now logged); a failure inside the undo hiding the DDL's own
error (now suppressed under it); `undo` deleting then re-upserting one key in a transaction (now one upsert);
`"a.b".c` and `a."b.c"` sharing a key (dots refused in new table and schema names); DML on `column_types`
through the frontend (refused, as DDL is); the catalog asking `tableExists` per table while the metadata table
is missing (once per namespace). Documented rather than fixed: a DDL reported as failed although ScalarDB
applied it, and a table recreated by another ScalarDB client inheriting rows (docs/type-encoding.md). Not
real: a non-superuser half-creating the setup (Cluster allows namespaces to superusers only, seen live).
`./gradlew check`: 320 tests; a cleanup deleting regardless of the token fails the two race tests.
