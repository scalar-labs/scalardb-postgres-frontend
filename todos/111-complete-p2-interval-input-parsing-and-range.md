---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Interval input follows PostgreSQL where it is read, and fails where it is not: a bare number is seconds alone or days before a time ('1 2:03:04', '-1 +02:03'), any other bare number ('1-2', '1 2') is 22007; a field given twice is 22007 (fractional seconds and a time also claim milliseconds and microseconds); '' is 22007; a field past its range, before or after 'ago', is 22015 rather than wrapping. A fraction of a year rounds to months, a fraction of a month carries into days and time (0.01 months is 07:12:00; it was dropped). A qualifier other than one field over a whole number ('1' YEAR TO MONTH, second(2), '1.5' DAY) is refused with 0A000. Found alongside: a positive field after a negative one prints with '+' (-1 days +02:03:00), the most negative microseconds printed as --2562047788:00:-54.-775808, and INTERVAL '...' is named interval. regress interval: 47 wrong results fixed before these last fixes; the rest are IntervalStyle (todo 120), 22008 vs 22015 codes, and interval * float overflow (#42). Year-month input ('1-2') and qualifiers remain to implement.
