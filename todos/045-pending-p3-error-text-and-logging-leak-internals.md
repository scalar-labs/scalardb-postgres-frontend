---
status: pending
priority: p3
issue_id: "045"
tags: [code-review, info-leak, hardening]
dependencies: []
---

# Error messages pass internal exception text to the client; failed statements are logged at INFO with full text

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

e.toString() for a null-message NullPointerException exposes internal class and method names;
backing-database messages pass through by design. A statement with a typo is logged at INFO with its
string literals (secrets included); every client-triggered Error logs a 1024-frame stack trace per
request; a malformed startup packet logs 'Connection failed' with a trace. The auth branch's
uncommitted-at-review authentication catch also returned e.getMessage() to the client (flagged by
the automated security review of the working tree).

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:531 message =
  e.toString() fallback; 533-538 Details; 540-542 logging; 161-162 startup failures;
  QueryParser.java:2250 'Invalid SQL: ' + sql.

## Proposed Solutions

### Option 1: Generic text for internal errors, DEBUG for statement text

**Approach:** Send 'internal error' with XX000 and log the detail for RuntimeExceptions with no
message; log parse failures at DEBUG or the first 200 chars; Error trace at DEBUG, toString at WARN;
treat NegativeArraySize/BufferUnderflow in startup as protocol errors at DEBUG; keep authentication
failure messages generic.

**Pros:**
- One-line changes

**Cons:**
- Less diagnostic detail at INFO

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#10, #11), automated security review of the auth working tree
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] An internal NPE reaches the client as 'internal error'.
- [ ] A failed INSERT's literals do not appear at INFO.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

