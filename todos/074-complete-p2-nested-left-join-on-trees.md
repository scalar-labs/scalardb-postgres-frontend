---
status: complete
priority: p2
issue_id: "074"
tags: [join, wrong-result, regress-suite]
dependencies: []
---

# Nested `LEFT JOIN ... ON` trees return duplicated rows

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

`a LEFT JOIN b LEFT JOIN c ... ON ... ON ...` nests the joins: each `ON` belongs to the innermost unmatched `JOIN`. The frontend attaches them wrongly and returns many more rows.

## Findings

- Where: join.sql #388, #390, #392.
- `select t1.* from text_tbl t1 left join (select *, '***'::text as d1 from int8_tbl i8b1) b1 left join int8_tbl i8 left join (select *, null::int as d2 from int8_tbl i8b2) b2 on (i8.q1 = b2.q1) on (b2.d2 = b1.q2) on (t1.f1 = b1.d1) left join int4_tbl i4 on (i8.q2 = i4.f1)`
  - PostgreSQL 16: 2 rows
  - Frontend: 50 rows

## Proposed Solutions

1. Build the join tree as the ON clauses nest it (JSqlParser's join list needs reassociating), or refuse a nested ON.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
- 2026-10-19: Refused first: a join carrying several ON clauses (JSqlParser hangs every nested ON on the last join) fails with 0A000 when it or the joins it nests is an outer join; nested inner joins give the same rows wherever their ON applies and run. Found alongside: SELECT * over JOIN ... USING gave the columns in table order; it now gives PostgreSQL's order (USING columns first in USING order, NATURAL's in the left side's, then each side's rest; a comma starts another FROM item), and a USING column found twice on the left fails with 42702 as in PostgreSQL. regress join: the 3 nested-ON wrong results and the USING column-order one fixed; the DIFFs left are cascades (an inherited table, a DELETE ... USING join the frontend refuses); select, union, subselect, with: none new. Building the nested tree (with parenthesized joins in FROM) remains to implement.
