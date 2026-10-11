---
status: complete
priority: p2
issue_id: "037"
tags: [code-review, performance, memory]
dependencies: []
---

# Three to four copies of each row between ScalarDB and the wire

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

rowOf builds a LinkedHashMap with qualifier.name concatenated per column per row, then a second
putIfAbsent pass with the same concatenation; Project builds a second map and copies the input when
keepInput; every join candidate, matching or not, gets a merged map before the ON clause runs; each
cell becomes a String, a byte[], a copy into Msg, a copy out of it and a copy into the socket
buffer.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1552-1561 rowOf;
  Operators.java:330-345 Project; 1265-1275 join candidates; PostgresServer.java:360-382 run,
  717-722 send (toByteArray), 726-755 Msg.

## Proposed Solutions

### Option 1: Precompute keys, write through, reuse Msg

**Approach:** Precompute the qualified key list once per Scan/Lookup source; in send() use
m.bytes.writeTo(out) and reuse one presized Msg per statement with reset(); evaluate the ON clause
for hash-join candidates against a two-map view before merging.

**Pros:**
- Per-row allocations drop by half or more

**Cons:**
- Several small edits across three files

**Effort:** Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#8)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Allocation profile per row shows one map and one byte copy per cell.
- [ ] bench TPC-C numbers hold or improve.
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
- STILL REAL (performance). Qualified names are concatenated per cell, rows are copied per join candidate, and send() copies each message twice.

### 2026-10-11 - Closed without a code change

**By:** Claude Code

**Reason:**
- The latency benchmark (bench/README.md, "Rerun, 2026-10-11") shows the frontend's CPU work
  disappearing behind network round trips: at a 0.7 ms round trip to PostgreSQL every script's
  cost is its ScalarDB round trips. This saving would not show in any OLTP measurement there.
- Reopen if a profile of a CPU-bound workload (large in-memory sorts or wide result sets on a
  local database) points here.
