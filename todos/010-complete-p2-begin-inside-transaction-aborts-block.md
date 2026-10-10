---
status: complete
priority: p2
issue_id: "010"
tags: [code-review, transaction, correctness]
dependencies: []
---

# BEGIN inside a transaction is an error that aborts the block; COMMIT/ROLLBACK outside send no WARNING

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

PostgreSQL answers a nested BEGIN with WARNING 25001 and continues. The frontend throws, the server
calls processor.fail(), the block is aborted, and the later COMMIT becomes a ROLLBACK: a script with
a stray BEGIN loses its writes. COMMIT or ROLLBACK outside a transaction is a no-op here but sends
no WARNING (PostgreSQL: 25P01), although the code comment says it should and the warning() helper
exists.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:484-486 throws
  IllegalStateException('A transaction is already active') (mapped to 25000).
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:312 processor.fail()
  after any error marks the block aborted.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:496-500 COMMIT outside a
  transaction returns an empty Rows without a warning; 691-701 ROLLBACK likewise.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:342-347 warning() exists
  and is used for SET LOCAL and SET TRANSACTION.

## Proposed Solutions

### Option 1: Return warnings

**Approach:** BEGIN inside a transaction returns warning('there is already a transaction in
progress') with tag BEGIN; COMMIT/ROLLBACK outside return warning('there is no transaction in
progress').

**Pros:**
- Three small edits
- Matches PostgreSQL

**Cons:**
- The Javadoc that documents the throw must change

**Effort:** Small (an hour)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/test/.../QueryProcessorTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #6)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] BEGIN; INSERT; BEGIN; INSERT; COMMIT commits both inserts and the client sees one WARNING.
- [ ] COMMIT outside a transaction sends WARNING 25P01 and tag COMMIT.
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
