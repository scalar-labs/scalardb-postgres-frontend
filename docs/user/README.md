# ScalarDB PostgreSQL frontend: user documentation

The PostgreSQL frontend lets psql, the PostgreSQL JDBC driver and other PostgreSQL clients run SQL as
ScalarDB transactions. It runs inside ScalarDB Cluster nodes, which is the recommended deployment, or as a
standalone server on ScalarDB core.

## Get started

- [Quick start](quick-start.md): run a ScalarDB Cluster node with the frontend in Docker, and connect with psql.
- [Getting started with the standalone server](../../getting-started/README.md): run the frontend on ScalarDB
  core, without ScalarDB Cluster.

## Use

- [Designing tables and queries](schema-and-queries.md): how tables map to ScalarDB, which queries run in the
  database, and how to check them with `EXPLAIN`.
- [SQL reference](sql-reference.md): statements, data types, functions and error codes.
- [Transactions](transactions.md): optimistic transactions, retries, isolation levels and their differences
  from PostgreSQL.
- [Clients and tools](clients.md): tested drivers, ORMs and tools, and what to know about each.

## Configure and operate

- [Configuration](configuration.md): every property, session setting and fixed limit.
- [Security](security.md): authentication, TLS, users, privileges, encryption and ABAC.
- [Operations](operations.md): connections across nodes, sizing, monitoring, upgrades and troubleshooting.

## Reference

- [Limitations](../../LIMITATIONS.md): what the frontend refuses, announces with a `NOTICE`, or does
  differently from PostgreSQL.
- [Release notes](../../RELEASE-NOTES.md): changes per release, and what to do when upgrading.
