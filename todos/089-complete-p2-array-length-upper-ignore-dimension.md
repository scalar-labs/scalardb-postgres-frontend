---
status: complete
priority: p2
issue_id: "089"
tags: [arrays, wrong-result, regress-suite]
dependencies: []
---

# array_length and array_upper ignore the dimension

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

The second argument chooses the dimension; a dimension the array lacks gives NULL. The frontend always answers for the first.

## Findings

- arrays.sql #352, #354, #355
- `array_length(array[[1,2,3],[4,5,6]], 2)`: PostgreSQL 3, frontend 2; `array_length(array[1,2,3], 2)`: PostgreSQL NULL, frontend 3

## Proposed Solutions

1. Index the nested dimension; NULL for a dimension below 1 or above the array's.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. array_length, array_upper and array_lower now take the dimension argument (nested lists, 1-based); a dimension below 1 or beyond the array's is NULL, and an inner dimension's lower bound is 1. Live check against PostgreSQL 16 matches for 2-D arrays, out-of-range dimensions, an empty array and int2vector (pg_index.indkey, from 0). QueryParserTest covers it. Fixed with 090.
