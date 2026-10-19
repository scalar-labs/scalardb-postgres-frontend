---
status: complete
priority: p2
issue_id: "090"
tags: [arrays, wrong-result, regress-suite]
dependencies: []
---

# array_to_string mishandles NULL elements and separators

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

NULL elements are skipped, or replaced by the third argument; a NULL separator makes the result NULL. The frontend writes `null`.

## Findings

- arrays.sql #346–349
- `array_to_string(array[1,2,NULL,6], ',')`: PostgreSQL `1,2,6`, frontend `1,2,null,6`; with `'*'` as third argument PostgreSQL `1,2,*,6`; a NULL separator: PostgreSQL NULL, frontend `1nullnull`

## Proposed Solutions

1. Follow PostgreSQL's NULL rules.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done with 089. array_to_string writes the elements of every dimension in order, skips NULL elements or writes the third argument for them when it is not NULL, and is NULL for a NULL separator; an empty first element no longer swallows the separator. Matches PostgreSQL 16 live; QueryParserTest covers it.
