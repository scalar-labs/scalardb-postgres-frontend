# Security

In a ScalarDB Cluster node with authentication enabled, PostgreSQL clients log in as ScalarDB Cluster users,
and ScalarDB Cluster enforces those users' privileges on every statement. This page explains how clients log
in, how to manage users and privileges in SQL, and how ScalarDB Cluster's encryption and attribute-based
access control (ABAC) appear through the frontend.

## Authentication

Authentication is enabled by ScalarDB Cluster's `scalar.db.cluster.auth.enabled=true`. The frontend then
accepts only clients that log in as a ScalarDB Cluster user, in one of two ways that
`scalar.db.postgres.auth` selects:

- **`userpass`** (the default): the client sends the user's name and password.
- **`oidc_jwt`**: the client sends an OIDC access token as its password, and ScalarDB Cluster takes the user
  name from the token. The `scalar.db.cluster.auth.oidc.*` properties of the node configure the token check.

PostgreSQL clients need nothing new. With psql or libpq, the password comes from the prompt, `PGPASSWORD` or
`~/.pgpass`:

```console
$ psql "host=pg.example.com port=5432 dbname=sales user=alice sslmode=verify-full sslrootcert=ca.pem"
```

With OIDC, pass the token as the password:

```console
$ PGPASSWORD="$(<COMMAND_THAT_PRINTS_A_TOKEN>)" psql "host=pg.example.com dbname=sales user=alice sslmode=verify-full"
```

With pgjdbc, set the user and password as usual:

```java
Properties props = new Properties();
props.setProperty("user", "alice");
props.setProperty("password", password);
Connection c = DriverManager.getConnection(
    "jdbc:postgresql://pg.example.com:5432/sales?sslmode=verify-full&sslrootcert=/path/to/ca.pem", props);
```

The password crosses the connection in clear text inside TLS, because ScalarDB Cluster must check it; this
is how PostgreSQL itself handles LDAP and RADIUS passwords. For the same reason, libpq's
`require_auth=scram-sha-256` refuses the connection; use `require_auth=password` if you set it.

### What a session sees

`current_user`, `session_user` and `is_superuser` report the ScalarDB Cluster user. The following failures
can occur:

| Situation | Result |
|-----------|--------|
| Wrong user name or password | SQLSTATE `28P01` after a one-second delay, and the connection closes. |
| Missing privilege | SQLSTATE `42501`. The session continues. |
| The login token expires | SQLSTATE `28000`, and the session ends. Connection pools reconnect. |

A login token lasts for `scalar.db.cluster.auth.auth_token_expiration_time_minutes` (one day by default).
Set your connection pool's maximum connection lifetime below that value, so that the pool replaces
connections before their tokens expire. `SET ROLE` and `SET SESSION AUTHORIZATION` are refused.

:::warning

Because of a ScalarDB Cluster issue, a user's old password can keep working through a frontend that it
logged in through before, for up to the token lifetime, after `ALTER USER ... PASSWORD` or after the user is
dropped and created again. Shorten `scalar.db.cluster.auth.auth_token_expiration_time_minutes`, or restart the
nodes after such a change.

:::

<!-- TODO: Check whether the stale-password issue applies to the in-node frontend, and link the Cluster issue once it has one. -->

## TLS

With authentication enabled, the frontend accepts only TLS connections, because clients send passwords or
tokens. Give the frontend a certificate with `scalar.db.postgres.ssl.cert` and `scalar.db.postgres.ssl.key`,
or enable TLS on the node with `scalar.db.cluster.tls.enabled=true` to have the frontend use the node's
certificate. For details, see [Configuration](configuration.md#properties-in-a-scalardb-cluster-node).

Clients should verify the certificate with `sslmode=verify-full` and the CA that signed it. `sslmode=require`
encrypts the connection but does not verify the server, so use it only for testing.

## Users and roles

A superuser manages ScalarDB Cluster's users and roles in SQL. The first superuser is `admin`, with the
password `admin`, which ScalarDB Cluster creates; change its password before you do anything else:

```sql
ALTER USER admin PASSWORD '<NEW_PASSWORD>';
```

The following statements create and manage users and roles:

```sql
CREATE USER carol WITH PASSWORD 'carolpw';
CREATE USER ops WITH PASSWORD 'opspw' SUPERUSER;
ALTER USER carol PASSWORD 'newpw';
CREATE ROLE auditors;
GRANT auditors TO carol;
DROP USER carol;
```

A role created without `LOGIN` is a ScalarDB role, which cannot log in, have a password or be a superuser.
Use `ALTER USER ... PASSWORD` to change a password; psql's `\password` sends a password hashed on the client,
which ScalarDB Cluster cannot check, so it is refused. psql's `\du` and `\drg` list the users and roles.

## Privileges

Privileges are ScalarDB Cluster's and mean what they mean in ScalarDB. They can be granted on a table, or on
all tables in a schema:

```sql
GRANT SELECT ON orders TO carol;
GRANT SELECT, INSERT, UPDATE ON orders TO carol;
GRANT SELECT ON ALL TABLES IN SCHEMA sales TO auditors;
REVOKE INSERT, UPDATE ON orders FROM carol;
```

ScalarDB's privileges differ from PostgreSQL's in the following ways:

- `INSERT` and `UPDATE` are one ScalarDB privilege, `WRITE`, so they must be granted and revoked together.
- `UPDATE` and `DELETE` also need `SELECT`.
- `CREATE`, `DROP` and `ALTER` can be granted on a table. On a schema, only `CREATE` (and `USAGE`) can be
  granted; other privileges fail with SQLSTATE `0LP01`. `CREATE TABLE` needs `CREATE` on the schema, and
  `ALTER TABLE` needs `ALTER` on the table.
- `GRANT ... ON ALL TABLES IN SCHEMA` grants on the namespace, so it also covers tables created later.
- `USAGE` on a schema is accepted and has no effect.
- `WITH GRANT OPTION` grants ScalarDB's `GRANT` privilege on the table or schema. ScalarDB has one grant
  option there, not one per privilege, so `REVOKE GRANT OPTION FOR` revokes it whatever privileges it names.
- `PUBLIC`, column privileges, objects other than tables and schemas, `CASCADE` and `GRANTED BY` are not
  supported.

psql's `\dp` lists the grants that have a PostgreSQL privilege letter. The catalog lists every table to every
user, as PostgreSQL does; reading a table's data is what requires the privilege.

### The frontend's metadata table

The frontend reads and writes its metadata table, `scalardb_postgres_frontend.column_types`, under the
node's own authority, not as the session's user, so no user needs privileges on it and there is no user or
password to configure. `CREATE FRONTEND METADATA TABLES`, run once as a superuser, creates the namespace and
the table. Do not grant privileges on the namespace `scalardb_postgres_frontend` to anyone: a user who can
write to that table can change how every table's columns are read. The frontend checks the session user's
`CREATE` or `ALTER` privilege before DDL writes to the metadata table.

The standalone server with `scalar.db.postgres.auth` set to `userpass` or `oidc_jwt` runs through the
ScalarDB Cluster client SDK and has no such authority. There, `CREATE FRONTEND METADATA TABLES` also creates
a service user named `scalardb_postgres_frontend` with a random password and a grant on that table only, and
prints the two properties to add to the server's properties file, `scalar.db.postgres.metadata_user` and
`scalar.db.postgres.metadata_password`. To change that password, run
`ALTER USER scalardb_postgres_frontend PASSWORD '<NEW_PASSWORD>'` as a superuser, then update the property
and restart the server.

## Encrypted columns

When ScalarDB Cluster's encryption is enabled, name the columns to encrypt when you create a table:

```sql
CREATE TABLE people (id INT PRIMARY KEY, name TEXT, ssn TEXT, card TEXT)
  WITH (encrypted = 'ssn, card');
```

ScalarDB Cluster stores those columns encrypted and decrypts them for the reader. An encrypted column cannot
be a key, be indexed, be renamed or be added later with `ALTER TABLE ADD COLUMN`. `WHERE`, `ORDER BY` and
joins on an encrypted column work, but run in the frontend after the read, so a condition on an encrypted
column alone reads the whole table. psql's `\d+` lists the encrypted columns. Without encryption enabled, the
`CREATE TABLE` fails with SQLSTATE `0A000`.

## Attribute-based access control

ScalarDB Cluster's ABAC applies to every statement that a user runs through the frontend. Set up policies,
levels, compartments, groups and user tags with ScalarDB SQL or ScalarDB Cluster's `AbacAdmin`; PostgreSQL
has no syntax for them. Then, rows that the user may not read are left out of every `SELECT`, `UPDATE` and
`DELETE` without an error, as PostgreSQL's row-level security does. An `INSERT` is tagged with the user's
default row tag unless it sets the data tag column itself, and fails with SQLSTATE `42501` if the user may
not write that tag. The data tag column reads as text, such as `CONF::`, and cannot appear in `WHERE` or
`ORDER BY`. A superuser bypasses ABAC.

## Without authentication

When ScalarDB Cluster's authentication is disabled, or when the standalone server runs on ScalarDB core, the
frontend asks for no password, and every client has the full access of the ScalarDB user that the node or the
server is configured with. `current_user` reports whatever name the client sent. Run the frontend this way
only on a trusted network, and bind it to an internal address with `scalar.db.postgres.listen_address`.
