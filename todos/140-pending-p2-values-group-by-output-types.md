---
status: pending
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

- [ ] The output types match PostgreSQL's for grouped VALUES columns and aggregates over them.
