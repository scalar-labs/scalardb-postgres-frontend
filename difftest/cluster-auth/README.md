# Authentication against a standalone ScalarDB Cluster node

The integration run for the authentication in `docs/plans/2026-10-09-001-feat-cluster-authentication-plan.md`:
a Cluster node with `scalar.db.cluster.auth.enabled=true` on the difftest PostgreSQL, the frontend in cluster
mode with `scalar.db.postgres.auth=userpass`, two users, and the psql scenario in `run.sh`. It settles what the
design marked **(verify)**: `getCurrentUser` under `UserpassHolder` as the login check, the holder honored on
`begin` and on every one-shot call including the lookup join's pool reads, and the SDK's exception types for the
`DB-AUTH` codes.

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
   java -cp ../../server/build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar:'sdk-classpath/lib/*' Setup.java admin.properties
   ```
4. **The frontend**, with TLS from the engine's test certificate and no service user. Note
   `scalar.db.cluster.auth.enabled=true` in `frontend.properties`: without it the SDK never logs in, and every
   call fails with `DB-AUTH-10006`.
   ```sh
   java -cp ../../server/build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar:'sdk-classpath/lib/*' \
        com.scalar.db.frontend.postgres.server.Main frontend.properties 15445
   ```
5. **The scenario:** `./run.sh` (`PORT` overrides 15445). Each check prints `PASS` or `FAIL`.

## What run.sh checks

alice reads, writes, deletes, runs a transaction, a lookup join (its inner reads come from the pool) and DDL;
`current_user` is alice; bob gets `42501` on SELECT, INSERT and CREATE TABLE and his session goes on; `SET ROLE`
is `0A000`; a wrong password and an unknown user are refused after about a second; a plaintext connection is
refused; `\conninfo` shows TLS. Connection-time refusals are matched on their message, since psql prints no
SQLSTATE before a session exists; the engine's unit tests assert the SQLSTATEs through pgjdbc.

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
