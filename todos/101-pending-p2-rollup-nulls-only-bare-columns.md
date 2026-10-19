---
status: pending
priority: p2
issue_id: "101"
tags: [aggregates, grouping-sets, wrong-result, regress-suite]
dependencies: []
---

# ROLLUP nulls only bare columns

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

In a grouping set that omits a grouping item, every output column that is that item is NULL. The frontend nulls only plain column references, matched by expression.

## Findings

- groupingsets.sql #39, #45
- `select q1+0, count(*) from int8_tbl group by rollup(q1+0)`: PostgreSQL total row `NULL|5`, frontend `123|5`
- `select q1, q1 as y, count(*) from int8_tbl group by q1, rollup(y)`: PostgreSQL `123|NULL|2`, frontend `123|123|2`

## Proposed Solutions

1. Track which output columns each grouping item feeds (PostgreSQL's sortgroupref), not expression equality.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
