---
status: complete
priority: p2
issue_id: "017"
tags: [code-review, null-semantics, correctness]
dependencies: []
---

# BETWEEN with a NULL bound returns NULL, and AND/OR/COALESCE evaluate every operand eagerly

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

5 BETWEEN NULL AND 3 is false in PostgreSQL (v >= lo AND v <= hi) and NULL here; 5 NOT BETWEEN NULL
AND 3 is true there and NULL here, so rows are dropped. Only the plain BETWEEN directly under WHERE
is split correctly. AND, OR and COALESCE evaluate all operands, so b <> 0 AND a/b > 1, an OR nested
in an OR, and COALESCE(a, 1/0) raise 22012 where PostgreSQL succeeds; top-level WHERE conjuncts do
short-circuit.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:571-585 BETWEEN returns NULL
  on any NULL operand; QueryParser.java:5329-5339 splits only the top-level form.
- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:488-503 AND/OR, 1966-1991
  COALESCE evaluate every operand.

## Proposed Solutions

### Option 1: Lazy evaluation

**Approach:** Evaluate BETWEEN as the conjunction of two comparisons with three-valued logic; AND
stops at the first FALSE, OR at the first TRUE, COALESCE at the first non-NULL.

**Pros:**
- Exact semantics
- Also faster

**Cons:**
- Order of side-effect-free evaluation changes, which is fine

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #16)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The four examples above match PostgreSQL.
- [ ] Existing fuzz baselines still pass.
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
