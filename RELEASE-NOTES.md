# Release notes

## Unreleased

### Writes in WITH, and UPDATE of a primary key

`INSERT`, `UPDATE` and `DELETE` inside `WITH` (with or without `RETURNING`) now run, before the rest of
the statement and in its transaction. Refused with `0A000`, since PostgreSQL would run them on one
snapshot and the rest of the statement here would see their effect: one that is nested, under `WITH
RECURSIVE`, or whose table the statement reads or writes elsewhere. A transaction's read-only check now
goes by what a statement does, not its first word, so a write in `WITH` fails in a read-only transaction
(`25006`).

`UPDATE ... SET` on a primary-key column, refused before, now deletes the row under its old key and
inserts it under the new one, in the statement's transaction. A new key another row has, or one another
row of the same statement moves to or from (`SET id = id + 1` over adjacent keys), fails with `23505`.

### More functions

`split_part`, `string_to_array`, `repeat`, `chr`, `overlay`, `width_bucket`, `random`, `ln`, `log`,
`log10`, `exp` and `trunc` on `numeric` (with PostgreSQL's result scale), `stddev`, `variance` and
their `_samp`/`_pop` forms, `jsonb_set`, `jsonb_insert`, `jsonb_strip_nulls`, `date_bin`,
`make_timestamp`, and more `date_trunc` units. Timestamp text accepts `now`, `today`, `tomorrow`,
`yesterday` and `epoch`, and zone abbreviations and POSIX zones as PostgreSQL reads them; `infinity` is
refused. `regexp_replace` and `to_number` stay refused.

### Answers that were wrong

Differential testing against PostgreSQL 18 (SQLancer, and PostgreSQL's own regression tests) found
statements that answered differently from PostgreSQL without an error. They now answer as PostgreSQL
does or fail. Among them: `x NOTNULL` evaluated as `IS NULL`; a comma in `FROM` bound tighter than
`JOIN`; `upper` and `lower` under `BINARY` changing non-ASCII letters; `int4` overflow hidden by
parentheses; a `real` key joined with a `float8` one; the column types of `VALUES`; integer literals
past `int4` and negative ones; spaces inside quoted array elements; a `WHERE` equality moved below a
later `FULL` or `RIGHT JOIN`; a trailing escape in `LIKE` (now `22025`) and `SIMILAR TO`.

Behaviour that changes:

- `ROLLBACK TO SAVEPOINT` now undoes the `SET`s, `SET TRANSACTION READ ONLY` and `search_path` changes made
  since the savepoint, as in PostgreSQL.
- A constant expression PostgreSQL's planner evaluates and fails on (`1/0` in a `CASE` arm no row
  reaches), and an operator with no match for its operand types (`42883`), now fail while planning,
  also over a table with no rows, where they answered before.
- A statement nested more than 256 brackets deep, or taking more than 5 seconds to parse, fails with
  `54001`.

### A statement outside BEGIN is one transaction

A read outside `BEGIN` used to run each of its ScalarDB reads as a transaction of its own: a join's outer
scan and every lookup into the inner table. Under `SERIALIZABLE` nothing then validated the statement as a
whole, so a join next to a concurrent writer could combine rows from before and after that writer's
commit. Now each such statement runs in one ScalarDB transaction, read-only, begun at its first read
(`SELECT 1` begins none) and committed when its rows run out; a failed validation fails the statement with
`40001`. A join's lookups now run one after another, as they always did inside `BEGIN`, so a join fanning
out across many partitions outside `BEGIN` is slower than before. `scalar.db.postgres.max_lookup_threads`
is gone with the lookup pool.

A join's lookups that cannot share a scan now go to ScalarDB as one batch per group of outer rows (16,
doubling up to 128). Through ScalarDB Cluster that is one round trip instead of one per row: a join of
200 rows across 200 partitions took about 40 ms instead of 125 ms against a local node, in auto-commit
and inside `BEGIN` alike. Against ScalarDB directly a batch still reads its rows one by one.

### Privileges under Cluster authentication

The catalog answers were checked as a ScalarDB Cluster user with `SELECT` on one table. They are the same
as the superuser's, as in PostgreSQL, whose catalog every user can read; no user sees another's grants
wrongly. Three changes:
- **The table owner.** Tables are owned by `scalardb`, which `pg_roles` now lists in cluster mode too, as
  a superuser nobody can log in as. Before, the owner and the grantor in every ACL named a role that did
  not exist there. `\dp` now lists the owner's entry first, as PostgreSQL does.
- **`has_table_privilege`.** It now answers from the same grants: directly, through roles, or to
  PUBLIC. Before, it failed as unsupported.
- **DDL that records declared types checks the user's privilege first.** The rows of
  `scalardb_postgres_frontend.column_types` are written under the frontend's own authority before the table changes, so
  `ALTER TABLE ... ADD COLUMN` or `RENAME COLUMN` by a user without `ALTER` on the table used to retype it
  for every session until ScalarDB refused the change and the rows were taken back. `CREATE TABLE` (`CREATE`
  on the schema), `ADD COLUMN` and `RENAME COLUMN` (`ALTER` on the table) now fail with `42501` before any
  row is written. Grant no privileges on `scalardb_postgres_frontend`: `WRITE` there lets a user change how
  any table's columns are read.

### json and jsonb columns keep their type

A `json` or `jsonb` column used to become plain `text`: `\d` showed `text`, a `jsonb` value was stored as
written, and `->` on a `json` column answered as jsonb, with its keys sorted. The type is now recorded like
`NUMERIC(p,s)` (docs/type-encoding.md). `json` keeps its text as written, and `jsonb` is stored normalised,
as PostgreSQL stores it. Both are described with their own type, and conditions on them run in the
frontend. `json` has no `=`, ordering or grouping (42883), and `jsonb` compares by value, as in PostgreSQL.
Declaring one now needs the metadata table, as `NUMERIC(p,s)` does (`55000` until `CREATE FRONTEND METADATA
TABLES`). Neither can be a key or index column (`0A000`). Columns created by earlier releases stay `text`.

### Catalog answers: the truth or an error

A catalog query the frontend could not plan used to answer no rows, which a client cannot tell from "there
is none": SQLAlchemy reflected tables without primary keys or indexes, for one. Now a catalog query answers
what PostgreSQL 16 would answer for the same schema, or fails. The queries psql 14 and 18, pgjdbc,
SQLAlchemy, asyncpg, Django and Prisma send are kept as fixtures with PostgreSQL 16's answers, and the
tests check each one. `prisma db pull` now introspects the database. Before, its schema engine crashed
on three gaps:
- `information_schema.columns` had no `udt_schema` or `is_identity`;
- tables had no row type (`pg_class.reltype` was 0; each now has one in `pg_type`, with its array
  type);
- `generate_subscripts(indkey, 1)` was described as text, since `int2vector` and `oidvector` did not
  count as arrays when a call's types were resolved. psql's `\d` of an index, `\dconfig` and `\df+`, SQLAlchemy's `get_columns`, `get_pk_constraint`
and `get_indexes`, and `pg_get_keywords()` now answer as in PostgreSQL 16. `pg_tables`, `pg_user` and
`pg_timezone_names` are new; `pg_settings` shows the session's values and their source, and `SHOW`,
`SHOW ALL` and `current_setting` read the same settings. [LIMITATIONS.md](LIMITATIONS.md) lists the catalog
relations that are empty, and why.

`pg_stat_activity` lists the frontend's sessions: each one's user, database, application name, client,
state and current or last query. `pg_backend_pid()` returns the process ID the session was given at connect
(pgjdbc's `getBackendPID()`), where it returned 0. `to_char` formats timestamps, dates and numbers as
PostgreSQL 16 does, and `pg_blocking_pids()` answers an empty array, so pgAdmin's dashboard lists the
sessions.

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

**Setup.** After upgrading, run `CREATE FRONTEND METADATA TABLES` once through any frontend, as a superuser
when Cluster authentication is on. In a ScalarDB Cluster node the frontend reads and writes the table under
the node's own authority (Cluster's privileged mode): nothing is configured and no node restarts. The
standalone server with authentication runs through the Cluster client SDK and has no such authority: there
the statement also creates the service user `scalardb_postgres_frontend` and prints its password, to add as
`scalar.db.postgres.metadata_user` and `scalar.db.postgres.metadata_password` to the server's properties
before it restarts. Such a server with no password refuses to read tables once the metadata table exists
(`55000`). Running the command again keeps the existing password; rotate it with
`ALTER USER scalardb_postgres_frontend PASSWORD '...'`.

**Mixed versions.** A frontend older than this release does not read the metadata table: it shows a
`NUMERIC(10,2)` column as a `bigint` holding 1990 and writes 19.90 into it as 19 or 20. During a rolling
upgrade, finish upgrading every frontend before the first `CREATE TABLE` or `ADD COLUMN` that declares one of
these types. There is no switch to turn the recording off in this release.
