---
status: pending
priority: p2
issue_id: "075"
tags: [aggregates, wrong-result, regress-suite]
dependencies: []
---

# An aggregate over outer columns in a subquery is computed per row

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

In a subquery, an aggregate whose arguments (and FILTER) reference only outer-query columns belongs to the outer query: it aggregates over the outer rows. The frontend evaluates it inside the subquery, once per outer row. PostgreSQL also rejects such an aggregate in the FROM clause of its own level; the frontend accepts it there (#597).

## Findings

- Where: aggregates.sql #109, #312, #314, #318; join.sql #597.
- `select (select max((select i.unique2 from tenk1 i where i.unique1 = o.unique1))) from tenk1 o`
  - PostgreSQL 16: 1 row: 9999
  - Frontend: 10,000 rows

## Proposed Solutions

1. Determine each aggregate's level from the variables it references, as PostgreSQL does (`check_agg_arguments`). Evaluate an outer-level aggregate with the outer query's aggregation, or refuse it with 0A000 until that exists.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
