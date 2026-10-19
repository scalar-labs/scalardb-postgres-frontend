---
status: pending
priority: p1
issue_id: "086"
tags: [json, arrays, dml, data-loss, regress-suite]
dependencies: []
---

# UPDATE SET col[subscript] = v overwrites the whole column

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`UPDATE t SET j['a'] = v` must change one member of j. The frontend drops the subscript and assigns v to the whole column, reporting success: silent data loss.

## Findings

- jsonb.sql #881–914 (11 statements), #894, #909, #968
- Verified on 2026-10-19: row `{"b": 2}`, then `UPDATE zz_sub SET j['a'] = '1' WHERE id = 1`: PostgreSQL `{"a": 1, "b": 2}`, frontend `1`
- `QueryParser.columnName()` strips `[...]` from the SET target. The same applies to array columns and to ON CONFLICT DO UPDATE SET.

## Proposed Solutions

1. Refuse a subscripted SET target with 0A000 at once; later rewrite it as `col = jsonb_set(col, path, v, true)` (and the array equivalent).
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
