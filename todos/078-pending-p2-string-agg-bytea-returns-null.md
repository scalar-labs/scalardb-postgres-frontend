---
status: pending
priority: p2
issue_id: "078"
tags: [aggregates, bytea, wrong-result, regress-suite]
dependencies: []
---

# `string_agg(bytea, bytea)` returns NULL

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

`string_agg` over `bytea` values concatenates them with the bytea delimiter. The frontend returns NULL.

## Findings

- Where: aggregates.sql #278–282.
- `select string_agg(v, decode('ee', 'hex')) from bytea_test_table`
  - PostgreSQL 16: \xffeeaa
  - Frontend: NULL

## Proposed Solutions

1. Implement string_agg for bytea, or refuse it with 0A000.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
