---
status: complete
priority: p2
issue_id: "149"
tags: [planner, constant-folding, regression-tests]
dependencies: ["148"]
---

# Errors PostgreSQL raises while planning, over no rows

PostgreSQL's eval_const_expressions evaluates every constant subexpression of immutable operators
and functions while planning, so `SELECT CASE WHEN i > 100 THEN 1/0 ELSE 0 END FROM t` fails there
even when no row reaches the arm; the frontend answered (case #20 of the regression run).

## Acceptance Criteria

- [x] While binding a SELECT (select list, WHERE, GROUP BY, HAVING, ORDER BY, ON), a constant
      subexpression is evaluated once and a data exception (class 22) or overflow fails the
      statement, as PostgreSQL's planner does. Its simplifier's stops are kept: a CASE arm after a
      WHEN folding to true or under one folding to false, COALESCE after a non-null constant, AND
      after false and OR after true; a quoted literal's cast fails anywhere (PostgreSQL reads it while
      parsing). Nothing is rewritten.
- [x] Not treated as constant: columns, parameters, subqueries, stable and volatile functions,
      reg* casts (the catalog); other failures (0A000, ...) do not fail the statement.
- [x] 44 statements match PostgreSQL 18 but LIMIT 1/0 (an error on both, other codes); the full
      regression run (full5 against full4) changes no statement for the worse.

## Not done

- `time + time` over an empty table: PostgreSQL finds the operator ambiguous (42725) through the
  implicit time-to-interval cast; the frontend answers no rows (with a row it fails). Left
  documented in LIMITATIONS.md at the user's request (2026-10-21).

## Work Log

- 2026-10-21: QueryParser.foldConstants/fold/evaluated; QueryParserTest.
