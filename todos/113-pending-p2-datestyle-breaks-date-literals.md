---
status: pending
priority: p2
issue_id: "113"
tags: [datetime, settings, regress-suite]
dependencies: []
---

# After SET DateStyle, date literals fail with an internal error

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`SET DateStyle = ymd` (or mdy) is accepted, then date input fails with `unexpected DateStyle: ymd/mdy` and no SQLSTATE.

## Findings

- date.sql #24, #25, #158 (SQLSTATE missing in the CODE bucket)

## Proposed Solutions

1. Support the DateStyle field orders for input, or refuse the SET with 22023; never fail without a SQLSTATE.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
