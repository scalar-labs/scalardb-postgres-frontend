---
status: complete
priority: p2
issue_id: "083"
tags: [char, wrong-result, regress-suite]
dependencies: []
---

# A cast to char(n) is not padded with blanks

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`x::char(n)` pads to n characters in PostgreSQL; the frontend keeps the value unpadded, so octet_length and concatenation differ.

## Findings

- strings.sql #62, #63
- `SELECT CAST('ab'::text AS char(5)), octet_length(CAST('ab'::text AS char(5)))`: PostgreSQL `'ab   '`, 5; frontend `'ab'`, 2

## Proposed Solutions

1. Pad to n on a cast or assignment to char(n), as a char(n) column already is when read.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done for the output: QueryParser.inferTypmod gives a cast to char(n), varchar(n) or numeric(p,s) its type modifier, so a char(n) cast is blank-padded on output (the existing padding of char(n) columns) and \\gdesc says character(5), numeric(10,2), character varying(3), as PostgreSQL 16 does; verified live. octet_length('ab'::char(5)) still gives 2 instead of 5: values are kept unpadded inside expressions, the documented CHAR(n) limit in LIMITATIONS. QueryParserTest covers the modifiers.
