---
status: complete
priority: p2
issue_id: "142"
tags: [datetime, step-4, differential]
dependencies: []
---

# Special datetime input and zones in timestamp text (step 4)

'epoch', 'now', 'today', 'tomorrow', 'yesterday' and 'allballs', and a trailing zone in timestamp
text ('2020-01-01 12:00 EST', '... Europe/London'), were refused.

## Acceptance Criteria

- [x] The special words alone (any case, trimmed), for date, time, timestamp and timestamptz, as
      PostgreSQL 18 gives them; with a time or BC added ('today 10:00') they stay refused.
- [x] A trailing zone: a zone database name with a region (any case), or an abbreviation of
      PostgreSQL's Default set with a fixed offset (resource timezone-abbreviations.txt, 145 of
      them); dynamic ones (MSK, 50) and POSIX names (UTC+3) are refused. A timestamp drops it.
- [x] DATE columns read their text as the date cast does (BC, a timestamp's text, the words).
- [ ] 'infinity' and '-infinity': not done; needs a value every datetime operation and the
      storage refusal handle (asked the user).

## Work Log

- 2026-10-20: Evaluator.specialInput, QueryParser.inputZone; 25 forms, DST edges and table
      round trips compared with PostgreSQL 18.
