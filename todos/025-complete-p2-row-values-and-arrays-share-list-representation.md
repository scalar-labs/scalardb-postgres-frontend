---
status: complete
priority: p2
issue_id: "025"
tags: [code-review, anti-pattern, correctness]
dependencies: []
---

# Row constructors and arrays are both List<Object>, so (1,2) is reported as int4[] and array equality is wrong with NULLs

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

SELECT (1, 2) is announced as int4[] and printed {1,2} (PostgreSQL: a record (1,2)); ARRAY[1,NULL] =
ARRAY[1,NULL] evaluates through rowCompare to NULL where PostgreSQL array equality is true. The
dispatch picks by instanceof List for both.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:746-753
  ParenthesedExpressionList -> List; 316-322 ArrayConstructor -> List; 610-617 two Lists compared
  with rowCompare; 827-841 vs 945-961 differing NULL rules; 1178-1203 text prints every List as
  {...}; PostgresServer.java:1271-1284 oid reports any List as an array.

## Proposed Solutions

### Option 1: A RowValue wrapper

**Approach:** A final class holding List<Object>, produced only by the row-constructor branch;
rowCompare applies to RowValue, arrays keep compare's list branch; text/oid treat RowValue
separately.

**Pros:**
- Small, local

**Cons:**
- Row values appear in IN ((1,2),(3,4)) and subquery comparisons; each site needs the wrapper

**Effort:** Small-Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** pattern reviewer (P2 #9)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] ARRAY[1,NULL] = ARRAY[1,NULL] is true.
- [ ] SELECT (1,2) is described as record, not int4[].
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
