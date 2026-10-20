---
status: complete
priority: p1
issue_id: "137"
tags: [datetime, time-zones, wrong-result, differential]
dependencies: []
---

# AT TIME ZONE read zones as Java does, not as PostgreSQL does

Found on 2026-10-20 while comparing make_timestamptz's zone argument: AT TIME ZONE took its zone
from `ZoneId.of(name, SHORT_IDS)`. PostgreSQL reads `'+02'`, `'2'` and `'UTC+3'` as POSIX specs, west
of UTC, where Java reads them east (four hours apart); its abbreviations are fixed offsets from its
own table (`'IST'` is Israel, +02; `'CET'` has no summer time), where SHORT_IDS maps them to regions
(`IST` to India, `PST` to Los Angeles with daylight time). `SET TimeZone = 'PST'`, which
PostgreSQL refuses, was accepted as Los Angeles.

## Acceptance Criteria

- [x] AT TIME ZONE takes POSIX offsets (hh[:mm[:ss]], with or without a name of three letters or
      `<...>`), UTC/GMT/Z/UT and zone database names with a region (case-insensitive) as
      PostgreSQL does; abbreviations and region-less names are refused (0A000).
- [x] SET TimeZone takes zone database names only (plus EST, MST, HST, the database's fixed zones
      Java names only as aliases).

## Work Log

- 2026-10-20: Evaluator.atZone and posixOffset (no regex: SpotBugs REDOS); QueryProcessor.zoneOf;
      31 zone spellings compared with PostgreSQL 18; HelpersTest.
