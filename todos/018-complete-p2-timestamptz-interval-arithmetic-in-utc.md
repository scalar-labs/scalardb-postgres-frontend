---
status: complete
priority: p2
issue_id: "018"
tags: [code-review, numeric, encoding, correctness]
dependencies: []
---

# timestamptz +/- interval does its day and month arithmetic in UTC instead of the session zone

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

With SET TIME ZONE 'America/New_York', '2024-03-09 12:00:00-05'::timestamptz + interval '1 day' is
2024-03-10 12:00:00-04 in PostgreSQL (same wall-clock time across the DST change) but 13:00-04 here
(24 hours later). timestamptz subtraction through the zone-local asTimestamp is off by an hour
across DST too.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Interval.java:165-167 applies months and days
  to the Instant in UTC; used from Evaluator.java:1449-1457 and 1465-1477.

## Proposed Solutions

### Option 1: Apply calendar units in the session zone

**Approach:** Convert the Instant to a ZonedDateTime in Evaluator.zone(), add months and days there,
add the time part as a Duration, convert back.

**Pros:**
- Exact PostgreSQL semantics

**Cons:**
- None

**Effort:** Small (an hour)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Interval.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #17)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The DST example matches PostgreSQL.
- [ ] timestamptz - timestamptz across a DST change matches PostgreSQL.
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
