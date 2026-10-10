---
status: complete
priority: p2
issue_id: "014"
tags: [code-review, dml, catalog, consistency]
dependencies: []
---

# ALTER TABLE ... ADD COLUMN silently drops DEFAULT, NOT NULL, UNIQUE and type modifiers

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

CREATE TABLE refuses a non-NULL DEFAULT and notices the other unenforced constraints, as
LIMITATIONS.md promises. ADD COLUMN reads only the data type, so ALTER TABLE t ADD COLUMN n INT NOT
NULL DEFAULT 5 succeeds without a word and existing rows read NULL; ADD COLUMN p NUMERIC(10,2) gives
no NOTICE.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2910-2916 ADD COLUMN uses
  getDataType() only.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2713-2739 CREATE TABLE's
  unenforced() check is not applied here.

## Proposed Solutions

### Option 1: Run the same checks

**Approach:** Call unenforced()/the DEFAULT refusal and the type-modifier notices for each ADD
COLUMN definition.

**Pros:**
- Reuses existing code

**Cons:**
- None

**Effort:** Small (an hour)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #11)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] ADD COLUMN ... DEFAULT 5 is refused like CREATE TABLE.
- [ ] ADD COLUMN ... NOT NULL / NUMERIC(10,2) send the same NOTICEs as CREATE TABLE.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

### 2026-10-10 - Resolved

**By:** Claude Code

**Actions:**
- Fixed in 46a2cf1 on `main`; see the commit message for how it was verified
