---
status: complete
priority: p2
issue_id: "015"
tags: [code-review, catalog, consistency]
dependencies: []
---

# CREATE INDEX discards the index name, so DROP INDEX IF EXISTS <name> is a silent no-op

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

ScalarDB has no index names; the frontend names every index <table>_<column>_idx in the catalog.
CREATE INDEX idx_users_email ON users(email) succeeds, DROP INDEX IF EXISTS idx_users_email answers
DROP INDEX and does nothing, and the next CREATE INDEX on the column fails. Without IF EXISTS it
errors 'Index not found'. No NOTICE says the name was not kept.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2814-2839 createIndex()
  never stores the given name.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2875-2899 dropIndex()
  matches only the generated form.

## Proposed Solutions

### Option 1: Keep a per-namespace name map or resolve by column

**Approach:** Refuse a name that is not the generated form with 0A000 plus a NOTICE, or accept it
with a NOTICE 'index name not kept; use <table>_<column>_idx'. DROP INDEX of an unknown name without
IF EXISTS stays an error; with IF EXISTS it sends a NOTICE.

**Pros:**
- Honours the error-or-NOTICE rule

**Cons:**
- Names still cannot be persisted without a side table

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `LIMITATIONS.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #12), architecture reviewer (#7)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] CREATE INDEX with a custom name sends a NOTICE naming the effective index.
- [ ] DROP INDEX IF EXISTS of an unknown name sends a NOTICE instead of silence.
- [ ] LIMITATIONS.md lists index naming.
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

**Remaining:**
- ScalarDB keeps no index names: the frontend remembers them only until it restarts.
