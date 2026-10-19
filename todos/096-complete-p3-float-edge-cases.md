---
status: complete
priority: p3
issue_id: "096"
tags: [types, float, regress-suite]
dependencies: []
---

# float8 edge cases: input overflow, negative zero, power(1, NaN)

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

Three float8 edge cases differ from PostgreSQL.

## Findings

- float8.sql #6–9: `'10e400'::float8`: PostgreSQL 22003, frontend Infinity; `'10e-400'`: PostgreSQL 22003, frontend 0
- float8.sql #51: `round(-1.2345678901234e-200::float8)`: PostgreSQL -0, frontend 0
- float8.sql #65: `power(float8 '1', float8 'NaN')`: PostgreSQL 1, frontend NaN (Java's Math.pow)

## Proposed Solutions

1. Raise 22003 for an overflowing or underflowing literal; keep the sign in round; power(1, x) is 1.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: float8 input follows float8in: Infinity, inf and NaN in any case, decimal digits only (1.5f is 22P02), and a value that overflows or underflows to zero fails with 22003, also numeric to float8 (1e400::float8). round(float8) is rint (half to even, -0 kept), and round(float8, n) fails with 42883 as in PostgreSQL. power follows dpow: 1 ^ NaN, NaN ^ 0 and -1 ^ Infinity are 1. float8 and real are written with Ryu's shortest digits, never a midpoint (1e23 is 9.999999999999999e+22; JDK 17's Double.toString is not always shortest): 20,000 random float8 and 20,000 random float4 bit patterns print as PostgreSQL 18 prints them. Regress float4.sql #31-34, #41 and float8.sql #6-9, #24-27, #34, #51, #64-93 now SAME. Left: hex input ('0x10'::float8, 16 in PostgreSQL) is refused.
