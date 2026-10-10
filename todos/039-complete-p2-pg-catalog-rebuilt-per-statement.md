---
status: complete
priority: p2
issue_id: "039"
tags: [code-review, performance, caching, catalog]
dependencies: []
---

# The pg_catalog emulation is rebuilt from the admin API on every catalog statement

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

parse() calls catalog.invalidate() before planning every statement; Catalog.load() then calls
getNamespaceNames, getNamespaceTableNames and getTableMetadata per table and rebuilds every
pg_catalog and information_schema row, and plans touching catalog tables are never cached. Each psql
\d and each ORM introspection issues several such queries: with 100 tables and six queries per \d,
~600 metadata reads.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1919 catalog.invalidate();
  400-403 catalog plans not cached; Catalog.java:512-759 load(); the session metadataCache
  (QueryParser.java:151) is not used by Catalog.

## Proposed Solutions

### Option 1: Invalidate on DDL and a short TTL

**Approach:** Invalidate where DDL already invalidates metadata (QueryProcessor.java:719) plus a TTL
equal to the metadata cache expiry; reuse metadataCache inside Catalog.load().

**Pros:**
- Removes the per-statement reload

**Cons:**
- Catalog sees another session's DDL only after the TTL (same as todo 011)

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#10)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] psql \d on 100 tables issues metadata reads once, not per query.
- [ ] Catalog reflects own-session DDL immediately.
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
- STILL REAL (performance). Every statement invalidates the catalog, so each catalog query reloads all table metadata.

### 2026-10-10 - Resolved

**By:** Claude Code

**Actions:**
- The catalog is kept until DDL in this process moves the generation, or for one second (DDL from other processes), instead of being reloaded for every statement. Measured: psql's first \d query 1.89 ms to 0.20 ms with five tables; bench/results/perf-20261010.
