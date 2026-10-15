---
title: "feat: Compare text through ScalarDB's configured collation"
type: feat
status: active
date: 2026-10-15
---

# feat: Compare text through ScalarDB's configured collation

## Summary

ScalarDB's `feature/collation` branch adds one database-wide setting, `scalar.db.collation=BINARY|ICU`
(with `scalar.db.collation.icu.locale` and `.icu.rules`), and `CollationComparator.from(DatabaseConfig)`,
which ScalarDB itself uses for every text comparison it makes in memory. The backing database is expected to
be configured to the same order; ScalarDB checks at startup only that the storage *can* be.

The frontend evaluates much of SQL itself (residual filters, sorts, grouping, joins, set operations). Today it
compares text by Java's `String.compareTo` (UTF-16 code units) and groups by `String.equals`. With this change
every text comparison and every text grouping in the frontend goes through the same comparator, so a query
gives the same answer whether a condition or ordering is pushed down or evaluated here. This removes the
collation entry under "Results that can differ without an error" in `LIMITATIONS.md`.

Decisions taken with the user (2026-10-15):

1. Support both `BINARY` and `ICU`.
2. Under ICU, `LIKE`, `ILIKE`, `SIMILAR TO` and regular expressions on user text fail with `0A000`, as
   PostgreSQL 16 (the version the frontend reports) does on a nondeterministic collation. Catalog text is
   exempt (see below).
3. `COLLATE` in a column definition: refused (`0A000`) except `"default"`. `COLLATE` in an expression:
   accepted for `"default"` and the configured collation's own name, refused otherwise.
4. Build against `feature/collation` installed with `publishToMavenLocal` until ScalarDB 3.20.0 ships.

## ScalarDB facts this relies on

Checked on `feature/collation` @ b7d52510f (PRs #3842 to #3844 merged; #3845, open, adds only integration
suites and CI setup).

- `CollationComparator` (`com.scalar.db.common`): `textComparator()`, `textEquals(a, b)`,
  `hasCanonicalTextForm()`, `canonicalTextFormOf(text)` (ICU sort-key bytes: equal bytes exactly when the
  collation calls the strings equal). Thread-safe; ICU uses a per-thread collator.
- BINARY orders by Unicode code point (`TextColumn.compareByCodePoint`), which equals UTF-8 byte order.
  Equality stays `String.equals`.
- Under ICU, equality is many-to-one. Consensus Commit treats collation-equal keys as one record.
- `DB-CORE-10309` (`COLLATION_ICU_LIKE_CONDITION_NOT_SUPPORTED`): ScalarDB refuses a LIKE condition in a
  transaction under ICU. Codes 0304 to 0311 cover the other collation errors, mostly configuration errors at
  startup.
- Comparisons the backend makes are not changed by the setting. PR #3845's PostgreSQL setup creates a
  nondeterministic ICU collation and `ALTER`s each text column to it; MySQL and MariaDB set the database
  default collation.

## What changes

### 1. One comparator for the process

- `PostgresServer` builds `CollationComparator.from(new DatabaseConfig(properties))` at startup (an invalid
  locale or rule string fails startup with ScalarDB's message) and hands it to the engine. The collation is
  per process and never changes, so a static field on `Evaluator`, set once, is enough; tests set it
  directly.
- In a Cluster node the node's own `DatabaseConfig` is the source, so the frontend and the node can't
  disagree.

### 2. Ordering and equality

- `Evaluator.compare(Object, Object)`: when both sides are `String`, use the comparator instead of
  `Comparable.compareTo`. This covers `=`, `<>`, `<`, `BETWEEN`, `IN` lists evaluated by comparison,
  `ORDER BY`, `min`/`max`, `greatest`/`least`, merge joins and window ordering, which all route through it.
  Under BINARY this is the code-point fix (differs from today only for characters outside the BMP).
- Audit every other place user text is compared by `equals` or `compareTo` directly (`Operators.compare`,
  `QueryParser`'s merge of pushed and local rows, `CASE`/`NULLIF`/`IS DISTINCT FROM`, arrays and rows of
  text, `char`/bpchar comparison from the type-modifiers branch) and route them through the same call.

### 3. Grouping and hashing (ICU only)

Hash-based operators key on `List<Object>` with `String.equals`, which would split `'Café'` and its decomposed
spelling into two groups under ICU. One helper, `Evaluator.hashKey(Object)`, returns the value itself under
BINARY and, for a `String` under ICU, a wrapper of `canonicalTextFormOf` bytes (recursing into lists). Use it
at:

- `GROUP BY` and `DISTINCT` (`Operators` around the `seen` sets and `byKey` maps; `QueryParser`'s grouping);
- hash joins (`Operators` hash table) and `IN (subquery)` / `IN` value sets;
- `UNION` / `INTERSECT` / `EXCEPT` (`rightCounts` and `seen`);
- window `PARTITION BY`.

The output value of a group is the first spelling met, as in PostgreSQL.

### 4. Pattern matching under ICU

`LIKE`, `ILIKE`, `SIMILAR TO`, `~` and its variants on user text raise `0A000`
"nondeterministic collations are not supported for LIKE" (PostgreSQL's wording per operator). Never pushed
down under ICU. Check PostgreSQL 16 for which text functions also refuse a nondeterministic collation
(`position`, `strpos`, `replace`, `split_part`, ...) and do the same for those.

**Catalog exemption.** psql and drivers match catalog names with regexes and `LIKE`
(`relname OPERATOR(pg_catalog.~) '^(t)$' COLLATE pg_catalog.default`). In PostgreSQL, catalog `name` columns
are byte-ordered whatever the database collation, so these queries work there and must work here. Rule: a
query whose tables are all catalog relations (`pg_catalog`, `information_schema`) evaluates text in BINARY.

### 5. `COLLATE`

- Column definitions (`CREATE TABLE`, `ALTER TABLE ADD COLUMN`): `QueryParser.unenforced` refuses any
  collation other than `"default"` with `0A000`. This changes current behaviour: `COLLATE "C"` on a column
  is accepted today under BINARY.
- Expressions (`Evaluator.checkCollation`): under BINARY keep today's list (`default`, `C`, `POSIX`,
  `ucs_basic`); under ICU accept `default` and the configured collation's name only.

### 6. Catalog and settings

- `pg_database.datcollate`/`datctype`, `datlocprovider` (`c` or `i`), `daticulocale`, and `SHOW lc_collate`
  report the setting: `C` under BINARY; the ICU locale (root as `und`) under ICU.
- `pg_collation` lists the configured collation under one name, with `collprovider = 'i'` and
  `collisdeterministic = false` under ICU. `attcollation` on text columns points at it.

### 7. Errors

- `DB-CORE-10309` and the other collation user errors from a statement map to `0A000`.
- Configuration errors (0304 to 0308, 0310, 0311) fail startup.

### 8. Interaction with type-modifiers

- bpchar comparison (`Evaluator.bpchar`, trailing spaces ignored) trims and then calls `compare`, so it
  picks up the collation with no change. `char(n)` is stored trimmed, so its hash keys need nothing extra.
- Unbounded and wide `NUMERIC` is stored as decimal text. It must keep comparing as a number, never through
  the collation: check that every path decodes it before `compare` or `hashKey` sees it, and say in the
  getting-started ICU section that those columns need no `ALTER ... COLLATE` on the backend.

## Out of scope

- Per-column or per-expression collations other than the configured one (ScalarDB has none).
- PostgreSQL 18's collation-aware `LIKE`.
- Configuring the backing database. The frontend's `CREATE TABLE` goes through ScalarDB, which creates text
  columns with the database default; on PostgreSQL a nondeterministic ICU collation can't be a database
  default, so each text column has to be altered afterwards. Documented in getting-started, not automated.

## Implementation order

Starts after `type-modifiers-phase0` lands on `main` (both change `QueryParser` and `Evaluator`). Work in a
worktree `.worktrees/collation` from `main`.

1. **Build.** `feature/collation` is based on ScalarDB `master` (4.0.0-SNAPSHOT), not the 3.x line the
   frontend pins. Export it with `git archive`, publish its core as `3.20.0-SNAPSHOT` into a scratch Maven
   repository (`publishToMavenLocal -Dmaven.repo.local=<scratch> -PprojectVersion=3.20.0-SNAPSHOT`), and put
   that repository first with a Gradle init script (`-I`), so neither `~/.m2` nor the build files change.
   Checked 2026-10-16 at b7d52510f: `./gradlew check` passes unchanged, and ICU4J 77.1 arrives transitively.
   Open: whether collation ships in ScalarDB 3.20.0 or only in 4.0.
2. **BINARY.** Comparator plumbing (§1), code-point ordering through `compare` and the audit (§2), the column
   `COLLATE` refusal (§5), error mapping (§7). Difftest sets must match their baselines apart from the
   intended `COLLATE "C"` change.
3. **ICU.** Hash keys (§3), pattern-matching refusal with the catalog exemption (§4), expression `COLLATE`
   (§5), catalog (§6).
4. **Docs.** `LIMITATIONS.md` (remove the collation entry; add the ICU pattern-matching refusal and the
   column `COLLATE` refusal), getting-started section on configuring ICU with each backend, README status
   line, `RELEASE-NOTES.md`.

## Tests

- **Unit** (`QueryParserTest`, `HelpersTest`), run under both collations:
  - BINARY: a supplementary character sorts after U+FFFF text.
  - ICU at secondary strength (`und-u-ks-level2`): `ORDER BY` follows ICU; `'a' = 'A'`; `GROUP BY`, `DISTINCT`,
    hash join, `IN`, `UNION`/`INTERSECT`/`EXCEPT` treat case variants as one; `LIKE`/`~` give `0A000`; a psql
    `\dt` query still works; `COLLATE "C"` in a query gives `0A000`; column `COLLATE` refusal.
  - Error mapping of `DB-CORE-10309`.
- **Difftest, BINARY:** all six sets against their baselines.
- **Difftest, ICU (new set):** PostgreSQL 16 with `CREATE COLLATION ... (provider = icu, locale =
  'und-u-ks-level2', deterministic = false)` applied to the reference tables' text columns, compared with the
  frontend running ICU on a backing PostgreSQL configured the same way. Covers ordering, equality, grouping,
  joins, set operations and the pattern-matching refusals.

## Risks and open points

- **The ScalarDB API can still change** before 3.20.0; the comparator and setting names are the exposure.
- **Backend equality under ICU on PostgreSQL** depends on the user altering every text column; an unaltered
  column compares bytes in the backend and ICU in the frontend. Worth asking ScalarDB for a way to create
  text columns with a given collation (a create option), which would let the frontend's `CREATE TABLE` do it.
- **Text functions under a nondeterministic collation** in PostgreSQL 16 need checking one by one (§4).
- **Performance.** ICU comparison and sort keys cost more than `String.compareTo`; run the TPC-C bench under
  ICU once and record it. BINARY should be unchanged.
