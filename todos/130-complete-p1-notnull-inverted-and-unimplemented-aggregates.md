---
status: complete
priority: p1
issue_id: "130"
tags: [wrong-result, sqlancer, differential]
dependencies: []
---

# x NOTNULL was x IS NULL; unimplemented aggregates answered no row

Found on 2026-10-20 by differential testing (SQLancer's statements mirrored to PostgreSQL 18).

## Problem Statement

1. `x NOTNULL`, PostgreSQL's postfix IS NOT NULL, was evaluated, and pushed down, as `x IS NULL`:
   JSQLParser marks that form with isUseNotNull(), not isNot(). `SELECT 1 NOTNULL` was false.
2. An aggregate PostgreSQL has and the frontend does not implement (bit_or, bit_and, bit_xor, stddev,
   variance, ...) ran as a plain function: over rows it failed, over no rows it answered no row instead of
   one NULL row.
3. real, double precision and numeric were cast to boolean when integral (PostgreSQL never does);
   COLLATE on a non-text value is refused when it is evaluated (the parser strips it elsewhere).

## Acceptance Criteria

- [x] NOTNULL is IS NOT NULL in evaluation and pushdown.
- [x] A PostgreSQL aggregate not implemented here fails with 0A000.
- [x] Only integer casts to boolean.

## Work Log

- 2026-10-20: Fixed; checked live against PostgreSQL 18.
