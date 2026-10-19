---
status: complete
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

## Recommended Action

Solution 1, decided with the user on 2026-10-19: json and jsonb are recorded types, like
numeric(p,s), so declaring one needs the metadata table (55000 without it).

- **Storage.** `ColumnType.Kind.JSON` and `JSONB` have TypeStore rows (`JSON/TEXT`, `JSONB/TEXT`). json
  is validated and stored as written. jsonb is stored normalised (`Json.text(node, true)`), and a value
  written unnormalised by another client still reads as jsonb.
- **Reading and describing.** Values are read back as `Json.Value` of the declared type, and described
  as OID 114 and 3802.
- **Conditions.** Conditions on these columns run in memory, and neither type can be a key or index
  column (0A000).
- **Equality.** json has no equality or ordering in PostgreSQL. `Evaluator.compare` and
  `Operators.normalize` now raise 42883 for json, so `=`, `ORDER BY`, `DISTINCT`, `GROUP BY`, `UNION`
  and `IN` on json fail. Before, they answered.
- **jsonb hash.** `Json.Value.hashCode` now agrees with `equals`, so `[1]` and `[1.0]` are one member
  of a jsonb `DISTINCT` or `GROUP BY`, as in PostgreSQL.

## Acceptance Criteria

- [x] The two queries above give PostgreSQL 16's text. A 31-statement probe also matches PostgreSQL 16.
  It covers both column types, duplicate keys, `->`/`->>`/`#>`, jsonb `=`, `@>`, `pg_typeof`, `\gdesc`,
  `format_type`, UPDATE, NULL, and json in `=`, `ORDER BY`, `DISTINCT`, `GROUP BY` and `UNION`. Two
  kinds of difference remain:
  - the wording of the invalid-JSON and 42883 messages (same SQLSTATEs);
  - which of two equal jsonb values `DISTINCT` shows (`[1]` or `[1.0]`), which follows row order.
- [x] Existing rows written before the change still read correctly: columns created by earlier
  releases have no TypeStore row and stay text, as before.

## Work Log

- 2026-10-19: Filed from todo 060.
- 2026-10-19: Done. Tested in ColumnTypeTest (row round trip, write and read rules, XX001 for
  invalid stored JSON, keyability) and HelpersTest (json has no equality, jsonb hash). Docs:
  docs/type-encoding.md, LIMITATIONS, RELEASE-NOTES and the difftest fe_init comment. The difftest
  read, types, session, character, numeric, bind and write sets, and the SQL-92 s01-s04 and s06-s08
  sets, keep their baselines.
