---
status: pending
priority: p2
issue_id: "081"
tags: [bytea, wrong-result, regress-suite]
dependencies: []
---

# bytea input without \x is decoded as hex

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

PostgreSQL reads bytea input that does not start with `\x` as escape format: plain bytes, `\\`, `\nnn`. The frontend decodes it as hex.

## Findings

- strings.sql #43, #52 (#44–46, #53–54 fail for the same reason)
- `SELECT 'DeAdBeEf'::bytea`: PostgreSQL `\x4465416442654566`, frontend `\xdeadbeef`
- `SELECT 'hello'::bytea`: PostgreSQL `\x68656c6c6f`, frontend error

## Proposed Solutions

1. Take the hex path only for input starting with `\x`; otherwise parse the escape format.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
