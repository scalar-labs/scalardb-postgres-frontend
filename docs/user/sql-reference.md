# SQL reference

The frontend accepts PostgreSQL 16's SQL dialect and reports itself to clients as PostgreSQL 16.0. This page
summarizes what it supports. What it refuses, and where its results can differ from PostgreSQL, is listed in
[LIMITATIONS.md](../../LIMITATIONS.md).

The frontend follows one rule: what it cannot do raises an error, and what it accepts but cannot fully honor
is announced with a `NOTICE`. It does not silently return a different result from PostgreSQL, except in the
few cases that [LIMITATIONS.md](../../LIMITATIONS.md#results-that-can-differ-without-an-error) lists.

## Databases and schemas

The database name in a connection is a ScalarDB namespace, and so is a schema. A client connected to the
database `sales` sees the namespace `sales` as the schema `public`, as tools that assume `public` expect,
and can still qualify tables as `sales.orders`. Other namespaces are reached by qualifying a table name or by
setting `search_path`, such as `SET search_path = public, inventory`.

## Statements

| Category | Supported |
|----------|-----------|
| Queries | `SELECT` with joins (inner, left, right, full, cross, natural, `LATERAL`), subqueries, `WITH` and `WITH RECURSIVE`, `UNION`, `INTERSECT`, `EXCEPT`, `GROUP BY`, `HAVING`, window functions, `DISTINCT` and `DISTINCT ON`, `ORDER BY`, `LIMIT` and `OFFSET`, `VALUES` |
| Writes | `INSERT` (including multi-row `VALUES` and `INSERT ... SELECT`), `UPDATE`, `DELETE`, `INSERT ... ON CONFLICT DO NOTHING` and `DO UPDATE`, `RETURNING`, and `INSERT`, `UPDATE` or `DELETE` inside `WITH` |
| Tables | `CREATE TABLE`, `DROP TABLE`, `TRUNCATE`, `ALTER TABLE ... ADD COLUMN`, `ALTER TABLE ... RENAME COLUMN`, `CREATE INDEX`, `DROP INDEX` |
| Schemas | `CREATE SCHEMA`, `DROP SCHEMA` |
| ScalarDB setup | `CREATE COORDINATOR TABLES`, `DROP COORDINATOR TABLES`, `CREATE FRONTEND METADATA TABLES` |
| Transactions | `BEGIN`, `START TRANSACTION`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `RELEASE SAVEPOINT`, `ROLLBACK TO SAVEPOINT`, `SET TRANSACTION`, read-only transactions |
| Users and privileges | `CREATE USER`, `CREATE ROLE`, `ALTER USER`, `ALTER ROLE`, `DROP USER`, `DROP ROLE`, `GRANT`, `REVOKE`, with ScalarDB Cluster authentication enabled. For details, see [Security](security.md). |
| Session | `SET`, `SET LOCAL`, `SHOW`, `RESET`, `DISCARD`, `DEALLOCATE` |
| Other | `EXPLAIN`, `EXPLAIN ANALYZE`; `VACUUM` and `ANALYZE` are accepted and do nothing |

The following are not supported and fail with an error: `COPY`, views, sequences, cursors (`DECLARE`,
`FETCH`), `MERGE`, functions and procedures (`CREATE FUNCTION`), triggers, `COMMENT ON`, `LISTEN` and
`NOTIFY`, temporary tables, `SELECT ... INTO`, `TABLESAMPLE`, and `ALTER TABLE` forms other than
`ADD COLUMN` and `RENAME COLUMN`.

`CREATE TABLE` options specific to ScalarDB go in `WITH`: `partition_key`, `clustering_key` and, in ScalarDB
Cluster with encryption enabled, `encrypted`. For details, see
[Designing tables and queries](schema-and-queries.md#how-a-table-maps-to-scalardb) and
[Security](security.md#encrypted-columns).

Drivers can use the extended query protocol: prepared statements with parameters, in text or binary format,
as pgjdbc, psycopg, node-postgres and pgx send them.

## Data types

The following types can be used for columns. "Can be a key" means that the column can be part of the primary
key or have an index, as far as the frontend is concerned; some backing databases restrict key and index
types further, as described in the ScalarDB documentation.

| Type | Stored in ScalarDB as | Can be a key | Notes |
|------|-----------------------|--------------|-------|
| `boolean` | `BOOLEAN` | Yes | |
| `smallint` | `INT` | Yes | Range checked as in PostgreSQL. |
| `integer`, `int` | `INT` | Yes | |
| `bigint` | `BIGINT` | Yes | |
| `real` | `FLOAT` | Yes | |
| `double precision` | `DOUBLE` | Yes | |
| `numeric(p,s)`, `decimal(p,s)` with `p` up to 18 | `BIGINT`, scaled | Yes | Exact. Up to 15 digits in ScalarDB Cluster. |
| `numeric`, `numeric(p,s)` with `p` over 18 | `TEXT` | No | Exact, but every comparison, sort and grouping on it runs in the frontend. |
| `char(n)`, `character(n)` | `TEXT` | Yes | Padded with spaces as in PostgreSQL. |
| `varchar(n)`, `character varying(n)` | `TEXT` | Yes | Length checked as in PostgreSQL. |
| `text` | `TEXT` | Yes | |
| `uuid` | `TEXT` | Yes | The column is a `text` column. |
| `bytea` | `BLOB` | Yes | |
| `date` | `DATE` | Yes | Years 1000 to 9999. |
| `time` | `TIME` | Yes | Microseconds, as in PostgreSQL. |
| `timestamp`, `timestamp(p)` | `TIMESTAMP` | Yes | Milliseconds. A value with a finer fraction is refused unless the column is declared `timestamp(3)` or coarser, which rounds it. |
| `timestamptz`, `timestamp with time zone` | `TIMESTAMPTZ` | Yes | Milliseconds, as for `timestamp`. |
| `json`, `jsonb` | `TEXT` | No | Kept as their PostgreSQL types, with PostgreSQL's operators and functions. |

`serial` and `bigserial` create `integer` and `bigint` columns that do not generate values. Array types,
`interval`, `time with time zone`, `money`, `inet`, `xml` and other types are refused as column types.
Arrays, intervals and other types still work as values in queries, for example `ARRAY[1, 2]` or
`now() - interval '1 day'`.

`numeric`, `char(n)`, `varchar(n)`, `smallint`, `json` and `jsonb` columns record their declared type in the
frontend's metadata table, so `CREATE FRONTEND METADATA TABLES` must have run before the first such column is
created. Other ScalarDB clients that read these columns see the stored values, such as `1990` for a
`numeric(10,2)` value of `19.90`. For how they are stored, see
[docs/type-encoding.md](../type-encoding.md).

## Functions and operators

The frontend implements about 165 of PostgreSQL's functions, with PostgreSQL's operators and casts between
the types above. psql's `\df`, `\do` and `\dC` list exactly those that the frontend implements, as do
`pg_proc`, `pg_operator` and `pg_cast`. A call to a function outside that list fails with SQLSTATE `0A000`.

The following aggregates are supported: `array_agg`, `avg`, `bool_and`, `bool_or`, `count`, `every`,
`json_agg`, `json_object_agg`, `jsonb_agg`, `jsonb_object_agg`, `max`, `min`, `stddev`, `stddev_pop`,
`stddev_samp`, `string_agg`, `sum`, `var_pop`, `var_samp` and `variance`, with `FILTER`, `DISTINCT` and
`ORDER BY` inside the call.

The following window functions are supported: `cume_dist`, `dense_rank`, `first_value`, `lag`, `last_value`,
`lead`, `nth_value`, `ntile`, `percent_rank`, `rank` and `row_number`, and every aggregate above as a window
function. `RANGE` and `GROUPS` frames with offsets are not supported.

Some functions are refused rather than approximated, because their results depend on rules that the frontend
has not ported: `regexp_replace` and the other regular-expression functions that return what matched,
`to_number`, `sqrt(numeric)`, ordered-set aggregates such as `percentile_cont`, and `make_timestamptz` with
a time zone argument.

## Text comparison

Text compares in ScalarDB's collation, which is set by `scalar.db.collation`: byte order (`BINARY`, the
default, like PostgreSQL's `C` collation) or an ICU locale (`ICU`), for example case-insensitive. Under
`ICU`, pattern matching on table text (`LIKE`, `ILIKE`, `SIMILAR TO`, regular expressions, `position`,
`strpos`, `replace` and `starts_with`) is refused, as PostgreSQL refuses it on a nondeterministic collation.
The backing database must be set up to compare text the same way. For details, see
[LIMITATIONS.md](../../LIMITATIONS.md).

## Catalog

The frontend builds `pg_catalog` and `information_schema` from ScalarDB's metadata, so psql's describe
commands (`\l`, `\dn`, `\dt`, `\d`, `\di`, `\du`, `\dp` and the rest), drivers' metadata calls and ORMs'
schema reflection work. A catalog query answers what is true or fails: a catalog table that the frontend
does not have fails with SQLSTATE `42P01`, as an unknown table does, instead of answering with no rows.

## Error codes

Errors carry PostgreSQL's SQLSTATE codes, so applications can handle them as they would with PostgreSQL.
The codes that are specific to how the frontend works are the following:

| SQLSTATE | Meaning |
|----------|---------|
| `0A000` | The statement uses a feature that the frontend does not support. |
| `40001` | The transaction conflicted with another one. Retry it. For details, see [Transactions](transactions.md). |
| `40003` | The outcome of `COMMIT` is unknown, because a backing database was unreachable. |
| `42501` | The user lacks a privilege, or a limit that only an operator may raise was exceeded. |
| `54000` | A query needed more rows in memory than `scalar.db.postgres.max_rows_in_memory`. |
| `54001` | A statement was nested too deeply or took too long to parse. |
| `55000` | The frontend's metadata table is missing, or, for the standalone server with authentication, its service user's password is not configured. |
