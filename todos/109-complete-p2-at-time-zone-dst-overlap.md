---
status: complete
priority: p2
issue_id: "109"
tags: [datetime, wrong-result, regress-suite]
dependencies: []
---

# AT TIME ZONE picks the earlier offset for an ambiguous local time

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

For a local time that occurs twice (a fall-back DST change), PostgreSQL uses the later, standard-time offset.

## Findings

- timestamptz.sql #294, #295
- `SELECT '2014-10-26 01:00:00'::timestamp AT TIME ZONE 'Europe/Moscow'`: PostgreSQL `2014-10-25 22:00:00+00`, frontend `2014-10-25 21:00:00+00`

## Proposed Solutions

1. Use `withLaterOffsetAtOverlap()` after `ZonedDateTime.ofLocal`; check casts of timestamp to timestamptz too.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. Evaluator.instant(LocalDateTime, ZoneId) resolves a local timestamp in a zone as PostgreSQL does: an ambiguous time (DST ending) takes the later, standard-time offset; a skipped one moves forward by the gap. AT TIME ZONE, casts of timestamp and text to timestamptz in the session zone, date_trunc on timestamptz and writes of a timestamp into a timestamptz column all use it (they used atZone, which takes the earlier offset). Live check against PostgreSQL 16 (Europe/Moscow, America/New_York, both directions) matches; HelpersTest pins it.
