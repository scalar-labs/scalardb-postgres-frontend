---
status: pending
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

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
