---
status: complete
priority: p1
issue_id: "007"
tags: [code-review, performance, algorithmic]
dependencies: []
---

# x IN (SELECT ...), NOT IN and = ANY (SELECT ...) scan the subquery rows linearly per outer row

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

The uncorrelated subquery is evaluated once, but for every outer row the Filter copies the first
column of every subquery row into a fresh ArrayList and scans it with compare(), which for two
numbers allocates two Strings and two BigDecimals. A 100k x 100k IN is 10^10 BigDecimal compares.
This is the shape every ORM produces for 'where id in (select ...)'.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:542-569 IN over a subquery:
  list copy at 545-550 then a linear compare loop.
- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:589-595 ANY/ALL the same.
- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:159-179 Context.subquery
  caches the rows once per execution but hands back the list.
- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:972 compare() of two Numbers
  goes through new BigDecimal(a.toString()).
- engine/src/main/java/com/scalar/db/frontend/postgres/Optimizer.java:79-141 only literal IN lists
  are rewritten; no rule turns an IN-subquery into a semi-join.

## Proposed Solutions

### Option 1: Hash the subquery column once per execution

**Approach:** Store a HashSet of normalized values (Operators.normalize) on the Plan next to
cachedRows; IN probes it; keep a NULL flag for three-valued logic.

**Pros:**
- Turns O(n*m) into O(n+m)
- Local change in Evaluator

**Cons:**
- normalize() itself allocates (todo 036)

**Effort:** Small (half a day)

**Risk:** Low

---

### Option 2: Rewrite uncorrelated IN-subqueries to a hash semi-join in the Optimizer

**Approach:** A new rule produces a semi/anti join whose right side is the subquery, reusing
HashJoin.

**Pros:**
- Also enables pushdown of the outer predicate
- EXPLAIN shows it

**Cons:**
- Larger change; NOT IN with NULLs needs anti-join null semantics

**Effort:** Medium (2 days)

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Optimizer.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (P1 #1)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] A 100k-row IN (SELECT ...) completes in seconds, not minutes, in bench.
- [ ] NOT IN with a NULL in the subquery still returns no rows; IN with NULL still gives NULL/true correctly.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Verified by reading only: Evaluator.java:545-568 copies every subquery row's first column into a new ArrayList and scans it with compare() on each evaluation, i.e. once per outer row.
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
