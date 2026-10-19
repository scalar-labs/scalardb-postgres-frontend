---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
- 2026-10-19: Done by refusing the wrong cases. After a RIGHT or FULL JOIN USING, the merged column is COALESCE of both sides, but the frontend bound it to the left copy (NULL where only the right side has the row): a later USING join matched on that copy, and an unqualified reference read it (SELECT name ... returned NULLs, WHERE name = 'cc' found nothing). Both are now refused with 0A000 (LogicalPlan.Source.mergedOuter); SELECT *, qualified columns and COALESCE work, and INNER/LEFT chains, where the left copy is the merged value, work as before. Live: five shapes match PostgreSQL 16 or refuse. LIMITATIONS lists it; QueryParserTest covers it. Carrying the merged value through the join operators would lift the refusal.
