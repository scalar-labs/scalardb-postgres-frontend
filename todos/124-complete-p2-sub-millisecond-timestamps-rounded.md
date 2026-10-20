---
status: complete
priority: p2
issue_id: "124"
tags: [types, timestamp, wrong-result]
dependencies: []
---

# Sub-millisecond timestamps were rounded on write

## Problem Statement

ScalarDB's TIMESTAMP and TIMESTAMPTZ keep milliseconds (its columns cut anything finer). The frontend
rounded a written value to milliseconds, so `'2024-01-01 00:00:00.123456'` read back as `.123`, and a
predicate's value was rounded the same way when pushed down (`WHERE ts = '...00.1234'` matched a `.123`
row). A `TIMESTAMP(0)` column also kept milliseconds where PostgreSQL rounds to seconds.

## Recommended Action

Truth or error (agreed 2026-10-20, option 1): refuse what cannot be stored exactly.

## Acceptance Criteria

- [x] A write of a value finer than a millisecond to a TIMESTAMP or TIMESTAMPTZ column fails with 0A000, for
      literals and for text and binary parameters.
- [x] `TIMESTAMP(p)` / `TIMESTAMPTZ(p)` with p ≤ 3 is recorded (docs/type-encoding.md) and rounds on write to p,
      as PostgreSQL does.
- [x] A comparison with a finer value is evaluated in the frontend, exactly, not pushed down rounded.
- [x] now(), clock_timestamp() and the other clocks read to the millisecond, so `INSERT ... now()` works.
- [x] Timestamp text input rounds to microseconds, as PostgreSQL's (`'...00.9999999'` is `...01`).

## Work Log

- 2026-10-20: Implemented and checked live against PostgreSQL 18 (inserts, updates, predicates on native
  and TIMESTAMP(3)/TIMESTAMPTZ(0) columns, psycopg text and binary parameters). Tables created earlier
  with TIMESTAMP(0..3) have no recorded precision, so they refuse finer values instead of rounding.
