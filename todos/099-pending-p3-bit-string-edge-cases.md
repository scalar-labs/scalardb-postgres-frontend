---
status: pending
priority: p3
issue_id: "099"
tags: [types, bit, regress-suite]
dependencies: []
---

# Bit strings: spaces accepted, position of an empty string

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

Two bit-string differences.

## Findings

- bit.sql #14, #15: `SELECT b' 0'`: PostgreSQL 22P02, frontend ' 0'
- bit.sql #54: `POSITION(B'' IN B'')`: PostgreSQL 0, frontend 1

## Proposed Solutions

1. Validate B'...' digits; return 0 for an empty haystack.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
