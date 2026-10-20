---
status: complete
priority: p3
issue_id: "098"
tags: [types, regress-suite]
dependencies: []
---

# The "char" type: escapes and column label

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

PostgreSQL's one-byte `"char"` type reads `\ooo` octal escapes, `\000` is empty, and a cast to it is labelled char.

## Findings

- char.sql #23–31
- `'\101'::"char"`: PostgreSQL A, frontend `\101`; the column label: PostgreSQL char, frontend `"char"`

## Proposed Solutions

1. Parse octal escapes in "char" input; label the cast char.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: "char" input follows charin (\ooo is that byte, anything else its first byte, '' is the zero byte) and keeps a byte past ASCII as its charout text \ooo; the column is labeled char. char.sql #23-31 now SAME (vs PostgreSQL 18). Left: "char" to integer ('A'::"char"::int, 65 in PostgreSQL) fails with 22P02.
