---
status: pending
priority: p2
issue_id: "072"
tags: [join, wrong-result, regress-suite]
dependencies: []
---

# A chained `FULL JOIN ... USING` matches on one side's column

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

After `a FULL JOIN b USING (k)`, the column `k` is `COALESCE(a.k, b.k)`. A further `FULL JOIN c USING (k)` must match on that merged column. The frontend matches on `a.k`, which is NULL for rows only `b` has.

## Findings

- Where: join.sql #77, #85.
- `SELECT * FROM t1 FULL JOIN t2 USING (name) FULL JOIN t3 USING (name)`
  - PostgreSQL 16: 4 rows; cc has t2's and t3's values in one row
  - Frontend: 5 rows; cc appears twice, once with t2's values and once with t3's

## Proposed Solutions

1. Give the USING/NATURAL merged column its own value (COALESCE of both sides for a FULL join, the left one for LEFT, and so on) and use it for later joins.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
