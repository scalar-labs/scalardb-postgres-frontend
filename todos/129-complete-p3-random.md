---
status: complete
priority: p3
issue_id: "129"
tags: [functions, sqlancer]
dependencies: []
---

# random() was not implemented

SQLancer's PQS oracle picks its pivot row with `ORDER BY RANDOM()` and never ran; random() is also among
the commonly used functions the regression run found refused (FE_ERR).

## Acceptance Criteria

- [x] random() (PostgreSQL 16's, oid 1598: double precision in [0, 1), volatile) is listed in pg_proc and
      information_schema.routines and evaluated per call.

## Work Log

- 2026-10-20: Added; client fixtures count 252 functions (182 for pgjdbc's function listing).
