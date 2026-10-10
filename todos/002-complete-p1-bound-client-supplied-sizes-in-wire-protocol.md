---
status: complete
priority: p1
issue_id: "002"
tags: [code-review, security, protocol, resource-exhaustion]
dependencies: []
---

# Client-supplied length fields drive unbounded allocations in the wire protocol

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Every message length, Bind parameter length, binary array element length and $n placeholder number
is taken from the wire and used as an allocation size with no upper bound. One unauthenticated
client can pin ~2 GB per connection with a 5-byte header, or make Describe build an 8 GB
ParameterDescription from SELECT $2000000000. An OutOfMemoryError escaping the accept loop (which
catches only IOException) ends the acceptor, and since every session thread is daemon the JVM exits.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:191 startup: new
  byte[length - 8] then readFully blocks for that many bytes; no socket timeout anywhere.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:257 serve: new
  byte[length - 4] for every message.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:440 bind: new
  byte[length] from an int inside the body, before remaining() is checked.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:1119 arrayText: the same
  for binary array elements.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:280 and 428-432: counts
  read with getShort() are signed, so 32768+ becomes a NegativeArraySizeException (harmless but
  should be a protocol error).
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1990-2017 parameterCount():
  Integer.parseInt of any digit run after $; PostgreSQL caps parameters at 65535.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:468-474 describe: builds
  n ParameterDescription entries and truncates the count to i16.
- The auth branch (commit 1a78003) caps the startup and password messages; the general message loop
  and Bind are still uncapped as of that commit.
- No protocol-robustness test exists (grep for length/malformed/truncated in PostgresServerTest
  finds none).
- Unconfirmed, from the security reviewer: JSQLParser's complex parsing runs without the timeout its
  own entry point uses; a statement length cap would bound it too.

## Proposed Solutions

### Option 1: Reject out-of-range lengths before allocating

**Approach:** In startup and serve: length < 4 (8 for startup) or > MAX_MESSAGE (64 MB,
configurable) is a protocol error (08P01) and the connection closes. In bind/arrayText: length >
msg.remaining() is an error. parameterCount throws for n > 65535. Read counts with & 0xFFFF.

**Pros:**
- A handful of ifs
- Mirrors PostgreSQL's own checks

**Cons:**
- Needs a chosen cap and a property for it

**Effort:** Small (half a day)

**Risk:** Low

---

### Option 2: Also add a startup read timeout and catch Throwable in the acceptor

**Approach:** socket.setSoTimeout during startup (10 s), reset afterwards; the accept loop catches
Throwable, logs, and continues.

**Pros:**
- Closes the slow-sender variant and the acceptor-death variant

**Cons:**
- Idle-timeout policy is a product decision

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/test/.../PostgresServerTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#1, #2, #14), performance reviewer (#12)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] A message header claiming 0x7FFFFFFF bytes gets an error and the connection closes without allocating.
- [ ] Parse + Describe of SELECT $2000000000 returns an error, not an allocation.
- [ ] A Bind with a parameter length larger than the body is a protocol error.
- [ ] Tests cover each rejected shape and a legitimately large (say 1 MB) statement still works.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Verified by reading only: new byte[length - 4] at PostgresServer.java:257 (and the startup, Bind and array-element sites) has no bound. Correction to the scenario: the allocation runs on the connection thread and an OutOfMemoryError there is caught at run() (line 161) and closes that connection; the damage is memory pinned by slow or idle senders within the heap limit and OOMs surfacing in other sessions, not an acceptor exit.
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
- Fixed in 9a79e97, 1a78003 on `main`; see the commit message for how it was verified
