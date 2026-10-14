---
title: "feat: Keep SQL type modifiers and store NUMERIC exactly"
type: feat
status: active
date: 2026-10-14
deepened: 2026-10-14
---

# feat: Keep SQL type modifiers and store NUMERIC exactly

## Enhancement Summary

**Deepened on:** 2026-10-14. **Inputs:** a SpecFlow pass; architecture, data-integrity, simplicity and
performance reviews of the first draft (each checked against `main` @ 1416486 and the ScalarDB source); and every
PostgreSQL behaviour the plan relies on, run against a throwaway PostgreSQL 18.1 (see "PostgreSQL ground truth").

### Key changes from the first draft

1. **One storage boundary instead of thirteen.** Operations are rewritten from client to storage names at the
   five places the engine actually executes them (`ColumnMap.toStorage(Operation)`), not at every builder.
   EXPLAIN therefore shows client names throughout.
2. **The exact/write split already in the code is reused** instead of a new "predicate encoder": the text→column
   converter (`columnFromValue` / `columnFromText`, the single converter of todo 021) becomes exact-only, and
   rounding, padding and truncation happen only on write paths. A **typed** "not representable" outcome joins the
   existing "out of range" one, so the fallbacks give PostgreSQL's "0 rows, no error" for over-precise, too-long
   and out-of-range predicate values without ever turning an in-range comparison into a constant.
3. **Decoding validates and never trusts** stored values (XX001 `data_corrupted` on a contract violation), and
   handles duplicate client names and half-finished renames left by other writers.
4. **CHAR is stored and decoded trimmed and padded only on wire output,** instead of a new `Bpchar` value class
   that would drop out of 27 `instanceof String` branches. Storing it trimmed also keeps a tiny write from
   becoming megabytes for a large n and makes ScalarDB's ordering ignore trailing spaces, as bpchar does. `NaN`/`±Infinity` decode to `Double`, which the evaluator
   already orders as PostgreSQL does, instead of a `NumericSpecial` class.
5. **Re-phased into shippable increments:** a Phase 0 of fixes that change results on existing tables (with
   their own difftest run), pure plumbing, then `NUMERIC(p,s)` first.
6. **Cut or deferred** (see "Review decisions" for what each loses): the `max_scaled_precision` property, the bare
   `v` code, int2 arithmetic operators, ParameterDescription inference, RENAME of encoded columns in the first
   increment.

### New considerations discovered

- PostgreSQL accepts `NaN` in `NUMERIC(p,s)`; the BIGINT encoding cannot hold it (refused, documented).
- PostgreSQL infers `text` (not `varchar`) for a parameter compared with a VARCHAR column.
- The numeric typmod packs the scale as an 11-bit two's complement, and `information_schema` reports
  `numeric_scale` 2046 for `NUMERIC(5,-2)`.
- `float8 → numeric` uses 15 significant digits in PostgreSQL, and `numeric = float8` compares as float8, so such
  comparisons must not be pushed down as scaled integers.
- `1e2147483647` would allocate a 2^31-digit integer if any `setScale` ran before the digit check.
- Prisma's default `DECIMAL(65,30)` and Rails' bare `decimal` land on the TEXT encoding, whose predicates run in
  memory; `LIMITATIONS.md` must say so and recommend p ≤ 18.

### Second review round (technical review, 2026-10-14)

Security, conventions and implementation-feasibility reviewers read the deepened plan against `main` and the
ScalarDB source. Their changes are folded into the sections below:

- **An existing injection bug on `main`** (not caused by this plan): quote characters in identifiers reach
  ScalarDB's JDBC metadata SQL unescaped. Filed separately as `todos/053-…`; refusing those characters is now a
  Phase 0 item, because every encoded name passes through the same path.
- **Typed fallbacks** (`OutOfRange`, `NotRepresentable`) instead of message matching; see §4.
- **The name map travels with the plan**: the static Reader has no metadata, so the plan captures each table's
  `ColumnMap` at plan time and the Reader resolves it by the operation's namespace and table (§3).
- **No global decode memo** (unbounded, and keyed only by name it could leak one table's type to another):
  decoding happens once per table into the cached `ColumnMap`.
- **Storage names are never accepted from SQL**: `toStorage` and the DDL name mapping raise 42703 for a name
  that is not in the table's `ColumnMap`, so a quoted storage name cannot bypass write enforcement.
- **Corrupt or foreign values block only their column**, not the whole table, and XX001 never echoes the stored
  value.
- **One coercion function** (`ColumnType.coerce`) shared by `Evaluator.cast` and the write paths, reusing `Evaluator.toDecimal`, `decimal()`
  and `checkedNumeric` (which already enforce PostgreSQL's 131072 / 16383 limits) rather than a parallel
  `toBigDecimal`.
- **Floats**: `numeric = float8` keeps comparing as float8 in `Evaluator.compare`; PostgreSQL's 15-digit
  float→numeric rule applies only on writes and in `::numeric`.
- **RETURNING carries its own OIDs and typmods**, and `inferOid` returns the declared OID before it looks at the
  storage type.
- **DETAIL on errors** needs a `detail` field on `PgException` and a `'D'` field in `PostgresServer.error()`
  (Phase 3).

### Decisions

- **D1: the `scalardb.frontend.type_modifiers` property — decided 2026-10-14: kept, default `keep`.** The
  simplicity review would have cut it and the data-integrity review would have defaulted it to `drop`. It only
  affects new CREATE/ADD; operators set `drop` during a rolling upgrade, as the release notes say.
- **D2: the deferral list** in "Review decisions". Each item is safe to add later because of the
  unknown-encoding rule, but each is a visible gap until then.

## Overview

Today `CREATE TABLE` maps every SQL type onto one of ScalarDB's eleven types and forgets the rest:
`NUMERIC(10,2)` and `DECIMAL` become `DOUBLE`, `VARCHAR(n)` and `CHAR(n)` become `TEXT`, `SMALLINT` becomes
`INT`, each with a `NOTICE`. The declared type is lost, so `\d`, `information_schema`, drivers and ORMs see
`double precision`, `text` and `integer`; values are neither rounded nor checked; and money columns carry
binary floating-point noise (`19.90 * 3` is `59.699999999999996`).

This plan keeps the declared type of every column the frontend creates, enforces it on write as PostgreSQL
does, shows it everywhere a client can look, and stores `NUMERIC` exactly:

| Declared | Stored in ScalarDB as | Enforced on write |
|---|---|---|
| `NUMERIC(p,s)`, `DECIMAL(p,s)`, p ≤ limit (18; 15 on Cosmos DB, Object Storage and Cluster) | `BIGINT` holding value × 10^s | rounded half up to s; precision → 22003 |
| `NUMERIC`, `DECIMAL` without precision, or p > limit | `TEXT` holding the exact decimal | precision and scale when declared → 22003 |
| `VARCHAR(n)` | `TEXT` | length in characters → 22001; trailing spaces beyond n truncated |
| `CHAR(n)`, `CHAR` = `CHAR(1)` | `TEXT`, trailing spaces trimmed (padded to n on output) | length → 22001 |
| `SMALLINT`, `INT2`, `SMALLSERIAL` | `INT` | 16-bit range → 22003 |

The declared type is recorded **in the ScalarDB column name** (`amount` declared `NUMERIC(10,2)` is the
ScalarDB column `amount__pg_n10_2`), and every client-facing surface shows the client name and declared type.
This is an interim until ScalarDB core has a decimal type or type modifiers; the encoding is specified so core
can adopt it as metadata without rewriting values or renaming columns.

Decisions carried from the design discussion of 2026-10-14 (no brainstorm document):

- Scope is both steps: keep and enforce modifiers (step 1) and exact decimals (step 2).
- One store for the whole deployment; the column-name encoding is preferred over a side table because it needs
  no service identity, is atomic with DDL, is protected by the table's own privileges, and arrives with the
  table metadata. Research confirms it is workable; the side table is the documented fallback.
- Unbounded or wide `NUMERIC` is stored as exact `TEXT`, not refused and not `DOUBLE`; such a column cannot be a
  key or index column.
- Existing `DOUBLE` columns created before this change keep working unchanged.

## Problem Statement

- **Silent result differences.** `LIMITATIONS.md` lists `NUMERIC`/`DECIMAL` first under "Results that can differ
  without an error": arithmetic noise, `WHERE price * 3 = 0.3` finding nothing, `1.005` staying `1.005` in a
  `NUMERIC(10,2)`, `19.90` printing as `19.9`, and `SMALLINT` arithmetic not overflowing.
- **Open boundary failures.** The difftest boundary rerun (`difftest/results/2026-10-08-boundary/`) has open
  rows I-NUM-02..06 (rounding, overflow, 20-digit and unbounded decimals), I-CHAR-02..04 and I-VC-02..03
  (padding, length), I-SMALL-02/03 (range); `sql92-entry/s01_types.sql` has the matching DIFF baselines.
- **Catalog gaps.** `information_schema.columns` returns NULL for `character_maximum_length`,
  `numeric_precision` and `numeric_scale` (`sql92_s01_types` check c26); `atttypmod` and the RowDescription
  typmod are always -1, and `format_type` ignores its typmod argument.
- **No core support.** ScalarDB 3.20 (and the 4.0 branch) has no DECIMAL type, no type modifiers and no persisted
  free-form metadata, so the frontend must record the declaration itself.

## Research Findings

### ScalarDB core (3.20.0-SNAPSHOT jar, matching `origin/master` of the ScalarDB checkout)

- **No persisted free-form metadata.** `TableMetadata` holds names, types, keys, clustering orders, secondary
  indexes and encrypted columns only; the JDBC `metadata` table, DynamoDB's metadata item and Cosmos DB's
  `CosmosTableMetadata` hold the same. `createTable`'s `options` are creation-time only.
- **No DECIMAL type**, in 3.20 or the 4.0 branch (`io/DataType.java`).
- **Column names are not validated by core** beyond Consensus Commit's ten reserved names (`tx_id` … and their
  `before_` forms). JDBC quotes every identifier per engine without escaping, so quote characters must never
  appear; `_` is safe on every engine and storage. Case is preserved, but MySQL (always) and SQL Server (default
  collation) compare column names case-insensitively.
- **Length budget.** The JDBC metadata table caps a column name at 128; the engines at 63 bytes (PostgreSQL,
  silently truncating), 64 (MySQL), 128 (Oracle ≥ 12.2, SQL Server, Db2). Consensus Commit adds `before_<col>` for
  each non-key column, so the frontend already limits column names to 56 bytes (`QueryParser.checkedName`).
  ScalarDB hashes JDBC index names over 63 characters (`JdbcUtils.shortenIndexNameIfNeeded`).
- **Rename.** `Admin.renameColumn` exists; JDBC supports it (Consensus Commit renames `before_<col>` in a second,
  non-atomic step); Spanner cannot rename, Db2 cannot rename key or indexed columns, Cassandra renames key columns
  only, Cosmos DB / DynamoDB / Object Storage cannot rename. `alterColumnType` only widens.
- **Ordering.** BIGINT sorts numerically on every storage; **Cosmos DB and Object Storage limit BIGINT to
  ±2^53** (15 safe decimal digits: 10^15 < 2^53). TEXT sorts by the JDBC database's collation, so a TEXT-encoded
  decimal can never be range-scanned by ScalarDB. Secondary index scans are equality-only.
- **Renaming at the boundary is cheap.** `io.Column.copyWith(String name)` exists; operations have
  `newBuilder(existing)` copy builders with `clear…` methods; conditions are built with
  `ConditionBuilder.column(name)`.
- **Metadata cache.** ScalarDB caches table metadata for 60 s by default
  (`DatabaseConfig.DEFAULT_METADATA_CACHE_EXPIRATION_TIME_SECS`; the standalone server sets 1 s), so another
  process's rename can surface only at COMMIT.

### Frontend (main @ 1416486)

- **DDL**: `QueryParser.createTable`, `unenforced` (notices and 22023 modifier validation), `columnType` /
  `dataType`, `createIndex`, `drop` (DROP INDEX by `table_column_idx`), `alter` (ADD COLUMN and RENAME COLUMN
  only). CTAS is refused (0A000).
- **Where operations execute** (the boundary): the Reader's `crud.get` / `scan` / `getScanner` (QueryParser.java
  ≈856, 861, 875) and `crud.mutate` (≈799, 1153). Builders are spread over at least 13 sites, which is why the
  rewrite belongs at execution.
- **Where columns are built**: `columnFromValue`, `conditions`, `forColumn`, `value`, both `pushable`s,
  `valueColumn`, `existing`, `column`, `columnFromText` and `Optimizer.orTerms` take a `TableMetadata`;
  conditions are frozen at plan time in `Source.pushed` / `pushedOr` and in `keyed()`.
- **Exact versus write conversions already exist**: `value()` (≈5786-5796) rounds a decimal for an integer
  column only on write; `pushable` keeps an "out of range" conjunct in memory (≈5873-5877), `keyed()` falls back
  to the by-scan path (≈5288-5290), `lookupOperation` returns null (≈1540), `beyondRange` (≈1574).
- **Reads**: `rowOf` / `rowsOf` are static and receive only `(qualifier, columns)` from `Reader.open`,
  `Operators.Scan` and four other sites; `lookupGroup` reads keys from the client view and indexes results with
  them (≈1465, 1486-1489, 1504).
- **Output types**: a null `DataType` in `outputType()` already makes `Plan.outputOids` and `PostgresServer.oids`
  fall through to the inferred OID; `Rows.columns()` / `describe()` expose `Map<String, DataType>`;
  `Evaluator.Context.types` is read only by `looksDouble`.
- **Value classes**: `Evaluator.compare` already orders non-finite numbers as PostgreSQL does and
  `Operators.normalize` handles them; there are 27 `instanceof String` branches across Evaluator, QueryParser,
  Json and PostgresServer.
- **Per-row cost today**: `normalize`, `compare` and `SUM` turn every Number into a BigDecimal through
  `toString()`; TPC-C NewOrder is dominated by ~45 ScalarDB round trips (5.5 ms p50), so added CPU is not the risk,
  pushdown is.
- **Catalog**: `Catalog.load` writes `atttypmod = -1` and NULL precision/scale/length; the type table lacks
  `bpchar` (1042); `format_type` ignores typmod; RowDescription typmod is hard-coded -1.
- **Reusable**: `Evaluator.cast` (`numeric(p,s)` rounding with "numeric field overflow", smallint range),
  `PostgresServer.numeric(BigDecimal)` binary encoder, `PgException.Argument`, `typeArguments` / `checkLength`,
  the per-session metadata cache invalidated through `DDL_GENERATION`.
- **Learnings** (difftest reports, todos): the boundary and SQL-92 cases above are ready acceptance tests;
  over-maximum declarations already raise 22023 (071156e); `::numeric(p,s)` casts check precision (todo 027);
  OID and typlen come from one table (todo 040, complete); psql `\d` and DBeaver call
  `format_type(atttypid, atttypmod)`.

### PostgreSQL ground truth (PostgreSQL 18.1, run 2026-10-14)

Every PostgreSQL behaviour this plan relies on was run against a throwaway PostgreSQL 18.1 cluster:

| Behaviour | PostgreSQL 18 |
|---|---|
| `19.905` into `NUMERIC(10,2)` | `19.91` (half up) |
| `123456789.995` into `NUMERIC(10,2)` | 22003 `numeric field overflow`, DETAIL `A field with precision 10, scale 2 must round to an absolute value less than 10^8.` |
| `'Infinity'` into `NUMERIC(10,2)` | 22003, DETAIL `… cannot hold an infinite value.` |
| `'NaN'` into `NUMERIC(10,2)` | **accepted**, reads `NaN` |
| `NUMERIC(5,-2)` ← `12345`; `NUMERIC(2,5)` ← `0.00012` | `12300`; `0.00012` |
| Unbounded `NUMERIC` ← `1.10` | `1.10` (display scale kept); `1.10` and `1.1` are one DISTINCT value and a duplicate key |
| Ordering | `-Infinity` < numbers < `Infinity` < `NaN`; `NaN = NaN` is true; `sum` with a `NaN` is `NaN` |
| `DELETE … WHERE numeric(10,2)_key = 1.005`; `WHERE varchar(5) = 'abcdefgh'`; `WHERE smallint = 40000` | 0 rows, no error |
| `'ab   '` into `VARCHAR(3)` | stored `'ab '` (spaces beyond n are cut, length 3; corrected 2026-10-14); `'abcd'` → 22001 |
| `'abcd'` into `CHAR(3)` | 22001 `value too long for type character(3)` |
| `CHAR(3)` holding `'ab'` | `c \|\| ']'` → `ab]`; `length` 2; `octet_length` 3; `= 'ab'` and `= 'ab'::varchar` true; `::text` `ab`; `LIKE 'ab'` **false**, `LIKE 'ab_'` true |
| `'a'::char(3)`; `'abcd'::char(3)`; bare `CHAR` | `'a  '` (padded); `abc`; `character(1)` |
| CHAR(5) `'ab   '` → `INSERT INTO varchar_col SELECT` | `'ab'` |
| `32768` into `SMALLINT`; `20000::smallint + 20000::smallint` | 22003 `smallint out of range` (both) |
| `smallint + int`; `smallint + smallint` | `integer`; `smallint` |
| `COALESCE(1, 1.5::numeric)`; `COALESCE(numeric, float8)`; `CASE … 1 ELSE numeric(10,2)` | `numeric`; `double precision`; `numeric` |
| `19.90::numeric(10,2) * 3`; `* 1.1`; `10.00 / 3`; `sum`; `avg` | `59.70`; `21.890`; `3.3333333333333333`; `20.00`; `10.0000000000000000` |
| `ON CONFLICT … SET a = EXCLUDED.a * 100` with `1.005` proposed | `101.00` (EXCLUDED is coerced) |
| `numeric_col LIKE '1%'` | 42883 `operator does not exist: numeric ~~ unknown` |
| `atttypmod` for `(10,2)`, `(5,-2)`, `(2,5)`, unbounded | `655366`, `329730`, `131081`, `-1`: `((p << 16) \| (s & 0x7ff)) + 4` |
| `information_schema.columns` | numeric(10,2): precision 10, radix 10, scale 2; unbounded: precision NULL, radix 10, scale NULL; **numeric(5,-2): scale 2046**; varchar(3)/char(3): `character_maximum_length` 3, `udt_name` `varchar` / `bpchar`; smallint: precision 16, radix 2, scale 0 |
| Typmod through a subquery; UNION ALL of equal typmods; of different typmods | kept; kept; `numeric` (-1) |
| Parameter types inferred for `numeric(10,2) = $1`; `varchar(3) = $1`; `char(3) = $2`; `INSERT INTO smallint VALUES ($1)` | `numeric`; **`text`**; `character`; `smallint` |
| `' 1e3 '::numeric(10,2)`; `'-0'`; `'0012.5'::numeric`; `'1.230'::numeric` | `1000.00`; `0.00`; `12.5`; `1.230` |

Not run, to verify in Phase 3's difftest: `float8 → numeric` with 15 significant digits
(`(0.1::float8 + 0.2)::numeric` is `0.3`), and `numeric_col = float8_param` comparing as float8.

## Proposed Solution

### 1. One declared-type model

A new package-private `ColumnTypes.java`, the single place that knows the rules:

```java
// engine/src/main/java/com/scalar/db/frontend/postgres/ColumnTypes.java (sketch; final classes, no records,
// matching the engine's style; ColumnType.Kind always written qualified, as LogicalPlan.Join.Kind is)
final class ColumnType {
  enum Kind { NATIVE, NUMERIC_SCALED, NUMERIC_TEXT, VARCHAR, CHAR, SMALLINT, UNKNOWN_ENCODING }
  final Kind kind; final DataType storage; final int precision, scale, length;

  long oid();              // 1700, 1043, 1042, 21; else Catalog.typeOid(storage) (long, as Catalog)
  int typmod();            // numeric ((p << 16) | (s & 0x7ff)) + 4; varchar/char n + 4; else -1
  String sqlName();        // from a typmod-aware Catalog.typeName(oid, typmod), shared with format_type
  Object coerce(Object v); // write coercion: round / check / strip; shared with Evaluator.cast
  Object decode(Column<?> stored); // validates; never wraps, pads or truncates silently
}

final class ColumnMap {    // one per table, built once with the cached metadata
  TableMetadata clientView();
  String storageName(String client);   // throws PgException.Argument("42703") if unknown
  ColumnType type(String client);
  boolean hasEncoded();
  Operation toStorage(Operation op);   // identity when !hasEncoded()
}

static ColumnType declared(String typeName, List<String> args, int maxScaledPrecision);
static String storageName(String clientName, ColumnType t);   // "amount__pg_n10_2"
static ColumnType parse(String storageName, DataType storageType); // NATIVE fast path without "__pg_"
```

The exact conversion is not a new function: `columnFromValue` / `columnFromText` take the `ColumnType` (instead
of a `DataType`) and become exact-only, throwing `OutOfRange` or `NotRepresentable`; their error messages use
`ColumnType.sqlName()` instead of `pgTypeName(DataType)`. Write coercion is `ColumnType.coerce`, which
`Evaluator.cast` also calls for `::numeric(p,s)`, `::smallint` and `::char(n)` so the two cannot drift, and which
throws typed `PgException.Argument("22003" / "22001", …)` instead of a bare `ArithmeticException`. Numeric
parsing reuses `Evaluator.toDecimal` (changed in place for the float rule), `decimal()` and `checkedNumeric`.

`Catalog.typeOid(DataType)` stays for `NATIVE` columns, so tables created before the change, and tables created
by other ScalarDB clients, behave exactly as today.

### 2. The column-name encoding (the contract)

```
<client name>__pg_<code>
  code := n<p>_<s>     NUMERIC(p,s), stored as BIGINT × 10^s        e.g. amount__pg_n10_2
        | t<p>_<s>     NUMERIC(p,s), p > max scaled precision, TEXT e.g. big__pg_t30_10
        | t            NUMERIC / DECIMAL without precision, TEXT   e.g. total__pg_t
        | v<n>         VARCHAR(n), TEXT                             e.g. email__pg_v255
        | c<n>         CHAR(n), TEXT, stored trailing-space-trimmed; CHAR = c1   e.g. code__pg_c3
        | s            SMALLINT, INT2, SMALLSERIAL; INT             e.g. qty__pg_s
  <s> := digits | m<digits>   a negative scale, e.g. NUMERIC(5,-2) -> n5_m2
```

- **Only `[a-z0-9_]`** in the suffix: valid unquoted everywhere and inside DynamoDB GSI names.
- **VARCHAR without a length is native TEXT** (no code): drivers treat 25 and 1043 alike, and encoding every ORM
  `string` column would spend name budget and hide those tables from other ScalarDB applications.
- **Decoding is self-describing**: a name ending in `__pg_` + a code that parses completely, with a
  stored DataType that matches (`n` ↔ BIGINT, `t`/`v`/`c` ↔ TEXT, `s` ↔ INT), decodes to that kind. A name ending
  in `__pg_` + anything else (or with a mismatched DataType) is `UNKNOWN_ENCODING`: read raw under its full name
  and **every write refused** with 0A000 ("column … uses a type encoding this frontend does not know"). That is the
  forward-compatibility rule: a frontend never writes a column whose encoding it cannot honour. Any other name is
  `NATIVE`.
- **A client name containing `__pg_`** is refused at CREATE TABLE / ADD COLUMN / RENAME with 42602.
- **Length.** The encoded name must fit the 56-byte column limit; the longest suffix (`__pg_t1000_m1000`) is
  16 bytes, so a modified column's client name may have up to 40 bytes; over that, 42622 names the budget.
- **Max scaled precision**, applied at CREATE / ADD only: 18; 15 when the configured storage is `cosmos` or
  `object storage`, and 15 in Cluster mode (`scalar.db.transaction_manager=cluster`), where the node's storage is
  not visible. 15 digits always fit in ±2^53, so no frontend range check is needed. Above the limit,
  `NUMERIC(p,s)` is `t<p>_<s>`. An existing column's code decides how it is read and written forever.
- **Specified in `docs/type-encoding.md`** as the contract for other ScalarDB clients and for core: what each code
  means, the stored value format, a decoding table with a ready-made SQL view per JDBC engine
  (`amount__pg_n10_2 / 100.0 AS amount`, `rtrim(code__pg_c3) AS code`), the rule that imports must write scaled
  longs and padded CHAR (or go through the frontend), and the core migration path (§9).

### 3. Client names everywhere, storage names only where operations run

- **Client view, cached.** The first `target()` for a table builds, from the raw metadata, a `ColumnMap`:
  each storage column is parsed once with `ColumnTypes.parse`, duplicate client names (case-folded) are detected
  **before** the client-named `TableMetadata` is built (its builder rejects duplicates; colliding columns keep
  their raw storage names, §7), and the map records client ↔ storage names, a `ColumnType` per column and a
  `hasEncoded` flag. It is cached with the metadata in `metadataCache`, so it is built once per cached table and
  invalidated exactly as today; there is no global decode cache. `Target.metadata` is the client view, so the ~30
  existing `t.metadata.*` reads keep working. (`metadataCache` is a per-session `HashMap` today; auto-commit reads
  run on the lookup pool, so the Reader must not touch it, see below.)
- **Mechanical refactor first (Phase 2):** the column-building functions (`columnFromValue`, `conditions`,
  `forColumn`, `value`, both `pushable`s, `valueColumn`, `existing`, `column`, `columnFromText`,
  `Optimizer.orTerms`) take a `Target` instead of a `TableMetadata`, with no behaviour change, so they can reach
  the `ColumnMap`; `columnFromText` / `parseColumn` take the `ColumnType` (§1).
- **The map travels with the plan.** Operations and conditions are built at plan time, and `reader(crud,
  identity)` is a static factory whose `Reader` sees only an `Operation`. So each `Plan` captures, at plan time,
  the `ColumnMap` of every table it touches (an immutable `Map<namespace.table, ColumnMap>`), and the Reader
  resolves the map from the operation's `forNamespace()` / `forTable()` in that captured map. A DDL between
  planning and execution invalidates the plan through `DDL_GENERATION` as today, so a plan never runs with a map
  from another schema version.
- **One rewrite where operations execute:** `ColumnMap.toStorage(Operation)` rewrites a Get / GetWithIndex,
  Scan / ScanAll / ScanWithIndex, Put, Insert, Upsert, Update or Delete with the copy builders
  (`newBuilder(existing)`): keys rebuilt with `Key.newBuilder().add(column.copyWith(storage))`, values with
  `clearValues` + `value(copyWith(…))`, projections and orderings cleared and replaced, conditions rebuilt with
  `ConditionBuilder.buildConditionalExpression` (`buildLikeExpression` with the original escape for LIKE),
  mutation conditions (`PutIf`, `UpdateIf`, `DeleteIf`) rebuilt from their expression lists. Builder order matters:
  `clearConditions()` before the other clears and before `where…`, and read/write tags last. Applied in the Reader
  before `crud.get` / `scan` / `getScanner` and before both `crud.mutate` calls; the identity when `hasEncoded` is
  false. Admin DDL calls (`createTable` incl. `WITH (partition_key, clustering_key)`, `createIndex`,
  `addNewColumnToTable`, `renameColumn`, DROP INDEX matching) map names at their own call sites.
- **Storage names are never accepted from SQL.** `toStorage` and every DDL name mapping raise 42703 for a name
  that is not a client name of the table's `ColumnMap`, so `UPDATE t SET "amount__pg_n10_2" = …`, `CREATE INDEX`
  or `WITH (partition_key = '…__pg_…')` naming a storage name cannot bypass write enforcement.
- **Values are converted where columns are built**, because conditions are frozen at plan time: literals in
  `Source.pushed`, keys in `keyed()` and lookup keys are already scaled, padded or exact when the plan is made.
- **Reads decode by storage name**: every `rowOf` / `rowsOf` caller passes raw ScalarDB `Result`s (verified:
  `Reader.open`, the lookup pool, `Operators.Scan`, ON CONFLICT), so `rowOf` takes the table's `ColumnMap` (from the
  plan's captured map) and, per column, keys the row by the client name and decodes the value; for a table with
  `!hasEncoded()` it does exactly what it does today. The qualified key `qualifier.client` is precomputed per
  column, which removes today's per-cell string concatenation. `columns` passed for `putIfAbsent` is the
  client-name list.
- **`lookupGroup`** builds its keys from `rowOf`-decoded rows instead of reading raw `result.getColumns()` with
  client names, and compares lookup keys in the same (decoded) form on both sides; its group Scan, built from a
  client-named Get, goes through `toStorage` like every other operation.
- **Catalog**: `Catalog.load` decodes names the same way, so `pg_attribute.attname`, `pg_indexes`, constraint
  definitions, `key_column_usage` and index names show client names.
- **EXPLAIN** shows client names throughout (the rewrite happens after planning).
- **Error messages** use client names. ScalarDB's own error texts (passed through as today) may still contain
  storage names; accepted and documented.
- **A stale storage name** (another process renamed or dropped a column after this session cached the table;
  with ScalarDB's metadata cache this may surface only at COMMIT): the ScalarDB error code is caught at the Reader
  and commit sites and rethrown as `PgException.Argument("42703", …)` with the client name, invalidating the
  table's metadata, in the same way the `DB-CORE-20013` handling works today. `PostgresServer.sqlState` gets no
  new `contains` rule.

### 4. Values: exact conversion, write coercion, validated decoding

**Exact conversion (`columnFromValue` / `columnFromText`, taking the `ColumnType`)** is what every predicate, key and lookup uses, and what every write ends
in. It returns the storage column only when the value is represented exactly. Otherwise it throws one of two
**typed** outcomes, never recognised by message text:

- `PgException.OutOfRange` (22003): the value lies wholly above or below the column's range (`40000` for
  SMALLINT, `10^9` for NUMERIC(10,2)). The existing sign shortcut applies: EQ matches nothing, NE is true, and
  GT/GTE/LT/LTE are constant by the value's sign.
- `PgException.NotRepresentable`: the value is in range but has no exact stored form (`1.005` for NUMERIC(10,2),
  `'abcdefgh'` for VARCHAR(5) or CHAR(5)). EQ matches nothing, NE is true for every non-NULL row, and every other
  comparison **stays in memory** (never a constant).

`QueryParser.isOutOfRange` becomes `instanceof` tests on these types instead of `getMessage().contains("out of
range for type")`, and the three fallback sites honour the two outcomes: `pushable` keeps the conjunct in memory
(for every caller, not only when `row == null`), `keyed()` takes the by-scan path, and `lookupOperation` /
`beyondRange` use the sign shortcut only for `OutOfRange` and in-memory evaluation (or no match for EQ) for
`NotRepresentable`; today `beyondRange` would make `x > 1.005` a constant and rethrow for a too-long string. About
fifteen lines of new code. So `DELETE … WHERE amount_key = 1.005`, `WHERE vc5 = 'abcdefgh'` and `WHERE small =
40000` match no row and raise nothing, and `x > 20.005` in a lookup join returns PostgreSQL's rows.

**Write coercion (`ColumnType.coerce`, then the exact conversion)** runs only on values being stored: direct INSERT and multi-row VALUES
(`value()`), UPDATE SET direct (`value()`) and by scan, INSERT … SELECT, upsert and ON CONFLICT DO UPDATE. The
keyed UPDATE/DELETE predicate path (`conditions()` → `primaryKey`) is exact, not write.

| Kind | Write rule (PostgreSQL's) | Error |
|---|---|---|
| NUMERIC_SCALED | check integer digits (`precision() - scale()`) ≤ p − s **before** any `setScale`; round half up to s; re-check after rounding; store `unscaledValue().longValueExact()` | 22003 `numeric field overflow` with PostgreSQL's DETAIL; `NaN` → 0A000 (PostgreSQL accepts it; the BIGINT encoding cannot); `±Infinity` → 22003 as PostgreSQL |
| NUMERIC_TEXT | input trimmed; with (p,s): digit check, round, re-check; without: integer digits ≤ 131072 and scale ≤ 16383 (PostgreSQL's limits), value and display scale kept; stored as `toPlainString()` of a non-negative-scale BigDecimal; `NaN`, `Infinity`, `-Infinity` accepted in PostgreSQL's spellings (`inf`, `+infinity`, `nan` …) and stored exactly as `NaN` / `Infinity` / `-Infinity` | 22003 |
| VARCHAR(n) | strip trailing spaces beyond n; length in code points ≤ n | 22001 `value too long for type character varying(n)` |
| CHAR(n) | strip all trailing spaces; length in code points ≤ n; stored trimmed (one helper shared with exact conversion and pushdown) | 22001 `value too long for type character(n)` |
| SMALLINT | −32768..32767 | 22003 `smallint out of range` |

Every TEXT-stored kind (NUMERIC_TEXT, VARCHAR, CHAR) refuses NUL with 22021 inside `coerce`, so paths that skip
today's `columnFromText` check (binary parameters) cannot store it.

- **Floats into NUMERIC** use PostgreSQL's conversion (15 significant digits for float8, 6 for float4) through
  `Evaluator.toDecimal`, changed in place, never `new BigDecimal(double)`, so a float8 `1.005` stores `1.01` and
  `(0.1 + 0.2)` stores `0.3`. This covers `setDouble` parameters, INSERT … SELECT from existing DOUBLE columns and
  `::numeric`. It does **not** apply to comparisons: `numeric = float8` keeps comparing as float8 in
  `Evaluator.compare`, as PostgreSQL does. A "float value" here means a float4/float8-typed value (a `setDouble`
  parameter, a DOUBLE/FLOAT column, `::float8`), not a JSQLParser `DoubleValue`: `19.90` and `12.5` are numeric
  literals in PostgreSQL.
- **No negative-scale BigDecimal leaves `ColumnTypes`** (`NUMERIC(5,-2)` values are rescaled to scale 0), so no
  `toString` path ever prints `1.2346E+6`.
- **RETURNING and EXCLUDED** see coerced values: `left()` encodes and then decodes, so `INSERT … VALUES (19.905)
  RETURNING amount` returns `19.91`, and `EXCLUDED.amount * 100` with `1.005` proposed is `101.00`.
- **`value()` no longer rounds a NUMERIC_SCALED value to scale 0** (today's BIGINT rule), and `pgTypeName`
  reports the declared type in messages.

**Validated decoding (`ColumnType.decode`)** never trusts the stored value, because old frontends and other
ScalarDB clients can write anything:

- scaled: `BigDecimal.valueOf(unscaled, max(s, 0))` after checking |unscaled| < 10^p;
- TEXT decimal: the canonical grammar, or the three special words decoded to `Double` NaN / ±Infinity (the
  evaluator already orders and aggregates non-finite numbers as PostgreSQL does);
- CHAR: at most n code points after trimming trailing spaces (so values padded by another client are accepted);
  decoded to the **trimmed** `String`;
- SMALLINT: within int2 range, decoded as `Integer` (int2 arithmetic operators are deferred).

A violation raises XX001 (`data_corrupted`); it never wraps, pads, truncates or returns NULL. The message names
the table and client column; the key goes in DETAIL; the stored value is never echoed. `PgException.Argument`
errors are not logged (only internal errors are), so keys do not reach the logs, and that stays so. A bad value
blocks only reads that fetch its column (projections fetch only referenced columns; `SELECT *` fetches all), and
writes are refused only for the offending column, not the whole table.

### 5. Reads, evaluation and the wire

- Decoded `NUMERIC` values are `BigDecimal` with the column's scale, so `19.90` prints as `19.90` and the existing
  exact arithmetic (sums keep the max scale, multiplication adds scales, PostgreSQL division scale) applies.
- **Output types without changing the `DataType` plumbing:** `outputType()` (and RETURNING's types) return null
  for non-NATIVE columns; `inferOid` returns `ColumnType.oid()` for column references **before** it consults the
  output type (today it returns 0 for a null type), including through derived sources (`s.derived`); one
  `outputTypmods` list sits next to `LogicalPlan.outputOids`. RETURNING plans have no logical plan, so
  `Returning` carries its own OID and typmod lists, which `Plan.outputOids` consults; otherwise an empty result or
  a NULL first row would be described by guessing. The existing null-DataType
  fall-through then reports 1700 / 1043 / 1042 / 21 in RowDescription and describe, and `sendRowDescription` sends
  the typmod (direct column references only, including through subqueries and CTEs; -1 for expressions and for
  set operations whose branches disagree, as PostgreSQL). `Rows.columns()` / `describe()` keep returning
  `Map<String, DataType>`, with null meaning "not a native type".
- **OID 1700 on every inference path** (column refs, MIN/MAX, CASE/COALESCE, window functions, scalar
  subqueries, CTE columns), so binary results never send `longValue()` of a decimal.
- **CHAR on output**: values are stored and decoded trimmed, so comparisons, hash/IN/DISTINCT keys, `||`,
  `length`, `::text` and assignment to VARCHAR follow PostgreSQL with no evaluator change, and ScalarDB's own
  ordering of the stored values ignores trailing spaces as bpchar does. Text and binary output for OID 1042 with a
  typmod pad back to n; RETURNING likewise. (PostgreSQL itself pads a CHAR(10485760) value on output; so does the
  frontend, but nothing is stored padded.) Known differences, documented:
  `octet_length(char_col)` counts no padding, in-memory LIKE patterns relying on trailing spaces (`LIKE 'ab_'`)
  do not match, and padding is lost through typmod -1 expressions such as `COALESCE(c, c)`. LIKE on a CHAR column
  is never pushed down, so pushed and in-memory results agree.
- **Binary output**: OID 1700 already uses `PostgresServer.numeric(BigDecimal)`; a non-finite `Double` under OID
  1700 writes the sign words 0xC000 / 0xD000 / 0xF000; 1042 and 1043 are text bytes; 21 a 2-byte integer.

### 6. Pushdown

| Column kind | Equality | Range / ORDER BY | Key / index |
|---|---|---|---|
| NUMERIC_SCALED | exact scaled value; not exact (more than s fraction digits, out of range) → existing in-memory fallback | same | allowed |
| NUMERIC_TEXT | in memory (`1.10` = `1.1`) | in memory | **refused**, 0A000 "a NUMERIC without precision, or with more than N digits, cannot be a key or index column: declare NUMERIC(p,s) with p ≤ N" |
| VARCHAR | as TEXT today; too long → no row | as TEXT today | allowed |
| CHAR | literal with trailing spaces stripped; too long → no row | stripped literal | allowed |
| SMALLINT | as INT; out of range → existing fallback | as INT | allowed |

- A comparison between a NUMERIC column and a float value (a float-typed parameter, a DOUBLE column,
  `::float8`) is never pushed down: PostgreSQL compares it as float8, which the evaluator does in memory. A decimal
  literal (`DoubleValue` in JSQLParser) is numeric, so `pushable`'s early return for `DoubleValue` literals must
  not apply to NUMERIC_SCALED columns; otherwise no decimal bound would ever be pushed.
- `checkTextAgainstNumber`, `forColumn`, LIKE pushdown and `Optimizer.keyed` look at the `ColumnType`: a
  TEXT-encoded decimal compares as a number (no 42883 for `amount = 1`), raises 42883 for LIKE as PostgreSQL does,
  and never becomes a key or range.
- The in-memory fallback decision is made per execution: a value that becomes part of the pushed plan marks the
  plan `EMBEDDED` (not cached), as `LIMIT $1` does today, so a cached plan never reuses the first execution's
  choice. Rewriting over-precise bounds with ceiling/floor (`x >= 20.005` → `x >= 20.01`) would keep these in
  ScalarDB; it is a later optimization, not needed for correctness.

### 7. Catalog and metadata answers

- `pg_attribute.atttypid` / `atttypmod` / `attlen` from `ColumnType`; `pg_type` gains `bpchar` (1042).
- `information_schema.columns`: `data_type`, `udt_name` (`numeric`, `varchar`, `bpchar`, `int2`; read by Rails,
  Prisma and SQLAlchemy introspection), `character_maximum_length`, `character_octet_length`,
  `numeric_precision`, `numeric_precision_radix`, `numeric_scale` (2046 for `NUMERIC(5,-2)`, as PostgreSQL).
  Values for native integer and float types (16/32/64/24/53) are deferred.
- `format_type(oid, typmod)` honours the typmod, so psql `\d`, DBeaver and pgjdbc `DatabaseMetaData.getColumns`
  (COLUMN_SIZE, DECIMAL_DIGITS) and `ResultSetMetaData.getPrecision/getScale` show the declaration.
- **Duplicate client names written by others** (an old frontend's `ADD COLUMN amount` next to
  `amount__pg_n10_2`, a case-only clash on MySQL or SQL Server, a concurrent ADD COLUMN racing the frontend's own
  check): `target()` and `Catalog.load` show both columns under their raw storage names (these raw names are the
  client names of those two columns in the `ColumnMap`) and refuse writes to them, rather than letting one
  silently win; other columns stay writable.
- **Orphan before-images**: a non-meta column named `before_<encoded name>` whose base column does not exist (left
  by a crash between Consensus Commit's two rename steps) is hidden from the client view, and writes to the
  renamed column are refused with an error naming the repair (re-run the RENAME); other columns stay writable.

### 8. DDL

- **CREATE TABLE**: resolve each column to a `ColumnType`; the CHAR, VARCHAR, NUMERIC and SMALLINT notices go
  away (the 22023 bound checks stay); refuse a NUMERIC_TEXT column as a key column or in `WITH (partition_key,
  clustering_key)`; check client-name collisions (case-folded) against fresh admin metadata (42701); map key and
  option names to storage names.
- **CREATE INDEX** on a NUMERIC_TEXT column: 0A000 as above. Index names stay `table_<client column>_idx`.
- **ALTER TABLE ADD COLUMN**: same resolution and checks (and use `columnType`, fixing today's `float(p)`
  inconsistency).
- **ALTER TABLE RENAME COLUMN of an encoded column**: refused with 0A000 in the first increment; implemented in
  Phase 5 (read the suffix from fresh metadata, keep it, run the 42602 / 42701 / 42622 checks on the new encoded
  name, then `admin.renameColumn`; make it idempotent when a previous attempt stopped between Consensus Commit's
  two steps; keep index-name bookkeeping). RENAME of native columns is unchanged.
- **ALTER COLUMN TYPE** and **DROP COLUMN** stay 0A000.
- **No change needed**: DROP TABLE, DROP SCHEMA, TRUNCATE.
- **`scalardb.frontend.type_modifiers=keep` (default) | `drop`** (decision D1, 2026-10-14): `drop` makes CREATE TABLE and
  ADD COLUMN map types as today, with today's notices. It never changes how an existing column is read or written:
  the storage name decides, so writes to an encoded column always encode.

### 9. Migration to ScalarDB core

Core cannot rename columns on Cosmos DB, DynamoDB, Object Storage or Spanner, so the migration must not depend
on renames. The recipe in `docs/type-encoding.md`: core reads the `__pg_` suffix as declared-type metadata (an
alias for the client name, with the scaled BIGINT kept as the representation), and the frontend keeps decoding
legacy names. A storage that can rename may additionally rename and convert, but nothing requires it.

## Technical Approach

### Implementation Phases

Each phase ends with `./gradlew check` green. Phases 0, 1, 2 and 3 together are the **first shippable
increment**: exact `NUMERIC(p,s)` money columns, shown as declared.

#### Phase 0: Fixes that change results on existing tables (own difftest run)

**Status: done 2026-10-14 on branch `type-modifiers-phase0`** (uncommitted): 222 unit tests and `./gradlew check` pass; difftest matches every baseline (`difftest/results/2026-10-14-phase0/summary.txt`). Type unification also makes the result OID of CASE / COALESCE / NULLIF / GREATEST / LEAST follow PostgreSQL (any float → 701, else numeric → 1700, else the widest integer).

- **Identifier characters (todo 053, security):** `checkedName` and every schema, table, column and index name
  the frontend sends to ScalarDB refuse `'`, `"`, `` ` ``, `[`, `]`, `\`, `;`, NUL and other control characters
  with 42602, with a test per character and per DDL path. This closes an injection path that exists on `main`
  today and that every encoded name would pass through.
- Type unification: `looksDouble` is true only for Float/Double values and FLOAT/DOUBLE types, and no longer for
  decimal literals (`DoubleValue`, numeric in PostgreSQL) or `::numeric` casts, so `COALESCE(int_col, 1.5)`,
  `COALESCE(int_col, numeric_expr)` and `CASE … 1 ELSE numeric END` return numeric, not double. Difftest cases
  to rerun: `cases_read.sql` (CASE / coalesce / greatest / nullif over salary and bonus; `coalesce(bonus, 1.5)`),
  `cases_write_ac.sql` (`coalesce(bonus, 0) * 2`), and the coalesce / nullif / greatest / least generators in
  `fuzz.py`.
- `format_type(oid, typmod)` and a typmod-aware `Catalog.typeName(oid, typmod)` honour the typmod; `pg_type`
  gains `bpchar` (1042).
- `::char` casts: a bare `char` / `character` is `char(1)`, and the value drops trailing spaces. (Padding a cast back to n would need the output typmod: a padded plain string makes `'[' || 'a'::char(3) || ']'` `[a  ]` and `length` 3, where PostgreSQL gives `[a]` and 1, so padding waits for Phase 5.)
- `Evaluator.toDecimal` changed in place to PostgreSQL's float→numeric rule (writes and `::numeric` only);
  `Evaluator.compare` gains a `Long`/`Long` fast path and keeps comparing numeric with float8 as float8.
- **Done when** the full difftest suite (read, session, sql92-entry, fuzz) shows no new DIFF and the fixed cases
  match PostgreSQL; baselines updated and a dated `difftest/results/<date>-phase0` summary written.

#### Phase 1: The codec and the contract (no behaviour change)

**Status: done 2026-10-14 on branch `type-modifiers-phase0`** (uncommitted): `ColumnType.java` (one class holding
the instance rules and the static factories, matching how `Catalog` and `Evaluator` are organised, instead of a
separate `ColumnTypes`), `PgException.OutOfRange` / `NotRepresentable`, `Evaluator.applyTypmod` (the numeric(p,s)
rounding shared by casts and writes, now checking integer digits before rounding and never returning a negative
scale), `docs/type-encoding.md`, and 47 tests in `ColumnTypeTest`; 269 tests and `./gradlew check` pass. Two
departures from the sketch above: `Evaluator.cast` shares only the numeric rounding, since `::char(n)` and
`::varchar(n)` casts truncate silently while writes must raise 22001 (smallint already shared `cast`'s range
check); and `ColumnMap` belongs to Phase 2.

- `ColumnTypes.java` as in §1, with every rule from §2 and §4: codes and `parse`, `coerce` (shared with
  `Evaluator.cast`, which switches to it here with no behaviour change beyond typed 22003 errors), validated
  `decode`, typmod, OID, SQL name, name budget, 42602 marker check, max-scaled-precision selection; the
  `PgException.OutOfRange` and `PgException.NotRepresentable` types.
- `docs/type-encoding.md` first version (the `n` code fully; others marked as coming).
- Tests (`method_Condition_ShouldOutcome`, in `HelpersTest` with the other static helpers, or a
  `ColumnTypesTest` beside it if it grows large): round trips for every code; parse of non-matching names and
  mismatched types as `NATIVE` / `UNKNOWN_ENCODING`; rounding and overflow (incl. the digit check before any
  `setScale` on `1e2147483647`), negative scale, scale > p, `-0`, exponents, leading zeros, trimmed input,
  special words; VARCHAR truncation; CHAR trimming with multi-byte characters; SMALLINT range; float rule; NUL
  refusal; XX001 for each contract violation without the stored value in the message.
- **Done when** the codec tests pass and nothing else changes.

#### Phase 2: Plumbing (no behaviour change for existing tables)

**Status: done 2026-10-14 on branch `type-modifiers-phase0`** (uncommitted): `ColumnMap.java` (the client view,
storage ↔ client names, duplicate and unknown-encoding columns shown raw and never written, `toStorage` for every
operation kind), cached per table in `QueryParser.columnMaps` (replaced, not cleared, after DDL, so a plan keeps the
maps it was made with); every plan of a statement gets the maps at parse time (`Plan.useColumnMaps`) and its reader
rewrites operations and decodes rows through them; mutations are rewritten at both `mutate` sites; `lookupGroup`
matches results by stored key values; `Catalog.load` uses client names; CREATE INDEX and DROP INDEX map names; new
column names may not contain `__pg_` (42602); RENAME of an encoded column is refused (0A000). 12 tests in
`ColumnMapTest` (every operation kind rewritten; point, range, index and full scans; the lookup-join partition group;
INSERT, UPDATE, DELETE, ON CONFLICT with RETURNING; EXPLAIN; information_schema and pg_indexes; storage names in SQL
refused with 42703); 285 tests and `./gradlew check` pass; difftest read, types, write, write_ac, bind, session and
three fuzz seeds match their baselines.

After the code review: only kinds whose value rules are wired are translated (`ColumnMap.TRANSLATED`: native and
varchar, whose written values now follow the varchar write rule); a column of any other kind, including a numeric,
char or smallint code written by another client, is shown raw with its stored value and never written, so a value is
never decoded on read and sent unencoded on write. **Each later phase adds its kind to `TRANSLATED`.** ADD COLUMN and
RENAME refuse a name a column already has as clients see it (42701, against fresh metadata); RENAME by the raw
storage name is refused too; DROP INDEX falls back when the indexed column is gone; a result's columns are fetched
once per row. An INSERT without a column list on a table with a raw, never-written column is refused (0A000), since
it writes that column; naming the columns avoids it.

Moved out of Phase 2: value conversion through `ColumnType` (`columnFromText` / `parseColumn` taking it, the
`TableMetadata` → `Target` refactor) and the typed `OutOfRange` / `NotRepresentable` fallbacks go to Phase 3, the
first phase with values that differ from their stored form; the Phase 2 fixture's varchar columns store values
unchanged, so they would be untested here. Orphan before-image detection and the 42703 mapping of a stale storage name
go to Phase 5, with RENAME of encoded columns, the only way either can arise: an orphan cannot be told apart from a
legitimate client column named `before_x` with a modifier, and ScalarDB reports a missing column under several
operation-check codes.

- `TableMetadata` → `Target` refactor of the column-building functions, and `ColumnType` into
  `columnFromText` / `parseColumn` (§3).
- `isOutOfRange` and the fallback sites switch to the typed outcomes (§4); `pushable` keeps a conjunct in memory
  for every caller.
- Cached `ColumnMap` in `metadataCache`; each `Plan` captures the maps of its tables; the Reader resolves them by
  namespace and table; `ColumnMap.toStorage(Operation)` at the five execution sites; 42703 for unknown names in
  `toStorage` and DDL mapping; admin DDL name mapping; `rowOf` decoding through the map; `lookupGroup` keyed from
  decoded rows; `Catalog.load` decoding; duplicate-name and orphan before-image handling; the unknown-encoding
  write refusal; ScalarDB's "unknown column" errors mapped to 42703 at the Reader and commit sites.
- **Fixture**: a test-only metadata in which TEXT columns, including a partition key, a clustering key and an
  indexed column, are stored as `__pg_v<n>` (name translation does not depend on the type), and a test class that
  runs representative SELECT / INSERT / UPDATE / DELETE / ON CONFLICT / RETURNING / lookup-join (incl. the group
  Scan) / IN-list / index scan / catalog / EXPLAIN statements, asserting that every executed operation uses storage
  names, every client-visible name is the client name, and a quoted storage name in each statement kind is refused
  with 42703.
- **Done when** all existing tests pass unchanged and the fixture tests pass.

#### Phase 3: NUMERIC(p,s) as scaled BIGINT — first shippable increment

**Status: done 2026-10-14 on branch `type-modifiers-phase0`** (uncommitted): 292 unit tests and `./gradlew check`
pass (`NumericColumnTest` covers rounding, overflow DETAIL, exact reads, scaled pushdown, unrepresentable keys,
RETURNING and the catalog). Difftest: the new `cases_numeric.sql` matches PostgreSQL in all 28 cases, RowDescription
OIDs and typmods match on the wire, six `s01_types` cases improved (baseline updated), every other set as its
baseline (`difftest/results/2026-10-14-phase3/summary.txt`). Deviations: XX001 has no DETAIL (its message already
names the table and column); `DEC(p,s)` needed its modifiers rejoined (the parser keeps them apart), found by
difftest; RENAME of encoded columns was already refused in Phase 2; `scalardb.frontend.type_modifiers` (D1) is
not implemented yet, the behaviour is `keep`.

Code review fixes (same day): numeric compared with a float as double precision on both paths (a numeric(16..18)
equality spanning several stored values is refused with 0A000, a `ponytail:` ceiling); a keyed UPDATE / DELETE
that can match no row plans no mutation; UNION typmods; CASE / COALESCE widening typed once per plan. Left for
Phase 5: `varchar(n)` shown as `text` in the catalog, and a read-only (unknown or colliding) key column blocking
every write to its table.

- CREATE TABLE / ADD COLUMN produce `n` codes; NUMERIC notice removed for them; write coercion; RETURNING and
  EXCLUDED decoding, and RETURNING's own OID / typmod lists; exact pushdown with the typed fallbacks, and
  `pushable`'s `DoubleValue` early return no longer applied to NUMERIC_SCALED columns; float comparisons kept in
  memory; `value()` no longer rounds to scale 0; `checkTextAgainstNumber` / `forColumn` use the declared type;
  `inferOid` returns the declared OID first; OID 1700 and typmods in RowDescription, catalog (`atttypmod`, numeric
  fields and `udt_name` of `information_schema.columns`), `format_type`; RENAME of encoded columns refused (0A000).
- Error DETAIL: an optional `detail` on `PgException.Argument` and a `'D'` field in `PostgresServer.error()`, used
  by the 22003 numeric overflow and by XX001.
- `ponytail:` comments on the deliberate ceilings: the fixed 18 / 15 scaled-precision limit, over-precise bounds
  kept in memory, the per-execution (`EMBEDDED`) plan for them, and the undetected same-suffix rename swap.
- **Done when** I-NUM-02..04 and the `s01_types` NUMERIC(p,s) cases (rounding, overflow, exact sums, money
  totals, NUMERIC primary key) match PostgreSQL; a range scan on a NUMERIC(10,2) clustering key is pushed down
  with scaled bounds (EXPLAIN); the float8 rules from the ground-truth note are verified by difftest.

#### Phase 4: NUMERIC without precision, and p above the limit, as TEXT

**Status: done 2026-10-14 on branch `type-modifiers-phase0`** (uncommitted): `t` and `t<p>_<s>` columns created by
CREATE TABLE and ADD COLUMN and translated by `ColumnMap`; refused as partition key, clustering key or index
(0A000 naming the fix); every comparison on them kept in memory, LIKE on any declared numeric 42883 as
PostgreSQL; unbounded values limited to 16383 fraction digits (22003 "value overflows numeric format"); numeric
OID with typmod -1 (unbounded) or (p,s); binary output of NaN and the infinities. `NumericTextColumnTest` (5
tests); difftest `cases_numeric.sql` now 56 cases, all matching PostgreSQL 18; `s01_6_1_decimal_large_exact`
NUM_FMT -> OK. `scalardb.frontend.type_modifiers` (D1) is still not implemented.

Code review fixes (2026-10-15): the numeric-LIKE check skips current_user and the like, covers columns read through
subqueries and CTEs, and names the right side's type as PostgreSQL does; one `ColumnType.isNumeric()`. Not
changed: `id NUMERIC PRIMARY KEY` is now refused where earlier releases stored a double (LIMITATIONS.md says so).
Left for the start of Phase 5: let the client view report declared types, so checks against `DataType.TEXT` stop
needing a numeric guard at each call site.

- `t` codes; canonical text; refusal as key / index / clustering option; pushdown refusal; LIKE 42883; special
  values as `Double`.
- **Done when** I-NUM-05/06 and the DECIMAL(20,0) / unbounded cases in `s01_types` match PostgreSQL, and a key
  declaration is refused with the documented 0A000.

#### Phase 5: VARCHAR(n), CHAR(n), SMALLINT, and RENAME of encoded columns

**Status: done 2026-10-15 on branch `type-modifiers-phase0`** (uncommitted), except the orphan before-image and
stale-name handling: `v`, `c` and `s` columns created and translated (`ColumnMap` now translates every kind but
unknown encodings); notices withdrawn; char stored trimmed and padded on output (text and binary, by typmod);
LIKE on char not pushed; smallint literals beyond the range decide a comparison instead of failing; OIDs 1043 /
1042 / 21 with typmods in RowDescription, RETURNING, pg_attribute and information_schema; RENAME COLUMN keeps the
code (42622 when the new name with its code is too long; index names follow). `ColumnType.comparesAsText()`
decides how a value is read for a column; the type check for `text = 1` keeps its numeric guard (the
Phase 4 review item, done only that far). `CharacterColumnTest` (5 tests);
difftest `cases_character.sql` 23 cases all matching; nine `s01_types` cases improved.

Code review fixes (same day): quoted smallint values are smallint input (22003 / 22P02) while number literals
compare as numbers; char comparisons outside pushdown strip a literal's trailing spaces and LIKE sees the padded
value (a plan-time rewrite in `analyze`, `rpad(c, n)`); lpad / rpad implemented; RENAME checks 42701 before 42622;
the type notices are no longer emitted then withdrawn; `ColumnType.convertsValues()` replaces `exactlyConverted`;
native numeric precisions in information_schema. Not changed: a char passed through an aggregate comes back
unpadded (documented).

**Not done, recorded as todo 054:** orphan before-images after an interrupted rename, and the 42703 mapping of
stale storage names. Detection is ambiguous (the plan says so), a repair through the transaction admin would itself
run ScalarDB's two-step rename, and neither can be tested without fault injection. LIMITATIONS.md gives the
manual repair.

- `v<n>`, `c<n>`, `s` codes; notices removed; write rules; CHAR stored and decoded trimmed, padded on output,
  stripped pushdown literals; LIKE on CHAR not pushed; SMALLINT range; OIDs 1043 / 1042 / 21 and typmods, including
  RETURNING's lists.
- RENAME COLUMN of encoded columns as described in §8.
- **Done when** I-CHAR-02..04, I-VC-02..03, I-SMALL-02/03 and the `s01_types` CHAR / VARCHAR / SMALLINT cases match
  PostgreSQL or are listed as the documented CHAR differences.

#### Phase 6: Docs, difftest baselines, benchmark

- `LIMITATIONS.md`: rewrite "Type modifiers are not kept" (what is now kept; NaN in NUMERIC(p,s); the name budget
  for modified columns; ALTER COLUMN TYPE unsupported; tables created before the change keep DOUBLE; the CHAR
  differences; TEXT-encoded decimals filter in memory, naming Prisma's `DECIMAL(65,30)` and Rails' `decimal`, and
  recommending p ≤ 18), shrink "Results that can differ" (NUMERIC/DECIMAL, SMALLINT write range); README status
  line; `difftest/README.md`; `docs/type-encoding.md` complete and linked from README and
  `docs/integration-design.md`.
- Release notes: the mixed-version rule (frontends older than this release must not write to tables with
  modified columns; finish a rolling upgrade before the first such CREATE TABLE, or keep `type_modifiers=drop`
  until it is finished) and a catalog query that lists existing `%__pg_%` columns.
- Rerun difftest (`sql92-entry`, `cases_session`, `cases_read`, boundary, fuzz); update baselines; dated summary.
- TPC-C with a typed DDL (`bench/tpcc/ddl-scalardb-typed.sql`, BenchBase's `decimal(12,2)`, `CHAR`, `VARCHAR`
  types).
- Optional: switch `getting-started/schema.sql` balances and prices to `NUMERIC(12,2)`.

## Review decisions

What the review rounds changed, and what each deferral loses until it is picked up. Every deferred item can be
added later without migrating data, because of the unknown-encoding rule.

| Item | Decision | Lost until added |
|---|---|---|
| Boundary rewrite at builders vs. execution | at the five execution sites (architecture) | — |
| Separate predicate encoder | replaced by exact-only conversion plus write coercion, reusing existing fallbacks (architecture, simplicity) | — |
| Over-precise literals | in-memory fallback, decided per execution; ceiling/floor rewrite later (performance proposed it now) | pushdown for literals with more than s fraction digits |
| `Bpchar` value class | trimmed `String`, stored trimmed, padded on output (architecture, security) | `octet_length`, trailing-space LIKE in memory, padding through expressions |
| Global decode memo | per-table `ColumnMap` cached with the metadata (security) | — |
| Reusing the "out of range" fallbacks as is | typed `OutOfRange` / `NotRepresentable` outcomes (conventions, feasibility) | — |
| `NumericSpecial` class | `Double` NaN / ±Infinity (architecture) | — |
| `max_scaled_precision` property | cut; fixed defaults 18 / 15 (simplicity, architecture) | scaled NUMERIC(16..18) in Cluster mode on JDBC backends (stored as TEXT: exact but not keyable) |
| `type_modifiers` property | kept, default `keep` (decision D1, 2026-10-14) | — |
| Bare `v` code | cut (architecture, simplicity) | `\d` shows `text` for `VARCHAR` without a length |
| int2 arithmetic operators | deferred (all reviewers) | `smallint + smallint` overflow widens instead of raising 22003 |
| ParameterDescription inference | deferred (simplicity, performance) | asyncpg / psycopg 3 binary / Npgsql pick text encoders for these parameters (speed, not correctness) |
| `numeric_precision` for native types | deferred (architecture) | NULL instead of 16/32/64/24/53 |
| RENAME of encoded columns | refused in the first increment, Phase 5 (simplicity) | renaming money columns until Phase 5 |
| `m` code (negative scale) | kept (one character in the codec) | — |

## Alternative Approaches Considered

- **Frontend-owned side table** (`scalardb_frontend.column_types`, one for the deployment). Rejected as the
  primary design: with Cluster authentication every call runs as the logged-in user, so the table needs a
  service identity (a credential in every frontend's configuration, created and rotated by an operator) or
  per-user grants that let any user corrupt another table's decoding; DDL becomes a non-transactional two-write
  sequence needing orphan cleanup; and it is a second cache to keep fresh. Embedding in a Cluster node does not
  remove the identity problem. It remains the fallback if the encoding proves unworkable.
- **ScalarDB core first** (a DECIMAL type or type modifiers). The right end state, outside this repository's
  timeline; the encoding is designed to be adopted by core as metadata (§9).
- **Free-form metadata in ScalarDB.** Does not exist.
- **Refusing unbounded NUMERIC.** Breaks Rails `decimal`, SQLAlchemy `Numeric()` and Prisma's default
  `DECIMAL(65,30)` migrations; rejected in favour of exact TEXT.
- **Fixed-width TEXT encoding for wide decimals so ScalarDB can order them.** Correct only where the JDBC
  collation is binary; not worth it for columns refused as keys.
- **Translating names at every operation builder.** At least 13 sites; replaced by the execution-site rewrite.
- **Value classes for CHAR and special numerics.** Would need every `instanceof String` / numeric branch to learn
  them; replaced by trimmed strings and `Double`.

## System-Wide Impact

### Interaction Graph

`CREATE TABLE t (id INT PRIMARY KEY, amount NUMERIC(10,2))` → `createTable` resolves
`ColumnType(NUMERIC_SCALED, BIGINT, 10, 2)` → `checkedName("amount__pg_n10_2")` → collision check on fresh
metadata → `admin.createTable` (Consensus Commit adds `before_amount__pg_n10_2`) → `invalidateMetadata()` bumps
`DDL_GENERATION` → other sessions' `metadataChanged()` clears their metadata and plan caches →
`Catalog.invalidateIfStale` reloads and decodes.

`INSERT … VALUES (1, 19.905)` → `insert` → `value()` → `coerce` rounds to `19.91` →
`BigIntColumn("amount", 1991)` → Reader → `ColumnMap.toStorage` renames to `amount__pg_n10_2` → `manager.mutate`.

`SELECT amount * 3 FROM t WHERE amount > 10` → `pushable` → `exact` scales `10` to `1000` → `Scan` built with
client names → `toStorage` → ScalarDB condition `amount__pg_n10_2 > 1000` → `rowOf` decodes `1991` to `19.91`
under `t.amount` → `Evaluator.arithmetic` exact `59.73` → RowDescription OID 1700, typmod -1 (an expression) →
text `59.73`.

### Error & Failure Propagation

New errors are `PgException.Argument` with PostgreSQL's SQLSTATE and wording (22001, 22003 with DETAIL, 42602,
42622, 42701, 0A000, 42883, XX001); they pass through `PostgresServer.sqlState` unchanged and abort a transaction
block like any statement error. ScalarDB's own refusals (rename on Spanner, Cassandra non-key renames) keep
passing through. A commit-time "unknown column" (a stale storage name) becomes 42703 and invalidates the table's
cached metadata.

### State Lifecycle Risks

- **No new persistent state** besides column names: no orphan rows and no second cache. DDL atomicity is whatever
  ScalarDB gives today.
- **Half-finished RENAME on Consensus Commit** (data column renamed, `before_` column not): detected and hidden, writes
  refused with the repair (§7); RENAME made idempotent in Phase 5.
- **Mixed tables**: a table created before the change keeps its DOUBLE columns; a later ADD COLUMN gets the new
  encoding; both decode correctly side by side.
- **Other ScalarDB clients and tools** (Data Loader, Analytics, engine dumps) see storage names and scaled longs;
  `docs/type-encoding.md` gives decoding views and the import rule. Values they write in violation of the
  contract raise XX001 on read instead of being misread.
- **Downgrade and mixed versions (the one silent-corruption risk).** A frontend built before this change sees
  `amount__pg_n10_2` as a plain BIGINT: it would show `1991` and store `19.90` as `20`, which the new frontend
  reads as `0.20`. Released builds cannot be patched, so: the unknown-encoding rule ships with the first
  increment, so every build from this one on refuses encodings it does not know; the release notes state the
  mixed-version rule; `type_modifiers=drop` keeps new tables unencoded during a rolling upgrade (decision D1). A same-suffix rename swap by another process (`a → tmp`, `b → a`) is not detected; documented.

### API Surface Parity

Every write path ends in `coerce` → exact conversion, every predicate in the exact conversion, every executed operation passes
`toStorage`, every read in `rowOf` / lookups, every catalog answer in `Catalog.load`, every wire type in
`PostgresServer.oids` / `sendRowDescription`. `QueryProcessor` callers embedding the engine see client names,
BigDecimal values and `DataType` null for non-native columns through `Rows`.

### Integration Test Scenarios

1. pgjdbc: create a NUMERIC(10,2) table, insert with `setBigDecimal` (binary 1700), with `setDouble(1.005)` and
   with a text literal; read back `getBigDecimal` (`1.01` for the double); `DatabaseMetaData.getColumns`
   COLUMN_SIZE 10, DECIMAL_DIGITS 2; `ResultSetMetaData.getScale` 2.
2. psql `\d t` shows `numeric(10,2)` (and, after Phase 5, `character varying(20)`, `character(3)`, `smallint`).
3. A second session caches `t`; the first runs `ALTER TABLE t ADD COLUMN c NUMERIC(8,3)`; the second sees `c` as
   `numeric(8,3)` within the cache window.
4. Range scan on a NUMERIC(10,2) clustering key with `BETWEEN 10.00 AND 20.005` returns PostgreSQL's rows (the
   upper bound stays in memory) and EXPLAIN shows the pushed lower bound.
5. node-postgres (OID-0 parameters) inserting `'00123.40'` into NUMERIC(10,2) stores `123.40`.
6. A table written by a simulated old frontend (`amount__pg_n10_2` holding 20 for "19.90") reads `0.20` — and a
   value outside the code's range (`10^10` under `n10_2`) raises XX001.

## Acceptance Criteria

### Functional

- [ ] `\d`, `information_schema.columns` (incl. `udt_name`), `format_type` and pgjdbc metadata show the declared
      type and modifiers of columns created after the change.
- [ ] NUMERIC(p,s) writes round half up to s and raise 22003 with PostgreSQL's DETAIL on overflow; `19.90 * 3`
      returns `59.70`; `SELECT amount` returns `19.90`; `NaN` into NUMERIC(p,s) raises 0A000.
- [ ] `INSERT '1e2147483647'` into NUMERIC(10,2) and into unbounded NUMERIC returns 22003 within a second.
- [ ] NUMERIC without precision and NUMERIC(p > limit) store and return exact values (`1.10` stays `1.10`;
      `' 1.10 '` and `'inf'` store `1.10` and `Infinity`); as a key or index column they are refused with the
      documented 0A000; GROUP BY / DISTINCT over `1.10` and `1.1` give one group; ORDER BY with `NaN` and
      `±Infinity` sorts as PostgreSQL.
- [ ] `DELETE`/`UPDATE … WHERE numeric_key = 1.005`, `WHERE vc5_key = 'abcdefgh'` and `WHERE small_key = 40000`
      affect 0 rows with no error.
- [ ] `INSERT … VALUES (19.905) RETURNING amount` returns `19.91` (OID 1700, typmod of (10,2)).
- [ ] `ON CONFLICT DO UPDATE SET amount = EXCLUDED.amount * 100` with `1.005` proposed stores `101.00`.
- [ ] A float8 `1.005` (pgjdbc `setDouble`, or INSERT … SELECT from a DOUBLE column) stores `1.01`; a float8-bound
      `DELETE … WHERE numeric_key = $1` with `19.9` deletes the `19.90` row.
- [ ] `COALESCE(int_col, amount)` and `CASE … THEN 1 ELSE amount END` return numeric, not double.
- [ ] `SUM`, `AVG`, `MIN`, `MAX` over a scaled and over a TEXT-encoded column return PostgreSQL's value, scale and
      OID, in text and binary formats.
- [ ] VARCHAR(n) raises 22001 beyond n (trailing spaces truncated); CHAR(n) pads on output and compares ignoring
      trailing spaces; a CHAR(3) key holding `'a😀'` or `'日本'` is found by key; `WHERE code = 'ab    '` finds
      `'ab'`; `'ab'` then `'ab '` into a CHAR key raises 23505; inserting `'a'` into a `CHAR(10485760)` column stores
      one character; SMALLINT raises 22003 out of range on write.
- [ ] ADD COLUMN onto an existing client name raises 42701; a client name containing `__pg_` raises 42602; a
      modified column whose client name exceeds 40 bytes raises 42622.
- [ ] `NUMERIC(5,-2)` and `NUMERIC(2,5)` round-trip under valid encoded names; `NUMERIC(5,-2)` holding `1234567`
      gives `amount || ''` = `'1234600'`; its `information_schema` scale is 2046.
- [ ] Every executed ScalarDB operation uses storage names and every client-visible name (labels, catalog,
      EXPLAIN, errors built by the frontend) is the client name (Phase 2 fixture); a quoted storage name in SQL
      raises 42703.
- [ ] In a lookup join, `x > 20.005` on a NUMERIC(10,2) column returns PostgreSQL's rows (no constant truth), and
      `vc5 = 'abcdefgh'` matches nothing without an error.
- [ ] Identifiers containing `'`, `"`, `` ` ``, `[`, `]`, `\`, `;`, NUL or control characters are refused with 42602
      on every DDL path (todo 053).
- [ ] NUL in a value for a TEXT-stored column raises 22021 on every write path, including binary parameters.
- [ ] XX001 messages never contain the stored value; the key is in DETAIL; a corrupt value blocks only reads that
      fetch its column and writes to that column.
- [ ] A column with an unknown `__pg_` encoding reads raw and refuses writes with 0A000; a stored value violating
      its code raises XX001; duplicate client names show under raw storage names and refuse writes; an orphan
      `before_<encoded>` column is hidden and writes to its table are refused with the repair message.
- [ ] Tables created before the change behave exactly as before (existing tests unchanged).
- [ ] `type_modifiers=drop` makes CREATE / ADD map types as today, and a write of `19.90` to an existing
      `amount__pg_n10_2` under `drop` still stores `1990`.
- [ ] In Cluster mode, NUMERIC(16..18, s) is stored as TEXT.

### Non-Functional

- [ ] TPC-C with the typed DDL, same setup as the 2026-10-02 run: throughput and per-procedure p50 within 5% of
      the DOUBLE DDL run in the same session (median of 3 runs each).
- [ ] The scan-heavy TPC-C statements in `bench/tpcc/results/explain-frontend.txt`, rerun on the typed schema,
      show the same ScalarDB operations, with no new in-memory `Filter`.
- [ ] Tables without encoded columns: `rowOf` and `toStorage` do no per-cell decoding work (a unit test that no
      decoder runs, or an allocation profile).

### Quality Gates

- [ ] `./gradlew check` passes (Error Prone, NullAway, SpotBugs) at the end of every phase.
- [ ] difftest: Phase 0's own run shows no new DIFF; the boundary rows and `s01_types` cases listed in Phases
      3-5 match PostgreSQL or are documented differences; no new DIFF in `cases_read`, `cases_session`, fuzz;
      baselines and a dated summary updated.
- [ ] `LIMITATIONS.md`, `README.md`, `docs/type-encoding.md`, release notes updated.

## Dependencies & Risks

| Risk | Mitigation |
|---|---|
| A raw storage name leaks to a client, or a client name reaches ScalarDB | client view in `target()`; one rewrite at the execution sites; Phase 2 fixture covering keys, indexes, lookups, catalog and EXPLAIN |
| A `DataType` consumer treats the storage type as the declared type | null `outputType` for non-native columns; `ColumnType` checks in `checkTextAgainstNumber`, `forColumn`, LIKE pushdown, `Optimizer.keyed`; tests per consumer |
| Predicate values treated as writes | exact-only conversion with the existing fallbacks; acceptance tests for over-precise, too-long and out-of-range values |
| An older frontend or another client writes an encoded column | unknown-encoding refusal; validated decoding (XX001); release notes; `type_modifiers=drop` during rolling upgrades |
| Huge or malformed numeric input | digit and scale checks before any `setScale`; PostgreSQL's 131072 / 16383 limits |
| Name budget too tight for long client names | 40 bytes for modified columns, 42622 naming the budget; documented |
| Pushdown regression for TEXT-encoded decimals (Prisma, Rails defaults) | documented with a p ≤ 18 recommendation; EXPLAIN test showing the `Filter` |
| Identifier injection through ScalarDB's JDBC metadata SQL (exists on `main`) | Phase 0 character refusal (todo 053); core fix reported separately |
| A decode cache or the client view leaks one table's types to another | no global cache; one `ColumnMap` per table, cached with its metadata |

Dependencies: none external. Coordinate with any open work touching `QueryParser`'s Reader and column-building
functions.

## Future Considerations

- Core adopts the suffix as declared-type metadata (§9); the frontend then reads core metadata first and keeps
  decoding legacy names.
- The deferred items in "Review decisions", in roughly this order: ceiling/floor pushdown of over-precise bounds,
  ParameterDescription inference, int2 arithmetic, `numeric_precision` for native types, a `max_scaled_precision`
  property if a Cluster-on-JDBC user needs scaled NUMERIC(16..18).
- `ALTER COLUMN TYPE` between kinds as add-copy-drop.
- `TIMESTAMP(p)` precision, `INTERVAL`, `serial` sequences and `uuid`/`json` OIDs could reuse the codec.

## Documentation Plan

- `docs/type-encoding.md` (new): encoding contract, value formats, decoding views per engine, import rule,
  migration to core.
- `LIMITATIONS.md`, `README.md`, `difftest/README.md`, release notes as listed in Phase 6.
- `docs/integration-design.md`: one paragraph that type modifiers live in column names, with no service identity.

## Sources & References

### Origin

- Design discussion in the session of 2026-10-14 (no brainstorm document): scope both steps; one store for the
  deployment, column-name encoding preferred over a side table with a service identity; unbounded NUMERIC as
  exact TEXT; existing DOUBLE columns unchanged.

### Internal References (main @ 1416486)

- DDL: `QueryParser.createTable`, `unenforced`, `columnType`, `dataType`, `createIndex`, `drop`, `alter`,
  `checkedName`.
- Execution sites: the Reader's `crud.get` / `scan` / `getScanner` and `crud.mutate` in `QueryParser`.
- Column building: `columnFromValue`, `columnFromText`, `parseColumn`, `value`, `forColumn`, `conditions`,
  `pushable`, `valueColumn`, `existing`, `column`, `keyed`, `lookupOperation`, `lookupGroup`, `left`;
  `Optimizer.orTerms`, `keyed`, `pushDownConditions`, `lateConditions`, `inListLookups`.
- Reads and types: `rowOf`, `rowsOf`, `outputType`, `inferOid`, `Plan.outputOids`, `LogicalPlan.outputOids`;
  `Evaluator.looksDouble`, `compare`, `arithmetic`, `cast`, `format_type`; `Operators.normalize`;
  `PostgresServer.oids`, `sendRowDescription`, `binary`, `numeric(BigDecimal)`; `Catalog.load`, `typeOid`,
  `typeName`.
- Tests: `QueryParserTest` (`parse_CreateTableWithConstraintsAndModifiers_ShouldNoticeWhatIsNotEnforced`,
  `parse_PsqlDescribeTableQueries…`, `parse_TextColumnComparedWithNumber_ShouldFailLikePostgres`),
  `PostgresServerTest` (DatabaseMetaData, DBeaver fixtures), `difftest/sql92-entry/s01_types.sql`,
  `difftest/cases_session.sql`, `difftest/results/2026-10-08-boundary/`, `bench/tpcc/`.
- Related todos: 001 (OID-0 parameters), 021 (one text-to-value converter), 027 (numeric edge semantics),
  040 (type tables), 052 (unknown literals by context).

### External References (ScalarDB, `origin/master` ≈ 3.20.0-SNAPSHOT jar)

- `core/src/main/java/com/scalar/db/api/TableMetadata.java`, `io/DataType.java`, `io/Column.java` (`copyWith`).
- `storage/jdbc/TableMetadataService.java` (128-character name columns), `storage/jdbc/JdbcAdmin.java` (rename,
  index names), `storage/jdbc/JdbcUtils.java` (index-name hashing), `RdbEngine*.java` (`enclose`, per-engine rename
  and type-change limits).
- `transaction/consensuscommit/ConsensusCommitUtils.java` (reserved names, `before_` columns),
  `ConsensusCommitAdmin.java` (two-step rename).
- `storage/cosmos/CosmosOperationChecker.java`, `storage/objectstorage/ObjectStorageOperationChecker.java`
  (BIGINT ±2^53); `config/DatabaseConfig.java` (metadata cache expiry).
- PostgreSQL 18.1, run locally on 2026-10-14 (ground-truth table).
