---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: No implicit casts between text and numbers, as in PostgreSQL: a text function given a number or boolean (length(42)) and || between two non-text values (3 || 4.0) fail with 42883, and so does comparing a number with a value typed text, varchar or bpchar as planning infers it, including a subquery's quoted literal ((select '123')). A quoted literal and an untyped parameter stay unknown and compare as before. Regress sweep (text, strings, subselect, select, case, union, aggregates, date, timestamp, interval, json vs PostgreSQL 18): no statement newly refused; two backend errors (cached plan must not change result type) come from select.sql and json.sql both creating a table foo.
