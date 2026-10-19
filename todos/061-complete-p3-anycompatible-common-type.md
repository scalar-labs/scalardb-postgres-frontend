---
status: complete
priority: p3
issue_id: "061"
tags: [catalog, types, compatibility]
dependencies: ["057"]
---

# anycompatible results take the first argument's type, not the common type

Found on 2026-10-17 in the review of todo 057's Phase 2.

## Problem Statement

For a signature with `anycompatible` or `anycompatiblearray` parameters, PostgreSQL resolves the result to
the common type of all those arguments. `Builtins.resolved` takes the type of the first polymorphic
argument instead.

| Query | PostgreSQL 16 | Frontend |
|---|---|---|
| `SELECT array_append(ARRAY[1], 2.5)` | `numeric[]` | `integer[]` |

The frontend describes the column with one type and sends a value of another, so a driver may decode
it wrongly.

`Catalog.arrayOid` also falls back to `text[]` for an element type that has no array type in
`ARRAY_OIDS`.

## Proposed Solutions

1. **Common type.** For the `anycompatible` family, take PostgreSQL's common type of the arguments: the
   preferred type of their category when they differ (numeric beats int, float8 beats numeric), as
   `QueryParser.unifiedOid` does for CASE and COALESCE. Effort: small.
2. **Leave it.** Only array functions given mixed argument types hit it. Effort: none.

## Recommended Action

Solution 1. The frontend does not run `array_append` itself; the calls with anycompatible parameters it
does run are the array `||` operators and lag/lead with a default. `Builtins.resolved` now takes the
common type of the anycompatible arguments: the type the others cast to implicitly. Two more fixes
were needed:

- **Operator choice:** `ARRAY[1] || ARRAY[2.5]` picked array_append, whose `anycompatible` also takes
  an array. It was described as `integer[]`, and in binary 2.5 came back as 2. An array argument now
  fits `anyarray` closer, so array_cat is chosen, as in PostgreSQL.
- **`Catalog.arrayOid`:** given an array element, such as `ARRAY[ARRAY[1]]`, it returns that array type
  (`integer[]`), not the `text[]` fallback.

## Acceptance Criteria

- [x] `ARRAY[1] || 2.5`, the operator behind `array_append`, is described as `numeric[]`, and its
  value is numeric. psycopg's binary results match PostgreSQL 16 for `|| numeric`, `|| numeric[]`,
  `|| bigint`, `|| float8` and the prepend form. A BuiltinsTest case pins the types.

## Work Log

- 2026-10-17: Filed from the review of todo 057 Phase 2 (finding C6).
- 2026-10-19: Done. Still different from PostgreSQL, and the same before this change:
  `ARRAY['a'] || 1` returns `{a,1}` where PostgreSQL has no such operator, since two types with no
  implicit cast either way keep the first (marked ponytail in `Builtins.common`). `ARRAY[1] || '2'`
  is described before it fails at execution. The difftest read, types, session, character, numeric
  and bind sets keep their baselines.
