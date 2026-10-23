---
status: pending
priority: p3
issue_id: "152"
tags: [sql, joins, name-resolution]
dependencies: []
---

# References into an unnamed parenthesized join: USING columns, nested joins, a.*

Found by /code-review of 7d6cfac..aa30b53 (the pgAdmin support), left open in 43e31cb.

An unaliased `(a JOIN b ON ...)` in FROM, as pgAdmin writes `LEFT OUTER JOIN (pg_depend JOIN
pg_class cs ON ...) ON ...`, is planned as `SELECT *` over the join: one derived source,
`unnamed_join_<hash>`, whose columns are named `a․x`, `b․y` (`QueryParser.qualifiedColumn`;
JSQLParser splits a column name at a dot). `Scope.resolve` binds `a.x` and a bare `x` to it. Three
forms PostgreSQL accepts fail with 42703 Unknown column:

- a column merged by USING: in `x JOIN (a JOIN b USING (id)) ON ...` the merged column is a bare
  `id`, so `a.id` does not match (`id` alone works);
- a nested unnamed join: in `x JOIN (a JOIN (b JOIN c ON ...) ON ...) ON ...` the inner join's columns
  come out as `unnamed_join_<hash>․b․y`, so `b.y` does not match;
- `a.*`: `a` is not a source of the enclosing query (`Scope.source`), so `SELECT a.*` fails.

## Impact

None of the clients in the fixtures (psql, pgjdbc, DBeaver, SQLAlchemy, asyncpg, Django, Prisma,
pgAdmin) sends these. Each fails with an error, none answers wrongly.

## Fix

Keep, per unnamed join source, the inner qualifiers and the columns each contributed (USING-merged
ones under both sides, nested joins flattened), and resolve `a.x` and `a.*` through that map rather
than by the `a․x` name. Column resolution runs for every query, so the fix wants tests for the
three forms and the existing ones (`QueryProcessorTest.pgAdminQueries_ShouldBeAnswered`).
