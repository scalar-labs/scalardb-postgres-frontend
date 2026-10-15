# Release notes

## Unreleased

### Declared types kept, `NUMERIC` exact

`NUMERIC(p,s)`, `DECIMAL`, `VARCHAR(n)`, `CHAR(n)` and `SMALLINT` columns keep their declaration and
PostgreSQL's rules: values are rounded to the scale, checked against precision, length and range with
PostgreSQL's SQLSTATEs, `CHAR` is padded, and psql `\d`, `information_schema`, `pg_catalog` and the driver's
RowDescription show the declared type. `NUMERIC` is exact: up to 18 digits it is stored as a scaled `BIGINT`
that keeps key and range pushdown; without a precision, or wider, as decimal text filtered in the frontend and
refused as a key or index column. The declaration is recorded in the ScalarDB column name
(`amount NUMERIC(10,2)` is `amount__pg_n10_2`); [docs/type-encoding.md](docs/type-encoding.md) is the
contract for other ScalarDB clients. Tables and columns created by earlier releases are unchanged, and
`NUMERIC` columns among them stay `double precision`.

Behaviour that changes:

- `id NUMERIC PRIMARY KEY` and `id NUMERIC(20) PRIMARY KEY`, accepted before as `double precision`, are
  refused with `0A000`: declare `NUMERIC(p,s)` with p up to 18.
- `CREATE TABLE` no longer sends the `NOTICE`s saying a modifier was dropped.
- A long column name with a type suffix can exceed ScalarDB's 56-byte limit (`42622`).

### Upgrading

**Mixed versions.** A frontend older than this release cannot tell an encoded column from an ordinary one: it
shows `amount__pg_n10_2` as a `bigint` holding 1990 and writes 19.90 into it as 19 or 20. During a rolling
upgrade, finish upgrading every frontend before the first `CREATE TABLE` or `ADD COLUMN` that declares one of
these types. There is no switch to turn the encoding off in this release.

**Columns that already look encoded.** A column created earlier whose name contains `__pg_` (unlikely, since
it must have been quoted) is now decoded by this release. List them on the backing database before upgrading;
on the JDBC storage with PostgreSQL:

```sql
SELECT table_schema, table_name, column_name
FROM information_schema.columns
WHERE column_name LIKE '%\_\_pg\_%'
  AND column_name NOT LIKE 'before\_%'   -- Consensus Commit's before-images
  AND table_schema NOT IN ('pg_catalog', 'information_schema')
ORDER BY 1, 2, 3;
```

On MySQL the same query works with `('mysql', 'information_schema', 'performance_schema', 'sys')` as the
excluded schemas; on other storages read the column names from
`DistributedTransactionAdmin.getTableMetadata`. Rename such a column outside the frontend, or expect it to be
shown under its decoded name and type (or under its full name, read-only, if the suffix is not a code this
release knows).
