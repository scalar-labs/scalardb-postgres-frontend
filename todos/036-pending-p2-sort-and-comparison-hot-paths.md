---
status: pending
priority: p2
issue_id: "036"
tags: [code-review, performance, algorithmic]
dependencies: []
---

# Sort recomputes keys per comparison, numbers compare through BigDecimal, and normalize() allocates per key

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

A sort of n rows does n log n comparisons; each re-extracts both keys (string concatenation,
possibly an eval) and for integers allocates two Strings and two BigDecimals. compare() is also used
by MIN/MAX, BETWEEN, CASE and IN. normalize() does new BigDecimal(v.toString()).stripTrailingZeros()
for every numeric key on every hash join, GROUP BY, DISTINCT, UNION and window partition. Roughly
50-100x the cost of Long.compare on the common integer case.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java:576-627 sort/compare/key;
  975-979 normalize; callers 1500-1510, 839-843, 671-679, 1085-1091, 944-950; Windows.java:179-195
  already extracts keys once per row; Evaluator.java:967-972 BigDecimal compare; 2403-2411 MIN/MAX.

## Proposed Solutions

### Option 1: Decorate-sort-undecorate and integral fast paths

**Approach:** Compute an Object[] of keys once per row and sort indices (as Windows.compute does);
in compare() fast-path same-class Long/Integer/Double before the BigDecimal fallback; normalize
integral Integer/Short/Long (and integral doubles) to Long, only non-integral values to a stripped
BigDecimal.

**Pros:**
- Large constant-factor win on every in-memory operator

**Cons:**
- Equality semantics between INT 1 and NUMERIC 1.0 must stay

**Effort:** Small-Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#6, #7)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] A 100k-row integer sort allocates no BigDecimal (allocation profiler).
- [ ] GROUP BY on an int key hashes Longs.
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
- STILL REAL (performance). Sort keys are recomputed per comparison, and numbers compare and normalize through BigDecimal strings.
