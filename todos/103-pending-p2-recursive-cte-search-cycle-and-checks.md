---
status: pending
priority: p2
issue_id: "103"
tags: [cte, wrong-result, validation, regress-suite]
dependencies: []
---

# Recursive CTEs: SEARCH and CYCLE ignored, recursion rules not checked

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

SEARCH and CYCLE add columns and stop cycles; the frontend parses and drops them, so cyclic queries run away. PostgreSQL also rejects ill-formed recursive terms that the frontend runs.

## Findings

- with.sql #55, #57, #74 (wrong), #58–63, #82–90 (not validated, or 54000 runaway)
- `with recursive t(x) as (select 1 union all select x+1 from t where x<3) search depth first by x set y select * from t`: PostgreSQL adds column y, frontend omits it
- with.sql #125–141, #129, #130, #133, #134: recursive reference in an outer join, a subquery, EXCEPT, twice, with OFFSET, an aggregate or FOR UPDATE, or with mismatched column types: PostgreSQL 42P19/0A000/42804, frontend runs

## Proposed Solutions

1. Refuse SEARCH/CYCLE with 0A000 until implemented; port checkWellFormedRecursion and the recursive-term type check.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
