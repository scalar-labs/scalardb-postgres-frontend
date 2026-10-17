---
status: pending
priority: p3
issue_id: "060"
tags: [json, compatibility]
dependencies: []
---

# json extraction re-spaces the extracted text

Found on 2026-10-17 by difftest/catalog/builtins.py, while doing todo 057's Phase 2.

## Problem Statement

A `json` value (not `jsonb`) keeps its text as written. PostgreSQL's `->`, `->>`, `#>` and
`json_extract_path` return the extracted member's text unchanged. The frontend parses the value and writes
the member out again in its own spacing:

| Query | PostgreSQL 16 | Frontend |
|---|---|---|
| `SELECT '{"a":[1,2]}'::json -> 'a'` | `[1,2]` | `[1, 2]` |
| `SELECT '{"a":[1,2]}'::json ->> 'a'` | `[1,2]` | `[1, 2]` |
| `SELECT '{"a":{"b":1}}'::json #> '{a}'` | `{"b":1}` | `{"b" : 1}` |

The values are equal as JSON. A client that compares the text, or that shows the text to a user, sees
a difference.

## Findings

- `Json.Value` keeps `verbatim` for a literal, but an extraction builds its result from the parsed node
  and has no verbatim text.
- The generator kept `->` and `#>` in operators.txt, because another of their sample calls matched
  PostgreSQL exactly. It reports a difference only when no sample matches, so this one did not show.

## Proposed Solutions

1. **Raw text from the parser.** Parse a `json` value with Jackson's streaming parser, which gives each
   token's character offsets, and take the member's substring of the verbatim text. Effort: medium.
2. **Leave it.** Document that `json` extraction returns normalised text. Effort: small.

## Recommended Action

## Acceptance Criteria

- [ ] The three queries above give PostgreSQL 16's text.
- [ ] `jsonb` extraction is unchanged.

## Work Log

- 2026-10-17: Filed from todo 057 Phase 2.
