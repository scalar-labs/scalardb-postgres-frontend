---
title: "fix: Catalog queries answer the truth or fail, never a made-up empty result"
type: fix
status: draft
date: 2026-10-17
origin: todos/057-pending-p2-catalog-answers-empty-instead-of-error.md
---

# Catalog queries answer the truth or fail

## Overview

The frontend emulates `pg_catalog` and `information_schema`. When it cannot plan a catalog read, it
answers "no rows" instead of failing. That makes clients believe things that are false: SQLAlchemy reports
an existing table as missing, pgjdbc misreads a type, and psql's `\dC`, `\do`, `\dx` and `\dconfig+ work_mem`
report that nothing exists. This plan removes the made-up answers, fixes what the audited clients actually
need, and lists what the frontend implements (functions, operators, casts) so the catalog is complete
where it claims to be.

Principle, agreed with the user (see origin): **a catalog query returns the truth or an error, never a
plausible made-up answer.** A table whose empty answer is true (`pg_trigger`, `pg_rewrite`,
`pg_publication*`, `pg_policy`, user functions in `pg_proc`) keeps answering empty, since drivers,
DBeaver, pgAdmin and ORMs read those tables when they connect.

## Problem Statement

The audit of 2026-10-17 (origin, section "Audit"; harness and raw results in `difftest/catalog/`) ran every
psql 18 meta-command, every pgjdbc 42.7.14 `DatabaseMetaData` method, DBeaver's recorded catalog queries,
every SQLAlchemy 2.0 `Inspector` method, and `count(*)` over 50 catalog relations, against PostgreSQL 18
and the frontend. It found five groups:

- **A. Made-up answers.** Two code paths produce them.
  - The catch-all in `QueryParser.parse` (`QueryParser.java:2190-2209`) turns any
    `IllegalArgumentException` from `plan(sql)` into an empty result. It applies to any SELECT that
    mentions `pg_catalog.`.
  - A second fallback in `QueryParser.source` (`QueryParser.java:5142-5153`) answers any `pg_catalog.*`
    table function other than `generate_series` with no rows.
  - Together they answered 44 distinct queries. The harm:
    - SQLAlchemy `get_columns` raises `NoSuchTableError`, because its query reads `pg_sequence`.
    - pgjdbc's `TypeInfoCache` lookup by name comes back empty, so `getTypeInfo` reports `_interval` as
      `OTHER`.
    - About 20 meta-commands and `\dconfig+` report "none" for things that exist or that the frontend
      simply doesn't emulate.
    - `count(*)` returns no row at all.
- **B. Missing columns**, which fail honestly but break clients.
  - `pg_index.indnkeyatts` and `indclass` break SQLAlchemy primary keys, indexes and unique constraints,
    and `\d <index>`.
  - `pg_proc.pronargs` and `prolang`, `pg_type.typacl`, `pg_language.*`, `pg_settings.source` and
    `pg_opclass.*` break `\da`, `\df+`, `\dT+`, `\dD+`, `\dL`, `\dconfig` and `\dAc`/`\dAf`.
  - The functions `pg_tablespace_size` and `pg_get_functiondef` are missing.
- **C. Incomplete catalog.** `pg_proc` has 0 rows (PostgreSQL has 3413). `pg_operator`, `pg_cast` and
  `pg_aggregate` are absent. `pg_class` has no catalog relations, `pg_collation` and `pg_depend` are empty,
  and JDBC `getFunctions` returns 0.
- **D. Visibility ignores search_path.** `pg_table_is_visible` always returns true
  (`Evaluator.java:3369-3371`), and `current_schemas` ignores search_path (`Evaluator.java:3412-3422`). So
  `\dt` and SQLAlchemy `get_table_names()` list `sales.orders` although `sales` is not on the path.
- **E. Settings disagree.** `current_setting('server_version_num')` fails although `pg_settings` has the
  setting. The two lists are `Catalog.SETTINGS` (`Catalog.java:39-50`) and the `QueryProcessor` defaults
  (`QueryProcessor.java:120-142`). Prisma reads this setting when it connects.

## Proposed Solution

Phase 0 is a safety net. Phases 1 and 2 are the fix and ship together, so that no interim release turns a
list that is empty today into an error that a later phase removes. Phase 3 is optional completeness, and
Phase 4 is documentation, delivered with each phase.

### Decisions this plan makes

1. **Phases 1 and 2 merge together.** Phase 1 alone would make `pg_cast`, `pg_operator` and
   `pg_aggregate` fail with 42P01 until Phase 2 fills them, which turns psql's `\dC`, `\do` and `\da` from
   "none" into an error in between. Shipping them together avoids that state entirely.
2. **The fallback is removed, not narrowed.** A query the engine cannot plan fails, like any other query.
   - Unknown catalog relations fail with PostgreSQL's 42P01: `relation "pg_catalog.pg_x" does not exist`,
     and unqualified `relation "pg_x" does not exist` instead of today's `Table not found: <db>.pg_x`.
   - Before removal, Phase 0 shows that no client in the test matrix still depends on the fallback.
3. **A declared-empty table needs a reason.** Every catalog table that answers empty is in an explicit
   allowlist (`Catalog.EMPTY_BECAUSE`, name → reason), and a guard test enforces it. A table whose truth
   the frontend cannot know (`pg_locks`, `pg_stat_*_tables` counters, `pg_stat_activity` beyond this
   process) is not declared, so reading it fails rather than guessing.
4. **Visibility follows PostgreSQL.** `pg_table_is_visible` and `current_schemas` honour search_path.
   - This changes `\dt` and `get_table_names()`: tables outside the path are no longer listed. Use
     `\dt *.*`, or `SET search_path`, to see them.
   - It goes in the release notes as a behaviour change.
5. **The function registry gates dispatch.** A hand-kept table of built-in functions (one row per
   overload) feeds `pg_proc` and `pg_aggregate`.
   - `Evaluator` refuses a function name that is not in the registry before it dispatches.
   - So a function that is implemented but not listed fails the existing function tests. That is the
     reverse check, which Java's `switch` cannot give by reflection.
6. **The audit build's logging stays available as an opt-in for one release.** The property
   `scalar.db.postgres.log_unplanned_catalog_queries` logs the SQL text of a catalog query that failed to
   plan, at INFO, so users can report what their tool sent. Deferred if Phase 0 finds nothing.

### Phase 0: Fixtures and client matrix first (on the audit build, fallback still on)

Purpose: prove what removing the fallback breaks before removing it.

- [x] **Branch.** Create branch `fix-catalog-truth` off `main` in `.worktrees/catalog-truth`. Build with
      `-I <scratchpad>/coll-init.gradle`, since main needs the collation core.
- [x] **Replay fixtures** under `engine/src/test/resources/clients/`, one file per client, in the
      `dbeaver/catalog.sql` format (queries separated by `\n-----\n`, a provenance header, and an expected
      outcome line per query: a row count, `rows`, `same` for PostgreSQL 16's rows by digest, or
      `ERROR <sqlstate>`).
  - `psql18.sql` and `psql14.sql`: the queries behind every command in `difftest/catalog/meta.txt`,
    captured with `psql -E` from each client version against the frontend itself: psql adapts its queries
    to the server version, and the frontend reports 16.0. PostgreSQL 14 is at
    `/opt/homebrew/opt/postgresql@14`, and versions 14 and 18 send different `\d` queries.
  - `asyncpg.sql` and `django.sql` as well. Every file records PostgreSQL 16.15's answer per query
    (`-- postgres16: N DIGEST`, from Docker `postgres:16`), and the capturing server's relation OIDs
    (`-- source:`), which the test maps by name to its own and fails on any it cannot resolve.
    `ClientCatalogTest`'s mock schema has ScalarDB's types (no metadata tables), so `same` fits only
    answers that do not depend on column types. Captured with `difftest/catalog/replay.py` and `pglog2fixture.py`.
  - `pgjdbc.sql`: the `TypeInfoCache` queries (by name, and `is_array`), plus the `DatabaseMetaData`
    queries from `difftest/catalog/Meta.java`.
  - `sqlalchemy.sql`: the 2.0 reflection queries, captured from `difftest/catalog/inspect_all.py`, with
    bound values inlined.
  - Expected counts state the frontend's truth, not PostgreSQL's raw counts. Each intended difference
    (system functions, catalog relations, built-in roles) gets a comment.
- [x] **Replay test.** `ClientCatalogTest.clientCatalogQueries_ShouldMatchExpected` replays each file, in
      the pattern of `dbeaverCatalogQueries_ShouldAllRun` (`PostgresServerTest.java:1003-1031`).
- [x] **Client matrix** on the audit build, which logs `CATALOG-FALLBACK`:
  - the four `compat/` stacks: psycopg 3 / SQLAlchemy, node-postgres / Sequelize, pgx, pg / ActiveRecord;
  - asyncpg, and Django `inspectdb` (both through pip into a scratch venv);
  - `difftest/catalog`'s psql 18 and psql 14 runs;
  - pgjdbc, DBeaver and SQLAlchemy;
  - [ ] the same psql run as a low-privilege user under `difftest/cluster-auth` (needs a Cluster node;
    deferred to after Phase 1).

  Record every fallback hit in `difftest/catalog/results/fallback-hits.txt`. Every hit becomes either a
  Phase 1 item or an expected error in a fixture.
- Exit criterion: each fallback hit in the matrix is assigned a fix or an expected error, and the fixture
  files are committed.

**Phase 0 result** (`difftest/catalog/results/matrix.md`). Beyond the audit, the matrix found wrong answers
that come from neither fallback. They join Phase 1 under "Other wrong answers":
- `reg*` output types print the OID instead of the name (`23::regtype::text` is `23`, not `integer`; `0`
  is not `-`). asyncpg names its codecs from this and fails every prepared statement with parameters
  (RecursionError). It is the likely cause of Prisma's stack overflow too.
- `generate_subscripts` over an `int2vector` starts at 1, but PostgreSQL starts at 0, so ActiveRecord's
  primary-key query (`indkey[idx]`) finds nothing. This is a regression since the 2026-10-04 compat run.
- Django `inspectdb`'s constraints query uses `unnest(...) WITH ORDINALITY`, which the fallback answered
  with nothing, so the primary key is lost.
- `'now'::regproc::oid` answers the text `now` in a column described as `oid` (psql's `\sf`).

### Phase 1: Stop inventing answers, and fix what clients hit

**Other wrong answers** (found by Phase 0):
- [x] `regtype`, `regclass` and `regnamespace` values print as names (`-` for 0), and their
      text casts give the name. A `reg*` cast of a name that doesn't exist fails (42704, 42P01 or 3F000), as in
      PostgreSQL. Acceptance: asyncpg's prepared statements, and the asyncpg fixture.
  - `regproc` waits for Phase 2's `pg_proc` OIDs; until then a function's name stands for it, with the
    `pg_catalog.` prefix stripped so pgjdbc's `typinput = 'pg_catalog.array_in'::regproc` holds.
  - asyncpg's RecursionError had another cause: Describe reported every undeclared parameter as type 0,
    and asyncpg introspects type 0 with a query whose own parameter was type 0. Parameters now take
    the type their place gives them (a cast, a compared or assigned column, IN, BETWEEN), text
    otherwise, and binary parameters are read by that type.
  - asyncpg then needed what its introspection reads to be typed as in PostgreSQL: catalog columns
    take PostgreSQL 16's types (`catalog-types.txt`, from `difftest/catalog/catalog_types.py`) where
    `pg_type` lists the type; `oid`, `"char"`, `uuid` and the `reg*` types are added to `pg_type`;
    `int2vector.typelem` is `int2`, and it goes out in binary as an `int2` array from 0; `json`,
    `jsonb` and `uuid` casts are described as those types.
- [x] `generate_subscripts` and `array_lower` follow an `int2vector`'s (or `oidvector`'s) lower bound of
      0. Acceptance: compat/ruby's primary-key, find/update and destroy steps (all 11 steps pass).
      `oidvector` takes the same `Evaluator.lowerBound` when it arrives with `pg_index.indclass`.
- [x] `unnest(...) WITH ORDINALITY` and a column list on its alias (`co(colid, arridx)`). Acceptance:
      Django `inspectdb` keeps `primary_key=True`. Django's constraints and indexes queries give
      PostgreSQL 16's rows. That also took `unnest(a, b)` over several arrays, a derived table's
      columns named by their labels (`SELECT *` over a join inside one failed with `Unknown column`
      before this branch), and a query marked correlated when one of its FROM items reads the
      enclosing row (`array(SELECT ... FROM unnest(tc.reloptions))` in psql's `\d+`).
- [x] Re-run asyncpg after the `reg*` fix, and look at its `jsonb` value (the binary version byte reached
      the client as text) and its `uuid` value (arrived as text). Both were casts described as text;
      fixed above.

**Removing the fallbacks** (`QueryParser.java`):
- [x] Delete the catch-all at 2190-2209, and `catalogColumnNames` (2218-2246), which becomes dead code.
- [x] Delete the table-function fallback at 5142-5153. `generate_series` over another FROM item's
      columns (psql's publication query) now runs, as a select-list set-returning function.
  - `pg_catalog.unnest` must keep working: strip the `pg_catalog.` prefix in the real `unnest` paths
    (5064 and 5113).
  - An unknown table function fails with `0A000 Unsupported table function`.
- [x] Rewrite `QueryParserTest.parse_CatalogQueryBeyondEngine_ShouldReturnNothing` (1054-1076) to expect
      the errors. Its two cases are `json_each` in FROM, and `EXCEPT ALL` with unequal columns.
- [ ] **Unknown relations.** In `target()` (6442-6460), when a name is not found and either the schema is
      `pg_catalog` or the name is unqualified, report PostgreSQL's 42P01 message.
  - `searchNamespaces()` (2992-3001) skips an explicit `pg_catalog` entry. Catalog names already resolve
    first (5025), as in PostgreSQL.
- [ ] **Plan cache.** No cached or described plan can carry an old constant answer. Test it on one
      connection over the extended protocol: Describe, then Execute, then Execute again, for a query that
      now fails. Check for a clean ErrorResponse, then ReadyForQuery after Sync, and that no failed
      transaction is left behind when none was open (`QueryProcessor.java:54-71, 906-912`).

**Making the audited client queries plannable:**
- [x] **pgjdbc `TypeInfoCache`.** (`generate_series` arguments known while planning are evaluated with
      the catalog; subscripting and `USING` already worked.)
  - `generate_series` should accept constant non-literal arguments, evaluated through `Evaluator.eval`
    with a context that carries the catalog. Today `literalContext()` (2559) passes `catalog = null`, so
    `current_schemas` would return null.
  - Also needed: subscripting a function result, `(current_schemas(false))[s.r]`, and `JOIN ... USING`.
  - Acceptance: `getTypeInfo` reports `_interval` as ARRAY (2003). `jdbcMetadata_ShouldDescribeTablesAsPostgreSqlDoes`
    (`PostgresServerTest.java:961-1001`) passes without the fallback; it is the test that fails today when
    the fallback is disabled.
- [ ] **SQLAlchemy 2.0 columns query** (full text in `difftest/catalog/results/fallback-hits.txt`). It
      needs:
  - correlated scalar subqueries over an empty `pg_sequence`;
  - `json_build_object` inside a subquery that returns no row;
  - `pg_get_serial_sequence` (NULL: no sequences);
  - `CAST(CAST(text AS REGCLASS) AS OID)`;
  - `pg_attribute.attidentity`, `attgenerated` and `attcollation`, and `pg_type.typcollation`.

  Acceptance: `get_columns`, `get_multi_columns`, `get_pk_constraint`, `get_indexes`,
  `get_unique_constraints` and `get_multi_*` give PostgreSQL's answers for `items` and `sales.orders`.
- [ ] Re-check each remaining Phase 0 hit against its fixture.

**Tables declared because they are truly empty**, added to `Catalog.EMPTY_BECAUSE` with a reason each:

| Table | Reason |
|---|---|
| `pg_sequence`, `pg_sequences` | no sequences |
| `pg_matviews` | no materialized views |
| `pg_extension`, `pg_available_extensions` | no extensions |
| `pg_event_trigger` | no event triggers |
| `pg_default_acl` | no default privileges |
| `pg_db_role_setting` | no per-role settings |
| `pg_subscription` | no subscriptions |
| `pg_largeobject_metadata` | no large objects |
| `pg_foreign_data_wrapper`, `pg_foreign_server`, `pg_foreign_table`, `pg_user_mappings` | no foreign data |
| `pg_prepared_xacts` | no two-phase transactions |
| `pg_ts_config`, `pg_ts_dict`, `pg_ts_parser`, `pg_ts_template` | no text search |
| `pg_conversion` | no encoding conversions |
| `pg_amop`, `pg_amproc` | operator families are not exposed (`pg_opfamily` rows have no members) |
| `pg_parameter_acl` | no parameter privileges |
| `pg_stats` | no statistics |

The tables already declared empty move into the allowlist too: `pg_trigger`, `pg_rewrite`, `pg_policy`,
`pg_policies`, `pg_inherits`, `pg_enum`, `pg_range`, `pg_attrdef`, `pg_statistic_ext`, `pg_publication*`
and `pg_shdescription`.

**Tables populated with true rows:**
- [ ] `pg_tables`: the user tables, as in `pg_class`; system tables arrive with Phase 3.
- [ ] `pg_views`: empty, because there are no views. It goes in the allowlist until Phase 3.
- [ ] `pg_user`: from the roles, with `usesuper` from the role, `passwd` shown as `********`, and
      `usecreatedb` false.
- [ ] `pg_timezone_names`: the zones `SET TimeZone` accepts, from `ZoneId.getAvailableZoneIds()` filtered
      by the frontend's own check. A test sets every listed zone.
- [ ] `pg_stat_activity`: the sessions of this frontend process (`pid`, `datname`, `usename`,
      `application_name`, `client_addr`, `backend_start`, `state`, `query`), from the server's session
      registry.
  - Columns the frontend cannot know are NULL, as PostgreSQL shows them to unprivileged users.
  - If the server has no session registry, the table stays undeclared (42P01), with a follow-up todo.
- Not declared, so reading them fails (the frontend cannot know the answer): `pg_locks`, and the
  `pg_stat_user_tables` and `pg_stat_all_tables` counters. `pg_statio_all_tables` keeps its current rows,
  which DBeaver reads; check that its values are NULL, not invented zeros.

**Missing columns (group B):**
- [ ] `pg_index`:
  - `indnatts` and `indnkeyatts` are the key count;
  - `indcollation` is 0 per column;
  - `indclass` is an oidvector of each column type's default btree operator class.
  - This needs an `OidVector`, modelled on `Int2Vector` (`Catalog.java:437-450`), plus a `catalogColumnOid`
    case.
- [ ] `pg_opclass` gains `opcintype`, `opcfamily` and `opcdefault`, and `pg_opfamily` gains `opfmethod`.
      Both get rows for the default btree classes of the types the frontend supports, using PostgreSQL's
      OIDs (for example `int4_ops` = 1978). `\dAc` and `\dAf` then list them truthfully.
- [ ] `pg_proc` gains `pronargs`, `prolang`, `prosrc`, `prosecdef`, `provolatile`, `proacl` and
      `proisstrict`, the columns psql 14 and 18 and pgjdbc read. Its rows come in Phase 2.
- [ ] `pg_type.typacl`: NULL.
- [ ] `pg_language`:
  - columns `lanowner`, `lanispl`, `lanpltrusted`, `lanplcallfoid`, `laninline`, `lanvalidator` and
    `lanacl`;
  - one row, `internal` (oid 12, `lanispl` false);
  - so `\dL` is truly empty and `\dLS` lists `internal`.
- [ ] **Settings, unified.** One `Catalog.SETTINGS` table (name, default, vartype, context, unit) feeds
      `pg_settings`, `SHOW`, `SHOW ALL` and `current_setting`. `QueryProcessor` seeds its defaults from
      that table.
  - `pg_settings.setting` and `source` are filled per read, from the session.
  - `source` is `session` after `SET`, `client` for startup parameters, and `default` otherwise.
  - This fixes `server_version_num`.
- [ ] `pg_tablespace_size(oid)` returns NULL, since sizes are unknown, as table sizes already are.

**Visibility (group D)** (`Evaluator.catalogFunction`):
- [ ] **`pg_table_is_visible(oid)`** is true when the relation's namespace is the first namespace on the
      effective search path that has a relation of that name. Catalog relations are always visible.
  - This needs an oid → (namespace, name) map in `Catalog`, and the session's search path in the
    evaluation context. Today the path lives only in `QueryParser.searchNamespaces()` (2977-3001).
- [ ] **`current_schemas(bool)`** returns the effective path, with `pg_catalog` first when the argument is
      true.
  - The connected namespace still shows as `public` (`Catalog.schemaName`, 731-733).
  - `$user` keeps its current mapping to the connected namespace; mapping it to a namespace named after
    the role is out of scope (note in LIMITATIONS).
- [ ] **Cases with their own tests:**
  - search_path `sales` alone: the connected namespace's tables are not visible, and `current_schemas`
    is `{sales}`;
  - an explicit `pg_catalog` placed later on the path;
  - a ScalarDB namespace literally named `public`: the connected namespace wins, as `namespace()` maps it
    today; document this;
  - `pg_type_is_visible` and the operator-class and collation visibility functions stay true, since
    their objects are all in `pg_catalog`.

### Phase 2: List what the frontend implements (group C)

- [ ] **Function registry**, `Builtins.java`: one row per overload with `name`, `argtypes` (OIDs),
      `rettype`, `kind` (`f`/`a`/`w`), `retset` and `strict`.
  - Covers the about 90 scalar functions dispatched in `Evaluator.scalar` (from 2924), the table
    functions in `QueryParser.source`, `Evaluator.AGGREGATES` (89-103) and `Windows.RANKING`
    (`Windows.java:28`).
  - Types come from `Catalog.typeOid(String)`, so a misspelled type name fails when the class loads.
- [ ] **Registry as a gate.** `Evaluator.scalar` checks the registry for the name, and arity, before it
      dispatches. An unlisted name gives the same `0A000 Unsupported function`. Every existing function
      test therefore checks that the registry is complete.
- [ ] **Dead names.** Delete the ten names in `TEXT_FUNCTIONS` (`Evaluator.java:414-449`) that are never
      dispatched (`INITCAP`, `MD5`, `SPLIT_PART`, `REPEAT`, `ASCII`, `TRANSLATE`, `QUOTE_LITERAL`,
      `QUOTE_IDENT`, `REGEXP_REPLACE`, `REGEXP_MATCH`), and the cases in `functionOid` for undispatched
      functions (`TRUNC`, `EXP`, `LN`, `LOG`, `PI`, `RANDOM`, `TO_CHAR`, `CHR`). Implementing them is a
      separate todo.
- [ ] **`BuiltinsTest`.** For each row, build a call from sample literals per argument type, execute it
      (the pattern of `QueryParserTest.java:525`), and assert that it runs and reports `rettype` (through
      `inferOid`, `QueryParser.java:4214`).
  - Also assert that `AGGREGATES`, `Windows.RANKING`, `TEXT_FUNCTIONS` and `NUMERIC_FUNCTIONS` are
    subsets of the registry.
- [ ] **`pg_proc` rows** from the registry:
  - namespace `pg_catalog`, `prolang` 12, `prosrc` = the name, OIDs taken from PostgreSQL 16 for the same
    signature, so that clients caching OIDs agree.
  - Pseudo-types that rows reference (`anyelement`, `anyarray`, `"any"`, `record`, `void`, `oid`,
    `regclass`, `regtype`, `regproc`, `unknown`, `cstring`) are added to the type table with
    `typtype 'p'` (the emitter hard-codes `b` at `Catalog.java:916`). So `\dTS` shows them, as in
    PostgreSQL.
- [ ] **`pg_aggregate`**: rows from the `a` rows (`aggfnoid`, `aggkind 'n'`, `aggnumdirectargs` 0,
      `aggtranstype`, ...), the columns psql's `\da` reads.
- [ ] **`pg_operator`**: an explicit table of the operators `Evaluator.eval` implements: comparison,
      arithmetic including the date, time and interval forms, `||`, `LIKE`/`~` and their negations, and
      the jsonb operators. Each row has left and right types, result type and kind.
  - `OperatorsTest` executes every row, as `BuiltinsTest` does.
- [ ] **`pg_cast`**: the pairs `Evaluator.cast` (2750-2890) supports between the supported types. It
      decides by target type only, so the table is a model of that, checked by executing each pair with a
      sample value.
  - `castmethod 'f'` with `castfunc` 0, since no function is exposed per cast. `castcontext` follows
      PostgreSQL's for the same pair.
- [ ] **New catalog functions:**
  - `pg_get_function_result`, `pg_get_function_arguments` and `pg_get_function_identity_arguments`;
  - `pg_function_is_visible` and `pg_operator_is_visible`;
  - `regproc` and `regprocedure` casts, which today return the string unchanged, a made-up answer;
  - `pg_get_functiondef`, which returns PostgreSQL's `LANGUAGE internal` form. Running it back fails
    cleanly with `Unsupported statement`, since `CREATE FUNCTION` is not supported.
- [ ] **JDBC acceptance.** `getFunctions`, `getFunctionColumns` and `getProcedureColumns` list the
      registry.

### Phase 3 (optional, a separate merge): the catalog lists itself

- [ ] **Typed catalog columns.** Change `cols(...)` to name and type pairs, so `pg_attribute` can describe
      catalog relations. Today the types are inferred from values (`catalogColumnOid`,
      `Catalog.java:433-438`).
- [ ] **Catalog relations** in `pg_class` (`relkind 'r'`/`'v'`, namespace `pg_catalog` or
      `information_schema`) and their columns in `pg_attribute`. `\dS`, `getTables(SYSTEM TABLE)`,
      `information_schema.tables` and `pg_views` then list them. Re-capture the DBeaver and JDBC fixtures,
      since table counts change for tools that do not filter `pg_catalog`.
- [ ] `pg_attribute` row for `tableoid` (attnum -6), the one system column the frontend supports. The
      other system columns (`ctid`, `xmin`, ...) do not exist here, so they are truly absent.
- [ ] `pg_collation`: rows for the collations the frontend accepts (`default`, `C`, `POSIX`, and the ICU
      collation when it is on).
- [ ] `pg_depend`: index → table (`a`), primary-key constraint → index (`i`), array type → element type
      (`i`). That fills DBeaver's Dependencies tab.
- [ ] **Missing `information_schema` views.** Empty-because-true ones (`views`, `triggers`, `sequences`,
      `referential_constraints`, `domains`) go in the allowlist. Populated ones: `routines` and
      `parameters` from the registry, `check_constraints` (empty), `table_privileges` and
      `role_table_grants` from the grants `\dp` already reads, `character_sets` (UTF8), `applicable_roles`.

### Phase 4: Documentation, with each merge

- [ ] **`LIMITATIONS.md`.** Replace the paragraph "psql's describe commands answer from that catalog"
      (around line 65) with a table generated from `EMPTY_BECAUSE` and the guard test's list: catalog
      relation | answers with | why.
- [ ] **`compat/README.md`.** Rewrite the paragraph "A catalog query the engine cannot plan is answered
      with no rows ...", since that behaviour is gone. Update the SQLAlchemy row in its table.
- [ ] **`RELEASE-NOTES.md`.** Add:
  - the 42P01 errors for catalog relations that are not emulated;
  - the `\dt` / `get_table_names()` visibility change, with the workaround;
  - the newly declared catalog names, which now shadow an unqualified user table of the same name
    (`pg_tables`, `pg_user`, `pg_stats`, ...), as in PostgreSQL;
  - the logging property, if it is kept.
- [ ] **`difftest/catalog`.** Committed, with a re-run of its results after each phase.

## Technical Considerations

- **Unknown client queries now fail.** That is the point, but a tool outside the matrix may break where
  it silently worked before. Phase 0's matrix and the opt-in logging (decision 6) reduce this; the release
  note names it.
- **Shadowing.** Each newly declared name resolves before a user table in an unqualified read. This is
  PostgreSQL's rule too (`pg_catalog` is implicitly first). A user table named `pg_stats` changes meaning:
  release note.
- **Cluster authentication.**
  - `pg_tables`, `pg_class` and `pg_user` list every object regardless of privileges, as PostgreSQL does.
  - Loading them must not trigger the per-grantee ACL load (`namespaceGrants`, `Catalog.java:972-992`),
    which only `\dp`-style reads need.
  - `pg_settings` holds nothing secret.
  - The Phase 0 low-privilege run, re-run after Phase 1, checks this.
- **Performance.** Visibility checks are a map lookup per row. The registry, operator and cast tables are
  static.

## System-Wide Impact

- **Interaction graph.** A catalog read is planned by `QueryParser.source` (`Catalog.columns`, then
  `catalog.rows`), evaluated by `Evaluator` (`catalogFunction`), described by `PostgresServer`
  (RowDescription OIDs from `inferOid` and `catalogColumnOid`), and cached by
  `QueryProcessor.planCache`.
- **Error propagation.** Planning errors that were swallowed now reach the client as ErrorResponse, with
  the same SQLSTATEs as other planning errors (42P01, 0A000, 42703). Inside an explicit transaction they
  abort it, as in PostgreSQL.
- **State lifecycle.** `Plan.constant` results are cacheable today. After the change no catalog fallback
  plan exists, so there is nothing stale to cache. `Catalog.load()` keeps caching per statement, as
  today.
- **API surface parity.** Simple and extended protocol, psql versions 14 and 18, and JDBC metadata and
  ORM reflection all go through the same planner. Fixtures cover each.
- **Integration scenarios.**
  1. SQLAlchemy reflects `sales.orders` with `schema="sales"`: columns, primary key and indexes.
  2. pgjdbc `getTypeInfo` then `createArrayOf("interval", ...)`.
  3. psql 14 `\d items_pkey`.
  4. A low-privilege Cluster user runs `\dt *.*`.
  5. A session with `SET search_path = sales` runs `\dt`.

## Acceptance Criteria

### Functional
- [ ] No catalog read anywhere returns a made-up answer:
  - the catch-all and the table-function fallback are gone;
  - every relation name in PostgreSQL 16's `pg_catalog` and `information_schema` either fails with 42P01
    or is declared;
  - every declared-empty table is in `EMPTY_BECAUSE` (guard test, `CatalogGuardTest`, with the name list
    captured from PostgreSQL 16);
  - `count(*)` over a declared-empty table returns one row.
- [ ] Named client flows pass, each as a test or fixture:
  - SQLAlchemy `get_columns`, `get_pk_constraint`, `get_indexes`, `get_unique_constraints` and
    `get_multi_*` on `items` and `sales.orders`;
  - pgjdbc `getTypeInfo` reports `_interval` as ARRAY;
  - psql 14 and 18: `\d <index>`, `\da`, `\df+`, `\dT+`, `\dD+`, `\dL`, `\dLS`, `\dconfig`,
    `\dconfig+ work_mem`, `\dAc`, `\dAf` and `\db+` run;
  - `\dfS`, `\doS`, `\dC` and `\da` list the registry, operators, casts and aggregates;
  - `current_setting('server_version_num')` returns 160000.
- [ ] Visibility: `\dt` with the default path lists only the connected namespace's tables, and the
      search_path cases above have tests.
- [ ] The Phase 0 client matrix, re-run on the final branch with the opt-in logging on, shows zero
      unplanned catalog queries, or each one is listed as an expected error in a fixture.

### Quality gates
- [ ] `./gradlew check` passes. `BuiltinsTest`, `OperatorsTest`, `CatalogGuardTest` and the client replay
      test are new.
- [ ] `difftest/catalog` re-run results are committed. The difftest baselines and fuzz runs are unchanged.
- [ ] Code review before merge.

## Dependencies & Risks

- **Prisma is out of scope.** Its schema engine crashes after the connect probe, even with
  `server_version_num` fixed. That gets a separate todo; Phase 1 includes only the settings fix.
- **Unknown client queries.** Covered above. Mitigated by the matrix and the logging property.
- **Registry upkeep.** Every new function now needs a registry row. The gate makes a missing row fail the
  tests at once, which is the intent.
- **psql version drift.** Fixtures from psql 14 and 18 bracket versions 15 to 17.
- **Builds.** Main builds only with the collation core (`coll-init.gradle`) until ScalarDB publishes it.

## Sources & References

- Origin: `todos/057-pending-p2-catalog-answers-empty-instead-of-error.md`, section "Audit (2026-10-17)";
  harness and results in `difftest/catalog/`.
- Related todos: 024 (narrowed the fallback, now removed), 040 (type tables, complete), 011 (metadata
  cache).
- Code:
  - fallbacks: `QueryParser.java:2190-2209` and `5142-5153`;
  - resolution: `QueryParser.java:5025-5035`, `6442-6460`, `2977-3001`;
  - catalog: `Catalog.java:39-50`, `66-370`, `615-629`, `846-1263`, `1470-1487`;
  - visibility: `Evaluator.java:3362-3483`;
  - dispatch: `Evaluator.java:2924-3333`;
  - casts: `Evaluator.java:2750-2890`;
  - settings: `QueryProcessor.java:120-142`.
- Example of adding catalog tables with psql fixtures: commit `fbac5a1`.
- Existing replay tests: `PostgresServerTest.java:915-959` (psql) and `1003-1031` (DBeaver).
