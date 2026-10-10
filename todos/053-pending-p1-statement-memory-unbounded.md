---
status: pending
priority: p1
issue_id: "053"
tags: [security, resource, availability]
dependencies: ["031"]
---

# One statement can exhaust the server's memory, and nothing stops it

Found while triaging todo 031 on 2026-10-10, against `main@85d3c40`.

## Problem Statement

`SELECT count(*) FROM emp a, emp b, emp c, dept d` (80 x 80 x 80 x 12, about 6.1 million rows of
the difftest data) answers in 0.2 s on PostgreSQL. On the frontend it ran 498 s and failed with
`OutOfMemoryError: Java heap space` on an 8 GB heap; the error also hit the JDBC pool's
housekeeper thread, so every session of the process was at risk. pgjdbc's `Statement.cancel()`
had no effect (todo 031). Any client can send such a statement, authenticated or not.

## Findings

- The rows of a join or an aggregate input are held in memory rather than streamed into the
  aggregate, so memory grows with the join's output, not with the result.
- There is no per-statement row or memory budget for reads (the write cap of 10,000 rows applies
  to writes only), and `statement_timeout` is accepted but not enforced (LIMITATIONS.md).
- The frontend process survived this time and answered afterwards, but an OOM in a shared thread
  can leave it broken.

## Proposed Solutions

1. Stream a join's output into aggregates instead of collecting it (fixes this query shape).
2. A per-statement budget on rows held in memory, failing with 53200 (out_of_memory) or 54000.
3. Enforce `statement_timeout` and honor CancelRequest (todo 031), both checked per row.

## Acceptance Criteria

- [ ] The query above either answers or fails with a SQLSTATE in bounded time and memory.
- [ ] Other sessions keep working while it runs.
- [ ] `Statement.cancel()` stops it.

## Work Log

### 2026-10-10 - Discovery

**By:** Claude Code

**Actions:**
- Probed with pgjdbc against the frontend on port 15444 (small pool, default 8 GB max heap) and native PostgreSQL on 15440.
