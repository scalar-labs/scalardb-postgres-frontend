# Operations

This page covers what is specific to running the PostgreSQL frontend in ScalarDB Cluster nodes: how client
connections spread over the nodes, how to size the frontend's limits, how to see what sessions are doing,
how to upgrade, and how to troubleshoot common errors. For deploying, scaling, monitoring and upgrading the
ScalarDB Cluster nodes themselves, see the ScalarDB Cluster documentation.

## Connections across nodes

Each ScalarDB Cluster node with `scalar.db.postgres.enabled=true` runs its own frontend on
`scalar.db.postgres.port`. A PostgreSQL connection stays on the node that it connected to for its whole life,
and that node runs all of the session's statements itself.

PostgreSQL connections usually last a long time, because applications keep them in connection pools.
Connections therefore spread over the nodes only as well as new connections do. Put a load balancer or a
Kubernetes Service in front of the nodes' PostgreSQL port, and set your connection pool's maximum connection
lifetime, for example to 30 minutes, so that connections move to nodes that were added later.

<!-- TODO: Document the Helm chart's values for the PostgreSQL port and Service once the chart supports them. -->

When a node stops, such as during scale-in or a rolling restart, its sessions end, and a transaction open on
it is not committed. Applications reconnect through the pool and retry, as they would after a PostgreSQL
restart.

<!-- TODO: Update once the node waits for open sessions when it is decommissioned. -->

## Sizing the limits

The frontend shares the node's memory with ScalarDB Cluster. Three properties limit what one node's frontend
can take:

- **`scalar.db.postgres.max_connections`** (100 by default) limits the sessions per node. The total across the
  nodes must cover the connection pools of every application instance. A connection beyond the limit fails
  with SQLSTATE `53300`.
- **`scalar.db.postgres.max_rows_in_memory`** (one million by default) limits the rows that one sort,
  aggregate, window or join input holds. Several sessions can each hold that many rows at once, so lower it
  if queries that sort or group large tables run concurrently. A query beyond the limit fails with SQLSTATE
  `54000`.
- **`scalar.db.postgres.max_rows_per_write_ceiling`** caps how far a session may raise
  `scalardb.max_rows_per_write` (10,000 rows by default). Set it to keep any session from writing an unlimited
  number of rows in one statement.

For the properties, see [Configuration](configuration.md#properties-of-the-frontend).

## Seeing what sessions do

`pg_stat_activity` lists the sessions of the node that you are connected to, with their user, client address,
state and current query. As in PostgreSQL, another user's query and state are shown only to a superuser.
`pg_backend_pid()` returns the session's own ID. A client's cancel request (Ctrl+C in psql,
`Statement.cancel()` in JDBC) and `statement_timeout` stop a running statement with SQLSTATE `57014`.
`pg_cancel_backend()` and `pg_terminate_backend()` are not supported.

Sessions on other nodes are not listed. Nothing waits for a lock, so `pg_locks` does not exist and
`pg_blocking_pids()` always returns an empty array.

To see where a slow query spends its time, run it with `EXPLAIN ANALYZE`, which shows the rows and the
ScalarDB reads of each operation. For details, see
[Designing tables and queries](schema-and-queries.md#check-a-query-with-explain).

The frontend logs a statement that fails at the `INFO` level, in the node's log.

## Changing tables in production

ScalarDB caches table metadata for `scalar.db.metadata.cache_expiration_time_secs` (60 seconds by default) in
every node and does not refresh it after DDL. For that long after `ALTER TABLE`, `DROP TABLE` or an index
change, statements on other nodes use the old definition. Set the same value on every node. A short value,
such as 1 second, makes changes visible quickly, as migration tools need, but makes every node read table
metadata from the backing database more often: about three reads per table in use per expiry.

## Upgrading

The first time that you enable the frontend, or when you upgrade from a release without the metadata table,
do the following once:

1. Connect as a superuser and run `CREATE FRONTEND METADATA TABLES`.
2. Add the two properties that it prints, `scalar.db.postgres.metadata_user` and
   `scalar.db.postgres.metadata_password`, to the configuration of every node that runs the frontend.
3. Restart the nodes.

During a rolling upgrade from a release that does not read the metadata table, finish upgrading every node
before the first `CREATE TABLE` or `ALTER TABLE ADD COLUMN` that declares `NUMERIC`, `VARCHAR(n)`, `CHAR(n)`,
`SMALLINT`, `JSON` or `JSONB`. An older node would read such a column by its stored form, such as a
`NUMERIC(10,2)` value of `19.90` as the integer `1990`.

For what changes in each release, see [RELEASE-NOTES.md](../../RELEASE-NOTES.md).

## Troubleshooting

### A connection fails with 28P01

The user name or password is wrong, or the user does not exist in ScalarDB Cluster. The frontend waits one
second before it answers. Check the user with `\du` as a superuser.

### A connection fails because TLS is required

With authentication enabled, the frontend refuses connections without TLS. Connect with `sslmode=require` or
`sslmode=verify-full`.

### A connection fails with 53300

The node already has `scalar.db.postgres.max_connections` sessions. Lower the connection pool sizes, add
nodes, or raise the limit.

### A statement fails with 55000

The frontend's metadata table is missing, or the node has no `scalar.db.postgres.metadata_password`. Run
`CREATE FRONTEND METADATA TABLES` as a superuser, add the properties that it prints to every node, and
restart the nodes.

### A statement fails with 42501

The user lacks a privilege that the statement needs. The error message names the missing ScalarDB privilege.
Grant it, as described in [Security](security.md#privileges).

### A statement fails with 40001

The transaction conflicted with another one. Retry the whole transaction. For details, see
[Transactions](transactions.md#retry-on-40001).

### A statement fails with 54000

A sort, aggregate, window or join input needed more rows than `scalar.db.postgres.max_rows_in_memory`.
Narrow the query with a key, an index or `LIMIT`.

### A query fails because a cross-partition scan is not enabled

A query that does not fix a table's partition key needs a cross-partition scan. Set
`scalar.db.cross_partition_scan.enabled`, `filtering.enabled` and `ordering.enabled` to `true` on the nodes.

### A statement fails with DB-CORE-10106

The transaction scanned records that it had already written. Read by primary key, read before writing, or use
`RETURNING`. For details, see [Transactions](transactions.md#reading-rows-that-the-transaction-wrote).

### A new column is missing, or an insert into it fails

The table's metadata is still cached on the node that ran the statement. Wait for
`scalar.db.metadata.cache_expiration_time_secs` to pass, or set it lower on every node.
