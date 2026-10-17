---
status: pending
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

## Acceptance Criteria

- [ ] `array_append(ARRAY[1], 2.5)` is described as `numeric[]`, and its value is numeric.

## Work Log

- 2026-10-17: Filed from the review of todo 057 Phase 2 (finding C6).
