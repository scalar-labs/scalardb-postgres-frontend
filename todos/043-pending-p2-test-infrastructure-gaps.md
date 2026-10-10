---
status: pending
priority: p2
issue_id: "043"
tags: [code-review, tests, testability]
dependencies: []
---

# Test infrastructure: no in-memory fake ScalarDB, triplicated fixtures, wording-pinned assertions, no protocol-robustness tests

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

The unit tests drive the engine with real SQL against Mockito stubs that honour keys only, so
pushed-down WHERE clauses are verified by verify(transaction).scan(expectedScan) rather than by
results and mutations are never applied: write-then-read semantics, savepoints and ON CONFLICT live
only in difftest, which needs a built jar and two PostgreSQL databases. Fixtures and mock wiring are
copied across the three test classes, there are no parameterized tests (three hand-rolled loops), 58
assertions pin error wording, mega-tests stop at the first failure, and nothing exercises malformed
protocol input or ~40 of the sqlState branches.

## Findings

- engine/src/test/.../QueryParserTest.java:99-130 stubTable filters on keys only; 56-66, 84-96,
  130-136, 1983-1989 duplicated fixtures; 695-712, 568-578 loops; 388-476 and others bundle a dozen
  features.
- engine/src/test/.../QueryProcessorTest.java:33-50 and PostgresServerTest.java:43-74 repeat the
  wiring; PostgresServerTest.java:403-424 pins 15 of ~40 SQLSTATEs.
- Untested static helpers: PostgresServer.split/sqlState/binary arrays, Interval.parse,
  Json.contains/same/delete/path, Windows frame bounds and nth_value, Catalog.relationOid,
  Optimizer.pushDownOr/lateConditions, QueryParser.escapeStringQuotes/unescape,
  Evaluator.similarPattern/compare/AT TIME ZONE.
- No test sends a malformed message length, an unknown message type, a truncated body or a
  CancelRequest.

## Proposed Solutions

### Option 1: FakeScalarDb plus shared fixtures

**Approach:** One FakeScalarDb implementing DistributedTransactionManager and DistributedTransaction
with in-memory tables honouring keys, where conjunctions, orderings, limits and conditional
mutations (and recording the caller, for the auth branch's identity assertions); a Fixtures class
beside TestScanners; convert the loops to @ParameterizedTest; split mega-tests; direct tests for the
static helpers; protocol tests for todo 002.

**Pros:**
- Lets QueryProcessorTest cover INSERT->SELECT, UPDATE...FROM, ON CONFLICT and savepoints without Mockito choreography

**Cons:**
- A ~300-line fake to maintain

**Effort:** Medium (2 days)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/test/java/com/scalar/db/frontend/postgres/`

**Database changes:** none (frontend only).

## Resources

- **Source:** architecture reviewer (#8), pattern reviewer (#14, #15), security reviewer (note)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] A write-then-read test passes against the fake.
- [ ] No duplicated fixture across the three classes.
- [ ] Each SQLSTATE branch has a test.
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
- PARTLY REAL. Raw-socket tests and SQLSTATE checks were added since; the key-only stub table, missing CancelRequest and unknown-message tests, and wording-pinned assertions remain.
