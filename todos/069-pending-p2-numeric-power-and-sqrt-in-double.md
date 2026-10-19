---
status: pending
priority: p2
issue_id: "069"
tags: [numeric, wrong-result, regress-suite]
dependencies: []
---

# `numeric` `^`, `power()` and `sqrt()` are computed in double precision

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

PostgreSQL computes `numeric ^ numeric`, `power(numeric, numeric)` and `sqrt(numeric)` exactly, at a result scale of at least 16. The frontend converts to `double`.

## Findings

- Where: numeric.sql #560–562, #850–893 (about 35 statements); #860, #861, #887, #888 also accept what PostgreSQL rejects.
- `SELECT 3.789 ^ 21.0000000000000000, sqrt(96627521408608.56340355805::numeric), 1.2 ^ 345, 0.0 ^ (-12.34), (-12.34) ^ 1.2, 10.0 ^ 2147483647`
  - PostgreSQL 16: 1409343026052.8716016316022141, 9829929.87811248648, 2077446682327378559843444695.6, then errors 2201F, 2201F and 22003
  - Frontend: 1409343026052.8728, 9829929.878112486, 2.077446682327352e+27, Infinity, NaN, Infinity

## Proposed Solutions

1. Compute with BigDecimal: an integer exponent by repeated squaring at the needed scale, and other exponents and sqrt with PostgreSQL's result-scale rules (numeric.c `power_var`, `sqrt_var`). Raise 2201F and 22003 as PostgreSQL does. `generate_series(numeric, numeric)` should also keep its arguments' scale (`0.0` gives `0.0, 1.0, ...`).
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
