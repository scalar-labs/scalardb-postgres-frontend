---
status: pending
priority: p2
issue_id: "042"
tags: [code-review, catalog, discoverability]
dependencies: []
---

# The catalog does not say which key columns are the partition key, nor the clustering order

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Partition and clustering keys are concatenated into one _pkey list; pg_index.indkey,
pg_get_indexdef, pg_get_constraintdef and key_column_usage show (w_id, d_id, o_id) and nothing says
which prefix is the partition key or that o_id is DESC (getClusteringOrder is never read;
pg_description is declared but empty). A client that must put the partition key in WHERE to get a
Scan instead of a ScanAll has no catalog query that tells it; EXPLAIN on a guess is the only way.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java:608-609, 653-703, 199.

## Proposed Solutions

### Option 1: pg_description rows and indexdef order

**Approach:** Emit one pg_description row per table ('partition key (w_id, d_id), clustering key
(o_id DESC)'), which \d+ and obj_description() already read, and append the clustering order to the
_pkey indexdef.

**Pros:**
- Standard hooks, no new tables

**Cons:**
- Free text; a structured column would need a custom view

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** agent-native reviewer (#2)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] \d+ t shows the partition and clustering keys.
- [ ] pg_get_indexdef shows DESC for a DESC clustering column.
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
- STILL REAL (discoverability). The catalog does not tell partition from clustering keys or show DESC order; obj_description is hard-wired to NULL.
