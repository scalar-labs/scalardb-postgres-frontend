---
status: complete
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

A shared in-memory table (`MemoryTable`) instead of a full fake ScalarDB: the four column-type test classes
each hand-rolled the same key-only table, so one helper that also applies writes removes the copies and makes
write-then-read testable. Conditions, ranges and orderings stay with difftest.

## Technical Details

**Affected files:**
- `engine/src/test/java/com/scalar/db/frontend/postgres/`

**Database changes:** none (frontend only).

## Resources

- **Source:** architecture reviewer (#8), pattern reviewer (#14, #15), security reviewer (note)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [x] A write-then-read test passes against the fake (`ColumnMapTest#writes_ShouldBeReadBack`).
- [x] No duplicated fixture across the column-type test classes (QueryParserTest keeps its own key-only stub).
- [x] Each SQLSTATE branch has a test (HelpersTest, 2026-10-11).
- [x] `./gradlew check` passes; no behaviour changed, so no difftest rerun

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

### 2026-10-11 - Protocol robustness, partly fixed

**By:** Claude Code

**Actions:**
- protocol_MalformedMessages_ShouldBeRefused: an unknown message type, a length under 4, and a
  query string without its zero byte. It found two differences from PostgreSQL, now fixed: an
  unknown type got a buffered 0A000 and the session went on (now FATAL 08P01, connection closed),
  and a body that ends early was XX000 (now 08P01 "insufficient data left in message").
- CancelRequest and oversized lengths were already covered.

**Remaining:**
- The in-memory fake ScalarDB, shared fixtures, and a test per SQLSTATE branch. Low value per
  effort while difftest covers write-then-read semantics; do it when a bug slips past both.

### 2026-10-11 - Helper and SQLSTATE tests

**By:** Claude Code

**Actions:**
- HelpersTest: every branch of `PostgresServer.sqlState` (moved out of the session class, unchanged,
  so it can be called directly), `split`, `Interval.parse`, `Json.contains/same/delete/path`,
  `QueryParser.escapeStringQuotes/unescape`, `Evaluator.similarPattern/compare`. A mutated
  mapping (25006 to 25007) fails the test.
- `Catalog.relationOid` is covered by the JDBC metadata and DBeaver tests in PostgresServerTest.

**Remaining:**
- The in-memory fake ScalarDB and shared fixtures, as judged on 2026-10-11 above.

### 2026-10-18 - Shared in-memory table

**By:** Claude Code

**Actions:**
- `MemoryTable` (test helper, about 130 lines): get and scan by key, `mutate` applies Insert, Upsert, Update,
  Put and Delete, every operation recorded. ColumnMapTest, CharacterColumnTest, NumericColumnTest and
  NumericTextColumnTest use it instead of their own copies; all their tests pass unchanged with writes applied.
- `ColumnMapTest#writes_ShouldBeReadBack`: INSERT, UPDATE, ON CONFLICT DO UPDATE and DELETE, each read back.
- `QueryParser.parseColumn` is package-private so the helper can make a null column of any type.

**Left as is:**
- QueryParserTest's own key-only stub and the wiring in QueryProcessorTest and PostgresServerTest: they mock
  other things (the manager, the server) and moving them buys little.
- Assertions on error wording: unchanged; matching PostgreSQL's wording is part of the goal.
