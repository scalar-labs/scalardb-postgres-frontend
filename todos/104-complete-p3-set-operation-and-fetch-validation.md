---
status: complete
priority: p3
issue_id: "104"
tags: [validation, regress-suite]
dependencies: []
---

# Set-operation and FETCH ... WITH TIES checks are missing

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

Statements PostgreSQL rejects run.

## Findings

- union.sql #34: `select q1 from int8_tbl union select q2 from int8_tbl for update`: PostgreSQL 0A000, frontend rows
- union.sql #106: ORDER BY a column named only in a later branch: PostgreSQL 42703, frontend empty result
- limit.sql #72, #73: `fetch first 1 row with ties` without ORDER BY, or with SKIP LOCKED: PostgreSQL 42601, frontend rows

## Proposed Solutions

1. Add the checks with PostgreSQL's SQLSTATEs.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: As PostgreSQL: a set operation's ORDER BY names a result column or position (42703 for another name, so a column of a later branch fails; 0A000 for an expression), FOR UPDATE/SHARE on a set operation fails with 0A000, and FETCH ... WITH TIES fails without ORDER BY or with SKIP LOCKED (42601) or with a NULL count (2201W). WITH TIES on a set operation, which was ignored, is refused (0A000). union.sql #34, #106 and limit.sql #72, #73 now SAME (vs PostgreSQL 18).
