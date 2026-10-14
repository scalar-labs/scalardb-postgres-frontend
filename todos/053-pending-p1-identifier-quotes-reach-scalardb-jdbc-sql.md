---
status: pending
priority: p1
issue_id: "053"
tags: [code-review, security, injection]
dependencies: []
---

# Quote characters in identifiers reach ScalarDB's JDBC SQL unescaped

Found on 2026-10-14 by the security review of `docs/plans/2026-10-14-001-feat-keep-type-modifiers-exact-numeric-plan.md`,
against `main` @ 1416486. Confirmed by reading the frontend and ScalarDB core; **not yet reproduced**.

## Problem Statement

A client that may run `CREATE TABLE` (or `ALTER TABLE … ADD COLUMN` / `RENAME`, `CREATE SCHEMA`) can most likely
execute arbitrary SQL on the backing database with ScalarDB's storage credential, by putting a quote character in a
quoted identifier. In Cluster mode this runs on the node and bypasses Cluster's authorization entirely.

## Findings

- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:3282-3297` `checkedName` checks only the
  UTF-8 byte length (63, or 56 for columns). No character is refused.
- `QueryParser.java:6818-6821` `unquote` keeps `'`, `` ` ``, `[`, `]`, `;` and control characters inside a quoted
  identifier (`""` becomes `"`).
- ScalarDB core (`origin/master` of the local checkout, matching the 3.20.0-SNAPSHOT jar):
  `core/src/main/java/com/scalar/db/storage/jdbc/TableMetadataService.java:150-159` builds the metadata row with
  `String.format("INSERT INTO %s VALUES ('%s','%s','%s',%s,%s,%s,%d)", …, columnName, …)` and no escaping;
  `JdbcAdmin.java:1659` runs it through `connection.createStatement()`. pgjdbc's `Statement.execute` accepts several
  `;`-separated statements in one call.
- ScalarDB's per-engine `enclose` (`RdbEnginePostgresql.java:202` `"x"`, `RdbEngineMysql.java:173` `` `x` ``,
  `RdbEngineSqlServer.java:213` `[x]`) adds quotes without escaping embedded ones, so CREATE TABLE and every DML
  statement ScalarDB generates can be broken out of the same way.
- Example shape (not run): `CREATE TABLE t (id INT PRIMARY KEY, "x',NULL,NULL,false,0);<statement>;--" INT)`.
  The 56/63-byte limits still leave room for a payload.

## Proposed Solutions

### Option 1: Refuse dangerous characters in the frontend (defence, now)

**Approach:** `checkedName` (and every schema/table/column/index name the frontend sends to ScalarDB: CREATE SCHEMA,
CREATE TABLE incl. `WITH` options, ADD COLUMN, RENAME, CREATE INDEX, and names in DML that reach ScalarDB) refuses
`'`, `"`, `` ` ``, `[`, `]`, `\`, `;`, NUL and other control characters with 42602 (invalid_name). A conservative
alternative is an allow-list such as letters, digits, `_`, `$` and non-ASCII letters.

**Pros:**
- Closes the hole for every frontend user regardless of the ScalarDB version
- Small, testable change

**Cons:**
- PostgreSQL allows these characters in quoted identifiers; a few legitimate names are refused (documented)
- Does not protect other ScalarDB clients

**Effort:** Small (half a day with a test per character and per DDL path)

**Risk:** Low

---

### Option 2: Fix ScalarDB core (root cause)

**Approach:** Use `PreparedStatement` parameters for the metadata INSERT/UPDATE/DELETE in `TableMetadataService`
(and the namespace / virtual-table services), and escape or validate identifiers in each `RdbEngine*.enclose`.

**Pros:**
- Fixes it for every ScalarDB client, not only the frontend

**Cons:**
- Outside this repository; needs a core change and release
- Responsible disclosure to the ScalarDB maintainers is the user's decision

**Effort:** Medium (in core)

**Risk:** Low

## Recommended Action

**To be filled during triage.** Likely both: Option 1 in this repository immediately (it is also Phase 0 material for
the type-modifier plan), Option 2 reported to the ScalarDB core maintainers through their security channel.

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java` (`checkedName`, `unquote`, DDL paths)
- ScalarDB core: `storage/jdbc/TableMetadataService.java`, `storage/jdbc/JdbcAdmin.java`, `storage/jdbc/RdbEngine*.java`

**Database changes:** none.

## Resources

- **Source:** security review of the type-modifier plan, 2026-10-14; verified by reading `main` and ScalarDB `origin/master`
- **Related:** `docs/plans/2026-10-14-001-feat-keep-type-modifiers-exact-numeric-plan.md` (Phase 0), todo 002 (protocol caps)

## Acceptance Criteria

- [ ] A reproduction against a JDBC-backed ScalarDB (PostgreSQL) confirms or refutes the injection, in a throwaway database
- [ ] Every DDL path refuses each listed character with 42602 (a test per character and per path)
- [ ] Names that reach ScalarDB from DML cannot contain them either (they must resolve to an existing column anyway)
- [ ] `LIMITATIONS.md` lists the refused characters
- [ ] The core issue is reported to the ScalarDB maintainers (user's decision)

## Work Log

### 2026-10-14 - Frontend fix (Option 1)

**By:** Claude Code

**Actions:**
- `QueryParser.checkedCharacters` refuses `'`, `"`, backquote, `[`, `]`, `\`, `;` and control characters with 42602;
  called from `checkedName` (new schema, table and column names) and on every name a DDL path passes to the admin:
  `namespaceOf` (all DDL and DML namespaces), TRUNCATE, CREATE INDEX (table, column), DROP TABLE, DROP SCHEMA,
  ALTER TABLE (table, renamed column)
- Test `parse_DdlWithQuotesOrControlCharactersInNames_ShouldFailWithInvalidName` covers 17 statements; a column
  name with a quote in CREATE INDEX was already refused as an expression index
- `LIMITATIONS.md` lists the refused characters
- On branch `type-modifiers-phase0`, uncommitted

**Learnings:**
- Still not reproduced against a real ScalarDB backend; the core issue (Option 2) is open and unreported

### 2026-10-14 - Initial Discovery

**By:** Claude Code

**Actions:**
- Security review flagged it; confirmed by reading `QueryParser.checkedName` / `unquote` on main and
  `TableMetadataService.java:150-159`, `JdbcAdmin.java:1659` in the ScalarDB checkout
- Not reproduced: no ScalarDB JDBC backend was running

**Learnings:**
- The frontend is the only gate between PostgreSQL clients and ScalarDB's identifier handling, which trusts its input
