---
status: complete
priority: p3
issue_id: "091"
tags: [json, arrays, validation, regress-suite]
dependencies: []
---

# Invalid json, jsonb and array input is accepted

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

Malformed values that PostgreSQL rejects are accepted.

## Findings

- json.sql #43, #44; jsonb.sql #45, #46: `'true, false'::jsonb`: PostgreSQL 22P02, frontend `true` (trailing content after the value)
- json #354, #371, #372; jsonb #246, #263, #264: `json_build_object('{1,2,3}'::int[], 3)`: PostgreSQL 22023 key value must be scalar, frontend `{"{1,2,3}" : 3}`
- arrays.sql #373, #374: `array_agg('{}'::int[])`: PostgreSQL 2202E; `array_agg(null::int[])`: PostgreSQL 22004
- arrays.sql #261–263: `'{}}'::text[]`: PostgreSQL 22P02, frontend `{"}"}`; `array[]` without a type: PostgreSQL 42P18
- arrays.sql #41: more than 6 subscripts: PostgreSQL 54000, frontend NULL

## Proposed Solutions

1. Fail on trailing tokens after a JSON value (json too, not only jsonb); add the other checks with PostgreSQL's SQLSTATEs.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: Refused as PostgreSQL does: content after a json value (22P02), \u0000 in jsonb (22P05), array_agg of an empty or NULL array (2202E, 22004), an unmatched } or an unterminated quote in array text (22P02), ARRAY[] without a cast around it (42P18). Array text now reads a backslash outside quotes as an escape ({a\,b} is one element). The json_build_object key check came with todo 088; more than 6 subscripts fails, with 42601 instead of 54000. Left: array_agg over only NULL arrays whose type the evaluator cannot see (a column of VALUES (null::int[])) still gives {NULL}. Regress vs PostgreSQL 18: json #43-44, jsonb #45-46, arrays #261-263, #373-374 SAME; no new failures.
