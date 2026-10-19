---
status: complete
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

## Recommended Action

Two fixes, one for each misreading:

- **The sign:** `Evaluator.jsonSteps` and `lastJsonOperator` unwrap a step whose SignedExpression
  hides the rest of the chain, and give the sign to the first step.
- **The lambda:** a tree rewrite was tried first and dropped, for two reasons. The lambda's body takes
  in the rest of the expression (`(j -> 'a') -> 'b' -> 0 = x` has the body `'b' -> 0 = x`). And
  columns inside the lambda's text are not seen by planning, so `j` was not fetched and the WHERE
  matched nothing. So `QueryParser.normalize` now rewrites the text before parsing: `(X) ->` becomes
  `COALESCE((X)) ->`, which has the same value and type and parses as a JSON chain. A function call's
  parentheses, `->>`, literals, quoted names and comments are left alone. EXPLAIN shows the
  `COALESCE((...))`.

## Acceptance Criteria

- [x] Both queries give PostgreSQL 16's answer. A 15-statement probe also matches PostgreSQL 16. It
  covers jsonb chains, `->>` after a parenthesised operand, nested parentheses, a scalar subquery, a
  WHERE on a table column, the unaliased column name `?column?`, and `\gdesc` types.

## Work Log

- 2026-10-19: Filed from todo 060.
- 2026-10-19: Done. Tested by HelpersTest (the text rewrite) and QueryParserTest (both chains). The
  difftest read, types, session, character, numeric, bind, write and SQL-92 s01, s03-s07 and s10 sets
  keep their baselines.
