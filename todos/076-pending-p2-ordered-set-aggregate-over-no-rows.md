---
status: pending
priority: p2
issue_id: "076"
tags: [aggregates, wrong-result, regress-suite]
dependencies: []
---

# An ordered-set aggregate over no rows returns no row

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

An aggregate without GROUP BY returns one row even over no input. For an ordered-set aggregate (`WITHIN GROUP`), the frontend returns none.

## Findings

- Where: aggregates.sql #353.
- `select percent_rank(0) within group (order by x) from generate_series(1,0) x`
  - PostgreSQL 16: 1 row: 0
  - Frontend: 0 rows

## Proposed Solutions

1. Return the one row for ordered-set aggregates, as for the others.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
