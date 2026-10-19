---
status: pending
priority: p3
issue_id: "092"
tags: [naming, regress-suite]
dependencies: []
---

# Result column names of typed literals, subscripted casts and AT TIME ZONE

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

PostgreSQL names `interval '1 day'` interval, `date '...'` date, `x AT TIME ZONE z` timezone, and `('...'::jsonb)[0]` jsonb. The frontend names them `?column?`.

## Findings

- interval.sql #93–103, #135, #164; timestamptz.sql #274–282, #292, #293, #296; jsonb.sql #853, #855
- `SELECT interval '1 day', timestamp '2011-03-27' AT TIME ZONE 'UTC', date '2020-01-01'`: PostgreSQL `interval | timezone | date`, frontend `?column?` three times

## Proposed Solutions

1. Name a typed literal and a subscripted cast after the type, and AT TIME ZONE `timezone`, as FigureColname does.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
