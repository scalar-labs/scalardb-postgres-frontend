---
status: complete
priority: p3
issue_id: "064"
tags: [functions, compatibility, pgadmin]
dependencies: []
---

# to_char and pg_blocking_pids are not implemented

Found on 2026-10-18 while doing todo 058.

## Problem Statement

pgAdmin's dashboard lists the sessions with a query that formats the times with `to_char(backend_start,
'YYYY-MM-DD HH24:MI:SS TZ')` and lists `array_to_string(pg_blocking_pids(pid), ', ')`. The frontend has
neither function, so the query fails with `Unsupported function`, although `pg_stat_activity` now answers.

`to_char` is missing for every type: `to_char(1.5, '9.9')` fails too.

## Proposed Solutions

1. **`pg_blocking_pids`** returns an empty `integer[]`: a session never waits on another's lock here, as
   ScalarDB's transactions are optimistic. Effort: small.
2. **`to_char`** for timestamps and numbers, with PostgreSQL's template patterns. Java's
   `DateTimeFormatter` covers the date parts once the patterns are translated. Effort: medium.

## Recommended Action

Both. `to_char` is ported from PostgreSQL 16's formatting.c (`ToChar`), for timestamps, timestamptz, dates and
the number types, and checked against 5,161 answers recorded from PostgreSQL 16; intervals and the number
patterns RN, EEEE and L fail with 0A000.

## Acceptance Criteria

- [x] pgAdmin's dashboard session query runs and gives PostgreSQL 16's formatting.
- [x] `to_char` of a timestamp and a number gives PostgreSQL 16's text for the common patterns.

## Work Log

- 2026-10-18: Filed from todo 058.
- 2026-10-18: Done. `ToChar` ports DCH_to_char and NUM_processor; `difftest/catalog/to_char_cases.py` records
  PostgreSQL 16's answers to `engine/src/test/resources/to_char-cases.txt`, which `ToCharTest` checks (all
  5,161 match). `pg_blocking_pids` answers `{}`. `builtins.py` added the seven `to_char` overloads and
  `pg_blocking_pids` to `pg_proc` with no differences from PostgreSQL 16, and now orders
  `information_schema.parameters` by position, so regenerating it is stable. pgAdmin's dashboard query runs
  live. Also fixed: `pg_stat_activity.client_addr` printed IPv6 in full (`0:0:0:0:0:0:0:1`) where
  PostgreSQL prints `::1`.
- 2026-10-18: Review. The cases now include rejected templates (42601) and `V` overflow; they caught a float4
  `V` bug (PostgreSQL multiplies by a float multiplier). `bigint` with 19 or more `V` digits fails with 22003
  as in PostgreSQL. Left out: PostgreSQL 16's `int4` `V` overflow wraps (`2` with `9V9999999999` is `-2`);
  the frontend fails with 22003.
