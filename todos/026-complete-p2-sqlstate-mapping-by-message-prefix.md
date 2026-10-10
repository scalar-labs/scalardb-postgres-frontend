---
status: complete
priority: p2
issue_id: "026"
tags: [code-review, error-handling, architecture]
dependencies: []
---

# SQLSTATE is recovered from message prefixes: wrong, dead and missing codes

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Errors are one exception type with a PostgreSQL-worded message and the wire SQLSTATE is decided
later by startsWith on the message in PostgresServer.sqlState (a ponytail-marked shortcut). Any
rewording silently changes the code; the same condition already gets different codes depending on
wording; the fallbacks are wrong (every other IllegalArgumentException is 42601 syntax_error, every
IllegalStateException 25000, so a metadata read failure is reported as invalid_transaction_state).
Clients that branch on SQLSTATE (retry on 40001, undefined table on 42P01) get misled.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:564-702 sqlState():
  659-661 duplicates 614-616; 689-691 is shadowed by 575-577; 701 catch-all 42601.
- Undefined objects reported as 42601: Catalog.java:454 ('relation ... does not exist'), 468, 482;
  QueryParser.java:3057 ('Unknown table or alias') while 'Table not found' (5313) maps to 42P01.
- ExecutionException wrapped in IllegalStateException and reported as 25000: Catalog.java:441, 464;
  QueryParser.java:2586; Evaluator.java:2306; Operators.java:1412, 1415.
- Codes promised in Javadoc but never produced: 42803 (QueryParser.java:5855 vs 5861/5904/5984),
  42804 (504 vs 527).
- Protocol errors as syntax errors: 'Unknown prepared statement'/'Unknown portal' (26000/34000),
  'Unsupported message type' (08P01); 'Unsupported function' as 0A000 instead of 42883; 'Cannot
  compare' as 42601.
- Only 15 states are pinned by tests (PostgresServerTest.java:403-424). The engine itself also
  sniffs messages: QueryParser.java:5674, 6116-6118, 721-722, 1063-1064, 1330.
- A library embedder calling QueryProcessor directly gets no SQLSTATE at all; the auth branch adds
  three more prefix rules.

## Proposed Solutions

### Option 1: Fix the specific codes now (small)

**Approach:** Delete the dead branches; map the undefined-object, protocol and metadata-failure
messages correctly; move sqlState to a package-level SqlStates utility so QueryProcessor callers and
tests can use it.

**Pros:**
- Immediate client-visible fixes

**Cons:**
- Prefix matching stays

**Effort:** Small (half a day)

**Risk:** Low

---

### Option 2: A typed error (large)

**Approach:** SqlError extends RuntimeException with a sqlState field and a few factories
(undefinedColumn, undefinedTable, featureNotSupported, invalidTextRepresentation); throw it at the
sites that already build PostgreSQL messages; keep the prefix table as the fallback for the long
tail; tests assert on sqlState.

**Pros:**
- Codes stop depending on wording
- The 58 wording assertions in tests can become code assertions

**Cons:**
- 166 throw sites; mechanical but large

**Effort:** Large (3+ days)

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** pattern reviewer (P1 #1), architecture reviewer (#4), simplicity reviewer (#4), own reading
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Unknown relation/schema/type raise 42P01/3F000/42704 on every path.
- [ ] A metadata read failure is not 25000.
- [ ] Unknown prepared statement/portal raise 26000/34000.
- [ ] The dead branches are gone and each mapped state has a test.
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
- Fixed in af8fc0f, fc5b954 on `main`; see the commit message for how it was verified

**Remaining:**
- fc5b954 made the difftest compare SQLSTATEs and fixed the ten codes it found.
