---
status: pending
priority: p2
issue_id: "040"
tags: [code-review, duplication, type-system]
dependencies: ["019"]
---

# Type tables are hand-copied in five places and have drifted; value-to-column goes through String.valueOf

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

DataType to OID lives in Catalog.typeOid and again in PostgresServer.oid; typlen in
Catalog.typeLength and PostgresServer.size (they disagree: int2 2 vs -1, interval 16 vs -1, so
RowDescription and pg_type differ); SQL type names are parsed in dataType, inferOid's cast switch,
castTypeLabel, unenforced and Catalog.typeOid(String) plus string tests in Evaluator.cast; array
OIDs in Catalog.ARRAY_OIDS and two case lists; two unrelated typeCategory methods. The next type
must be added in seven switches. Separately, columnFromValue converts any value with String.valueOf,
so INSERT INTO t(txt) VALUES (ARRAY['a','b']) stores Java's '[a, b]' where PostgreSQL raises 42804
and Evaluator.text would give '{a,b}'.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java:391-416 typeOid, 308-329
  typeLength, 331-350 typeCategory, 265-306 ARRAY_OIDS, 474-483 typeOid(String), 222-291 names.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:1223-1252 oid, 1305-1322
  size, 904-918 and 1039-1053 array cases.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2982-3038 dataType,
  3614-3683 inferOid, 4105-4136 castTypeLabel, 2740-2799 unenforced, 6120-6135 pgTypeName, 571-602
  typeCategory; 1835-1862 columnFromValue String.valueOf.
- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:1791-1906 cast by
  t.contains(...).

## Proposed Solutions

### Option 1: One PgType table

**Approach:** A table in Catalog (oid, internal name, SQL name, aliases, DataType, typlen, category,
array oid) with byName/byOid/of(DataType); every switch becomes a lookup; PostgresServer.binary
tests elementOid != 0; columnFromValue uses Evaluator.text(value) and refuses a List for a scalar
column.

**Pros:**
- Adding a type is one row
- Fixes the typlen and '[a, b]' differences

**Cons:**
- Medium refactor; needs a test per table edge

**Effort:** Medium (1-2 days)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** pattern reviewer (#6), architecture reviewer (#5), simplicity reviewer (#2, #12)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] One DataType->OID function; RowDescription typlen equals pg_type.typlen.
- [ ] An array into a TEXT column raises 42804.
- [ ] No array OID case lists remain.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

### 2026-10-10 - Triage

**By:** Claude Code (code read against main@85d3c40; probes against native PostgreSQL 18 where marked)

**Verdict:**
- STILL REAL, as a silent difference. Probed: an array stored into a TEXT column is [a, b], where PostgreSQL stores {a,b} (it does not raise 42804, as the reviewer thought). The typlen drift between Catalog and PostgresServer is harmless to drivers.

### 2026-10-10 - Partly fixed

**By:** Claude Code

**Actions:**
- An array stored into a TEXT column keeps PostgreSQL's text form, {a,b}, not Java's [a, b].

**Remaining:**
- The type tables are still duplicated between Catalog and PostgresServer (harmless typlen drift).
