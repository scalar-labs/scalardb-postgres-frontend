---
status: complete
priority: p2
issue_id: "108"
tags: [datetime, wrong-result, regress-suite]
dependencies: []
---

# A TIME WITH TIME ZONE literal is evaluated as timestamptz

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`TIME WITH TIME ZONE '2020-05-26 13:30:25-04'` is the local time 13:30:25 with offset -04. The frontend converts the whole value to a UTC timestamp, so EXTRACT gives other hours and an epoch since 1970.

## Findings

- timetz.sql #39, #40, #45, #49
- `EXTRACT(HOUR FROM TIME WITH TIME ZONE '2020-05-26 13:30:25.575401-04')`: PostgreSQL 13, frontend 17

## Proposed Solutions

1. Parse timetz as local time plus offset, dropping any date part; or refuse timetz with 0A000.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Evaluator.cast refuses time with time zone and timetz with 0A000 before the timestamptz branch, which read it as a timestamptz and converted it to UTC. Test in QueryProcessorTest.
