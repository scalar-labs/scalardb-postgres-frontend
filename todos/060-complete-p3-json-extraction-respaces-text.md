---
status: complete
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

Solution 1. `Json.extracted` serves `->`, `->>`, `#>`, `#>>` and json_extract_path(_text). For a json
value that has its written text, it walks that text with Jackson's streaming parser along the same
steps and takes the member's substring. This applies to objects, arrays and numbers (`1e2` stays
`1e2`). Strings are re-escaped from the parsed node, as PostgreSQL re-escapes them (`"x\u0041"` comes
out as `"xA"`). With a duplicate key, the last member is taken, as PostgreSQL takes it.

## Acceptance Criteria

- [x] The three queries above give PostgreSQL 16's text. A 19-statement probe also matches
  PostgreSQL 16, except for two statements that fail for an unrelated reason: two JSqlParser quirks
  that also fail on the old jar (todo 066). The probe covers negative indexes, nested paths,
  duplicate keys, numbers, escaped strings, NULL and json_extract_path on an untyped literal.
- [x] `jsonb` extraction is unchanged (same probe). A QueryParserTest case pins both.

## Work Log

- 2026-10-17: Filed from todo 057 Phase 2.
- 2026-10-19: Done for json values that carry their text: literals, casts and earlier extractions.
  json_extract_path now reads an untyped text argument as json, not jsonb. A json column is still
  read as jsonb, and a jsonb column prints its text as stored; both are filed as todo 065. The
  difftest read, types, session, write and bind sets keep their baselines. The bind set needs
  data.sql reloaded first, since the write set changes dept and emp.
