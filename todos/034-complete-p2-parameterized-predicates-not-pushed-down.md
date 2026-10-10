---
status: complete
priority: p2
issue_id: "034"
tags: [code-review, performance, pushdown]
dependencies: []
---

# Parameterized predicates fall back to a full scan plus in-memory filter

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

WHERE id IN ($1, $2, $3) on a partition key is neither turned into keyed Gets nor pushed as a
condition (the literal form is); a JOIN whose build side has b.status = $1 scans b fully and filters
after the join (the literal is pushed); WHERE qty > 12.5 on an INT column scans; ORDER BY/LIMIT
pushdown is limited to single-table plans so a join with ORDER BY a.ck LIMIT 10 sorts the whole join
in memory. These are the shapes every ORM and driver produces in the extended protocol.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Optimizer.java:110-125 inListLookups needs
  literals; 301-307 lateConditions accepts column op $n only on the first or a lookup source;
  QueryParser.java:5395-5445 pushable() has no InExpression case; 5452-5459 decimal literal vs int;
  3983-3998 single-table pushdown only.

## Proposed Solutions

### Option 1: Widen lateConditions and add IN

**Approach:** Let lateConditions accept any table source whose read is issued at open time (hash-
join build sides are); add an InExpression case to pushable() producing an OR-of-ANDs or mark IN-
list-with-parameters plans EMBEDDED and resolve them at bind time like LIMIT $1.

**Pros:**
- Covers the ORM shapes

**Cons:**
- Optimizer rules to test with check_ops

**Effort:** Medium (1-2 days)

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Optimizer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `bench/check_ops.py`

**Database changes:** none (frontend only).

## Resources

- **Source:** performance reviewer (#4)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] IN ($1,$2,$3) on a key becomes keyed Gets in EXPLAIN.
- [ ] A join-side column = $1 is pushed into that table's read.
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
- STILL REAL (performance). IN ($1, $2), parameterized join-side conditions, decimal literals against INT columns and multi-source ORDER BY/LIMIT stay in memory. IN with parameters on a key scans the whole table.

### 2026-10-10 - Closed: main case not real

**By:** Claude Code

**Actions:**
- Measured: IN ($1, $2, $3) with parameters plans as one Get per value (EXPLAIN ANALYZE: 3 ScalarDB reads), since the values are embedded in an uncached plan; pgbench in_list in prepared mode runs at 0.3 ms on 20,000 customers, not a full scan. A parameterized condition on a join's looked-up side is filtered after a keyed Get, which costs little. The remaining shapes (multi-source ORDER BY/LIMIT pushdown, decimal literals against INT keys) are optimizations without a measured need.
