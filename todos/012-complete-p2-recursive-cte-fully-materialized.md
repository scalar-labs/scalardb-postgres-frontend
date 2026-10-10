---
status: complete
priority: p2
issue_id: "012"
tags: [code-review, operator, memory, resource-exhaustion]
dependencies: []
---

# A recursive CTE is fully materialized, so LIMIT cannot stop it and the cap counts rows, not bytes

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

RecursiveUnion.start loops until no fresh rows and only checks a 1,000,000-row cap. WITH RECURSIVE
n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n) SELECT i FROM n LIMIT 5 returns 5 rows in PostgreSQL
and an error after building a million rows here. A string-doubling CTE stays at a few dozen rows
while allocating 2^k bytes per step, reaching gigabytes in ~30 iterations; LIMITATIONS.md's memory
note covers large tables, not computed growth.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java:876-926 RecursiveUnion.start
  accumulates result; cap at 878/916 is rows only; the error maps to 42601.
- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:623-625 || has no length cap.

## Proposed Solutions

### Option 1: Lazy iteration plus a byte budget

**Approach:** Produce one iteration on demand so Limit stops the loop; track an approximate byte
total (String lengths) and raise 54000 past a cap (say 256 MB).

**Pros:**
- Fixes both the LIMIT case and the growth case

**Cons:**
- The operator becomes stateful across next() calls (it already is for the result list)

**Effort:** Medium (1 day)

**Risk:** Medium

---

### Option 2: Byte budget only

**Approach:** Keep eager evaluation, add the byte cap and a 1 GB cap on || results.

**Pros:**
- A few lines

**Cons:**
- LIMIT on an unbounded CTE still errors

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #9), security reviewer (#8)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The LIMIT 5 example returns 5 rows.
- [ ] The string-doubling example fails with 54000 well before the heap is exhausted.
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
- Fixed in 4d622ab on `main`; see the commit message for how it was verified
