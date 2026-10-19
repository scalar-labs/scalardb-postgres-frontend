---
status: complete
priority: p3
issue_id: "077"
tags: [aggregates, types, wrong-result, regress-suite]
dependencies: []
---

# `sum(real)` returns `double precision`

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

In PostgreSQL `sum(float4)` is `float4`. The frontend sums in and returns `float8`, so the text shows more digits.

## Findings

- Where: aggregates.sql #16.
- `SELECT sum(b) FROM aggtest`
  - PostgreSQL 16: 431.773
  - Frontend: 431.77261

## Proposed Solutions

1. Return float4 for sum(float4); check avg, min and max of float4 too.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
- 2026-10-20: Fixed by todo 094 (ea4725b): sum(real) is real. aggtest's values give 431.7726 in both PostgreSQL 18 and the frontend, and the types (sum real, avg double precision, min real) match. PG16's 431.773 was written with extra_float_digits=0 by pg_regress.
