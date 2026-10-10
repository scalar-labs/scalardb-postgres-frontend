---
status: complete
priority: p2
issue_id: "030"
tags: [code-review, security, resource-exhaustion, embedding]
dependencies: []
---

# Connection lifecycle: no connection cap, no socket timeouts, unbounded per-session maps, no registry or drain

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

serve() spawns an untracked daemon thread per socket with no cap and no read timeout, so a client
that opens sockets and sends one byte each exhausts threads (slowloris).
statements/portals/parameterTypes grow without bound if a client never sends Close. close() only
closes the listening socket, so open sessions and their ScalarDB transactions run until the JVM
exits, while docs/integration-design.md requires stop-accepting, a drain period and a
max_connections cap for embedding. The port is bound in the constructor on all interfaces with no
bind-address option, and an Error escaping the accept loop ends the acceptor and, since all threads
are daemon, the JVM.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:60-66 bind in the
  constructor; 121-134 accept loop catches only IOException; 115-118 close(); 141-143 per-session
  maps; 145-149 buffers; no setSoTimeout anywhere.
- docs/integration-design.md:65-67 max_connections (53300) and the drain requirement.
- The auth branch (commits 2585a1b, 1a78003) already reworked the startup path and TLS stream
  switch; coordinate so the registry and caps land on top of it rather than on main.
- server/.../Main.java:42-47: port only, parseInt unchecked.

## Proposed Solutions

### Option 1: Registry, caps and timeouts

**Approach:** Keep a Set<Connection>; accept through a bounded executor or Semaphore sized by a
max_connections property (53300 when full); setSoTimeout during startup (10 s) and an idle timeout;
cap named statements/portals per session (1000); close() stops accepting and drain(Duration) waits
for !inTransaction then rolls back and closes; catch Throwable in the accept loop; optional bind
address (default loopback until auth ships in main).

**Pros:**
- What embedding needs; what the DoS findings need

**Cons:**
- Medium change in PostgresServer

**Effort:** Medium (1-2 days)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `server/src/main/java/com/scalar/db/frontend/postgres/server/Main.java`
- `docs/integration-design.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#7, #12), architecture reviewer (P1 #2), performance reviewer (#11)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] 10k idle sockets do not prevent a new client from connecting; the 10k+1st gets 53300 or the idle ones time out.
- [ ] close() rolls back open transactions after the drain period.
- [ ] An OutOfMemoryError in one connection does not stop the acceptor.
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
- Fixed in 4d622ab, 5c1ae48, 63fc0b5 on `main`; see the commit message for how it was verified

**Remaining:**
- 5c1ae48 made the startup timeout a total deadline and capped prepared bytes per session; 63fc0b5 documents the settings and limits.
