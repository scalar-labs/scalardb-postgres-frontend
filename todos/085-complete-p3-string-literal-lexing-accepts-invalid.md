---
status: complete
priority: p3
issue_id: "085"
tags: [strings, validation, regress-suite]
dependencies: []
---

# Invalid string and bytea literals are accepted

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

Several malformed literals that PostgreSQL rejects are accepted.

## Findings

- strings.sql #40: `E'\\xDeAdBeE'::bytea` (odd hex digits): PostgreSQL 22023, frontend `\xdeadbe`; a bad hex digit gives 22P02 instead of 22023 (#41)
- strings.sql #20–26: `E'x\u061'`: PostgreSQL 22025, frontend `xu061`; a lone surrogate `E'x\udb99'`: PostgreSQL 42601, frontend `x?`
- strings.sql #1: `SELECT 'a' /* c */ 'b'`: PostgreSQL 42601, frontend `a` with the column named b (a string literal must never be an alias; adjacent literals join only across a newline without comments)

## Proposed Solutions

1. Validate in the lexer and bytea input as PostgreSQL does.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: The odd-number-of-hex-digits item (strings.sql #40) and the 22P02-vs-22023 bad hex digit (#41) are fixed by todo 081's bytea parser. The \\u escapes and adjacent literals remain.
- 2026-10-20: E'' escapes now make bytes that must be valid UTF-8 together (22021, so E'\xc3\xa9' is é and E'\xDE' fails, as in PostgreSQL); \u and \U need all their hex digits (22025); surrogates must pair (42601); \u0000 and code points past U+10FFFF fail (42601). Adjacent literals join only across a newline and -- comments; on one line, across /* */, or after a dollar quote they fail with 42601 instead of being read as an alias. Regress (strings, text, json vs PostgreSQL 18): strings.sql #1, #20-26, #40, #41 now SAME; nothing newly refused.
