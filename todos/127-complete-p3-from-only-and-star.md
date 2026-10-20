---
status: complete
priority: p3
issue_id: "127"
tags: [syntax, sqlancer]
dependencies: []
---

# FROM ONLY t and FROM t* failed to parse

Found on 2026-10-20: most of SQLancer's generated queries were refused with 42601 because they write
PostgreSQL's inheritance markers, `FROM ONLY t` and `FROM t*` (pg_dump writes `ALTER TABLE ONLY`).

## Acceptance Criteria

- [x] `ONLY t` (after FROM, JOIN, UPDATE, INTO, USING, TABLE or a comma) and `t*` in a FROM list read as `t`: the
      frontend has no table inheritance, so they mean the table alone. `t.*` and multiplications are untouched.

## Work Log

- 2026-10-20: Rewritten in QueryParser.normalize, outside literals; tests in HelpersTest.
