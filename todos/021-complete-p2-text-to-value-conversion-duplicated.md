---
status: complete
priority: p2
issue_id: "021"
tags: [code-review, duplication, error-handling, correctness]
dependencies: []
---

# Text-to-value conversion exists twice with different grammars, messages and SQLSTATEs

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

The pushed-down and write path (columnFromText/parseColumn) accepts what PostgreSQL and its drivers
send (a bare date, +09, +0900, Z) and reports 22P02/22003/22007/22008 with PostgreSQL's wording. The
in-memory path (Evaluator.coerce, temporal, cast) re-implements it with bare LocalDateTime.parse and
new BigDecimal, so the moment a predicate is not pushed (nullable side of an outer join, OR across
tables, HAVING, CASE) ts = '2024-01-01' fails with 'Cannot convert ... to LocalDateTime' under 42601
while the same predicate pushed down works. cast() collapses every failure into 22P02 and skips the
range checks, so CAST('99999999999' AS INT) is 22P02 here and 22003 on the column path.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:1009-1037 coerce (used by
  compare at 962-966); 1104-1117 temporal; 1840-1903 cast with catch(RuntimeException) -> 'Cannot
  cast'.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:6059-6109 columnFromText;
  6137-6182 parseColumn; 6410-6444 localDateTime/instant/offsetDateTime; 6188-6201 inRange.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:599-601 'Cannot cast' ->
  22P02; 'Cannot convert' falls to 42601 at 701.

## Proposed Solutions

### Option 1: One converter keyed by DataType

**Approach:** Expose parseColumn's converter as a value-returning helper with its message/code
mapping; coerce, temporal and cast call it; delete the LocalDate/LocalTime/LocalDateTime/Instant
branches of coerce.

**Pros:**
- Same grammar and codes everywhere

**Cons:**
- cast() for numeric(p,s) keeps its own scale handling

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** pattern reviewer (P1 #2, P2 #5)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] A timestamp comparison on the nullable side of an outer join accepts the same literals as a pushed one.
- [ ] CAST('99999999999' AS INT) reports 22003.
- [ ] TIMESTAMP '2024-01-01' parses.
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
