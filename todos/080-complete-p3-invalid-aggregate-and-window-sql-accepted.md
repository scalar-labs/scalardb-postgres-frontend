---
status: complete
priority: p3
issue_id: "080"
tags: [aggregates, window, validation, regress-suite]
dependencies: []
---

# Aggregate and window SQL that PostgreSQL rejects is accepted

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`.

## Problem Statement

The frontend answers statements that PostgreSQL rejects as invalid, so a mistake in an application's SQL goes
unnoticed until it meets a real PostgreSQL.

## Findings

| Statement | PostgreSQL 16 | Frontend |
|---|---|---|
| `select max(unique1) filter (where sum(ten) > 0) from tenk1` | 42803 aggregate functions are not allowed in FILTER | 9999 |
| `select (select max(unique1) filter (where sum(ten) > 0) from int8_tbl) from tenk1` | 42803 aggregate function calls cannot be nested | 10,000 rows |
| `select string_agg(distinct f1, ',' order by f1::text) from varchar_tbl` | 42P10 in an aggregate with DISTINCT, ORDER BY expressions must appear in argument list | a,ab,abcd |
| `SELECT count(*) OVER w FROM tenk1 WINDOW w AS (ORDER BY unique1), w AS (ORDER BY unique1)` | 42P20 window "w" is already defined | 10,000 rows |
| `select count(*) from int8_tbl group by q1 order by q2` (select_implicit.sql #13, #31) | 42803 column must appear in the GROUP BY clause | 2, 3 |
| `SELECT round(5.5e131071, -131072)` | 22003 value overflows numeric format | a number |

(aggregates.sql #272, #273, #319–322; window.sql #232; numeric.sql #643)

## Proposed Solutions

1. Add these checks to the parser, with PostgreSQL's SQLSTATEs.

## Acceptance Criteria

- [x] Each statement above fails with PostgreSQL's SQLSTATE.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
- 2026-10-20: Refused as PostgreSQL does: an aggregate in FILTER (42803), DISTINCT aggregate ORDER BY expressions not among its arguments (42P10, also for a no-op cast such as s::text on text, which PostgreSQL accepts), a WINDOW name defined twice (42P20), an ungrouped column in ORDER BY of a grouped query (42803), and round() past numeric's range (22003). The nested-aggregate case is refused by todo 075 with 0A000. Regress (aggregates, select_implicit, window, groupingsets vs PostgreSQL 18): those statements are now SAME, nothing newly refused.
