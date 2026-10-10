---
status: complete
priority: p2
issue_id: "013"
tags: [code-review, text, correctness]
dependencies: []
---

# substring, left, right and strpos are wrong for start < 1, negative counts and non-BMP text

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

substring('hello' from 0 for 3) returns 'hel' (PostgreSQL 'he'), substring('hello', -1, 3) returns
'hel' ('h'), substring('hello', 2, -1) returns '' (PostgreSQL 22011), left('abc', -1) throws
StringIndexOutOfBounds (PostgreSQL 'ab'). All four index by UTF-16 units, so strpos('😀a','a') is 3
(PostgreSQL 2) and left('😀ab', 1) splits a surrogate pair. length() is already code-point based.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:2097-2116
  substring/left/right/strpos clamp the start before adding the count and use String indices.

## Proposed Solutions

### Option 1: Compute the end first and use code points

**Approach:** end = start + count - 1 before clamping (error 22011 on negative count);
offsetByCodePoints/codePointCount for all four; negative left/right counts as PostgreSQL defines
them.

**Pros:**
- Exact PostgreSQL semantics

**Cons:**
- None

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/test/.../QueryParserTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #10)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The five examples above match PostgreSQL.
- [ ] A difftest case covers substring with from 0, negative start, negative count and an emoji.
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
