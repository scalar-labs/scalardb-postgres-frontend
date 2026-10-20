---
status: complete
priority: p3
issue_id: "106"
tags: [datetime, wrong-result, regress-suite]
dependencies: []
---

# The BC suffix of a date literal is dropped

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`DATE '2020-08-11 BC'` is year -2020 (2021 BC). The frontend ignores BC in a literal, while INSERT refuses it.

## Findings

- date.sql #209
- `SELECT DATE '2020-08-11 BC', EXTRACT(YEAR FROM DATE '2020-08-11 BC')`: PostgreSQL `2020-08-11 BC | -2020`, frontend `2020-08-11 | 2020`

## Proposed Solutions

1. Support BC dates, or refuse them with 22007 everywhere, as INSERT does.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: Dates and timestamps read a trailing BC or AD (1 BC is the year 0), write a year at or before 0 with BC (0001-12-31 BC, the offset before BC for timestamptz), and EXTRACT(YEAR) counts without a year 0 (-2020). Input in the year 0 or before 4714-11-24 BC fails with 22008, and text after a date must now be a time ('2020-08-11 xyz' fails; it was dropped). Regress vs PostgreSQL 18: date.sql #157, #158, #209 and timestamp.sql #101, #102 SAME; no new failures in date, timestamp, timestamptz, interval.
