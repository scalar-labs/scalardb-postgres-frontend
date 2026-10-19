---
status: pending
priority: p3
issue_id: "107"
tags: [datetime, regress-suite]
dependencies: []
---

# EXTRACT and date_part: result scale, type and unit checks

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

EXTRACT from a date is numeric with scale 0, date_part returns double precision, and EXTRACT of a unit the type lacks (HOUR of a date) is 0A000.

## Findings

- date.sql #176, #225: `EXTRACT(EPOCH FROM DATE '2020-08-11')`: PostgreSQL 1597104000, frontend 1597104000.000000; `date_part('second', timestamp ...)`: PostgreSQL 5, frontend 5.000000
- date.sql #203–205: `EXTRACT(HOUR FROM DATE '2020-08-11')`: PostgreSQL 0A000, frontend 0

## Proposed Solutions

1. Follow PostgreSQL's per-type result scales and unit validity.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
