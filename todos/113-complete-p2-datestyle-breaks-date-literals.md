---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. The 'unexpected DateStyle: ymd' error (no SQLSTATE) came from psycopg, which parses the DateStyle the server reports and failed on the bare 'ymd' the frontend stored. QueryProcessor.dateStyle now stores DateStyle as PostgreSQL does: an output style and a field order, each kept from the current value when not given ('ymd' is 'ISO, YMD', 'ISO' is 'ISO, MDY'), the European/US aliases mapped, conflicting orders and unknown words 22023; a style other than ISO is refused with 0A000 since dates are written in ISO style here. Applies to SET, set_config and startup parameters. Eight values match PostgreSQL 16 live; psycopg reads dates after SET DateStyle = ymd. psql18's \\dconfig fixture now counts 2 (pgjdbc's DateStyle=ISO is stored as the boot value, as in PostgreSQL). Also closes the DateStyle point left open in todo 059. QueryProcessorTest covers it.
