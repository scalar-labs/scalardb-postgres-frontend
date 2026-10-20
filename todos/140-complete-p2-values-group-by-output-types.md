---
status: complete
priority: p2
issue_id: "140"
tags: [types, values, group-by, differential]
dependencies: []
---

# VALUES with GROUP BY describes its columns with the wrong types

Found on 2026-10-20: `SELECT g, sum(x) FROM (VALUES (1, 1), (1, 4)) v(g, x) GROUP BY g` describes g
as bigint (PostgreSQL: integer) and sum(x) as numeric (PostgreSQL: bigint). The values are right;
a client reading by type (JDBC getObject, binary format) gets another type than PostgreSQL's.

## Acceptance Criteria

- [x] The output types match PostgreSQL's for grouped VALUES columns and aggregates over them.

## Findings

Not GROUP BY's: every VALUES column was typed by its Java values, so an integer literal (a Long)
made bigint, and sum over it numeric. A quoted literal in a typed column also stayed text, so
`VALUES ('x'), (9)` answered 'x' as an integer where PostgreSQL fails.

## Work Log

- 2026-10-20: QueryParser.values: each column's type from its typed items as unifiedOid gives it
      (as CASE), text for quoted literals and NULLs alone, and the quoted literals read as that
      type; 33 queries compared with PostgreSQL 18; QueryParserTest.
