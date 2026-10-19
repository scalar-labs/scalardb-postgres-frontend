---
status: complete
priority: p3
issue_id: "088"
tags: [json, wrong-result, regress-suite]
dependencies: []
---

# json values are re-serialized instead of kept as written

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

json keeps its text as written. json_array_elements(_text), and json values nested by json_build_array/object, write the value out again in the frontend's spacing. A nested jsonb is printed in json style.

## Findings

- json.sql #207–210, #342, #351, #377
- `json_array_elements(json '[[1,2], {"a" : 1}]')`: PostgreSQL `[1,2]`, frontend `[1, 2]`
- `json_build_object('e', '{"x": 3}'::jsonb)`: PostgreSQL `{"e" : {"x": 3}}`, frontend `{"e" : {"x" : 3}}`

## Proposed Solutions

1. Carry each json value's source text (as todo 060 does for extraction) and splice it in when nesting; print a nested jsonb in jsonb style.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-20: json results are built as text from their parts, as PostgreSQL's datum_to_json does: a json part as written, a jsonb part as jsonb prints, arrays compact, json_agg starting arrays on a new line, json_object_agg as { k : v }; array elements keep their text. Also: json_build_object with a non-scalar key fails with 22023 (from todo 091), a record as json is refused (0A000; PostgreSQL writes an object), and a set-returning call nested in an expression (json_array_elements(x)::text) is refused instead of collapsing its rows into one array. Regress vs PostgreSQL 18: json.sql #207-210, #342, #351, #377 SAME; no new failures in json, jsonb, arrays.
