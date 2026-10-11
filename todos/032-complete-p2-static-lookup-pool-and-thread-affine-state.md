---
status: complete
priority: p2
issue_id: "032"
tags: [code-review, concurrency, embedding, architecture]
dependencies: []
---

# The lookup-join pool is a static unbounded cached pool shared by all sessions, next to three ThreadLocals

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

LookupJoin.LOOKUPS is Executors.newCachedThreadPool: 16 tasks per auto-commit lookup join, so N
concurrent statements grow it to 16N threads with no cap, no shutdown and no timeout on
future.get(); within a batch the slowest read gates the next. Evaluator.ZONE, QueryParser.BOUND and
EMBEDDED are static ThreadLocals; today pool threads only run reads, but any future executor that
evaluates expressions off the connection thread gets UTC and empty parameters, exactly the failure
mode the auth plan worries about for identity. A node embedding the engine cannot own or bound the
pool.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java:1323-1329 pool; 1394-1419
  run(): submits 16, waits with future.get() and no timeout.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:752-756 concurrent() true
  only for auto-commit; 1982-1987 BOUND/EMBEDDED; Evaluator.java:186-195 ZONE set by
  QueryProcessor.applyZone (285-292).
- engine/src/main/java/com/scalar/db/frontend/postgres/Operator.java:15-23 Execution is constructed
  only at QueryParser.java:1079 and 1154 and is the natural carrier.

## Proposed Solutions

### Option 1: Executor and zone on Execution

**Approach:** Create a bounded executor per PostgresServer (or injected by the embedder), pass it
through Operator.Execution; LookupJoin.run uses it with a timeout tied to a statement deadline; move
ZONE into Evaluator.Context/Execution (~10 call sites). Leave BOUND/EMBEDDED until Plan templates
(todo 006) exist.

**Pros:**
- A node owns the pool
- No hidden thread affinity

**Cons:**
- Execution gains two fields

**Effort:** Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#11), architecture reviewer (#3), pattern reviewer (#13)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Thread count under 50 concurrent lookup joins stays bounded.
- [ ] A hung read times out with an error instead of blocking the connection forever.
- [ ] Timestamp functions evaluated on a pool thread honour the session zone (test).
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
- PARTLY REAL. The lookup pool is an uncapped cached pool (about 16 threads per busy session, so up to about 1,600). The ThreadLocal hazard does not apply today: pool tasks only read and build rows.

### 2026-10-11 - Fixed

**By:** Claude Code

**Actions:**
- The lookup pool is capped: 100 threads across all sessions by default, set with
  `scalar.db.postgres.max_lookup_threads`. There is no queue: when every thread is busy, the
  session reads on its own thread, so a session holding the pool with slow or hung reads slows
  only itself (a queue would have let it stall every other session's lookup joins; flagged by the
  commit security review). 100 is half of ScalarDB's
  default JDBC pool (200), leaving the other half for the sessions' own reads at the default
  `max_connections` of 100; threads beyond the free connections would only wait inside the pool.
- Checked that no lookup task waits on another (each is one `reader().read(...)` plus row
  conversion), so a full pool cannot deadlock.
- Tests: execute_LookupJoinPastTheThreadCap_ShouldReadOnItsOwnThread (cap 3, 40 lookups; fails
  when the cap is not applied) and execute_LookupJoinWhilePoolIsHeld_ShouldNotWaitForOtherSessions
  (cap 1 held by a hung session; fails with a queue).
- The ThreadLocal part stays as triaged: pool tasks only read and build rows.
