---
status: complete
priority: p1
issue_id: "126"
tags: [types, boolean, wrong-result, sqlancer]
dependencies: []
---

# Non-boolean values were given truth values of their own

Found on 2026-10-20 by SQLancer (TLP WHERE, NoREC): four reports, one cause.

## Problem Statement

A number used as a condition, or cast to boolean, got a truth value instead of PostgreSQL's error, and not
consistently: `0.5::real::boolean` was false (int value 0), `true AND 0.5::real` was true (anything not
false), `WHERE 0.5::real` kept no row. So `WHERE x`, `WHERE NOT x` and `WHERE x IS NULL` over a real column
did not partition its rows (TLP: 3 rows, partitions 1), and `WHERE c0` disagreed with `sum(c0::int)` (NoREC).

## Acceptance Criteria

- [x] A non-boolean value in WHERE, HAVING, FILTER, AND, OR, NOT, CASE WHEN, IS TRUE fails with 42804; a quoted
      or text value is read as boolean input ('t'), as an unknown literal is.
- [x] real, double precision and numeric to boolean fails with 42846; integer to boolean stays.

## Work Log

- 2026-10-20: Fixed in Evaluator (one check where conditions are read); checked live against PostgreSQL 18.
