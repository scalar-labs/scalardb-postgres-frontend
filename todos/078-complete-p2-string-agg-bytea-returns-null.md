---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
- 2026-10-19: Done. The root cause differs from the title: string_agg over bytea joined the values' text, returning the text \\xff\\xee\\xaa instead of the bytes \\xffeeaa; the NULLs in aggregates.sql came from rows that were never inserted (decode() is unsupported). Evaluator.aggregateValues now joins bytea values as bytes, reading an untyped delimiter literal as bytea input. Over a bytea column the value, pg_typeof and the described type match PostgreSQL 16. Over a VALUES list the column type is not inferred, so the result is described as text (its text is the same); that is a separate gap in VALUES column typing. HelpersTest covers it.
