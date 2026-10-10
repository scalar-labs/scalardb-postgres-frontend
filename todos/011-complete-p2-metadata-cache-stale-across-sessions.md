---
status: complete
priority: p2
issue_id: "011"
tags: [code-review, catalog, caching, correctness]
dependencies: []
---

# The per-session table metadata cache is never refreshed after DDL from another connection

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

QueryParser.metadataCache is an unbounded per-connection HashMap cleared only after this session's
own DDL. Connection A caches t; connection B adds a column or drops and recreates t; A never sees
the new column ('Unknown column' on INSERT) and, for a recreated table, issues operations with the
old key layout. A long-lived pooled connection stays wrong indefinitely; ScalarDB's own cache expiry
does not help because this layer sits above it. The ponytail note at line 150 ('never invalidated')
is also stale.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:150-161 metadataCache and
  invalidateMetadata(); the comment says never invalidated.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:5292-5317 target() fills the
  cache and never expires entries.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:715-721 the only
  invalidation, after this session's DDL (planCache.clear() too).
- Catalog.load() (pg_catalog emulation) bypasses this cache and reloads per statement (todo 039), so
  psql sees the change while the planner does not.

## Proposed Solutions

### Option 1: Expire by time

**Approach:** Store (metadata, loadedAt) and reload after
scalar.db.metadata.cache_expiration_time_secs (already 1 s by default in Main); clear planCache when
a table's metadata changes identity.

**Pros:**
- Bounded staleness, same knob operators already know

**Cons:**
- One metadata read per table per second per session at worst

**Effort:** Small (half a day)

**Risk:** Low

---

### Option 2: Validate on error

**Approach:** On DB-CORE-10017 / unknown column / key mismatch from the engine, invalidate and
replan once before reporting the error.

**Pros:**
- No steady-state cost

**Cons:**
- Only catches cases that fail; a SELECT * that silently omits a new column is not caught

**Effort:** Small

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #8), own reading, pattern reviewer (#11 stale note)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Connection A sees a column added by connection B within the configured expiry.
- [ ] A table dropped and recreated by another connection is planned with the new key layout.
- [ ] The stale ponytail comment is corrected.
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

**Remaining:**
- Invalidation reaches every session of one process. Several frontend processes on one database still see another's DDL only after the metadata cache expires.
