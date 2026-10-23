# Quick start: ScalarDB Cluster through PostgreSQL clients

ScalarDB Cluster nodes can speak the PostgreSQL wire protocol, so that psql, the PostgreSQL JDBC driver
and other PostgreSQL clients can run SQL as ScalarDB transactions. This guide starts one ScalarDB Cluster
node in Docker with the PostgreSQL frontend and authentication enabled, stores its data in a PostgreSQL
container, and then creates a table, writes to it and reads from it with psql.

:::note

This guide runs the frontend inside a ScalarDB Cluster node, which is the recommended deployment. To use
the frontend with ScalarDB core, without ScalarDB Cluster, see
[Getting started with the standalone server](../../getting-started/README.md).

:::

## Prerequisites

Before you begin, make sure you have the following:

- **Docker**.
- **psql**, the PostgreSQL command-line client.
- **OpenSSL**, to create a certificate for the frontend.
- **A license key for ScalarDB Cluster** and its license check certificate. To request a trial license,
  contact [Scalar](https://www.scalar-labs.com/contact).
- Access to the ScalarDB Cluster node image, `ghcr.io/scalar-labs/scalardb-cluster-node-byol-premium`.

<!-- TODO: State the first ScalarDB Cluster version that includes the PostgreSQL frontend, and use it for <VERSION> below. -->

## Set up the environment

### Create a Docker network

The ScalarDB Cluster node and the PostgreSQL server that stores its data run in separate containers. To
let them reach each other by name, create a Docker network:

```console
$ docker network create scalardb-pg
```

### Start the backing PostgreSQL server

ScalarDB Cluster stores its data in a database that ScalarDB supports. This guide uses PostgreSQL. To
start it, run the following command:

```console
$ docker run -d --name backend-postgres --network scalardb-pg \
    -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=scalardb \
    postgres:17
```

This PostgreSQL server only stores ScalarDB's data. Clients do not connect to it; they connect to the
frontend in the ScalarDB Cluster node.

### Create a certificate for the frontend

With authentication enabled, clients send their passwords to the frontend, so the frontend accepts only
TLS connections. For this guide, create a self-signed certificate and its private key in a new directory:

```console
$ mkdir scalardb-pg-quickstart && cd scalardb-pg-quickstart
$ openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=localhost" \
    -keyout server.key -out server.crt
$ chmod 644 server.key
```

The frontend reads the private key as an unencrypted PKCS#8 PEM file, which is what OpenSSL 3 writes by
default. The `chmod` command lets the container read the key; do this only for a local test.

:::note

If your private key is in another format, convert it with
`openssl pkcs8 -topk8 -nocrypt -in <KEY_FILE> -out server.key`.

:::

### Configure the ScalarDB Cluster node

In the same directory, create a file named `scalardb-cluster-node.properties` with the following content.
Replace `<YOUR_LICENSE_KEY>` and `<LICENSE_CHECK_CERT_PEM>` with the values that you received from Scalar:

```properties
# The database that stores ScalarDB's data
scalar.db.storage=jdbc
scalar.db.contact_points=jdbc:postgresql://backend-postgres:5432/scalardb
scalar.db.username=postgres
scalar.db.password=postgres
scalar.db.cross_partition_scan.enabled=true
scalar.db.cross_partition_scan.filtering.enabled=true
scalar.db.cross_partition_scan.ordering.enabled=true

# One node, without Kubernetes
scalar.db.cluster.node.standalone_mode.enabled=true

# Users, roles and privileges
scalar.db.cluster.auth.enabled=true

# The PostgreSQL frontend
scalar.db.postgres.enabled=true
scalar.db.postgres.port=5432
scalar.db.postgres.ssl.cert=/scalardb-cluster/node/tls/server.crt
scalar.db.postgres.ssl.key=/scalardb-cluster/node/tls/server.key

# License
scalar.db.cluster.node.licensing.license_key=<YOUR_LICENSE_KEY>
scalar.db.cluster.node.licensing.license_check_cert_pem=<LICENSE_CHECK_CERT_PEM>
```

The three `cross_partition_scan` properties let queries that do not specify a key run, such as
`SELECT * FROM items WHERE price > 100`. For details about each property, see
[Configuration](configuration.md).

### Start the ScalarDB Cluster node

The node reads its configuration from `/scalardb-cluster/node/scalardb-cluster-node.properties` in the
container. To start the node with your configuration and certificate, run the following command, replacing
`<VERSION>` with the version of ScalarDB Cluster:

```console
$ docker run -d --name scalardb-node --network scalardb-pg \
    -p 127.0.0.1:15432:5432 \
    -v "$PWD/scalardb-cluster-node.properties:/scalardb-cluster/node/scalardb-cluster-node.properties:ro" \
    -v "$PWD/server.crt:/scalardb-cluster/node/tls/server.crt:ro" \
    -v "$PWD/server.key:/scalardb-cluster/node/tls/server.key:ro" \
    ghcr.io/scalar-labs/scalardb-cluster-node-byol-premium:<VERSION>
```

<!-- TODO: Verify that standalone mode needs nothing else in this image, which sets SCALAR_DB_CLUSTER_MEMBERSHIP_TYPE=KUBERNETES; that the container user can read the mounted files; and the exact startup log line. -->

The `-p 127.0.0.1:15432:5432` option publishes the frontend's port 5432 as port 15432 on your machine's
loopback interface only. To confirm that the frontend is ready, check the node's log:

```console
$ docker logs scalardb-node 2>&1 | grep "PostgreSQL frontend"
```

You should see output similar to the following:

```console
PostgreSQL frontend listening on port 5432 with userpass authentication
```

On its first start with authentication enabled, the node creates ScalarDB's coordinator tables and a
superuser named `admin` with the password `admin`.

## Create the frontend's metadata table

ScalarDB has fewer data types than PostgreSQL. The frontend records the types that you declare, such as
`NUMERIC(10,2)` and `VARCHAR(20)`, in a metadata table of its own, and reads columns back through those
records. Create the metadata table once, as a superuser, before you create your first table.

### Connect as the superuser

The database name in a connection is a ScalarDB namespace. To connect to the namespace `demo` as `admin`,
run the following command and enter `admin` when psql asks for the password:

```console
$ psql "host=localhost port=15432 dbname=demo user=admin sslmode=require"
```

The `sslmode=require` option encrypts the connection without verifying the self-signed certificate. In
production, use a certificate that your clients trust and `sslmode=verify-full`.

### Change the superuser's password

Every ScalarDB Cluster deployment starts with the same superuser password. To change it, run the
following statement:

```sql
ALTER USER admin PASSWORD '<NEW_PASSWORD>';
```

### Create the metadata table and the service user

To create the metadata table, run the following statement:

```sql
CREATE FRONTEND METADATA TABLES;
```

The statement also creates a service user named `scalardb_postgres_frontend`, which the frontend uses to
read and write the metadata table, and prints the two properties that tell the frontend its password:

```console
               property               |                    value
--------------------------------------+---------------------------------------------
 scalar.db.postgres.metadata_user     | scalardb_postgres_frontend
 scalar.db.postgres.metadata_password | <GENERATED_PASSWORD>
```

Running the statement again creates only what is missing and keeps the existing password.

Exit psql with `\q`.

### Give the node the service user's password

Add the two printed properties to `scalardb-cluster-node.properties`:

```properties
scalar.db.postgres.metadata_user=scalardb_postgres_frontend
scalar.db.postgres.metadata_password=<GENERATED_PASSWORD>
```

Then, restart the node so that it reads them:

```console
$ docker restart scalardb-node
```

:::warning

Do not grant privileges on the namespace `scalardb_postgres_frontend` to any other user. A user who can
write to the metadata table can change how every table's columns are read.

:::

## Create a user and a table

### Create a schema, a table and a user

Connect as `admin` again with the new password:

```console
$ psql "host=localhost port=15432 dbname=demo user=admin sslmode=require"
```

Create the schema `demo`, which is a ScalarDB namespace, and a table in it:

```sql
CREATE SCHEMA demo;

CREATE TABLE demo.orders (
  customer_id INT,
  order_id    INT,
  item        VARCHAR(40),
  amount      NUMERIC(10,2),
  PRIMARY KEY (customer_id, order_id)
);
```

The first column of the primary key, `customer_id`, becomes the table's ScalarDB partition key, and
`order_id` becomes its clustering key. All the orders of one customer are stored together in one
partition, ordered by `order_id`. Choosing keys this way decides which queries are fast; for details, see
[Designing tables and queries](schema-and-queries.md).

Next, create a user who can read and write the table:

```sql
CREATE USER alice WITH PASSWORD 'alicepw';
GRANT SELECT, INSERT, UPDATE, DELETE ON demo.orders TO alice;
```

Exit psql with `\q`.

## Use the table as a user

### Connect as the user

To connect as `alice`, run the following command and enter `alicepw` when psql asks for the password:

```console
$ psql "host=localhost port=15432 dbname=demo user=alice sslmode=require"
```

### Write and read rows

Insert a few orders:

```sql
INSERT INTO orders VALUES
  (1, 1, 'keyboard', 49.90),
  (1, 2, 'mouse',    19.95),
  (2, 1, 'monitor', 199.00);
```

Read one customer's orders:

```sql
SELECT order_id, item, amount FROM orders WHERE customer_id = 1 ORDER BY order_id;
```

You should see output similar to the following:

```console
 order_id |   item   | amount
----------+----------+--------
        1 | keyboard |  49.90
        2 | mouse    |  19.95
(2 rows)
```

### See how a query runs

`EXPLAIN` shows the ScalarDB operations that a statement turns into. Run the following statement:

```sql
EXPLAIN SELECT order_id, item, amount FROM orders WHERE customer_id = 1 ORDER BY order_id;
```

You should see output similar to the following:

```console
 Project [order_id, item, amount]
   -> ScalarDB Scan demo.orders partitionKey={customer_id=1} ordering=[order_id ASC] projections=[customer_id, order_id, item, amount] AS orders
```

The plan is one ScalarDB `Scan` of the partition `customer_id = 1`, which returns the rows already in
`order_id` order, so the frontend does not sort them. Lines that start with `ScalarDB` run in the database;
every other line, such as `Project`, `Filter` or `Sort`, runs in the frontend over the rows that those
operations return.

### Run a transaction

Transactions work as in PostgreSQL. Run the following statements:

```sql
BEGIN;
UPDATE orders SET amount = amount - 5 WHERE customer_id = 1 AND order_id = 2;
SELECT amount FROM orders WHERE customer_id = 1 AND order_id = 2;
COMMIT;
```

ScalarDB transactions are optimistic: a transaction that conflicts with another one fails at `COMMIT` with
SQLSTATE `40001`, and the application retries it. For details, see [Transactions](transactions.md).

### Check what the user may not do

`alice` has no privileges on other tables. Run the following statement:

```sql
CREATE TABLE demo.other (id INT PRIMARY KEY);
```

The statement fails with SQLSTATE `42501`, because `alice` does not have the `CREATE` privilege on the
namespace `demo`.

## Clean up

To remove the containers and the network, run the following commands:

```console
$ docker rm -f scalardb-node backend-postgres
$ docker network rm scalardb-pg
```

## Next steps

- [Configuration](configuration.md): every property of the frontend.
- [Designing tables and queries](schema-and-queries.md): keys, and which queries run in the database.
- [Transactions](transactions.md): isolation, conflicts and retries.
- [Limitations](../../LIMITATIONS.md): what the frontend refuses or does differently from PostgreSQL.
