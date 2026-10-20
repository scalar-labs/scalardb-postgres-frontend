---
status: complete
priority: p3
issue_id: "125"
tags: [catalog, information_schema, sqlancer]
dependencies: []
---

# information_schema.tables lacked is_insertable_into

Found on 2026-10-20 by SQLancer, whose PostgreSQL schema reader selects `t.is_insertable_into` from
`information_schema.tables` (42703 here).

## Acceptance Criteria

- [x] `information_schema.tables.is_insertable_into` is YES for a table and NO for a view (the frontend does
      not write through views).

## Work Log

- 2026-10-20: Added; the pgjdbc fixture's count of declared catalog columns is 834.
