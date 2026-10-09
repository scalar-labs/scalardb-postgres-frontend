# Authentication against a standalone ScalarDB Cluster node

The integration run for the authentication in `docs/plans/2026-10-09-001-feat-cluster-authentication-plan.md`:
a Cluster node with `scalar.db.cluster.auth.enabled=true` on the difftest PostgreSQL, the frontend in cluster
mode with `scalar.db.postgres.auth=userpass`, two users, and the psql scenario in `run.sh`. It settles what the
design marked **(verify)**: `getCurrentUser` under `UserpassHolder` as the login check, the holder honored on
`begin` and on every one-shot call including the lookup join's pool reads, and the SDK's exception types for the
`DB-AUTH` codes.

## Setup

1. **A core snapshot the Cluster build accepts.** The frontend builds against the ScalarDB checkout's
   `4.0.0-SNAPSHOT`; Cluster's `main` may expect an older or newer core. If `:node:installDist` fails to compile
   against the local snapshot, publish a core of Cluster main's date from an export of the ScalarDB checkout
   (`git archive <commit> | tar -x -C <dir>`, then `./gradlew :core:publishToMavenLocal -x test` there), build the
   node, and republish the branch's core from the checkout afterwards. Cluster builds need JDK 21.
2. **The client SDK**, published from the same Cluster export: `./gradlew :client:publishToMavenLocal -x test`.
   The frontend's server module compiles against it (`compileOnly`, non-transitive); nothing else of the
   frontend needs it.
3. **The node.** From the Cluster export: `./gradlew :node:installDist -x test`, then
   `node/build/install/scalardb-cluster-node/bin/scalardb-cluster-node --config node.properties` from this
   directory, with the database `clusterdb` created on the PostgreSQL at 15440 (`createdb -p 15440 clusterdb`).
   The auth tables and the default superuser `admin` / `admin` are created at startup.
4. **Users and data.** `Setup.java`, as the superuser, creates `authdemo.items` with two rows, `alice` with READ
   and WRITE on the namespace and `bob` with nothing:
   ```sh
   java -cp ../../server/build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar:$SDK Setup.java admin.properties
   ```
   where `$SDK` is `~/.m2/repository/com/scalar-labs/scalardb-cluster-java-client-sdk/4.0.0-SNAPSHOT/scalardb-cluster-java-client-sdk-4.0.0-SNAPSHOT.jar`.
5. **The frontend**, with TLS from the engine's test certificate and no service user:
   ```sh
   java -cp ../../server/build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar:$SDK \
        com.scalar.db.frontend.postgres.server.Main frontend.properties 15445
   ```
6. **The scenario:** `./run.sh` (`PORT` overrides 15445). Each check prints `PASS` or `FAIL`.

## What run.sh checks

alice reads, writes, deletes, runs a transaction, a lookup join (its inner reads come from the pool) and DDL;
`current_user` is alice; bob gets `42501` on SELECT, INSERT and CREATE TABLE and his session goes on; `SET ROLE`
is `0A000`; a wrong password and an unknown user are `28P01` after about a second; a plaintext connection is
`28000`; `\conninfo` shows the TLS connection.

## Results, 2026-10-09

Not run yet: the node could not be built on this machine. What was done and where it stopped, so the next
attempt starts there:

- The client SDK built and published from an export of Cluster `main` (2026-09-28) with JDK 21, after a clean
  of stale generated protobuf sources; the frontend's server module compiles and tests against it.
- The node did not build. Cluster `main` predates a core constructor change of 2026-09-29
  (`RollbackMutationComposer`, called once in `replication-repository`); a core of 2026-09-28 did not help,
  and patching that one call did. The node then needs `scalardb-graphql` and `scalardb-sql-direct-mode` at
  `4.0.0-SNAPSHOT`, which are published nowhere reachable from here, and the local checkouts of those
  repositories are from 2023 and mid-2026. Publishing them at matching snapshots is a build of the whole
  ScalarDB ecosystem, not of this branch.
- Two ways forward: a Cluster node image with a license key (`scalar.db.cluster.node.licensing.*`), which
  needs only `node.properties` and this directory; or a developer with the Cluster build set up, who runs
  steps 3 to 6 above as they are.

Until then the frontend side rests on the engine tests, which drive the server through pgjdbc with a stub
identity and fail any ScalarDB call made outside the session's scope on any thread, and on the server module's
test of the startup checks. The design's three **(verify)** items stay open.
