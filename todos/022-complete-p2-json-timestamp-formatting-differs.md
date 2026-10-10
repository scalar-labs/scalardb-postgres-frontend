---
status: complete
priority: p2
issue_id: "022"
tags: [code-review, encoding, correctness]
dependencies: []
---

# to_json/to_jsonb format timestamps their own way: seconds dropped and UTC regardless of session zone

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Json.toNode uses LocalDateTime.toString(), which omits :SS when seconds are zero, so
to_json(timestamp '2024-01-01 00:00:00') is "2024-01-01T00:00" (PostgreSQL "2024-01-01T00:00:00");
an Instant is always rendered in UTC with +00:00 although every other textualisation honours SET
TIME ZONE. A silent result difference. Reached from to_json, json_build_object/array and json_agg.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Json.java:133-143 toNode; the canonical
  formatters at Evaluator.java:1210-1216, 1237-1247 (timestampText), 198-203 (offsetText) are
  bypassed; lines 140-143 are a redundant if.

## Proposed Solutions

### Option 1: Reuse the canonical formatters

**Approach:** LocalDateTime -> timestampText(t).replace(' ', 'T'); Instant -> the same on
atZone(Evaluator.zone()) plus offsetText with :MM always present; drop lines 140-143.

**Pros:**
- Three lines

**Cons:**
- None

**Effort:** Small (an hour)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Json.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** pattern reviewer (P1 #3)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] to_json of a midnight timestamp includes :00 seconds.
- [ ] to_json of a timestamptz follows SET TIME ZONE like text output does.
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
