---
status: complete
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

- [x] The statements above give PostgreSQL 16's answer, or an error. A subscripted assignment target
      (UPDATE SET, INSERT's column list, ON CONFLICT DO UPDATE SET) is refused with 0A000, and the column
      is left as it was.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them. The jsonb.sql subscript UPDATEs are now
      FE_ERR or CODE. #859 (a numeric subscript) answers 42804 as in PostgreSQL.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done.
  - **Root cause.** JSqlParser keeps a column's subscript in `Column.getArrayConstructor()`, and
    `columnName()` ignored it. `QueryParser.assignedColumn` now refuses a subscripted target; it is
    called for UPDATE SET and ON CONFLICT (through `checkSingleAssignments`) and for INSERT's column
    list.
  - **Reads were wrong too.** `SELECT j['b']` on a jsonb column returned NULL. `Evaluator.jsonSubscript`
    now reads a member by text key, an element by integer index (negative from the end), and a member by
    a numeric key on an object, as PostgreSQL does. `json` cannot be subscripted (42804), and neither can
    a numeric subscript.
  - **Checks.** A live run against PostgreSQL 16 gives the same reads and the same WHERE filter. jsonb.sql
    #849–873 now give PostgreSQL's values; they still count as DIFF only because of the column name
    (todo 092). Tests are in QueryParserTest. The `int[]` column issue from the same check is todo 087.
  - **Follow-up when a client needs it:** assigning to a subscript, as `jsonb_set(col, path, v, true)`
    (jsonb_set is not implemented either).
