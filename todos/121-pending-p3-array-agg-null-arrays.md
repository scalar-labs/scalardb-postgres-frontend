---
status: pending
priority: p3
issue_id: "121"
tags: [arrays, aggregates, wrong-result]
dependencies: []
---

# array_agg over only NULL arrays gives {NULL} instead of 22004

Found on 2026-10-20 while closing todo 091.

## Problem Statement

`array_agg` of arrays fails in PostgreSQL when an input is NULL (22004). The frontend knows the input is an
array only from a non-NULL value or from the argument as written (a cast to an array type, `ARRAY[...]`). When
every value is NULL and the argument is a plain column, it aggregates them as scalars.

## Findings

- `SELECT array_agg(x) FROM (VALUES (null::int[])) t(x)`: PostgreSQL 22004 cannot accumulate null arrays,
  frontend `{NULL}`
- `array_agg(null::int[])` (a cast written in the call) is refused correctly.

## Proposed Solutions

1. Give the evaluator the argument's inferred type (the planner's output OIDs for a derived table's column) and
   check it in `Evaluator.aggregateValues`.
2. Refuse array_agg over a column whose type is unknown and whose values are all NULL (0A000).

## Acceptance Criteria

- [ ] The statement above gives 22004, or an error.

## Work Log

- 2026-10-20: Filed while closing todo 091.
