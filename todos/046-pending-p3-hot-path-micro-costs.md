---
status: pending
priority: p3
issue_id: "046"
tags: [code-review, performance]
dependencies: []
---

# Hot-path micro-costs: String.format in temporal and bytea text, regex compiled per CAST evaluation

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Every timestamp cell pays two String.format calls plus a regex replace; every bytea byte a
String.format; CAST to time*/char* compiles a Pattern per row; firstWord runs replaceAll + split two
to four times per statement; the window key is deparsed per row although Spec caches it.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:1207, 1238-1250, 1253-1259,
  1799, 1861, 1768, 1780, 1811, 380; QueryParser.java:1850-1853; QueryProcessor.java:907, 397;
  Windows.java:67-72.

## Proposed Solutions

### Option 1: Static patterns, hand-rolled padding, HexFormat

**Approach:** Static final Patterns; zero-padded appends into a StringBuilder; java.util.HexFormat
(JDK 17) for hex; resolve cast type modifiers at plan time; take the window key from Spec.

**Pros:**
- Mechanical

**Cons:**
- None

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#13, #14), simplicity reviewer (#7)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] No Pattern.compile in per-row paths (profiler).
- [ ] Timestamp text output unchanged byte for byte.
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
- STILL REAL (performance). String.format and per-call Pattern compiles on per-cell paths.
