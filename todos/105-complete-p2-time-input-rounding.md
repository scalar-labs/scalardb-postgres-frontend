---
status: complete
priority: p2
issue_id: "105"
tags: [datetime, wrong-result, regress-suite]
dependencies: []
---

# time input beyond microseconds is truncated, and can print a dangling dot

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

PostgreSQL rounds time input to microseconds (23:59:59.9999999 is 24:00:00). The frontend truncates, and can print `12:00:00.`.

## Findings

- time.sql #18
- `SELECT '23:59:59.9999999'::time, '12:00:00.0000005'::time`: PostgreSQL `24:00:00 | 12:00:00`, frontend `23:59:59.999999 | 12:00:00.`

## Proposed Solutions

1. Round half up to microseconds, allowing 24:00:00; drop the dot when the fraction is zero.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. Evaluator.time is the one time-input parser (casts, comparisons, literals, writes): it rounds to microseconds half to even, as PostgreSQL does (.1234565 is .123456), and gives 24:00:00 (given, or rounded to from 23:59:59.9999999) as LocalTime.MAX, which prints as 24:00:00 and sorts last. The fraction printer no longer writes a bare '.' for a sub-microsecond rest. Writing 24:00:00 to a column is refused with 0A000 (ScalarDB's TIME ends at 23:59:59.999999; it was silently stored as 23:59:59.999999); columnFromText now passes a PgException through instead of rewording it as invalid input. Live check matches PostgreSQL 16 for reads; LIMITATIONS lists the storage limit; HelpersTest pins the rounding. A rerun of 8 type files shows only improvements.
