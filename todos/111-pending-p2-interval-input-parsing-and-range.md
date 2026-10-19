---
status: pending
priority: p2
issue_id: "111"
tags: [datetime, interval, wrong-result, regress-suite]
dependencies: []
---

# Interval input is misparsed, unchecked and can wrap around

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

The interval input parser does not follow PostgreSQL's DecodeInterval, ignores qualifiers and precision, accepts duplicate units and empty input, and builds values without overflow checks.

## Findings

- interval.sql #5, #92, #105, #106, #128, #151, #158: `interval '1-2'`: PostgreSQL `1 year 2 mons`, frontend `-00:00:01`; `interval '-1 +02:03'`: PostgreSQL `-1 days +02:03:00`, frontend `02:02:59`; `interval '1 2:03:04'`: PostgreSQL `1 day 02:03:04`, frontend `02:03:05`
- interval.sql #104, #136: `interval '1' year to month`: PostgreSQL `1 mon`, frontend `1 year`; `interval '1.234' second(2)`: PostgreSQL `00:00:01.23`, frontend `00:00:01.234`
- interval.sql #87–91, #156 and #109–144: `interval '1 day 1 day'`, `interval ''`, `interval '1 2' hour to minute`: PostgreSQL 22007, frontend a value
- interval.sql #42, #196–205, #263–274, #301–310: `interval '2562047789 hours'`: PostgreSQL 22015, frontend `-2562047787:01:49.551616` (wrapped)

## Proposed Solutions

1. Port DecodeInterval's rules (bare numbers, Y-M, signs, qualifiers, precision, repeated units) and use exact arithmetic with 22015/22008.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
