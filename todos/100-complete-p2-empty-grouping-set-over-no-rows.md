---
status: complete
priority: p2
issue_id: "100"
tags: [aggregates, grouping-sets, wrong-result, regress-suite]
dependencies: []
---

# An empty grouping set over no rows returns no row

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

Each `()` grouping set yields one row even over empty input, as an aggregate without GROUP BY does. The frontend yields none.

## Findings

- groupingsets.sql #19, #30–33, #63, #70, #71, #100, #101, #103, #113
- `select f1, count(*) from int4_tbl where false group by grouping sets ((f1),())`: PostgreSQL one row `|0`, frontend none

## Proposed Solutions

1. Emit the empty grouping set's row for empty input (compare todo 076).
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. Operators.Aggregate gives the empty grouping set () its row over no input, as an aggregate without GROUP BY does: an EmptyGroup that knows what the set left out, so GROUPING() is 1 for those expressions and the grouping columns read as NULL (Evaluator.columnValue). Non-empty sets over no input still yield nothing. Seven statements (GROUPING SETS with (), ((),()), ROLLUP, CUBE, HAVING) match PostgreSQL 16 live; groupingsets.sql's DIFFs went from 16 to 4 (the rest are todos 101 and 102 and an aborted COMMIT). QueryParserTest covers it.
