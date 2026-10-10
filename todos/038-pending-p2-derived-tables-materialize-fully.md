---
status: pending
priority: p2
issue_id: "038"
tags: [code-review, performance, memory]
dependencies: []
---

# Derived tables and CTE bodies materialize their whole input before the first row

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Only the outermost SELECT streams; a subquery in FROM is planned with streaming=false because it has
a Scope, so its first table is read fully into memory even when opened exactly once. SELECT * FROM
(SELECT * FROM big WHERE ...) s LIMIT 10 and the CTE form load all of big; the LIMIT early-stop is
defeated. The README documents materialization for sorts and aggregates only.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:3184 physical(logical, outer
  == null); Operators.java:140-153 Scan.start caches when !stream; 1120-1152 Rekey pulls from the
  derived root. Unconfirmed for CTE and set-operation members.

## Proposed Solutions

### Option 1: Stream single-use derived sources

**Approach:** Pass streaming=true for the first source of a FROM-subquery of a top-level plan when
it is not correlated, not LATERAL and not the re-opened side of a join.

**Pros:**
- Restores early stop and bounded memory

**Cons:**
- Must keep caching where a source is re-opened

**Effort:** Small (half a day)

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#9)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The LIMIT 10 example reads at most a few pages of big (EXPLAIN ANALYZE reads).
- [ ] A correlated or LATERAL derived table still works.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

