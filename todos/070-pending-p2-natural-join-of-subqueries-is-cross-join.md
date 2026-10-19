---
status: pending
priority: p2
issue_id: "070"
tags: [join, wrong-result, regress-suite]
dependencies: []
---

# `NATURAL JOIN` between subqueries runs as a cross join

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

`NATURAL JOIN` joins on the columns both sides have. Between derived tables (subqueries), the frontend finds no common columns, so it returns the cross product, with the common columns twice.

## Findings

- Where: join.sql #81, #84.
- `SELECT * FROM (SELECT name, n AS s2_n FROM t2) s2 NATURAL INNER JOIN (SELECT name, n AS s3_n FROM t3) s3`
  - PostgreSQL 16: 2 rows: name, s2_n, s3_n
  - Frontend: 9 rows: name, s2_n, name, s3_n

## Proposed Solutions

1. Resolve a derived table's output columns for NATURAL JOIN as for a base table.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
