---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: A cast to real (real, float4, float(1-24)) rounds to float, failing 22003 out of range; an unknown literal next to a real is a real, in arithmetic and comparisons; real op real is real (computed in double, rounded once); -real stays real; sum(real) is the exact sum rounded once to real, avg reads a real's exact value; a real prints its shortest round-trip digits (JDK 17's Float.toString can give more). regress: float4 DIFF 7 -> 1 (the remaining one follows from the harness's primary key on f1, which refuses the UPDATE), PG_ERR 1 -> 0; int8, int2, int4, aggregates, numeric each fix one or two; no new DIFF or PG_ERR in float4, float8, int2, int4, int8, aggregates or numeric. Still an error: 'inf' as real input.
