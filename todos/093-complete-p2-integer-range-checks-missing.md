---
status: complete
priority: p2
issue_id: "093"
tags: [types, wrong-result, regress-suite]
dependencies: []
---

# smallint arithmetic and int8-to-oid casts have no range check

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`smallint + smallint` stays smallint and must raise 22003 on overflow; a cast to oid must fit 0..4294967295. The frontend returns out-of-range values of the type.

## Findings

- int2.sql #32, #35, #38, #45, #46: `SELECT 32767::int2 + 1::int2`: PostgreSQL 22003, frontend 32768 typed smallint
- int8.sql #115: `SELECT 4567890123456789::int8::oid`: PostgreSQL 22003, frontend 4567890123456789

## Proposed Solutions

1. Range-check int2 results as int4 and int8 results are; check the oid cast.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. Evaluator.arithmetic keeps smallint op smallint as smallint (both operands typed int2 at plan time, decided once per expression in Context.smallints) and raises 22003 smallint out of range, as integer op integer already did. Casts to oid follow PostgreSQL: an integer's bits (-1 is 4294967295), a bigint in 0..4294967295 (else OID out of range), text in -2147483648..4294967295; a bigint within integer's range is taken as an integer (ponytail: (-1)::int8::oid wraps where PostgreSQL fails). Live check of 12 statements matches PostgreSQL 16; QueryParserTest covers it.
