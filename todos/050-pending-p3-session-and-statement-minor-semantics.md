---
status: pending
priority: p3
issue_id: "050"
tags: [code-review, consistency, documentation]
dependencies: []
---

# Minor session and statement semantics that differ from PostgreSQL and are not documented

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Each is small; together they should be fixed or listed under 'Results that can differ without an
error'.

## Findings

- now()/CURRENT_TIMESTAMP call Instant.now() per evaluation
  (engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:1119-1123, 2024-2025): SELECT
  now() = now() can be false; PostgreSQL gives the transaction start time. Fix: store the instant in
  the Context built by Plan.context.
- SET anything = x is accepted and stored for any name
  (engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:194-211); PostgreSQL
  rejects an unknown non-dotted GUC with 42704.
- INSERT INTO t(a,a) and UPDATE ... SET c=1, c=2 are accepted (last wins); PostgreSQL 42701.
- INSERT ... VALUES (1,..),(1,..) ON CONFLICT DO NOTHING with a duplicate inside the statement
  raises 23505; PostgreSQL inserts the first row.
- A bool parameter sent as yes/on/1 with oid 16 gives 22P02
  (engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:829).
- nth_value with an empty frame at the end of a partition throws IndexOutOfBounds
  (engine/src/main/java/com/scalar/db/frontend/postgres/Windows.java:400).
- DEFAULT in UPDATE SET / ON CONFLICT DO UPDATE becomes NULL via defaultToNull
  (engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:4824, 4853, 5047, 5209);
  consistent with the INSERT rule but not stated for UPDATE.
- Execute's max-row count is ignored (also in todo 031).

## Proposed Solutions

### Option 1: Fix the cheap ones, document the rest

**Approach:** now() once per statement; reject unknown non-dotted GUCs; 42701 for duplicate targets;
first-row ON CONFLICT semantics; accept PostgreSQL boolean spellings; guard nth_value; add the
UPDATE DEFAULT note to LIMITATIONS.md.

**Pros:**
- Each is a few lines

**Cons:**
- None

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Windows.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `LIMITATIONS.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P3 #20), architecture reviewer (#7)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Each item above matches PostgreSQL or is listed in LIMITATIONS.md.
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
- MOSTLY REAL. Probed against PostgreSQL: now() changes per row (49,043 distinct values in one statement, PostgreSQL 1); SET of an unknown parameter is accepted (PostgreSQL 42704); INSERT INTO t (k, k) and UPDATE ... SET n = 1, n = 2 succeed silently (PostgreSQL 42701 and 42601); INSERT ... ON CONFLICT DO NOTHING with the same key twice fails 23505 (PostgreSQL inserts one); nth_value over an empty frame fails with 'Index 3 out of bounds' (PostgreSQL NULL). Not real: DEFAULT in UPDATE, since a non-NULL DEFAULT is refused at CREATE TABLE.
