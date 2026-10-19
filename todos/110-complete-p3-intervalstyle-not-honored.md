---
status: complete
priority: p3
issue_id: "110"
tags: [datetime, settings, regress-suite]
dependencies: []
---

# IntervalStyle is accepted but intervals are always printed in the postgres style

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

Todo 059 added IntervalStyle as a setting that is stored but not applied (documented in LIMITATIONS). The regression suite shows the difference.

## Findings

- interval.sql #69, #154, #155, #158, #160, #172–186, #314, #316, #318
- `SET IntervalStyle = postgres_verbose; SELECT interval '1 day 2 hours'`: PostgreSQL `@ 1 day 2 hours`, frontend `1 day 02:00:00`

## Proposed Solutions

1. Implement sql_standard, iso_8601 and postgres_verbose output, or refuse values other than postgres with 22023.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: Fixed by todo 120 (c1995d1): output follows IntervalStyle; postgres_verbose '1 day 2 hours' is '@ 1 day 2 hours', as in PostgreSQL.
