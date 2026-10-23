# Clients and tools

The frontend speaks the PostgreSQL wire protocol, version 3, so PostgreSQL's drivers and tools connect to it
as they connect to PostgreSQL. This page lists the clients that have been tested and what to know about each.

## Connecting

Connect as you would connect to PostgreSQL, with the following values:

| Setting | Value |
|---------|-------|
| Host and port | The ScalarDB Cluster node's address and `scalar.db.postgres.port` (`5432` by default). With Kubernetes, the address of a Service in front of the nodes. |
| Database | A ScalarDB namespace. The client sees it as the schema `public`. |
| User and password | A ScalarDB Cluster user, with authentication enabled. For details, see [Security](security.md). |
| TLS | Required with authentication enabled. Use `sslmode=verify-full` in production. |

For example, the JDBC URL for the namespace `sales` is the following:

```text
jdbc:postgresql://pg.example.com:5432/sales?sslmode=verify-full&sslrootcert=/path/to/ca.pem
```

## Tested clients

The following drivers, ORMs and tools have been tested against the frontend. Each driver ran a typical
application session: connecting, reading the schema, creating a table, inserting with parameters, querying,
updating, deleting and running a transaction.

| Client | Status |
|--------|--------|
| psql | Works, including the describe commands (`\d`, `\dt`, `\di`, `\du`, `\dp` and the rest). |
| pgjdbc (PostgreSQL JDBC driver) | Works, including `DatabaseMetaData` for tables, columns, primary keys, indexes and type information. |
| psycopg 3 (Python) | Works. |
| SQLAlchemy 2 (Python) | Works, including declarative models and reflection. See [Reading rows that the transaction wrote](#reading-rows-that-the-transaction-wrote). |
| node-postgres (Node.js) | Works. |
| Sequelize 6 (Node.js) | Works, including `sync` and `describeTable`. See [Reading rows that the transaction wrote](#reading-rows-that-the-transaction-wrote). |
| pgx v5 (Go) | Works, with parameters and results in binary format. |
| pg and ActiveRecord 7 (Ruby) | Works, with primary keys that the application supplies. |
| Prisma 6 | `prisma db pull` reads the schema. |
| DBeaver 26 | Browses tables, columns, keys and indexes, and edits rows in the data grid. |
| BenchBase | Runs TPC-C with two small patches: one for a catalog query, one that reorders two statements to avoid reading rows the transaction wrote. See `bench/tpcc/README.md`. |

pgAdmin and DataGrip have not been tested.

<!-- TODO: Add Hibernate/Spring Data JPA, Flyway or Liquibase once tested. -->

## What to know

### Reading rows that the transaction wrote

Within a transaction, ScalarDB refuses a scan over records that the transaction has already written, and the
error message contains `DB-CORE-10106`. ORMs trigger this when they insert rows and then query the same
table in one transaction, such as SQLAlchemy's `create_all` followed by an insert and a select, or a Sequelize
transaction that reads back what it wrote. Read by primary key, use `RETURNING`, or commit before the query.
For details, see [Transactions](transactions.md#reading-rows-that-the-transaction-wrote).

### Schema migrations

DDL takes effect immediately and is not undone by `ROLLBACK`, so a migration that fails halfway leaves the
changes made before the failure. ScalarDB caches table metadata, so a migration tool that changes a table and
then uses it needs `scalar.db.metadata.cache_expiration_time_secs` set to 5 seconds or less; for details, see
[Configuration](configuration.md#scalardb-properties-that-matter). `ALTER TABLE` supports only `ADD COLUMN` and
`RENAME COLUMN`.

### Generated values and defaults

`serial` columns and column defaults do not generate values. ORMs that rely on the database to assign IDs,
such as an auto-increment primary key, need the application to supply them instead, for example as UUIDs.

### Decimal columns

ORMs often declare decimal columns with a large precision, such as Prisma's `Decimal`, which is
`DECIMAL(65,30)`, or Rails' `decimal` without options. Such columns are exact, but every condition on them
runs in the frontend. Declare a precision of 18 or less, such as `@db.Decimal(12,2)` in Prisma or
`precision: 12, scale: 2` in Rails, for columns that queries filter on.

### Passwords

The frontend asks clients for a password in clear text, inside TLS. libpq's `require_auth=scram-sha-256`
refuses that; use `require_auth=password` if you set it. psql's `\password` is refused; use
`ALTER USER ... PASSWORD` instead.

### Result metadata

A query result does not say which table its columns come from (`ResultSetMetaData.getTableName` in JDBC is
empty). Tools that rely on it to edit rows in a result grid treat the grid as read-only. DBeaver finds the
table from the query instead.

### Connection lifetime

With authentication enabled, a session ends when its login token expires (one day by default). Set your
connection pool's maximum lifetime below `scalar.db.cluster.auth.auth_token_expiration_time_minutes`.
