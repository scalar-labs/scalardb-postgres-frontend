---
status: complete
priority: p1
issue_id: "004"
tags: [code-review, protocol, correctness]
dependencies: []
---

# Describe of a SHOW portal sends a zero-column or stale RowDescription

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

QueryProcessor.describe handles SHOW without setting describedPlan, so PostgresServer reads the
column labels from the previous plan (or none). pgjdbc's getTransactionIsolation() runs SHOW
TRANSACTION ISOLATION LEVEL through Parse/Bind/Describe(P)/Execute and receives a RowDescription
with 0 fields followed by a DataRow with 1 field, which fails with 'column index is out of range'.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:809-813 describe(): the
  SHOW branch fills the columns map but leaves describedPlan null.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:777-786
  describedOids()/describedLabels() return empty (or the previous plan's) when describedPlan is
  null.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:491-496
  sendRowDescription(processor.describedLabels(), oids(columns, ...)) uses the label count for the
  field count.
- No test runs SHOW through the extended protocol (PostgresServerTest has no SHOW case).

## Proposed Solutions

### Option 1: Set describedPlan for SHOW

**Approach:** In describe(), build the SHOW plan once (show(sql)), assign
describedSql/describedPlan/describedParameters as the default branch does, and return its columns.

**Pros:**
- Three lines
- Execute then reuses the same plan

**Cons:**
- None

**Effort:** Small (an hour)

**Risk:** Low

---

### Option 2: Derive labels from the columns map

**Approach:** PostgresServer.describe uses the keys of the returned map as labels when
describedLabels() is empty.

**Pros:**
- Defensive for any future branch that forgets describedPlan

**Cons:**
- Keeps two sources of truth

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/test/.../PostgresServerTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P1 #3)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] pgjdbc Connection.getTransactionIsolation() returns a value against the frontend.
- [ ] Describe(P) of SHOW x reports one column named as SHOW names it; Describe(S) likewise.
- [ ] A PostgresServerTest covers SHOW through a PreparedStatement.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Reproduced with pgjdbc against a mocked manager: Connection.getTransactionIsolation() fails with PSQLException 'The column index is out of range: 1, number of columns: 0'.
- Reproduction: `scratchpad/review-main/engine/src/test/java/com/scalar/db/frontend/postgres/P1VerificationTest.java (session scratchpad; mock-based JUnit, runnable with ./gradlew :engine:test --tests '*P1VerificationTest' against an export of 6c71631)`

**Learnings:**
- Confirmed real.

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
- Fixed in 9a79e97 on `main`; see the commit message for how it was verified
