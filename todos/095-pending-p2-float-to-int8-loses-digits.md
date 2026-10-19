---
status: pending
priority: p2
issue_id: "095"
tags: [types, float, wrong-result, regress-suite]
dependencies: []
---

# A large float cast to bigint loses digits

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

The cast goes through a 17-digit decimal string, so the exact integer value of a large double is lost.

## Findings

- float4.sql #68, float8.sql #148
- `'9223372036854773760'::float8::int8`: PostgreSQL 9223372036854773760, frontend 9223372036854773800

## Proposed Solutions

1. Convert with `Math.rint` and a range check, not through a decimal string.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
