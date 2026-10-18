# Release notes

## Unreleased

### Catalog answers: the truth or an error

A catalog query the frontend could not plan used to answer no rows, which a client cannot tell from "there
is none": SQLAlchemy reflected tables without primary keys or indexes, for one. Now a catalog query answers
what PostgreSQL 16 would answer for the same schema, or fails. The queries psql 14 and 18, pgjdbc,
SQLAlchemy, asyncpg and Django send are kept as fixtures with PostgreSQL 16's answers, and the tests check
each one. psql's `\d` of an index, `\dconfig` and `\df+`, SQLAlchemy's `get_columns`, `get_pk_constraint`
and `get_indexes`, and `pg_get_keywords()` now answer as in PostgreSQL 16. `pg_tables`, `pg_user` and
`pg_timezone_names` are new; `pg_settings` shows the session's values and their source, and `SHOW`,
`SHOW ALL` and `current_setting` read the same settings. [LIMITATIONS.md](LIMITATIONS.md) lists the catalog
relations that are empty, and why.

`pg_stat_activity` lists the frontend's sessions: each one's user, database, application name, client,
state and current or last query. `pg_backend_pid()` returns the process ID the session was given at connect
(pgjdbc's `getBackendPID()`), where it returned 0.

Behaviour that changes:

- A catalog relation the frontend does not have, such as `pg_locks` or `pg_stat_user_tables`, fails with
  `42P01` instead of answering no rows, qualified or not.
- `\dt`, `pg_table_is_visible` and SQLAlchemy's `get_table_names()` list only the tables on `search_path`,
  as in PostgreSQL. By default that is the connected namespace (schema `public`); before, they listed every
  namespace's tables. `SET search_path = public, sales` or `\dt sales.*` shows another namespace's tables.
- Newly declared catalog names, such as `pg_tables`, `pg_user`, `pg_views` and `pg_stats`, come before an
  unqualified user table of the same name, as in PostgreSQL; qualify the table (`public.pg_user`) to read it.
- A statement that cannot be planned fails at Describe, not only at Execute; before, Describe answered that
  it returns no rows.
- `pg_proc`, `pg_aggregate`, `pg_operator` and `pg_cast` list what the frontend implements, as PostgreSQL 16
  catalogs it, and a call's result is described with the type of the signature it matches, as PostgreSQL
  does. Types that change: `current_database()`, `current_schema()`, `current_user` and `pg_get_userbyid()`
  are `name`, not `text`; `date_part()` is `double precision`, not `numeric`; `date_trunc()`,
  `clock_timestamp()` and `gen_random_uuid()` are their timestamp or `uuid` type; `smallint` and `real`
  arithmetic stay `smallint` and `real`; `x::varchar` is `character varying`; `json -> x` stays `json`.
- A function called with a number of arguments none of its signatures takes fails with `0A000`, as do the
  text functions on `bytea` and `date_trunc` with a time zone, which answered wrongly before.
  `'f'::regproc` and `'f(text)'::regprocedure` name functions by OID, and `pg_typeof` works.
- Array text with an empty unquoted element (`'{a,,b}'`) fails with `22P02`, as in PostgreSQL; before, the
  element was an empty string.
- The catalog lists itself: `\dS`, JDBC's `getTables` and `getColumns`, `pg_class`, `pg_tables`, `pg_views`
  and `information_schema.tables`/`columns` include the `pg_catalog` and `information_schema` relations the
  frontend has, as PostgreSQL lists its own, so a tool that does not filter out `pg_catalog` sees them.
  `information_schema` is a schema, with its domains, and `pg_collation` and `pg_depend` have rows. A text
  column's collation is `default` (100), as in PostgreSQL, not 0.
- `ROLLBACK` undoes the `SET`s made in the transaction, and a `SET` after `SET LOCAL` outlasts the
  transaction, as in PostgreSQL. `SET`, `RESET` and `SET ... TO DEFAULT` of a setting PostgreSQL fixes, such
  as `server_version`, fail with `55P02`.

### Text in ScalarDB's collation

Text compared in the frontend (residual filters, `ORDER BY`, `GROUP BY`, `DISTINCT`, joins, set operations,
`min`/`max`) now follows ScalarDB's `scalar.db.collation`, as what is pushed down to ScalarDB does, so a query
answers the same whichever path it takes. Under the default `BINARY`, text orders by code point, which differs
from before only for characters outside the Basic Multilingual Plane, such as emoji. Under `ICU`
(`scalar.db.collation.icu.locale`), texts the locale calls equal are one value, as in a PostgreSQL column with a
nondeterministic ICU collation, which `pg_collation` and psql's `\d` show (see [Case-insensitive
text](getting-started/README.md#case-insensitive-text)).

Behaviour that changes:

- A column definition can name only `COLLATE "default"`; `COLLATE "C"` on a column, accepted before, is
  refused with `0A000`.
- Under `ICU`, `LIKE`, `ILIKE`, `SIMILAR TO`, regular expressions, `position`, `strpos`, `replace` and
  `starts_with` on table text fail with `0A000`, as in PostgreSQL 16, and `COLLATE` can name only the ICU
  collation.

### Declared types kept, `NUMERIC` exact

`NUMERIC(p,s)`, `DECIMAL`, `VARCHAR(n)`, `CHAR(n)` and `SMALLINT` columns keep their declaration and
PostgreSQL's rules: values are rounded to the scale, checked against precision, length and range with
PostgreSQL's SQLSTATEs, `CHAR` is padded, and psql `\d`, `information_schema`, `pg_catalog` and the driver's
RowDescription show the declared type. `NUMERIC` is exact: up to 18 digits it is stored as a scaled `BIGINT`
that keeps key and range pushdown; without a precision, or wider, as decimal text filtered in the frontend and
refused as a key or index column. The column keeps its name, and the declaration is recorded in the frontend's
own table, `scalardb_postgres_frontend.column_types` ([docs/type-encoding.md](docs/type-encoding.md) is the
contract for other ScalarDB clients). Tables and columns created by earlier releases are unchanged, and
`NUMERIC` columns among them stay `double precision`.

Behaviour that changes:

- Declaring one of these types needs the metadata table: run `CREATE FRONTEND METADATA TABLES` once, as a
  superuser when Cluster authentication is on. Until then such a `CREATE TABLE` or `ADD COLUMN` fails with
  `55000`; tables without them work as before.
- `id NUMERIC PRIMARY KEY` and `id NUMERIC(20) PRIMARY KEY`, accepted before as `double precision`, are
  refused with `0A000`: declare `NUMERIC(p,s)` with p up to 18.
- `CREATE TABLE` no longer sends the `NOTICE`s saying a modifier was dropped.

### Upgrading

**Setup.** After upgrading, run `CREATE FRONTEND METADATA TABLES` through any frontend. With Cluster
authentication on, run it as a superuser: it also creates the service user `scalardb_postgres_frontend` and
prints its password. Add the two properties it prints, `scalar.db.postgres.metadata_user` and
`scalar.db.postgres.metadata_password`, to every frontend's configuration and restart them. A frontend with
authentication on and no password refuses to read tables once the metadata table exists (`55000`). Running the
command again keeps the existing password; rotate it with `ALTER USER scalardb_postgres_frontend PASSWORD '...'`.

**Mixed versions.** A frontend older than this release does not read the metadata table: it shows a
`NUMERIC(10,2)` column as a `bigint` holding 1990 and writes 19.90 into it as 19 or 20. During a rolling
upgrade, finish upgrading every frontend before the first `CREATE TABLE` or `ADD COLUMN` that declares one of
these types. There is no switch to turn the recording off in this release.
