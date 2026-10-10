---
status: complete
priority: p1
issue_id: "003"
tags: [code-review, transaction, correctness]
dependencies: []
---

# ROLLBACK TO SAVEPOINT reports success but keeps writes after a nested or released savepoint

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Savepoint accounting is a single counter reset by every SAVEPOINT and ignored by RELEASE, and
ROLLBACK TO ignores the savepoint name. BEGIN; SAVEPOINT a; INSERT; SAVEPOINT b; ROLLBACK TO a;
COMMIT commits the insert while answering ROLLBACK. LIMITATIONS.md promises an error after a write;
this path returns success. Separately, a failed write still counts as a write, so the ORM retry
pattern (SAVEPOINT; failing INSERT; ROLLBACK TO) is refused although nothing was written.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:535-543 SAVEPOINT pushes
  a name and resets writesSinceSavepoint to 0.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:544-553 RELEASE pops but
  does not restore the count of the enclosing savepoint.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:517-532 ROLLBACK TO
  checks only writesSinceSavepoint and never looks at the name; an unknown name succeeds
  (PostgreSQL: 3B001).
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:585-587 the counter is
  incremented before the statement runs, so a failed INSERT counts.
- The comment at 519-520 says a failed statement wrote nothing; the code contradicts it.

## Proposed Solutions

### Option 1: Per-savepoint write counters

**Approach:** Push (name, writesAtSavepoint) on the stack; RELEASE pops; ROLLBACK TO name pops to
that entry and compares the running write count with the entry's; unknown name is 3B001; decrement
the counter in the catch block of a failed write.

**Pros:**
- Honours the documented rule exactly
- Small state change

**Cons:**
- None of note

**Effort:** Small (half a day)

**Risk:** Low

---

### Option 2: Refuse nested savepoints after a write

**Approach:** Error on SAVEPOINT when writesSinceSavepoint > 0 and keep a single counter.

**Pros:**
- Even smaller

**Cons:**
- Breaks ORMs that nest savepoints routinely
- Still ignores the name

**Effort:** Small

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/test/.../QueryProcessorTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P1 #2, P2 #13)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] SAVEPOINT a; INSERT; SAVEPOINT b; ROLLBACK TO a raises the documented error instead of succeeding.
- [ ] SAVEPOINT a; INSERT; SAVEPOINT b; RELEASE b; ROLLBACK TO a raises the error.
- [ ] ROLLBACK TO nosuch raises 3B001.
- [ ] SAVEPOINT s; failing INSERT; ROLLBACK TO s succeeds and clears the aborted state.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Reproduced with mocks: SAVEPOINT a; INSERT; SAVEPOINT b; ROLLBACK TO a succeeded silently; the RELEASE variant and ROLLBACK TO nosuch succeeded too; after a failing INSERT, ROLLBACK TO s was refused with 'Cannot roll back to a savepoint after writes'.
- Reproduction: `scratchpad/review-main/engine/src/test/java/com/scalar/db/frontend/postgres/P1VerificationTest.java (session scratchpad; mock-based JUnit, runnable with ./gradlew :engine:test --tests '*P1VerificationTest' against an export of 6c71631)`

**Learnings:**
- Confirmed real.

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
- Fixed in 166a00d on `main`; see the commit message for how it was verified
