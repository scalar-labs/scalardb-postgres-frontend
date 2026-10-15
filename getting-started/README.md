# Getting started: ScalarDB through the PostgreSQL frontend

The frontend speaks the PostgreSQL wire protocol and runs SQL as ScalarDB transactions, so psql,
the PostgreSQL JDBC driver and other PostgreSQL clients can use ScalarDB. This guide builds the
frontend, starts it on top of ScalarDB on PostgreSQL, loads three tables of 10,000 rows each, and
connects with psql and JDBC. `tour.sql` then shows what the frontend does, section by section.

| File | What |
|---|---|
| `scalardb.properties` | ScalarDB settings for the frontend (ScalarDB on PostgreSQL) |
| `schema.sql` | three tables (`customers`, `items`, `orders`) with ScalarDB partition and clustering keys and two secondary indexes |
| `data.sql` | 10,000 rows per table as multi-row INSERTs; regenerate with `python3 gen_data.py` |
| `tour.sql` | the tour, 15 numbered sections; every statement stands on its own |

## Prerequisites

- **JDK 25** to build the frontend (the build's toolchain) and **Java 17** or
  later to run it: the jar targets Java 17.
- **A PostgreSQL server** to hold ScalarDB's data, with an empty database for it, e.g.
  `createdb scalardb`. Any database ScalarDB supports works; PostgreSQL is used here.
- **psql** (any recent version), and optionally a Java application with the PostgreSQL JDBC driver.

## 1. Build

The frontend builds against ScalarDB `3.20.0-SNAPSHOT`, which the build downloads from ScalarDB's
snapshot repository. To build against your own ScalarDB checkout instead, run
`./gradlew :core:publishToMavenLocal` there first: a core in your local Maven repository wins. Build
the frontend from the root of this repository:

```sh
./gradlew shadowJar
```

This creates `server/build/libs/scalardb-postgres-frontend.jar`, a self-contained jar.

## 2. Configure

Edit `scalardb.properties` in this directory so that it points at your PostgreSQL database:

```properties
scalar.db.storage=jdbc
scalar.db.contact_points=jdbc:postgresql://localhost:5432/scalardb
scalar.db.username=postgres
scalar.db.password=postgres
scalar.db.transaction_manager=consensus-commit
scalar.db.consensus_commit.one_phase_commit.enabled=true
scalar.db.cross_partition_scan.enabled=true
scalar.db.cross_partition_scan.filtering.enabled=true
scalar.db.cross_partition_scan.ordering.enabled=true
```

The three cross-partition scan settings are what let queries without a key work, such as
`SELECT ... WHERE price > 199`.

The same file takes the frontend's own settings, all optional:

| Property | Default | What it does |
|---|---|---|
| `scalar.db.postgres.listen_address` | every interface | The one address to listen on, such as `127.0.0.1` |
| `scalar.db.postgres.max_connections` | `100` | Sessions at once; one more is refused with `53300`, as in PostgreSQL |
| `scalar.db.postgres.max_rows_per_write_ceiling` | none | The most a client may raise `scalardb.max_rows_per_write` to; `0` (no cap) and higher values are refused with `42501` |
| `scalar.db.postgres.allow_drop_coordinator_tables` | `false` | Let any session run `DROP COORDINATOR TABLES`; otherwise only a Cluster superuser may (`42501`) |
| `scalar.db.postgres.max_lookup_threads` | `100` | Threads all sessions share for a join's reads by key, up to 16 per join; when all are busy, a session reads on its own thread. Keep it under ScalarDB's connection pool (`scalar.db.jdbc.connection_pool.max_total`, 200) |
| `scalar.db.postgres.max_rows_in_memory` | `1000000` | Rows one sort, aggregate, window or join side may hold; past it the statement fails with `54000` |
| `scalar.db.postgres.auth` | `none` | `userpass` or `oidc_jwt` to log in as Cluster users: see [Connect as a user](#connect-as-a-user) |
| `scalar.db.postgres.ssl.cert`, `scalar.db.postgres.ssl.key` | none | PEM certificate chain and key, to accept TLS |
| `scalar.db.postgres.ssl.require` | `true` with `auth` on | Refuse clients that do not use TLS |
| `scalar.db.postgres.metadata_user`, `scalar.db.postgres.metadata_password` | `scalardb_postgres_frontend`, none | With `auth` on, the service user that reads and writes the declared types of columns (`NUMERIC(p,s)`, `VARCHAR(n)`, ...); `CREATE FRONTEND METADATA TABLES`, run once as a superuser, creates it and prints both |

Two ScalarDB settings get different defaults from the frontend, unless the file sets them:
`scalar.db.metadata.cache_expiration_time_secs` is `1` (not 60), so sessions see DDL from others
quickly, and `scalar.db.scan_fetch_size` is `1000` (not 10), so a scan fetches 1000 rows per round
trip to the database instead of 10.

The frontend also enforces fixed limits that protect it from a single client:

| Limit | Value | Past it |
|---|---|---|
| Time from connecting to finishing the startup and login | 60 s | The connection is closed |
| One protocol message | 64 MiB | `08P01`, and the session ends |
| Prepared statements, and portals, per session | 10,000 each | `53000` |
| SQL text and bound values held by a session's prepared statements and portals | 64 MiB | `53000`; close some to make room |
| Regular expression matching (`~`, `SIMILAR TO`, `LIKE`) in one statement, over all its rows | 5 s | `2201B` |

## 3. Start the frontend

From this directory (`getting-started`), give it the properties file and the
port to listen on:

```sh
java -jar ../server/build/libs/scalardb-postgres-frontend.jar scalardb.properties 15432
```

It logs `Listening on port 15432` when it is ready. Leave it running in its own terminal.

If the checkout is in a folder that a sync tool such as Dropbox rewrites, copy the jar elsewhere
first and run the copy: a running jar that gets rewritten fails with class loading errors.

### Or run it in a container

The image holds the frontend and the Cluster client SDK, on a Java 25 runtime, so the same image
serves every mode, authentication included. Build it from the root of this repository:

```sh
./gradlew :server:dockerBuild    # scalardb-postgres-frontend:3.20.0-alpha.1-SNAPSHOT, in the local Docker
```

`./gradlew :server:dockerContext` alone assembles the build context in `server/build/docker`, for
`docker build` or a CI build of other platforms.

Mount the properties file at `/scalardb-postgres-frontend/scalardb.properties`. Any value in it may
read the environment as `${env:NAME}`, or `${env:NAME:-default}`, which keeps passwords out of the
file:

```properties
scalar.db.contact_points=jdbc:postgresql://${env:PG_HOST}:5432/scalardb
scalar.db.password=${env:PG_PASSWORD}
```

```sh
docker run -d -p 127.0.0.1:15432:5432 -e PG_HOST=host.docker.internal -e PG_PASSWORD=postgres \
  -v "$PWD/scalardb.properties:/scalardb-postgres-frontend/scalardb.properties:ro" \
  scalardb-postgres-frontend:3.20.0-alpha.1-SNAPSHOT
```

`localhost` inside the container is the container itself: `host.docker.internal` reaches a database
on the host with Docker Desktop. `-p 127.0.0.1:15432:5432` publishes the port on the host's
loopback only: plain `-p 15432:5432` would open it, with no authentication, to the whole network.
The server listens on 5432 in the container, on every interface as a container must, so it logs
the no-authentication warning even then. It runs as uid and gid 201, which Kubernetes'
`runAsNonRoot` accepts. Mount TLS files the same way, readable by uid 201, and point
`scalar.db.postgres.ssl.cert` and `.key` at them. The heap may use 75% of the container's memory,
and running out of it ends the process. Both come from `JAVA_TOOL_OPTIONS`,
together with `--enable-native-access=ALL-UNNAMED`: setting it replaces all three, so repeat the ones
to keep.

## 4. Create the tables and load the data

In another terminal, from this directory:

```sh
psql -h localhost -p 15432 -d demo -f schema.sql -f data.sql
```

The database name in the connection (`demo`) is the ScalarDB namespace. `schema.sql` creates
ScalarDB's coordinator tables and the namespace if needed, and it drops and recreates the three
tables, so running this step again starts over. Loading takes 10 to 15 seconds on a local database, longer against a container or a remote one.

With the default `scalar.db.postgres.auth=none`, any user name works and no password is asked
for; [Connect as a user](#connect-as-a-user) turns authentication on.

## 5. Connect

With psql:

```sh
psql -h localhost -p 15432 -d demo
```

```sql
\dt
SELECT * FROM customers WHERE id = 6509;
EXPLAIN SELECT * FROM customers WHERE id = 6509;
```

`EXPLAIN` shows the ScalarDB operations a statement turns into; here, one Get.

With JDBC (the standard PostgreSQL driver, `org.postgresql:postgresql`):

```java
try (Connection c =
        DriverManager.getConnection("jdbc:postgresql://localhost:15432/demo", "user", "");
    PreparedStatement ps = c.prepareStatement("SELECT name, balance FROM customers WHERE id = ?")) {
  ps.setInt(1, 6509);
  try (ResultSet rs = ps.executeQuery()) {
    while (rs.next()) {
      System.out.println(rs.getString("name") + " " + rs.getDouble("balance"));
    }
  }
}
```

Transactions work as usual: `setAutoCommit(false)`, then `commit()` or `rollback()`.

## 6. Take the tour

Open psql and paste from `tour.sql`, or run it all with `psql -h localhost -p 15432 -d demo -f tour.sql`.
`\timing on` is its first line.

1. psql's `\dt`, `\d`, `\di` answered from ScalarDB metadata
2. a point read is one ScalarDB Get (`EXPLAIN` shows the operations)
3. one partition in clustering order, with range, ORDER BY and LIMIT pushed down
4. a secondary index, with the other conditions pushed to ScalarDB
5. a cross-partition scan, with the conditions pushed down
6. `IN` on the key becomes lookups; `OR` on one table is pushed down as a disjunction
7. a lookup join by key, and a LEFT JOIN feeding an aggregate
8. aggregates, GROUP BY, HAVING, DISTINCT
9. expressions, CASE, subqueries, a CTE, UNION
10. INSERT ... RETURNING, UPDATE by key and by scan, UPSERT, DELETE
11. a write over the row cap (10,000 by default) is refused; `SET scalardb.max_rows_per_write = n`
    changes it for the session (`0` for no limit, `SHOW` and `RESET` work as for other settings)
12. transactions: COMMIT, ROLLBACK, the aborted state after an error, `BEGIN READ ONLY`
13. `EXPLAIN ANALYZE` with rows and ScalarDB reads per operator
14. `$1` parameters bound with psql's `\bind`, and `current_user` / `current_catalog`
15. cleanup of the rows the tour inserted

Sections 11 and 12 show errors on purpose: the refused write, a statement on a missing table, and
the statement after it that the aborted transaction ignores.

## A conflict, with two psql windows

ScalarDB aborts a transaction whose rows another transaction changed first. The frontend reports
it as SQLSTATE 40001 (serialization failure), which applications retry.

```sql
-- window A                                        -- window B
BEGIN;
UPDATE customers SET balance = 1 WHERE id = 5;
                                                   BEGIN;
                                                   UPDATE customers SET balance = 2 WHERE id = 5;
                                                   COMMIT;
COMMIT;   -- fails: the row changed under A
```

## Connect as a user

Against ScalarDB Cluster, the frontend can make clients log in as Cluster users, so that Cluster's
privileges apply to everything a session runs. Three things in the properties file:

```properties
scalar.db.transaction_manager=cluster          # only Cluster has users; a Cluster client config follows
scalar.db.contact_points=indirect:cluster.example.com
scalar.db.postgres.auth=userpass               # or oidc_jwt: the client sends an access token as the password
scalar.db.postgres.ssl.cert=server.crt         # PEM certificate chain
scalar.db.postgres.ssl.key=server.key          # PEM, unencrypted PKCS#8 (openssl pkcs8 -topk8 -nocrypt converts)
```

The frontend sets the Cluster SDK's `scalar.db.cluster.auth.enabled=true` and
`scalar.db.cluster.client.auth.type` (`userpass` or `oidc_jwt`) to match, and refuses to start if the file sets
them otherwise. Leave `scalar.db.username` and `scalar.db.password` out: every call runs as the session's user, and
the server refuses to start with a configured user, so that nothing can ever run as anyone else. TLS is
required with authentication on; `scalar.db.postgres.ssl.require=false` allows plaintext for local
testing only. The container image includes the Cluster client SDK. For the jar, the SDK and its
dependencies are on the classpath rather than inside it; `difftest/cluster-auth/sdk-classpath/` copies them into one directory:

```sh
java -cp scalardb-postgres-frontend.jar:'sdk-classpath/lib/*' \
     com.scalar.db.frontend.postgres.server.Main scalardb.properties 5432
```

Clients need nothing new. psql and libpq:

```sh
psql "host=fe.example.com port=5432 dbname=sample user=alice sslmode=verify-full sslrootcert=ca.pem"
```

The password comes from the prompt, `PGPASSWORD`, `~/.pgpass` or `-W`; do not set
`require_auth=scram-sha-256`, which refuses the cleartext request (`require_auth=password` matches). For
OIDC, `PGPASSWORD=$(get-token) psql ...` with `auth=oidc_jwt`. pgjdbc:

```java
Properties p = new Properties();
p.setProperty("user", "alice");
p.setProperty("password", secret);
DriverManager.getConnection(
    "jdbc:postgresql://fe.example.com:5432/sample?sslmode=verify-full&sslrootcert=/path/ca.pem", p);
```

What the session sees: `current_user`, `session_authorization` and `is_superuser` report the Cluster
user; a wrong password is `28P01` after a one-second delay and the connection closes; a missing
privilege is `42501` and the session goes on; a token that expires mid-session is `28000` and the
session ends, which pools handle by reconnecting; `SET ROLE` and `SET SESSION AUTHORIZATION` are
refused. The design is in [docs/auth-design.md](../docs/auth-design.md), and
[difftest/cluster-auth](../difftest/cluster-auth/README.md) is a working demo against a local Cluster node.

A superuser manages users and privileges in SQL; they live in Cluster:

```sql
CREATE USER carol WITH PASSWORD 'carolpw';
GRANT SELECT, INSERT, UPDATE ON items TO carol;  -- INSERT and UPDATE go together (ScalarDB's WRITE)
CREATE ROLE auditors;                             -- no LOGIN: a ScalarDB role
GRANT SELECT ON ALL TABLES IN SCHEMA sample TO auditors;
GRANT auditors TO carol;
ALTER USER carol PASSWORD 'newpw';                -- not psql's \password, which hashes on the client
DROP USER carol;
```

## Encrypted columns and row-level access

ScalarDB Cluster's encryption and attribute-based access control (ABAC) work through the frontend,
when the Cluster nodes have them enabled.

**Encryption.** Name the columns when creating the table; Cluster stores them as ciphertext and
decrypts them for the reader:

```sql
CREATE TABLE people (id int PRIMARY KEY, name text, ssn text, card text)
  WITH (encrypted = 'ssn, card');
```

A key column cannot be encrypted (`0A000`), and neither can an indexed one. `WHERE`, `ORDER BY` and
joins on an encrypted column work, but the frontend evaluates them after reading the rows, so a
filter on an encrypted column alone reads the whole table. `\d+ people` lists the encrypted columns
under `Options`. Without encryption enabled, the `CREATE TABLE` fails with `0A000`.

**ABAC.** Set up policies, levels, compartments, groups and user tags with ScalarDB SQL or Cluster's
`AbacAdmin`; there is no PostgreSQL syntax for them. Then log in as a user ([Connect as a
user](#connect-as-a-user)), and Cluster applies the user's tags to every statement: rows the user may
not read are left out of every `SELECT`, `UPDATE` and `DELETE` without an error, as PostgreSQL's
row-level security does, and an `INSERT` is tagged with the user's default row tag unless it sets the
data tag column itself (`42501` if the user may not write that tag). The data tag column reads as
text, such as `CONF::`, and cannot appear in `WHERE` or `ORDER BY` (`0A000`). A superuser bypasses ABAC.

[difftest/cluster-security](../difftest/cluster-security/README.md) runs both against a local Cluster node.

## Case-insensitive text

Text compares in ScalarDB's collation. By default that is byte order, as PostgreSQL's `C` collation;
ScalarDB can instead compare with an ICU locale, set in `scalardb.properties`:

```properties
scalar.db.collation=ICU
# und: the root locale; -u-ks-level2: case-insensitive, accent-sensitive (level1 ignores accents too)
scalar.db.collation.icu.locale=und-u-ks-level2
```

Then `'alice' = 'ALICE'`, keys included: `WHERE name = 'ALICE'`, `GROUP BY`, `DISTINCT`, joins and
`UNION` treat the two as one, `ORDER BY` follows the locale, and inserting `'ALICE'` next to a key
`'alice'` is a duplicate (`23505`). It works like a PostgreSQL column with a nondeterministic ICU
collation, which psql's `\d` shows (`und-u-ks-level2-x-icu`), and like one in PostgreSQL 16,
`LIKE`, `ILIKE`, `SIMILAR TO`, regular expressions and substring searches (`position`, `strpos`,
`replace`, `starts_with`) on table text fail with `0A000`.

The backing database compares keys and pushed-down conditions itself, so it must use the same
collation. On PostgreSQL, a nondeterministic collation cannot be a database's default: create the
tables through the frontend, then alter each character column on the backing database, as
[backend.sql](../difftest/collation-icu/backend.sql) does for the schema `sdb`:

```sql
CREATE COLLATION ci (provider = icu, locale = 'und-u-ks-level2', deterministic = false);
ALTER TABLE sdb.people ALTER COLUMN name TYPE varchar(128) COLLATE ci;  -- and every other text column
```

Run that again after each `CREATE TABLE` or `ADD COLUMN`. On MySQL, set the database's default
collation before creating the tables instead (`utf8mb4_0900_as_ci` approximates the example). With
Cluster, set `scalar.db.collation` on the nodes and the same value in the frontend's properties.

[difftest/collation-icu](../difftest/collation-icu/README.md) compares the frontend under ICU with
PostgreSQL.

## Limitations to know

What the frontend refuses, announces with a `NOTICE`, or does differently from PostgreSQL is listed
in [LIMITATIONS.md](../LIMITATIONS.md).
