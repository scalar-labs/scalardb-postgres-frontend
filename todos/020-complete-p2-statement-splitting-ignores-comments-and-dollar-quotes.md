---
status: complete
priority: p2
issue_id: "020"
tags: [code-review, protocol, correctness]
dependencies: []
---

# split() and parameterCount() do not understand comments, dollar quotes or E'' escapes

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Both scanners toggle quote state on every apostrophe and know nothing about -- and /* */ comments,
$$ dollar quoting or E'\'' escapes. 'SELECT 1; -- don''t' followed by a newline and 'SELECT 2;' is
not split; 'SELECT 1 -- a; b' is split inside the comment; a comment-only Query gives 'Invalid SQL'
instead of EmptyQueryResponse; Describe(S) reports 0 parameters when $1 follows a comment containing
an apostrophe.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:767-792 split();
  engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1990-2018 parameterCount().
- escapeStringQuotes (QueryParser.java:2271-2333) handles comments but is a third scanner.

## Proposed Solutions

### Option 1: One shared tokenizer

**Approach:** A small lexer that yields statement boundaries and placeholder numbers while tracking
'...', "...", E'...', $tag$...$tag$, -- and /* */; split, parameterCount and escapeStringQuotes call
it.

**Pros:**
- One place for quoting rules

**Cons:**
- A ~60-line class

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #19), own reading of split()
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The four examples above behave as in PostgreSQL.
- [ ] A comment-only Query returns EmptyQueryResponse.
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
