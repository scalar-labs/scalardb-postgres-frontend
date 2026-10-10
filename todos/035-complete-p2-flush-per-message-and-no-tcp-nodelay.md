---
status: complete
priority: p2
issue_id: "035"
tags: [code-review, performance, io]
dependencies: []
---

# One flush per extended-protocol message and no TCP_NODELAY

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

serve() flushes at the end of every loop iteration, so a pipelined Parse/Bind/Describe/Execute/Sync
produces five write syscalls and up to five small segments instead of one. With Nagle on and the
client's delayed ACK, later segments stall: the classic Nagle/delayed-ACK interaction PostgreSQL
avoids by setting TCP_NODELAY. Loopback benchmarks hide it.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:319 unconditional flush;
  265-268 Flush; 712-715 ReadyForQuery flush; 124-127 socket accepted without setTcpNoDelay; 147-148
  default 8 KB buffers.

## Proposed Solutions

### Option 1: Flush on Sync/Flush/ReadyForQuery/error only, NODELAY on

**Approach:** socket.setTcpNoDelay(true) in serve(); remove the flush at 319; keep the every-64-rows
flush.

**Pros:**
- Two lines

**Cons:**
- Measure on a non-loopback path

**Effort:** Small (an hour)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#5)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] A pipelined prepared-statement round produces one write per Sync (strace/tcpdump).
- [ ] bench numbers on a non-loopback path improve or hold.
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
- STILL REAL (performance). One flush per message and no TCP_NODELAY; a two-line fix.

### 2026-10-10 - Resolved

**By:** Claude Code

**Actions:**
- No flush per message: ReadyForQuery and Flush send what is buffered, so a Bind/Execute/Sync is answered in one write; TCP_NODELAY is set on every connection. Measured on loopback: point_get 0.158 ms to 0.124 ms; bench/results/perf-20261010. The gain under a real network round trip was not measured.
