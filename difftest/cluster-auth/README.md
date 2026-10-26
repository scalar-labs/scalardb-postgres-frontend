# Authentication against a standalone ScalarDB Cluster node

The integration run for the authentication in `docs/plans/2026-10-09-001-feat-cluster-authentication-plan.md`:
a Cluster node with `scalar.db.cluster.auth.enabled=true` on the difftest PostgreSQL, the frontend in cluster
mode with `scalar.db.postgres.auth=userpass`, two users, and the psql scenario in `run.sh`. It settles what the
design marked **(verify)**: `getCurrentUser` under `UserpassHolder` as the login check, the holder honored on
`begin` and on every one-shot call including the lookup join's pool reads, and the SDK's exception types for the
`DB-AUTH` codes.

## Demo, in two terminals

Everything runs from this directory. It needs the frontend's fat jar (`./gradlew :server:shadowJar` at the
repository root), the SDK classpath in `sdk-classpath/lib/` (`./gradlew -p difftest/cluster-auth/sdk-classpath
lib`), and a locally built node in `node/`, which git ignores and the Setup section below shows how to build.
The scripts assume JDK 21 for the node and JDK 17 for the frontend at the paths in them; `JAVA21_HOME` and
`JAVA17_HOME` override those.

Terminal 1, the node (authentication on, data in the database `clusterdb` on the PostgreSQL at 15440):

```sh
./node.sh
```

Terminal 2, once the node says it is listening on 60053: the users and table, then the frontend on 15445:

```sh
./users.sh list                       # admin (superuser) is created by the node itself
java -cp ../../server/build/libs/scalardb-postgres-frontend.jar:'sdk-classpath/lib/*' Setup.java admin.properties
./frontend.sh                         # ./frontend.sh 15446 for another port
```

Then, from any terminal, what a PostgreSQL client sees:

```sh
PGPASSWORD=alicepw psql "host=localhost port=15445 dbname=authdemo user=alice sslmode=require" \
    -c 'SELECT current_user' -c 'SELECT * FROM items' -c '\conninfo'
PGPASSWORD=bobpw   psql "host=localhost port=15445 dbname=authdemo user=bob sslmode=require" -c 'SELECT * FROM items'
PGPASSWORD=wrong   psql "host=localhost port=15445 dbname=authdemo user=alice sslmode=require" -c 'SELECT 1'
PGPASSWORD=alicepw psql "host=localhost port=15445 dbname=authdemo user=alice sslmode=disable" -c 'SELECT 1'
```

alice sees her name and the rows, and `\conninfo` reports TLS; bob is refused with `42501` and Cluster's message
naming the missing READ privilege; the wrong password is refused after about a second; the plaintext attempt is
refused outright. Then, as the node's superuser `admin`, it creates a user and a role in SQL, grants, revokes and
drops them, and checks what each step lets the new user do. `./run.sh` performs 85 such checks and prints PASS or
FAIL for each.

### Users

Log in as a superuser (`admin`, password `admin`, which the node creates) and use SQL: `CREATE USER carol WITH
PASSWORD 'carolpw'`, `GRANT SELECT ON items TO carol` and so on (see `LIMITATIONS.md`). `users.sh` does the same
through the Cluster API, with `admin.properties`, and needs no frontend:

```sh
./users.sh create carol carolpw            # add superuser as a third word for a superuser
./users.sh grant carol authdemo READ,WRITE # privileges: READ, WRITE, DELETE, CREATE, DROP, TRUNCATE, ALTER, GRANT
./users.sh revoke carol authdemo WRITE
./users.sh list
./users.sh drop carol
```

A `users.sh` grant is on a namespace and covers every table in it; SQL grants on a table too. A user logs in the moment it exists; dropping it refuses
the next login (open sessions keep their token until it expires).

## Setup

1. **The Cluster client SDK and its dependencies.** The SDK jar alone is not runnable: it needs
   `scalardb-cluster-common`, `scalardb-cluster-rpc`, gRPC and the rest. `sdk-classpath/` is a two-file Gradle
   project that copies the whole runtime classpath into `sdk-classpath/lib/`:
   ```sh
   ./gradlew -p difftest/cluster-auth/sdk-classpath lib      # from the repository root
   ```
   The SDK and its two Cluster siblings come from a Cluster checkout, or an export of it
   (`git archive origin/main | tar -x -C <dir>`), built with JDK 21:
   `./gradlew :common:publishToMavenLocal :rpc:publishToMavenLocal :client:publishToMavenLocal -x test`.
2. **The node.** `./gradlew :node:installDist -x test` in the same export, then from this directory:
   ```sh
   node/build/install/node/bin/scalardb-cluster-node --config node.properties
   ```
   with the database `clusterdb` created on the PostgreSQL at 15440 (`createdb -p 15440 clusterdb`). The auth
   tables and the default superuser `admin` / `admin` are created at startup. On 2026-10-09 the node build
   needed, in the export only: `mavenLocal()` first in the root `build.gradle`'s repositories, the snapshots of
   `scalardb-sql` and `scalardb-graphql` published from clones of their `main`, and one call in
   `replication-repository` adapted to a core constructor changed on 2026-09-29.
3. **Users and data.** `Setup.java`, as the superuser, creates `authdemo.items` with two rows, `alice` with READ,
   WRITE and DELETE on the namespace (DELETE is its own privilege) and `bob` with nothing:
   ```sh
   java -cp ../../server/build/libs/scalardb-postgres-frontend.jar:'sdk-classpath/lib/*' Setup.java admin.properties
   ```
4. **The frontend**, with TLS from the engine's test certificate and no service user. The frontend sets
   `scalar.db.cluster.auth.enabled=true` and `scalar.db.cluster.client.auth.type` itself (before 2026-10-15 it
   did not: without the first every call failed with `DB-AUTH-10006`, and without the second every `oidc_jwt`
   login failed with `DB-CLUSTER-10066`).
   ```sh
   java -cp ../../server/build/libs/scalardb-postgres-frontend.jar:'sdk-classpath/lib/*' \
        com.scalar.db.frontend.postgres.server.Main frontend.properties 15445
   ```
5. **The scenario:** `./run.sh` (`PORT` overrides 15445). Each check prints `PASS` or `FAIL`.

## What run.sh checks

alice reads, writes, deletes, runs a transaction, a lookup join (its inner reads come from the pool) and DDL;
`current_user` is alice; bob gets `42501` on SELECT, INSERT and CREATE TABLE and his session goes on; `SET ROLE`
is `0A000`; a wrong password and an unknown user are refused after about a second; a plaintext connection is
refused; `\conninfo` shows TLS. Connection-time refusals are matched on their message, since psql prints no
SQLSTATE before a session exists; the engine's unit tests assert the SQLSTATEs through pgjdbc.

## Results, 2026-10-11, extended run

`run.sh` grew to 84 checks: `SUPERUSER` and `NOSUPERUSER` (with a user refused making itself superuser), a role
granted to a role and privileges through two levels of roles, a refused role cycle (`0LP01`), `WITH GRANT OPTION`
and `REVOKE GRANT OPTION FOR`, `WITH ADMIN OPTION`, `GRANT DELETE`, `CREATE` on a schema, `PASSWORD NULL`,
`DROP ROLE IF EXISTS`, a `CREATE USER` behind a comment, and psql's `\du`, `\drg`, `\dp` and `\dn+` as a non-superuser.
82 pass. The two that fail, "the old
password no longer logs in" and "no password, no login", are a Cluster bug: the node does not revoke a user's
tokens when the password changes, and the SDK reuses its cached token for the old password without logging in
again. A frontend restart makes both pass. See `cluster-issue-stale-passwords.md`; the checks stay as they are and
should pass once Cluster is fixed.

## Results, 2026-10-11

All 38 checks pass (`results-20261011.txt`), twice in a row (the run cleans up its users), same node. The SQL user
management's **(verify)** items are settled: Cluster's admin calls carry their `DB-AUTH` code in the message
(`10000` "already exists" reached the client as `42710`, `10014` "drop the current user" as `55006`), and user,
role and privilege lookups need only a login, so a grantee resolves to a user or a role for anyone. No password
appeared in the frontend's or the node's log.

## Results, 2026-10-09

All 17 checks pass (`results-20261009.txt`): node built from Cluster `main` 6e2cb011 (2026-09-28), frontend from
this branch, both on the difftest PostgreSQL. The design's **(verify)** items are settled:

- `admin.getCurrentUser()` under `UserpassHolder` is the login check: a valid pair logs in and returns the user, a
  wrong one fails with `DB-AUTH-10006` (the SDK's token request is what fails, not a password comparison), which
  the frontend reports as `28P01`.
- The holder is honored on `begin` and on every one-shot call, the lookup join's pool reads included: the join
  returned the right count as alice, where a read outside the scope would have failed with `DB-AUTH-10006`
  rather than returned rows, and bob's reads were refused with `DB-AUTH-10008`.
- Errors arrive as `ExecutionException` (admin) and `CrudException` (CRUD) whose message starts with the gRPC
  status and carries the code, for example `PERMISSION_DENIED: DB-AUTH-10008: Access denied: You need the READ
  privilege on the table authdemo.items`; keying on the code works.

Not exercised: a token expiring mid-session (`28000`), since tokens live a day, and OIDC, for want of an
identity provider.

## The in-node frontend

The same scenario runs against a node that serves the PostgreSQL protocol itself (Cluster branch
`postgres-frontend`, scalar-labs/scalardb-cluster#2114). Add to `node.properties`:

```properties
scalar.db.postgres.enabled=true
scalar.db.postgres.port=15445
scalar.db.postgres.ssl.cert=<this repository>/engine/src/test/resources/tls/server.crt
scalar.db.postgres.ssl.key=<this repository>/engine/src/test/resources/tls/server.key
```

Start that node instead of `node.sh`, skip `frontend.sh`, run `Setup.java` as above, then `PORT=15445 ./run.sh`.
`./typed.sh` then checks the declared-types table in Cluster's privileged mode: `CREATE FRONTEND METADATA TABLES`
creates no service user and prints no properties, a user with no grant on `column_types` creates and uses
`NUMERIC(p,s)` columns, DDL on another user's table is refused before any row is written, and nobody reaches the
table through SQL. The frontend hides that schema, so the script counts its rows in the backing database
(`BACKEND`, by default the PostgreSQL at 15440, database `clusterdb`). On 2026-10-26, `run.sh` passed 86 of 86 and
`typed.sh` 19 of 19 against a node built from `postgres-frontend` with Cluster's removal of the privileged mode
(#2091) reverted, since `main` is to bring the mode back.
