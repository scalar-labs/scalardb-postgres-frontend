---
status: complete
priority: p2
issue_id: "079"
tags: [window, wrong-result, regress-suite]
dependencies: []
---

# Window frame `EXCLUDE` is ignored

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress` (calibration run:
int4, numeric, join, aggregates, window).

## Problem Statement

`EXCLUDE CURRENT ROW`, `EXCLUDE GROUP` and `EXCLUDE TIES` remove rows from the window frame. The frontend computes the frame without excluding anything.

## Findings

- Where: window.sql #55–63, #71.
- `SELECT sum(unique1) over (rows between 2 preceding and 2 following exclude group), unique1, four FROM tenk1 WHERE unique1 < 10`
  - PostgreSQL 16: NULL in every row
  - Frontend: the full sums (7, 13, 22, ...)

## Proposed Solutions

1. Apply the exclusion when computing the frame, or refuse EXCLUDE with 0A000, as `RANGE` with an offset already is.
2. Refuse the construct with 0A000 until it is right, so the answer is an error rather than a wrong result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite calibration run.
- 2026-10-19: Refused first: a window frame with EXCLUDE CURRENT ROW, GROUP or TIES fails with 0A000 (frames are contiguous ranges here); EXCLUDE NO OTHERS runs. regress window: 8 wrong results fixed, none new. Applying the exclusion remains to implement.
