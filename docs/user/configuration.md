# Configuration

This page lists the properties that configure the PostgreSQL frontend, the ScalarDB properties that change
how it behaves, the settings that a client can change for its session, and the fixed limits that protect the
frontend from a single client.

## Where the properties go

When the frontend runs in a ScalarDB Cluster node, all of its properties go in the node's configuration
file, next to the node's own properties. The node passes its whole configuration to the frontend, so the
frontend also reads ScalarDB properties such as `scalar.db.collation` from the same file. Set the same
values on every node.

When the frontend runs as the standalone server, its properties go in the ScalarDB properties file that you
give the server when you start it. For details, see
[Getting started with the standalone server](../../getting-started/README.md).

In both cases, a property that is not set takes its default value. A change takes effect when the node or
the server restarts.

## Properties in a ScalarDB Cluster node

The following properties enable the frontend in a ScalarDB Cluster node and decide how clients connect to
it. The node needs transactions enabled; otherwise, the node fails to start when the frontend is enabled.

| Property | Default | Description |
|----------|---------|-------------|
| `scalar.db.postgres.enabled` | `false` | Whether the node accepts PostgreSQL clients. |
| `scalar.db.postgres.port` | `5432` | The port that the frontend listens on. |
| `scalar.db.postgres.listen_address` | All interfaces | The one address to listen on, such as `127.0.0.1`. |
| `scalar.db.postgres.auth` | `auto` | What clients send as their password when `scalar.db.cluster.auth.enabled` is `true`. With `auto`, each connection decides: a well-formed JWT is checked as an OIDC access token, anything else as the ScalarDB Cluster user's password, and a client can state which with the startup parameter `scalardb.auth` (see below). `userpass` or `oidc_jwt` allows only that kind. With a token, the node takes the user name from the token as configured by the `scalar.db.cluster.auth.oidc.*` properties. When `scalar.db.cluster.auth.enabled` is `false`, clients log in without a password and this property has no effect. |
| `scalar.db.postgres.ssl.cert` | The node's certificate chain | The PEM file holding the certificate chain that the frontend presents to clients. When this property and `scalar.db.postgres.ssl.key` are not set and `scalar.db.cluster.tls.enabled` is `true`, the frontend uses the node's `scalar.db.cluster.node.tls.cert_chain_path`. |
| `scalar.db.postgres.ssl.key` | The node's private key | The PEM file holding the private key, as unencrypted PKCS#8. When this property and `scalar.db.postgres.ssl.cert` are not set and `scalar.db.cluster.tls.enabled` is `true`, the frontend uses the node's `scalar.db.cluster.node.tls.private_key_path`. |

:::note

A private key in another format can be converted with
`openssl pkcs8 -topk8 -nocrypt -in <KEY_FILE> -out <PKCS8_KEY_FILE>`.

:::

A client can state what it sends with the startup parameter `scalardb.auth`, set to `userpass` or
`oidc_jwt`: `options='-c scalardb.auth=oidc_jwt'` or `PGOPTIONS` with libpq, the `options` property of pgjdbc
and psycopg, `Options` with Npgsql. Under `auto` it overrides the shape rule. Under `userpass` or `oidc_jwt`,
asking for the other kind fails with SQLSTATE `28000`.

## Properties of the frontend

The following properties apply wherever the frontend runs.

| Property | Default | Description |
|----------|---------|-------------|
| `scalar.db.postgres.max_connections` | `100` | The number of sessions that the frontend accepts at once, in each node or server. One more connection is refused with SQLSTATE `53300`, as in PostgreSQL. |
| `scalar.db.postgres.max_rows_in_memory` | `1000000` | The number of rows that one sort, aggregate, window or join input may hold in memory. A statement that needs more fails with SQLSTATE `54000`. |
| `scalar.db.postgres.max_rows_per_write_ceiling` | None | The largest value that a session may set `scalardb.max_rows_per_write` to (see [Session settings](#session-settings)). When set, `0` (no limit) and larger values are refused with SQLSTATE `42501`. |
| `scalar.db.postgres.allow_drop_coordinator_tables` | `false` | Whether any session may run `DROP COORDINATOR TABLES`. When `false`, only a ScalarDB Cluster superuser may run it. |
| `scalar.db.postgres.ssl.require` | `true` with authentication, `false` without | Whether to refuse clients that do not use TLS. With authentication enabled, clients send passwords or tokens, so set this to `false` only for local testing. |

:::warning

Grant no privileges on the namespace `scalardb_postgres_frontend`. A user who can write to the frontend's
metadata table can change how every table's columns are read. In a node, the frontend reads and writes that
table under the node's own authority; see [Security](security.md#the-frontends-metadata-table).

:::

The following example enables the frontend with password authentication and its own certificate:

```properties
# scalardb-cluster-node.properties
scalar.db.cluster.auth.enabled=true

scalar.db.postgres.enabled=true
scalar.db.postgres.port=5432
scalar.db.postgres.ssl.cert=/scalardb-cluster/node/tls/server.crt
scalar.db.postgres.ssl.key=/scalardb-cluster/node/tls/server.key
```

## Properties of the standalone server only

The following properties apply only when the frontend runs as the standalone server. The server takes the
port to listen on as its second command-line argument (`5432` by default), not as a property.

| Property | Default | Description |
|----------|---------|-------------|
| `scalar.db.postgres.listen_address` | All interfaces | The one address to listen on, such as `127.0.0.1`. |
| `scalar.db.postgres.auth` | `none` | `none` lets any client connect without a password, with the full access of the configured ScalarDB user. `userpass` or `oidc_jwt` makes clients log in as ScalarDB Cluster users, as in a node; this needs `scalar.db.transaction_manager=cluster`, TLS and no `scalar.db.username` or `scalar.db.password`. `auto` is not available here: the ScalarDB Cluster client SDK takes one kind of secret per client. |
| `scalar.db.postgres.ssl.cert`, `scalar.db.postgres.ssl.key` | None | The PEM certificate chain and unencrypted PKCS#8 private key that make the server accept TLS. |
| `scalar.db.postgres.metadata_user` | `scalardb_postgres_frontend` | With `scalar.db.postgres.auth` set to `userpass` or `oidc_jwt`, the service user that reads and writes the frontend's metadata table, because the server runs through the ScalarDB Cluster client SDK and has no authority of its own. `CREATE FRONTEND METADATA TABLES`, run once as a superuser through the server, creates this user and prints this property. A node ignores it. |
| `scalar.db.postgres.metadata_password` | None | The service user's password, which `CREATE FRONTEND METADATA TABLES` prints. With authentication and no password set, statements on tables fail with SQLSTATE `55000` once the metadata table exists. A node ignores it. |
| `scalar.db.postgres.backend_collation_check` | `true` | At startup, under `scalar.db.collation=BINARY`, check that a backing PostgreSQL or MySQL database compares text in byte order, and refuse to start if it does not. |
| `scalar.db.postgres.timing_log_interval` | `0` (off) | For benchmarks: every this many seconds, log how much of the sessions' time went to waiting for ScalarDB and how much to the frontend's own work. |

The standalone server also sets `scalar.db.scan_fetch_size` to `1000` unless the file sets it, so that a
scan fetches 1,000 records per round trip instead of ScalarDB's default of 10.

<!-- TODO: Decide whether a Cluster node should also default scan_fetch_size to 1000 for the frontend, or recommend setting it. -->

## ScalarDB properties that matter

The frontend runs on ScalarDB, so some ScalarDB properties decide what it can do. In a ScalarDB Cluster
node, set them in the node's configuration; for the standalone server, in its properties file.

| Property | Recommended | Why |
|----------|-------------|-----|
| `scalar.db.cross_partition_scan.enabled`, `scalar.db.cross_partition_scan.filtering.enabled`, `scalar.db.cross_partition_scan.ordering.enabled` | `true` | Without them, a query that does not specify a table's partition key fails, such as `SELECT * FROM items WHERE price > 100`. |
| `scalar.db.metadata.cache_expiration_time_secs` | `1` to `5` for applications that change tables, such as migration tools and ORMs | ScalarDB caches table metadata (60 seconds by default) and does not refresh it after DDL. With 5 seconds or less, `ALTER TABLE`, `DROP TABLE` and index DDL return only once the change is visible, which takes that long per statement. With a longer cache, they return at once with a `NOTICE` saying when the change takes effect. A short cache costs database load in every node. |
| `scalar.db.consensus_commit.isolation_level` | As your application needs | `READ_COMMITTED` and `SNAPSHOT` (the default) appear to clients as `read committed`, and `SERIALIZABLE` as `serializable`. For details, see [Transactions](transactions.md). |
| `scalar.db.collation`, `scalar.db.collation.icu.locale` | As your application needs | How text compares: `BINARY` (byte order, the default) or an ICU locale. The backing database must be set up to compare text the same way. For details, see [LIMITATIONS.md](../../LIMITATIONS.md). |
| `scalar.db.cluster.auth.auth_token_expiration_time_minutes` | Longer than your connection pool's maximum connection lifetime | A session's login token expires after this time (one day by default), and the session then ends with SQLSTATE `28000`. Connection pools reconnect. |

## Session settings

A client can change the following setting for its own session with `SET`, and read it with `SHOW`.
`RESET` restores the default.

| Setting | Default | Description |
|---------|---------|-------------|
| `scalardb.max_rows_per_write` | `10000` | The number of rows that one `INSERT`, `UPDATE` or `DELETE` may write. A statement that would write more is refused before it writes anything. `0` removes the limit, unless `scalar.db.postgres.max_rows_per_write_ceiling` forbids it. |

The frontend also accepts PostgreSQL's session settings that clients and drivers commonly send, such as
`search_path`, `TimeZone`, `DateStyle`, `application_name` and `statement_timeout`. For which ones take
effect, see [LIMITATIONS.md](../../LIMITATIONS.md).

## Fixed limits

The frontend enforces the following limits, which cannot be configured.

| Limit | Value | What happens past it |
|-------|-------|----------------------|
| Time from connecting to finishing the startup and login | 60 seconds | The connection is closed. |
| One protocol message | 64 MiB | SQLSTATE `08P01`, and the session ends. |
| Prepared statements, and portals, per session | 10,000 each | SQLSTATE `53000`. |
| SQL text and bound values held by a session's prepared statements and portals | 64 MiB | SQLSTATE `53000`. Close some to make room. |
| Regular expression matching (`~`, `SIMILAR TO`, `LIKE`) in one statement, over all its rows | 5 seconds | SQLSTATE `2201B`. |
| Nesting depth of one statement | 256 brackets | SQLSTATE `54001`. |
| Time to parse one statement | 5 seconds | SQLSTATE `54001`. |
| Characters that `lpad` and `rpad` may build | 10 million | The function fails. |
