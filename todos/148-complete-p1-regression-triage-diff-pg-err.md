---
status: complete
priority: p1
issue_id: "148"
tags: [regression-tests, wrong-result, transactions, time-zones]
dependencies: ["147"]
---

# Every DIFF and PG_ERR of the regression run (full4), triaged

The user asked that the DIFF and PG_ERR statements be fixed. 99 of them are not marked cascades;
each was read. Fixed:

## Acceptance Criteria

- [x] ROLLBACK TO SAVEPOINT kept SET, SET LOCAL and SET TRANSACTION made after the savepoint
      (SHOW transaction_read_only was on where PostgreSQL's is off): a savepoint now keeps the
      settings and transaction characteristics, restored with it (search_path re-applied).
- [x] SET timezone = '04:30' (unsigned hh:mm) read east of UTC: it is POSIX, -04:30, as
      PostgreSQL reads it (a bare number of hours stays east).
- [x] A recursive WITH whose step changes the column's type: numeric(3,0) against numeric(10,0),
      varchar(5) against text, int4 against int8 now fail with 42804 as in PostgreSQL; an int8
      anchor with an int2 step, wrongly refused, now runs.

## Not frontend bugs (left)

- Order SQL leaves open: string_agg without ORDER BY, window frames over ties or unordered
  partitions, LIMIT without ORDER BY (aggregates 280-281, window 17, 71, 241, 266, 282).
- Cascades the harness does not flag: tables or rows only PostgreSQL could create (CREATE RULE,
  DROP COLUMN, table functions, pg_temp, a refused isolation level), and ScalarDB's DDL not being
  transactional (transactions 11, 95; documented).
- PostgreSQL 18's infinite interval (interval 312-318); PostgreSQL's index row size limit
  (strings 309-319); pg_relation_size of a TOAST table the frontend does not have (NULL).
- PostgreSQL fails at plan time where the frontend fails only when a row reaches the expression:
  `CASE WHEN i > 100 THEN 1/0 ELSE 0 END`, `f1 + time '00:01'` over an empty table. An error in
  PostgreSQL, an answer here only when no row evaluates it; constant folding would be needed.

## Work Log

- 2026-10-20: QueryProcessor.Savepoint, POSIX_ZONE; QueryParser.recursive's type check; tests.
