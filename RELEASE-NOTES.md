# Release notes

## Unreleased

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
