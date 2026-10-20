---
status: complete
priority: p2
issue_id: "138"
tags: [types, literals, differential]
dependencies: []
---

# Integer literals beyond bigint were described as bigint

Found on 2026-10-20 by differential testing of ln and log: the frontend computed
`ln(626325975980000000000000000000000000000)` as numeric but described it as float8, since
QueryParser.inferOid typed every integer literal of 32 bits or more as int8. PostgreSQL makes one
beyond bigint numeric, and folds minus signs into the constant, through parentheses:
`-(2147483648)` is int4, `-(-2147483648)` int8. `99999999999999999999 + 1` was described as bigint.

## Acceptance Criteria

- [x] inferOid: int4, int8, or numeric by the constant's value with its minus signs folded in; the
      evaluator folds through parentheses alike.

## Work Log

- 2026-10-20: QueryParser.integerOid; Evaluator's SignedExpression case unwraps; HelpersTest.
