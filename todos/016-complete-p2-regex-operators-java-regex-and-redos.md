---
status: complete
priority: p2
issue_id: "016"
tags: [code-review, text, security, resource-exhaustion]
dependencies: ["031"]
---

# ~, ~*, SIMILAR TO pass patterns to java.util.regex: POSIX classes differ and matching is unbounded

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

'abc' ~ '^[[:alpha:]]+$' is false here (Java reads [[:alpha:]] as a class of ':alph'), \y, \m and
other POSIX ARE syntax raise PatternSyntaxException. Java's engine also backtracks exponentially, so
one client can pin a core indefinitely with '^(a+)+$' on a long input, and nothing can stop it
because CancelRequest is ignored (todo 031).

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:445-464 ~ family compiles the
  pattern as given; 460 matcher.find() with no deadline.
- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:847-870 SIMILAR TO keeps
  |*+?{}() verbatim; 2501-2516 LIKE builds .* chains.
- PostgreSQL's engine is bounded; Java's is backtracking.

## Proposed Solutions

### Option 1: Translate POSIX classes and bound the match

**Approach:** Translate [[:alpha:]] etc. and \y/\m/\M to Java equivalents (raise 0A000 for the
rest); wrap the input in a CharSequence whose charAt checks a per-statement deadline or cancel flag;
cap pattern and input length.

**Pros:**
- Fixes both correctness and the DoS

**Cons:**
- A translation table to maintain

**Effort:** Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #14), security reviewer (#4)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] 'abc' ~ '^[[:alpha:]]+$' is true.
- [ ] 'aaaa...!' ~ '^(a+)+$' fails fast with an error or is cancelled within a second.
- [ ] Unsupported constructs raise 0A000 instead of XX000.
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
- Fixed in 4d622ab, 5c1ae48 on `main`; see the commit message for how it was verified

**Remaining:**
- 5c1ae48 made the five-second regex budget cover a whole statement rather than each match.
