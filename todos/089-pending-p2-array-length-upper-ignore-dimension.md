---
status: pending
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

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
