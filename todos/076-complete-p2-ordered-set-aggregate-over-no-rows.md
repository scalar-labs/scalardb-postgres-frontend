---
status: complete
priority: p2
issue_id: "076"
tags: [aggregates, wrong-result, regress-suite]
dependencies: []
---

# An ordered-set aggregate over no rows returns no row

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

An aggregate without GROUP BY returns one row even over no input. For an ordered-set aggregate (`WITHIN GROUP`), the frontend returns none.

## Findings

- Where: aggregates.sql #353.
- `select percent_rank(0) within group (order by x) from generate_series(1,0) x`
  - PostgreSQL 16: 1 row: 0
  - Frontend: 0 rows

## Proposed Solutions

1. Return the one row for ordered-set aggregates, as for the others.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
- 2026-10-19: Done by refusing: WITHIN GROUP aggregates were not implemented at all (over rows they failed with Unsupported expression, percentile_disc was not even recognised as an aggregate), and over no rows nothing was evaluated, so the failure showed as an empty result. QueryParser.hasAggregate, which every select item and WHERE pass through, now raises 0A000 for WITHIN GROUP at planning. LIMITATIONS lists it; QueryParserTest covers the empty-input case. Implementing percentile_cont/disc, mode and the hypothetical aggregates is left for when a client needs them.
