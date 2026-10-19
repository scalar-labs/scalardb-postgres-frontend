---
status: pending
priority: p2
issue_id: "067"
tags: [types, wrong-result, regress-suite]
dependencies: []
---

# A float cast to integer rounds halves away from zero

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

PostgreSQL rounds a `float4`/`float8` cast to `int2`/`int4`/`int8` to the nearest even integer (`rint`). The frontend rounds halves away from zero.

## Findings

- Where: int4.sql #60.
- `SELECT x, x::int4 FROM (VALUES (-2.5::float8), (-1.5::float8), (-0.5::float8), (0.5::float8), (1.5::float8), (2.5::float8)) t(x)`
  - PostgreSQL 16: -2.5 → -2, -0.5 → 0, 0.5 → 0, 2.5 → 2
  - Frontend: -2.5 → -3, -0.5 → -1, 0.5 → 1, 2.5 → 3

## Proposed Solutions

1. Round half to even (`Math.rint`) when a float is cast to an integer type; numeric casts keep rounding halves away from zero, as in PostgreSQL.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
