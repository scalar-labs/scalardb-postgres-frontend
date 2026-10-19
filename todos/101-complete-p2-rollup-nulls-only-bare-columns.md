---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: A grouping item a set leaves out now reads NULL whatever it is (q1 + 0, lower(f1)), matched by expression at group level; aggregates read each row as it was before the set's NULLs, which also fixes sum(q1) reading NULL in ROLLUP(q1)'s total row (found while fixing). An item named by an output name or position whose expression is listed again (group by q1, rollup(y) with q1 as y) is refused with 0A000, since telling them apart needs PostgreSQL's sortgroupref. regress groupingsets: 2 wrong results fixed, none new; the 3 left are todo 102 (grouping() at another query level) and a COMMIT after an earlier error. aggregates, select_having, window: no new DIFF or PG_ERR. Still errors: (q1+0)*2 and grouping(q1+0) under GROUP BY q1+0.
