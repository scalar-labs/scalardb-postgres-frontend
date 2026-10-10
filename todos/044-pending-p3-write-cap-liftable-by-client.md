---
status: pending
priority: p3
issue_id: "044"
tags: [code-review, hardening]
dependencies: []
---

# Any client can lift the write cap with SET scalardb.max_rows_per_write = 0

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

The 10,000-row default protects the backend from UPDATE t SET x = 1 over a huge table, but the
session that would issue it can disable the guard first; the setting is mentioned only in
integration-design.md.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:205-208 validates digits
  only (0 = no limit); 295-298; QueryParser.java:215 default.

## Proposed Solutions

### Option 1: Ceiling

**Approach:** Treat the server default as a ceiling: sessions may lower it, raising it above the
configured value (or to 0) needs an operator property.

**Pros:**
- Few lines

**Cons:**
- Batch loaders need the property

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `README.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#9)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] SET scalardb.max_rows_per_write = 0 is refused unless allowed by configuration.
- [ ] README documents the setting.
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
- STILL REAL (hardening). SET scalardb.max_rows_per_write = 0 lifts the cap for any client; there is no operator ceiling.
