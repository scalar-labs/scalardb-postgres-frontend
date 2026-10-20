---
status: complete
priority: p2
issue_id: "143"
tags: [with, dml, step-4, differential]
dependencies: []
---

# Data-modifying statements in WITH (step 4)

`WITH d AS (DELETE ... RETURNING ...) SELECT ... FROM d` and the INSERT and UPDATE forms were
refused.

## Acceptance Criteria

- [x] A top-level WITH query that inserts, updates or deletes runs once, before the statement, in
      the statement's transaction (its own outside BEGIN, committed after the statement's rows are
      read; rolled back with everything if anything fails); its RETURNING rows are its table.
- [x] Refused (0A000) where running it first could show what PostgreSQL's single snapshot would
      not: the statement (or another WITH write) mentions the modified table elsewhere; also a
      nested data-modifying WITH (as PostgreSQL) and WITH RECURSIVE; a reference to one without
      RETURNING fails as in PostgreSQL.
- [x] numeric(p,s) columns read through the statement's column maps in the WITH writes too.

## Work Log

- 2026-10-20: QueryParser.writingCtes, Plan.Prelude, QueryProcessor.withWrites; 28 statements
      (incl. BEGIN/ROLLBACK and a failing statement) compared with PostgreSQL 18.
