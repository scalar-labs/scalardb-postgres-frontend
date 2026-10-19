---
status: pending
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

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: The odd-number-of-hex-digits item (strings.sql #40) and the 22P02-vs-22023 bad hex digit (#41) are fixed by todo 081's bytea parser. The \\u escapes and adjacent literals remain.
