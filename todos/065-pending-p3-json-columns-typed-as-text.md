---
status: pending
priority: p3
issue_id: "065"
tags: [json, compatibility]
dependencies: []
---

# json and jsonb columns behave as each other's type

Found on 2026-10-19 while doing todo 060.

## Problem Statement

A json or jsonb column is TEXT in ScalarDB, and the evaluator sees only that text, so it does not know
which of the two the column is. This gives two wrong answers.

| Query (`j json`, `b jsonb`, both inserted as `{"z" : [1 ,2], "a" : {"y":1, "b" : 2}}`) | PostgreSQL 16 | Frontend |
|---|---|---|
| `SELECT j -> 'a'` | `{"y":1, "b" : 2}` | `{"b": 2, "y": 1}` (read as jsonb: keys sorted) |
| `SELECT b` | `{"a": {"b": 2, "y": 1}, "z": [1, 2]}` | the text as inserted |

`json_extract_path(j, ...)` is right, since the function's own type decides.

## Findings

- `Json.of(String)` reads any text as jsonb.
- The insert path stores jsonb text as written. PostgreSQL normalises jsonb on input: it sorts keys,
  removes duplicate keys and respaces the text.
- The declared type is in TypeStore and in the result's type OID (`inferOid`). The value the evaluator
  gets carries neither.

## Proposed Solutions

1. **Normalise on write, type on read.** Store jsonb as `Json.text(node, true)` on INSERT and UPDATE.
   On read, wrap json and jsonb column values in `Json.Value` with their declared type, where the row
   is built from ScalarDB's columns. Effort: medium.
2. **Document.** Effort: small.

## Acceptance Criteria

- [ ] The two queries above give PostgreSQL 16's text.
- [ ] Existing rows written before the change still read correctly.

## Work Log

- 2026-10-19: Filed from todo 060.
