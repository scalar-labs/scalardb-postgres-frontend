---
status: complete
priority: p2
issue_id: "028"
tags: [code-review, security, resource-exhaustion, numeric]
dependencies: []
---

# Unbounded numeric exponents turn 1e500000000 into a 145 MB BigInteger and a 500-million-digit string

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

PostgreSQL rejects 1e500000000 ('value overflows numeric format'). Here decimal() calls setScale(0)
on any negative-scale value, computing 10^500000000 via BigInteger.pow (tens of seconds, hundreds of
MB), and toPlainString then builds a 500 M-char string. '1e999999999'::jsonb skips the pow and
allocates a ~1 GB StringBuilder directly. Reachable from literals, casts and Bind parameters before
any SQL runs.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:874-877 decimal(); 303-305
  literals; 1848-1849 casts; PostgresServer.java:833 typed(); Evaluator.java:1170 toPlainString;
  Json.java:36 USE_BIG_DECIMAL_FOR_FLOATS and 432 toPlainString.

## Proposed Solutions

### Option 1: Guard the scale

**Approach:** In decimal() and before toPlainString in Json.write, reject |scale| > 131072 + 16383
with 22003 numeric_value_out_of_range, mirroring PostgreSQL's limits.

**Pros:**
- One guard covers all entry points

**Cons:**
- None

**Effort:** Small (an hour)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Json.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#6)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] SELECT 1e500000000 raises 22003 immediately.
- [ ] '1e999999999'::jsonb raises 22003.
- [ ] 1e100 still works.
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
- Fixed in 59d4176 on `main`; see the commit message for how it was verified
