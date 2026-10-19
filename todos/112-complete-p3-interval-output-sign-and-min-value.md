---
status: complete
priority: p3
issue_id: "112"
tags: [datetime, interval, wrong-result, regress-suite]
dependencies: []
---

# Interval output: the sign of mixed fields, and the smallest value

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

A positive time part after a negative day part is written with `+`, and the smallest interval has no doubled signs.

## Findings

- interval.sql #6, #9, #151, #152: `interval '-1 days +02:03'`: PostgreSQL `-1 days +02:03:00`, frontend `-1 days 02:03:00`
- interval.sql #176–186, #312: `interval '-9223372036854775808 microseconds'`: PostgreSQL `-2562047788:00:54.775808`, frontend `--2562047788:00:-54.-775808` (negating Long.MIN_VALUE)

## Proposed Solutions

1. Write `+` as PostgreSQL does; split into fields before negating.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Fixed by todo 111 (e93ef5f): '-1 days +02:03' is '-1 days +02:03:00' and the smallest interval is '-2562047788:00:54.775808', checked live against PostgreSQL.
