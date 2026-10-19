---
status: pending
priority: p3
issue_id: "102"
tags: [aggregates, grouping-sets, regress-suite]
dependencies: []
---

# GROUPING() in a subquery is not bound to the outer query

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

GROUPING()'s arguments belong to the query level of their columns. The frontend evaluates it inside the subquery (compare todo 075).

## Findings

- groupingsets.sql #56, #67
- `select (select grouping(e,f)) from (values(6,7)) v3(e,f) group by rollup(e,f)`: PostgreSQL 3, 0, 1; frontend 0, 0, 0
- `select (select grouping(f1) from int2_tbl) from int4_tbl group by f1`: PostgreSQL 42803

## Proposed Solutions

1. Resolve GROUPING() at its arguments' level, with todo 075's outer-level aggregates.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
