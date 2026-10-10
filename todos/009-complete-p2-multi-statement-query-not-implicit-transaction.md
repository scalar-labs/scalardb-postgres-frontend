---
status: complete
priority: p2
issue_id: "009"
tags: [code-review, transaction, protocol]
dependencies: []
---

# A multi-statement simple Query and a pipelined extended batch are not one implicit transaction

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

PostgreSQL runs all statements of one simple Query string in an implicit transaction block, and the
extended protocol commits at Sync. The frontend autocommits each statement, so 'INSERT ...; INSERT
...' with a failing second insert leaves the first committed, and a pgjdbc batch (Bind/Execute x N,
one Sync) in autocommit mode is partially applied on failure.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:323-332 simpleQuery runs
  each part through run() independently.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:733-747 every autocommit
  write begins and commits its own transaction.
- Not listed in LIMITATIONS.md.

## Proposed Solutions

### Option 1: Open one transaction for the span

**Approach:** When not in a transaction, begin a ScalarDB transaction for the whole simple Query (or
between Syncs) and commit at the end, rolling back on error; a BEGIN/COMMIT inside the string takes
over as PostgreSQL does.

**Pros:**
- Matches PostgreSQL

**Cons:**
- Loses the one-shot fast path for single statements unless special-cased (one statement = current behaviour)
- Interacts with the DB-CORE-10106 read-your-writes limitation

**Effort:** Medium (1-2 days)

**Risk:** Medium

---

### Option 2: Document it

**Approach:** Add the difference to LIMITATIONS.md under results that differ without an error.

**Pros:**
- No code

**Cons:**
- A silent partial commit stays

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `LIMITATIONS.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #5)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Either: 'INSERT ...; INSERT <dup>' as one Query leaves no row; a pgjdbc batch failing midway applies nothing.
- [ ] Or: LIMITATIONS.md states the difference.
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
- Fixed in 46a2cf1, 5f7e08b on `main`; see the commit message for how it was verified

**Remaining:**
- 46a2cf1 covers one simple Query of writes; 5f7e08b covers extended-protocol writes up to a Sync (pgjdbc batches). A read among the writes commits them first; LIMITATIONS.md states it.
