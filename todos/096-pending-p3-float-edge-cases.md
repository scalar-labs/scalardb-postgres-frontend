---
status: pending
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

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
