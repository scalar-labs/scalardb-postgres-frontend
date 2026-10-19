---
status: pending
priority: p2
issue_id: "068"
tags: [parser, wrong-result, regress-suite]
dependencies: []
---

# `^` binds looser than `*`

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

In PostgreSQL `^` (exponentiation) binds tighter than `*` and `/`. The frontend evaluates `a * b ^ c` as `(a * b) ^ c`.

## Findings

- Where: numeric.sql #639.
- `SELECT -0.5 * 10 ^ -5`
  - PostgreSQL 16: -5e-06 (that is -0.5 * (10 ^ -5), in double precision)
  - Frontend: -0.00032 (that is (-5) ^ -5)

## Proposed Solutions

1. Rebalance the parse tree for `^` (JSqlParser gives it the precedence of bitwise XOR), or rewrite `x ^ y` before parsing, so that it binds tighter than `*`, `/` and `%`.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
