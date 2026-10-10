---
status: complete
priority: p2
issue_id: "031"
tags: [code-review, protocol, hardening]
dependencies: ["030"]
---

# CancelRequest and the Execute row limit are ignored, so clients cannot stop runaway statements

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

A CancelRequest closes the cancelling socket and does nothing; the random BackendKeyData is never
stored. Execute's max-row count is ignored, so a portal never suspends. psql's Ctrl-C and
Statement.cancel() cannot interrupt a regex, a BigInteger.pow or a growing CTE, and the DDL settle
sleep is uninterruptible. docs/auth-design.md:137 notes the key must be validated before
cancellation is implemented.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:188-190 cancel -> return
  null; 219-223 keys sent, not stored; 504 'The maximum row count is ignored'.

## Proposed Solutions

### Option 1: Registry plus a cooperative flag

**Approach:** ConcurrentHashMap<(pid,key), Connection> populated at startup (needs todo 030's
registry); CancelRequest sets a volatile cancelled flag checked per row in Operators/Evaluator and
per char by the regex wrapper (todo 016); error 57014 query_canceled. PortalSuspended after max rows
is a separate small change in execute().

**Pros:**
- Turns the DoS findings from 'restart the server' into 'the client gives up'

**Cons:**
- Cooperative checks in hot loops

**Effort:** Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#13), correctness reviewer (P3 list), architecture reviewer (#7)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Ctrl-C in psql during a slow query returns 57014 within a second.
- [ ] pgjdbc with fetchSize in a transaction receives PortalSuspended.
- [ ] A cancel with a wrong key does nothing.
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
- STILL REAL. Probed: a pgjdbc Statement.cancel() after 0.5 s had no effect; the query ran 498 s until the heap ran out (see todo 053). The Execute row limit is ignored: results are right, but setFetchSize buffers everything.

### 2026-10-10 - Partly fixed

**By:** Claude Code

**Actions:**
- CancelRequest now works: BackendKeyData's process ID and secret key name the session, whose running statement fails 57014 at its next row (see todo 053).

**Remaining:**
- The Execute row limit is still ignored: results are right, but pgjdbc's setFetchSize in a transaction receives all rows at once.

### 2026-10-10 - Resolved

**By:** Claude Code

**Actions:**
- Execute's row limit now suspends the portal: at most that many DataRows, then PortalSuspended, and the run stays open for the portal's next Execute (with a fresh statement_timeout) until it completes; it is closed by Close, a new Bind of the same name, the end of the transaction (or the Sync outside one) and disconnect. A write still runs to completion and sends all its RETURNING rows, as PostgreSQL holds them.
- A plan whose run is open is not handed out again by the session's plan cache: the subquery rows and reader live in the plan, so a second run of the same statement finishing first broke the suspended one (`…DE` instead of `…DDC` in the raw-protocol test without the guard).
- Probed: pgjdbc with fetchSize 0, 1, 100, 4999, 5000 and 7000 over 5,000 rows of a real ScalarDB table returns every row, with the same query run in full mid-fetch.

**Remaining:**
- The final CommandComplete counts all the portal's rows; PostgreSQL counts those of the last Execute. No driver reads it for a SELECT.
