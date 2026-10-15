# ScalarDB PostgreSQL Frontend

A server that speaks the PostgreSQL wire protocol and runs SQL as [ScalarDB](https://github.com/scalar-labs/scalardb)
transactions, so psql, the PostgreSQL JDBC driver and other PostgreSQL clients can use ScalarDB.

SQL is parsed with JSQLParser and planned into ScalarDB Get, Scan and mutation operations, pushing
keys, conditions, ordering and limits down to ScalarDB. The rest runs in an in-memory operator tree:
joins, aggregates, window functions, subqueries, CTEs (including recursive ones) and set operations.
It also emulates enough of `pg_catalog` for psql's meta-commands, and supports DDL, `RETURNING`,
`ON CONFLICT`, `EXPLAIN [ANALYZE]` and transactions.

**Status: experimental.** Authentication and TLS only in front of ScalarDB Cluster, whose users and privileges
then apply (see [Connect as a user](getting-started/README.md#connect-as-a-user)); no `COPY`; `NUMERIC` exact
but filtered in the frontend beyond 18 digits, and declared types recorded in a metadata table of the frontend's
([docs/type-encoding.md](docs/type-encoding.md)); text comparison following the backing database's collation; and no
scan of rows a transaction has itself written. [LIMITATIONS.md](LIMITATIONS.md) has the full list, including the
results that can differ from PostgreSQL without an error.

Without authentication (the default, and the only mode without ScalarDB Cluster), anyone who can reach
the port has the configured ScalarDB user's full access: keep the frontend on a trusted network, or set
`scalar.db.postgres.listen_address=127.0.0.1`.

## Quick start

Requires JDK 25 to build (Java 17 or later to run). The frontend builds against ScalarDB `3.20.0-SNAPSHOT`,
which the build fetches from ScalarDB's snapshot repository; a core you install from a ScalarDB checkout with
`./gradlew :core:publishToMavenLocal` takes precedence.

```sh
./gradlew shadowJar
java -jar server/build/libs/scalardb-postgres-frontend.jar scalardb.properties 15432
```

`scalardb.properties` is an ordinary ScalarDB configuration. The database name a client connects
with is the ScalarDB namespace. [`getting-started/`](getting-started/README.md) walks through the
whole setup with sample data, psql and JDBC, and through the container image
(`./gradlew :server:dockerBuild`).

## Layout

| Path | What |
|---|---|
| `engine/` | the frontend as a library: the PostgreSQL wire protocol, the SQL parser and planner, and the in-memory operators, with the unit tests. A ScalarDB Cluster node can embed it. |
| `server/` | the standalone server: `main`, the fat jar, and the container image (`server/src/docker`) |
| `build-logic/`, `gradle/libs.versions.toml` | shared Gradle conventions and the dependency versions |
| [`getting-started/`](getting-started/README.md) | setup guide, sample schema and data, and a tour of what the frontend does |
| [`LIMITATIONS.md`](LIMITATIONS.md) | what the frontend refuses, announces with a `NOTICE`, or does differently from PostgreSQL |
| [`RELEASE-NOTES.md`](RELEASE-NOTES.md) | changes per release, and what to do when upgrading |
| [`difftest/`](difftest/README.md) | differential tests against native PostgreSQL, and a concurrency test |
| [`bench/`](bench/README.md) | benchmarks against native PostgreSQL, including BenchBase TPC-C |
| [`compat/`](compat/README.md) | small client programs for other languages' PostgreSQL drivers |
| `docs/` | design notes: [running in ScalarDB Cluster nodes](docs/integration-design.md), [authentication](docs/auth-design.md) and [declared types](docs/type-encoding.md) |

Changes the frontend needs in ScalarDB core itself are described in design notes kept with ScalarDB
(`scan-read-your-writes-design.md`, `text-collation-design.md`, `core-todo.md`), currently on the
`postgres-frontend` branch of the ScalarDB repository.

## Development

The build compiles with the JDK 25 toolchain and `--release 17`, because the engine runs inside
ScalarDB Cluster nodes, whose baseline is Java 17.

```sh
./gradlew check        # unit tests, javadoc, Spotless, SpotBugs with FindSecBugs, Error Prone, NullAway
./gradlew spotlessApply
```
