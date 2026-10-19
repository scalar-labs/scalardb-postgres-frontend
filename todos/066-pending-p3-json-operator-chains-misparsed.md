---
status: pending
priority: p3
issue_id: "066"
tags: [json, parser]
dependencies: []
---

# Two JSON operator chains are misparsed by JSqlParser

Found on 2026-10-19 while doing todo 060. Both also fail on the jar from before 060.

| Query | PostgreSQL 16 | Frontend | JSqlParser's tree |
|---|---|---|---|
| `SELECT '[1,{"b":[3]}]'::json -> -1 -> 'b'` | `[3]` | `Not a JSON value: 1` | `j -> SignedExpression(-(1 -> 'b'))` |
| `SELECT ('{"a":{"b":1}}'::json -> 'a') -> 'b'` | `1` | `0A000 Unsupported expression` | `LambdaExpression` |

## Proposed Solutions

1. Rewrite the two trees after parsing: a SignedExpression whose operand is a JsonExpression, and a
   LambdaExpression whose parameter is a parenthesised expression. Effort: small to medium.
2. Fix the grammar upstream in JSqlParser. Effort: unknown.

## Acceptance Criteria

- [ ] Both queries give PostgreSQL 16's answer.

## Work Log

- 2026-10-19: Filed from todo 060.
