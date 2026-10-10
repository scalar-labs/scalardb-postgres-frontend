---
status: pending
priority: p3
issue_id: "047"
tags: [code-review, duplication, protocol]
dependencies: []
---

# Session defaults are defined in three places and ParameterStatus is never re-sent after SET

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

PostgresServer.startup hard-codes nine ParameterStatus values, QueryProcessor's constructor defines
overlapping defaults, and Evaluator.version() hard-codes 'PostgreSQL 16.0' a third time; SET TIME
ZONE or application_name update settings but no ParameterStatus follows (PostgreSQL reports
GUC_REPORT changes). The auth branch adds is_superuser/session_authorization to the same list.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:205-218;
  QueryProcessor.java:100-117; Evaluator.java:2028.

## Proposed Solutions

### Option 1: Build from the processor

**Approach:** startup() sends processor.currentSetting(name) for the reportable names plus
session_authorization; run() sends a ParameterStatus after SET of a reportable key; version() reads
the same default.

**Pros:**
- Removes ~10 lines and the drift

**Cons:**
- None

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** architecture reviewer (#10), simplicity reviewer (#1)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] One definition of server_version.
- [ ] SET TIME ZONE produces a ParameterStatus.
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
- STILL REAL. Probed with pgjdbc: the startup ParameterStatus says TimeZone=UTC although the client sent Asia/Tokyo, nothing is reported after SET TIME ZONE, and application_name is never reported.
