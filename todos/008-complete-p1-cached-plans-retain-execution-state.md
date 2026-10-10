---
status: complete
priority: p1
issue_id: "008"
tags: [code-review, performance, memory]
dependencies: []
---

# Cached plans keep the previous execution's hash tables, materialized rows and transaction reader alive

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Blocking operators store their working sets in fields that close() never clears, the operator tree
hangs off Plan.root which only rebind() nulls, and Plan.reader keeps the finished
DistributedTransaction reachable. The per-session LRU holds 256 plans, so a pooled connection that
runs many distinct joins or aggregates retains up to 256 build sides and materialized inputs for its
lifetime.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java:1442-1443, 1467-1477,
  1512-1516 HashJoin.table and rightRows set in build(), never cleared in close().
- engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java:1609, 1644-1648
  NestedLoopJoin.rightRows; 1002 SetOperation.rightCounts; 128, 140-153, 161-168 Scan.cache for non-
  streamed scans.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:335, 1100-1114
  Plan.cachedRows and plan.reader set in attach(), reset only on the next execution.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1093-1098 root() builds the
  tree; only rebind() (423-427) nulls it.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:434-437 Rows.close() only
  closes the cursor; 52-58 planCache of 256.

## Proposed Solutions

### Option 1: Release the tree when the statement finishes

**Approach:** Rows.close() calls plan.release(): root = null unless rootFixed, cachedRows = null,
reader = null. rebind() already rebuilds the tree, so nothing is lost.

**Pros:**
- A few lines
- Also drops the transaction reference

**Cons:**
- A plan executed without going through Rows (tests) must release itself

**Effort:** Small (half a day)

**Risk:** Low

---

### Option 2: Clear working sets in each operator's close()

**Approach:** Null table/rightRows/rightCounts/cache in HashJoin, NestedLoopJoin, SetOperation and
Scan close().

**Pros:**
- No Plan change

**Cons:**
- Must be repeated for every future blocking operator
- Scan.cache is kept by design across re-opens of the same execution, so it needs care

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (P1 #2), architecture reviewer (#11)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] After a statement completes, a heap dump of the session holds no HashJoin build table or Scan cache for it.
- [ ] Re-executing the same statement still works (rebind rebuilds the tree) and the ponytail Scan.cache semantics within one execution are unchanged.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Reproduced by reflection after an autocommit SELECT a.id FROM t a JOIN t b ON a.name = b.name: the cached Plan still held its operator tree, and HashJoin.table held the two right rows after the statement had finished.
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
