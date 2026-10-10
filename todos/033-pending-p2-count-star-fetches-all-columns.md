---
status: pending
priority: p2
issue_id: "033"
tags: [code-review, performance, pushdown]
dependencies: []
---

# COUNT(*) and column-free selects fetch every column of every row

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

An empty projection list tells ScalarDB to return all columns. SELECT count(*) FROM t, SELECT 1 FROM
t WHERE ... and EXISTS (SELECT 1 FROM t ...) transfer the whole table, decode every column into a
LinkedHashMap with concatenated keys, and discard it.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:4567 projections = s.star ?
  emptyList : new ArrayList<>(s.projections); 3097 projections only from resolved column references;
  1552-1561 rowOf builds a full-width map.

## Proposed Solutions

### Option 1: Project the key columns

**Approach:** In operation(), when !s.star && s.projections.isEmpty(), project just the partition-
key columns.

**Pros:**
- One condition

**Cons:**
- None

**Effort:** Small (an hour)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#3)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] EXPLAIN SELECT count(*) FROM t shows projections=[key columns].
- [ ] bench check_ops confirms the operation shape.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

