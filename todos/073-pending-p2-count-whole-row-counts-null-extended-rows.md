---
status: pending
priority: p2
issue_id: "073"
tags: [join, aggregates, wrong-result, regress-suite]
dependencies: []
---

# `count(t.*)` counts the all-NULL rows an outer join adds

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

`count(t2.*)` counts rows whose whole-row value is not NULL. A row an outer join fills with NULLs for t2 has a NULL whole-row value. The frontend counts it.

## Findings

- Where: join.sql #277–280.
- `SELECT t1.q2, count(t2.*) FROM int8_tbl t1 LEFT JOIN int8_tbl t2 ON (t1.q2 = t2.q1) GROUP BY t1.q2`
  - PostgreSQL 16: 456 → 0
  - Frontend: 456 → 1

## Proposed Solutions

1. Treat `t.*` in an aggregate as NULL when the row was NULL-extended for t (all of t's columns NULL because of the join).
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
