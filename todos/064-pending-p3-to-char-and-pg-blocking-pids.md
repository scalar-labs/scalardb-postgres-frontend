---
status: pending
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

## Acceptance Criteria

- [ ] pgAdmin's dashboard session query runs and gives PostgreSQL 16's formatting.
- [ ] `to_char` of a timestamp and a number gives PostgreSQL 16's text for the common patterns.

## Work Log

- 2026-10-18: Filed from todo 058.
