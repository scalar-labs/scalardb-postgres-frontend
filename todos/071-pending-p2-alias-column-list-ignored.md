---
status: pending
priority: p2
issue_id: "071"
tags: [join, wrong-result, regress-suite]
dependencies: []
---

# A table alias's column list is ignored

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

`FROM t AS x (a, b, c)` renames t's columns. The frontend keeps the original names, so `SELECT *` shows them, `x.a` is unknown, and a NATURAL JOIN matches on the old names.

## Findings

- Where: join.sql #27–29, #52–54.
- `SELECT * FROM J1_TBL t1 (a, b, c) NATURAL JOIN J2_TBL t2 (d, a)`
  - PostgreSQL 16: columns a, b, c, d and 2 rows (joined on a)
  - Frontend: columns i, j, t, k and 5 rows (joined on i)

## Proposed Solutions

1. Apply the alias column list to the source's columns; a list longer than the table's columns is 42P10.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
