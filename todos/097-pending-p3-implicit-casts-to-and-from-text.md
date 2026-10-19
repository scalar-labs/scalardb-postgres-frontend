---
status: pending
priority: p3
issue_id: "097"
tags: [types, validation, regress-suite]
dependencies: []
---

# Non-text values are cast implicitly to text, and typed text to numbers

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

PostgreSQL has no implicit cast from integer to text (except into `||` with a text operand), nor from typed text to a number. The frontend applies both.

## Findings

- text.sql #3, #6: `SELECT length(42)`: PostgreSQL 42883, frontend 2; `SELECT 3 || 4.0`: PostgreSQL 42883, frontend 34.0
- subselect.sql #155: `select * from int8_tbl where q1 = (select '123'::text)`: PostgreSQL 42883 operator does not exist: bigint = text, frontend 2 rows

## Proposed Solutions

1. Coerce only unknown literals; raise 42883 for typed mismatches, as Builtins' signature resolution already reports.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
