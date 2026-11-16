---
title: "feat: Sequences kept by the frontend, for serial, identity and nextval"
type: feat
status: draft
date: 2026-11-09
---

# feat: Sequences kept by the frontend, for serial, identity and nextval

## Summary

ScalarDB has no sequences or auto-increment, so today `serial`, identity columns and `nextval` generate
nothing: an INSERT that leaves the key to the database fails with a not-null violation. Every ORM's default
id setup hits this (compat runs of 2026-11-09: SQLAlchemy and Sequelize `SERIAL`, ActiveRecord `bigserial`,
Spring Data JDBC `save` with a null id, Hibernate `IDENTITY` and `SEQUENCE`).

The frontend will keep sequences itself: one counter row per sequence in its metadata namespace, from which
each process reserves a block of values in a short transaction of its own and then hands them out from
memory. A column's default (`nextval('t_id_seq')`) is recorded with its declared type, and INSERT fills it in.
`RETURNING`, which already works, returns the value, so pgjdbc's generated keys and every ORM above work
without changes on their side.

The design gives PostgreSQL's semantics for a sequence with `CACHE n`: values are unique, increase within one
process, may arrive out of order across processes, and leave gaps (rollbacks and restarts). PostgreSQL makes
the same promises, so applications written for it are not relying on more.

## Problem statement

- `QueryParser.unenforced` (QueryParser.java:2528, 2572) accepts `serial`, `bigserial`, `smallserial` and
  `GENERATED ... AS IDENTITY` with a NOTICE that they generate nothing.
- `insertColumns` (QueryParser.java:5949) and `checkRowLength` (5924) raise not-null when a key column is
  omitted; `DEFAULT` in VALUES and `DEFAULT VALUES` mean NULL (6485, 8206, 8211).
- `nextval` is an unknown function (`0A000`, Evaluator.java:4478); HikariCP treats `0A000` as a broken
  connection, so Hibernate's `SEQUENCE` strategy also loses the pooled connection.
- The catalog answers no defaults and no sequences: `pg_attrdef`, `pg_sequence`, `pg_sequences` are declared
  empty (Catalog.java:1201-1208), as are `information_schema.sequences` and `columns.column_default`.

## Design

### Storage

A second table in the frontend's namespace, created by `CREATE FRONTEND METADATA TABLES` next to
`column_types` (TypeStore.java:327):

```
scalardb_postgres_frontend.sequences
  sequence_name   TEXT    partition key   -- namespace.name
  data_type       TEXT                    -- smallint | integer | bigint
  increment_by    BIGINT
  min_value       BIGINT
  max_value       BIGINT
  start_value     BIGINT
  next_value      BIGINT                  -- the first value no process has reserved
  is_called       BOOLEAN                 -- setval(..., false) semantics
  owned_by        TEXT                    -- namespace.table.column, or null
  created_by      TEXT                    -- DDL token, as column_types rows have (TypeStore.write)
```

The column default goes into `column_types` as a new nullable column, `column_default` (text of the
expression, `nextval('orm.widgets_id_seq')`), plus `identity` (`a`, `d` or null, as `pg_attribute.attidentity`).
The type row is read whenever a table's `ColumnMap` is built (QueryParser.java:6651), so knowing a column's
default costs no extra read per statement. Constant `DEFAULT`s, refused today (QueryParser.java:2531), can
later use the same column; they are out of scope here.

*Alternative considered:* a `serial` marker in `sql_type` with the sequence name derived from the column, and
no new column. It saves the upgrade step but cannot express identity, `OWNED BY` a renamed sequence, or later
constant defaults.

**Upgrade.** Existing deployments re-run `CREATE FRONTEND METADATA TABLES`. It creates `sequences` and adds the
two columns to `column_types` (ScalarDB's `addNewColumnToTable`, to verify), and, with a service user, grants it
READ, WRITE and DELETE on the new table. Until the upgrade has run, CREATE TABLE with `serial` fails with a
55000 that names the command, as a declared type does today without the table (QueryParser.java:2903).

### Reserving blocks

A new `Sequences` class (one per process, shared by its sessions, like `TypeStore`) holds per sequence the
current block `[next, end)` and, optionally, the one after it.

- `next(name)` takes a value from the block in memory, under a per-sequence lock. No ScalarDB call.
- When the block is empty, it reserves one: in a transaction of its own (TypeStore's `inTransaction`, run as
  the frontend's identity or service user, TypeStore.java:377), Get the row, check bounds, Put
  `next_value + blockSize * increment_by`, commit. Only then are the values handed out.
- Prefetch: when a block is half used, a background reservation fetches the next one, so a session waits
  only when the prefetch has not finished.
- Conflicts (two processes reserving at once): retry with jittered backoff, a bounded number of times, then
  `55000` naming the sequence. An unknown commit outcome discards the block (the values may or may not be
  taken; a gap is safe, a reuse is not) and reserves again.
- Block size: `scalar.db.postgres.sequence_block_size`, default from Phase 0's measurement (100 expected). It
  is in values, so `INCREMENT BY 50` (Hibernate's) reserves 50 x block size.

**Why values are unique.** Every reservation reads the counter row and writes it in one transaction.
Consensus commit prepares a written record conditionally on the version the transaction read, so of two
transactions that read the same `next_value`, only one commits. This holds at every isolation level ScalarDB
offers, since it is the write-write check, not the read validation. *To verify in the core source before
Phase 1* (memory: read core before claiming).

**What callers see.** Within a process values increase. Across processes they interleave by block. A
transaction that rolls back keeps its values used, and a restart loses the rest of its blocks: gaps, as in
PostgreSQL. `setval`, `ALTER SEQUENCE RESTART` and `DROP SEQUENCE` change the row at once, but other processes
finish the blocks they hold first, as PostgreSQL sessions finish their cached values; this is documented.

The user's transaction never touches the counter row, so it cannot conflict on it, and no lock is held
across the two transactions.

### SQL surface

| Statement or function | Behavior |
|---|---|
| `serial`, `bigserial`, `smallserial` | integer column, `NOT NULL`, sequence `<table>_<column>_seq` (PostgreSQL's name, with its truncation and numeric suffix on collision) owned by the column, default `nextval(...)` |
| `GENERATED BY DEFAULT AS IDENTITY [ (options) ]` | as serial, `attidentity = 'd'` |
| `GENERATED ALWAYS AS IDENTITY` | as above, `'a'`: an explicit value is refused (428C9) unless `OVERRIDING SYSTEM VALUE` |
| `CREATE SEQUENCE [IF NOT EXISTS] name [AS type] [INCREMENT BY] [MINVALUE] [MAXVALUE] [START WITH] [CACHE] [NO CYCLE] [OWNED BY]` | `CACHE` is accepted and ignored (the block is per process); `CYCLE` refused (0A000) |
| `ALTER SEQUENCE ... RESTART [WITH] / INCREMENT BY / OWNED BY / RENAME TO` | other options refused |
| `DROP SEQUENCE [IF EXISTS] ... [CASCADE]` | refused while a column default uses it, unless CASCADE (which drops the default) |
| `nextval`, `currval`, `lastval`, `setval` (2 and 3 arguments) | `currval`/`lastval` are per session; `nextval` past the bounds is 2200H |
| `pg_get_serial_sequence(table, column)` | the owned sequence's qualified name, or null |
| `DROP TABLE` | drops its owned sequences afterwards (`afterDdl`, QueryParser.java:2964) |

Argument forms: `'name'`, `'name'::regclass`, schema-qualified, and quoted identifiers. JSqlParser's
support for `CREATE/ALTER SEQUENCE` is to be checked in Phase 0; a hand parser like `Dcl`'s is the fallback.

### INSERT

Both paths change: the direct one (`insert`, QueryParser.java:5847) and `insertRows` (6477, which every
INSERT with `RETURNING` takes).

- A column with a default may be omitted, listed with `DEFAULT`, or filled by `DEFAULT VALUES`; the key
  checks in `insertColumns` and `checkRowLength` accept such a key column.
- The `DEFAULT` keyword stops being folded to NULL at plan time: a marker survives to execution, where the
  default is evaluated.
- `nextval` runs per row at execution, never at planning: a cached plan (`plan.cacheable`,
  QueryParser.java:776) is executed many times. A test pins this.
- `INSERT ... ON CONFLICT` consumes a value even when the row conflicts, as in PostgreSQL.
- `ALTER TABLE ADD COLUMN ... serial` on a table with rows would have to fill every row; refused (0A000)
  in this feature, allowed on an empty table.

### Catalog

Rows for what exists now replace the empty declarations:

- `pg_class` rows with `relkind 'S'`, oids from the same scheme as tables; `pg_sequence`, `pg_sequences`,
  `information_schema.sequences` (Hibernate reads it at startup to check `increment_by`).
- `pg_attrdef` with `adbin` that `pg_get_expr` renders as `nextval('t_id_seq'::regclass)`;
  `pg_attribute.atthasdef` and `attidentity`; `information_schema.columns.column_default`,
  `is_identity` and the `identity_*` columns.
- `pg_depend` rows for `OWNED BY` (`deptype 'a'`, identity `'i'`), which psql's `\d` and Rails read.
- Answers checked against PostgreSQL 16's for the clients' captured queries
  (`engine/src/test/resources/clients`, captured with `difftest/catalog`), recaptured with serial and
  identity tables.

### Privileges (with Cluster authentication)

The counter rows are the frontend's, like type rows, so the session user needs no ScalarDB privilege on
them. A column default's `nextval` is allowed to anyone who may INSERT into the table. PostgreSQL also
requires USAGE on the sequence; this is more permissive, and only lets a user consume values.

For free-standing sequences (Hibernate's `SEQUENCE` strategy) there is no table privilege to lean on. See
open question 1.

## Phases

**Phase 0: verify and measure (before any feature code).**
- Read the ScalarDB core: the conditional prepare on read versions (uniqueness argument), and
  `addNewColumnToTable` on the transaction admin.
- Check JSqlParser on `CREATE SEQUENCE ... OWNED BY`, `ALTER SEQUENCE`, `GENERATED ... AS IDENTITY (...)`,
  `OVERRIDING SYSTEM VALUE`.
- Prototype only the reservation loop and measure on the local PostgreSQL (build/local-pg, 25432) and the
  cloud setup (bench/cloud): reservation latency, and conflict and retry rate with 1, 4 and 16 concurrent
  reservers on one row, at block sizes 1, 100 and 1,000. Exit: a default block size that keeps retries rare at
  the TPC-C insert rate of the largest cloud run, and a written number for the cost of a reservation.

**Phase 1: sequences.** The `sequences` table and upgrade, `Sequences`, `CREATE/ALTER/DROP SEQUENCE`,
`nextval`/`currval`/`lastval`/`setval`. Hibernate's `SEQUENCE` strategy works after this phase.

**Phase 2: column defaults.** `column_default` and `identity` in `column_types`; serial and identity in
CREATE TABLE; both INSERT paths; `DROP TABLE` drops owned sequences; removal of the NOTICEs. Spring Data JDBC,
Hibernate `IDENTITY`, SQLAlchemy, Sequelize and ActiveRecord defaults work after this phase.

**Phase 3: catalog.** As above, with the recapture and difftest answers.

**Phase 4: documentation and compat.**

## Tests

- Unit: QueryParserTest and PostgresServerTest for every row of the SQL table; a cached prepared INSERT run
  twice gets two values; `DEFAULT` keyword and `DEFAULT VALUES`; `GENERATED ALWAYS` refusal and override;
  bounds (2200H) for smallint and integer; unknown commit outcome discards the block (a test double for the
  manager).
- Uniqueness under concurrency: extend `difftest/Concurrency.java`: many sessions, then two frontend
  processes on one backend, inserting into one serial table; assert no duplicate and count every value.
- Difftest: a sequences case set against native PostgreSQL (`nextval`, `currval` before `nextval` is 55000,
  `setval` with `is_called`, `lastval`, identity override, `pg_get_serial_sequence`). Cross-process ordering is
  excluded by construction: one session per case.
- Compat: each ORM's default-id step in `compat/` turns from FAIL to ok; Hibernate starts without its
  `create sequence` warning.
- Fault: kill a frontend between reservation and use; the restarted one continues past the lost block.

## Documentation

- `LIMITATIONS.md`: the serial line (165), the catalog's "no sequences / no column defaults" rows (83-88), and
  a new entry on ordering across processes, gaps and `CACHE` being per process.
- `docs/user/clients.md` "Generated values and defaults"; `docs/user/sql-reference.md` for the statements and
  functions; `docs/type-encoding.md` for the new table and columns, since other ScalarDB clients read the
  metadata; `compat/README.md` tables.
- The operations page: re-run `CREATE FRONTEND METADATA TABLES` after upgrading.

## Out of scope

- `CYCLE`; gapless or globally ordered values (block size 1 would be possible but makes the counter row
  hot, see bench/scalardb-core-issue-hot-row-retries.md).
- Constant and expression `DEFAULT`s, and `ALTER COLUMN SET/DROP DEFAULT`: follow-ups on the same
  `column_default` column.
- `ALTER TABLE ADD COLUMN serial` on a non-empty table.
- A coordination-free `unique_rowid()`-style default (CockroachDB's) as an alternative to sequences.

## Open questions

1. **Who may call `nextval` on a free-standing sequence with authentication on?** Options: any
   authenticated user (simplest; a user can only consume values), the namespace's users judged by a
   privilege on some table in it, or `GRANT USAGE ON SEQUENCE` kept in the frontend's metadata (Dcl.java
   refuses `SEQUENCE` today). Needed before Phase 1 ships with auth.
2. **Block size default and adaptivity.** Fixed per property, or growing when a process reserves often.
   Phase 0's numbers decide; adaptive only if a fixed size cannot fit both a few-insert app and TPC-C.
3. **Process restarts in Cluster.** A rolling restart of N nodes loses up to N blocks per sequence. With a
   block of 100 this is negligible for ids, but visible to anyone expecting dense values. Document only?
