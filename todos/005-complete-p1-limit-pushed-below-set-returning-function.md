---
status: complete
priority: p1
issue_id: "005"
tags: [code-review, correctness, pushdown]
dependencies: []
---

# LIMIT is pushed into the scan below a set-returning function, returning too many rows

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

pushLimit checks aggregation, windows, DISTINCT, WITH TIES and ORDER BY but not whether the select
list expands rows (unnest, json_array_elements, generate_series in the select list). The scan is
limited to N source rows, Expand multiplies them, and no in-memory Limit remains. SELECT id,
unnest(ARRAY[1,2,3]) FROM t LIMIT 2 returns 6 rows; with OFFSET the pushed limit+offset is wrong as
well.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:3989-4001 pushLimit has no
  lp.expanded condition; pushedLimit = limit + offset and the plan limit becomes -1.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1268-1269 the Expand
  operator is added above the source when logical.expanded is non-empty.
- The Limit operator is only added when the plan still carries a limit (not pushed).

## Proposed Solutions

### Option 1: Exclude expanded plans from pushdown

**Approach:** Add && lp.expanded.isEmpty() to pushLimit (and to pushOrder if an expanded column can
appear in ORDER BY).

**Pros:**
- One line
- Keeps pushdown everywhere else

**Cons:**
- Loses the early-stop for a set-returning select on a large table (acceptable; PostgreSQL also cannot stop early there)

**Effort:** Small (an hour)

**Risk:** Low

---

### Option 2: Push a limit and keep an in-memory Limit too

**Approach:** Push limit+offset as a source hint but always keep the Limit operator when expanded is
non-empty.

**Pros:**
- Still reads fewer source rows when each row expands to at least one

**Cons:**
- Wrong when a row expands to zero rows (empty array), so the hint must be dropped then anyway

**Effort:** Small

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/test/.../QueryParserTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P1 #4); pushLimit conditions verified at QueryParser.java:3989-4001
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] SELECT id, unnest(ARRAY[1,2,3]) FROM t LIMIT 2 returns 2 rows; with OFFSET 1 returns rows 2-3.
- [ ] SELECT json_array_elements(doc) FROM t LIMIT 2 returns 2 rows.
- [ ] EXPLAIN shows no limit on the ScalarDB read for such plans.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Reproduced with the parser and a scan stub that honours the pushed limit: SELECT id, unnest(ARRAY[1,2,3]) AS u FROM t LIMIT 2 returned 6 rows; json_array_elements('[1,2,3]'::json) likewise returned 6.
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
- Fixed in 166a00d on `main`; see the commit message for how it was verified
