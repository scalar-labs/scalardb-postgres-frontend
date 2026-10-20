---
status: complete
priority: p2
issue_id: "123"
tags: [regress-suite, wrong-result]
dependencies: []
---

# Full regression run of 2026-10-20: the wrong results it found

## Problem Statement

After the p2 and p3 todos, all 43 regression files used so far were run again against PostgreSQL 18 (Docker's
PostgreSQL 16 was unresponsive), keeping only DIFF (a different answer) and PG_ERR (accepted what PostgreSQL
rejects). Statements the frontend refuses (FE_ERR) are out of scope: refusing is allowed.

## Findings

Totals: SAME 4316, FE_ERR 4301, CODE 972, DIFF 216, PG_ERR 12, ORDER 46, META 40, SKIP 459.

Of the 228 DIFF and PG_ERR statements, six were wrong answers of the frontend's own:

1. `extra_float_digits` <= 0 was ignored: floats were written shortest (aggregates.sql #16, 431.7726 for 431.773).
2. `CREATE TABLE ... PARTITION BY` made a plain table that took rows PostgreSQL rejects (join.sql #518, #520).
3. interval * float, interval + interval and -interval wrapped around past the range instead of failing
   (interval.sql #42 gave -9223372036854.775808).
4. `power(numeric, '3')` read the quoted literal as double precision, escaping todo 069's refusal and
   answering with a double's scale (numeric.sql #560, #562).
5. `bigint IN (SELECT text_column)` compared across types instead of failing with 42883 (subselect.sql #155).
6. Seven subscripts gave NULL instead of 54000 (arrays.sql #41).

The rest:

- cascades: a statement the frontend refused (or a failing INSERT) left different rows, which later
  statements read (transactions, timestamp, timestamptz, jsonb subscript updates, with.sql's writable
  CTEs, delete, numeric's num_result, strings' toasttest, time, insert_conflict, union, join)
- the scratch backend's database collation (ja_JP), the documented collation limitation (char.sql and
  varchar.sql `f1 < 'a'`)
- PostgreSQL 18 behavior that PostgreSQL 16 lacks (infinite intervals, interval.sql #312-318)
- ties left unordered by the test (window.sql #17, #71, #241, #266, #282)
- PostgreSQL folding a constant 1/0 at plan time (case.sql #20); pg_stats, which has no rows here (json #68,
  jsonb #61); DDL inside a transaction, which is documented (transactions.sql #11, #95)
- the known numeric typmod leftovers of todo 103 (with.sql #140, #141)

## Recommended Action

Fix the six, each failing as PostgreSQL does or with 0A000.

## Acceptance Criteria

- [x] Each of the six gives PostgreSQL 18's answer or an error; tests added.

## Work Log

- 2026-10-20: Ran, triaged and fixed the six (extra_float_digits honored as %.*g at DBL_DIG/FLT_DIG plus it,
  with its -15..3 range checked; PARTITION BY and PARTITION OF refused; interval overflow 22008; power's
  quoted argument typed numeric beside a numeric; IN (subquery) typed text against a number 42883, the
  subquery's type taken from its own plan; 54000 past six subscripts). Checked live against PostgreSQL 18.
