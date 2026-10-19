---
status: pending
priority: p2
issue_id: "094"
tags: [types, float, wrong-result, regress-suite]
dependencies: []
---

# real (float4) values are computed and compared in double precision

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

A cast to real must round to single precision, and real op unknown-literal is real arithmetic. The frontend keeps double precision, which also changes comparisons.

## Findings

- int8.sql #112, #113: `4567890123456789::int8::float4`: PostgreSQL `4.56789e+15`, frontend `4.567890123456789e+15`
- float4.sql #65: `'2147483647'::float4::int4`: PostgreSQL 22003, frontend 2147483647
- float4.sql #47, #51–54: aggtest.b real 7.8: `b*'-10'`: PostgreSQL -78, frontend -78.00000190734863; `count(*) WHERE '324.78' > b`: PostgreSQL 3, frontend 4

## Proposed Solutions

1. Round through float on a cast to real; coerce an unknown literal next to a real to real; compute real-by-real operators in float.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
