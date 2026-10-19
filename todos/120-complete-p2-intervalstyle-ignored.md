---
status: complete
priority: p2
issue_id: "120"
tags: [types, interval, wrong-result, regress-suite]
dependencies: []
---

# SET IntervalStyle is accepted, but intervals are always written in postgres style

Found on 2026-10-19 while closing todo 111 (interval.sql through `difftest/regress`).

## Problem Statement

`SET intervalstyle = iso_8601` (ActiveRecord sends it on connect), `sql_standard` or `postgres_verbose`
succeeds and `SHOW intervalstyle` reports it, but interval values are still written as `1 day 02:00:00`. A
client that parses the style it asked for reads them wrongly.

## Findings

- interval.sql #154, #155, #160, #172–186, #312–318
- `SET intervalstyle = iso_8601; SELECT interval '1 day 2 hours'`: PostgreSQL `P1DT2H`, frontend `1 day 02:00:00`
- `SET intervalstyle = postgres_verbose; SELECT interval '0.7 seconds'`: PostgreSQL `@ 0.7 secs`, frontend `00:00:00.7`

## Proposed Solutions

1. Write the other three styles as PostgreSQL's EncodeInterval does (text and binary are unaffected for
   binary results), reading the session's setting.
2. Refuse the other styles with 0A000, as DateStyle's are (todo 113); this breaks ActiveRecord's connect.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF for them.

## Work Log

- 2026-10-19: Filed while closing todo 111.
- 2026-10-19: Interval output follows IntervalStyle (postgres_verbose, sql_standard, iso_8601 written as EncodeInterval does); sql_standard's leading minus negates every field on input; SET of an unknown style fails with 22023. Compared live with PostgreSQL 18 (Docker's PG16 was unresponsive; interval output is the same since 8.4): 128 style/value cases match, except '- 1 day 2 hours' (a space after the sign), which is refused with 22007.
