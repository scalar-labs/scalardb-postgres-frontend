---
status: complete
priority: p2
issue_id: "029"
tags: [code-review, security, resource-exhaustion]
dependencies: []
---

# SET_TRANSACTION still matches raw SQL with the cubic (.+?)\s*;?\s*$ tail removed elsewhere

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Commit 9ece34a moved SET/RESET/SHOW/SAVEPOINT onto trimStatement() because this tail took 45 s with
4,000 spaces. SET_TRANSACTION was missed: it runs on the untrimmed statement with the same tail.
'SET TRANSACTION x' + 20,000 spaces + 'y' pins the connection thread for minutes.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:82-84 the pattern; 556
  SET_TRANSACTION.matcher(sql) on raw text.
- Lower impact: QueryParser.java:2664 (\w+)\s*=\s*'...' with find() on a long WITH (...) option
  token is O(n^2).

## Proposed Solutions

### Option 1: Match the trimmed statement with a greedy tail

**Approach:** SET_TRANSACTION.matcher(QueryParser.trimStatement(sql)) and TRANSACTION\s+(.+)$.

**Pros:**
- Two tokens

**Cons:**
- None

**Effort:** Small (minutes)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#5)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The 20,000-space statement is answered within milliseconds.
- [ ] A regression test with a long whitespace run.
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
- Fixed in 59d4176 on `main`; see the commit message for how it was verified
