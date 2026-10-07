# ScalarDB PostgreSQL Frontend

A server that speaks the PostgreSQL wire protocol and runs SQL as [ScalarDB](https://github.com/scalar-labs/scalardb)
transactions, so psql, the PostgreSQL JDBC driver and other PostgreSQL clients can use ScalarDB.

SQL is parsed with JSQLParser and planned into ScalarDB Get, Scan and mutation operations, pushing
keys, conditions, ordering and limits down to ScalarDB. The rest runs in an in-memory operator tree:
joins, aggregates, window functions, subqueries, CTEs (including recursive ones) and set operations.
It also emulates enough of `pg_catalog` for psql's meta-commands, and supports DDL, `RETURNING`,
`ON CONFLICT`, `EXPLAIN [ANALYZE]` and transactions.

**Status: experimental.** There is no authentication or TLS yet; see [Limitations](getting-started/README.md#limitations-to-know).

## Quick start

Requires JDK 17 and a checkout of ScalarDB. The frontend builds against ScalarDB `4.0.0-SNAPSHOT`,
which is not published, so install it into your local Maven repository first:

```sh
# in the ScalarDB checkout
./gradlew :core:publishToMavenLocal

# in this repository
./gradlew shadowJar
java -jar build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar scalardb.properties 15432
```

`scalardb.properties` is an ordinary ScalarDB configuration. The database name a client connects
with is the ScalarDB namespace. [`getting-started/`](getting-started/README.md) walks through the
whole setup with sample data, psql and JDBC.

## Layout

| Path | What |
|---|---|
| `src/` | the frontend and its unit tests |
| [`getting-started/`](getting-started/README.md) | setup guide, sample schema and data, and a tour of what the frontend does |
| [`difftest/`](difftest/README.md) | differential tests against native PostgreSQL, and a concurrency test |
| [`bench/`](bench/README.md) | benchmarks against native PostgreSQL, including BenchBase TPC-C |
| [`compat/`](compat/README.md) | small client programs for other languages' PostgreSQL drivers |
| `docs/` | design notes: [running in ScalarDB Cluster nodes](docs/integration-design.md) and [authentication](docs/auth-design.md) |

Changes the frontend needs in ScalarDB core itself are described in design notes kept with ScalarDB
(`scan-read-your-writes-design.md`, `text-collation-design.md`, `core-todo.md`), currently on the
`postgres-frontend` branch of the ScalarDB repository.

## Development

```sh
./gradlew check        # unit tests, Spotless, SpotBugs, Error Prone
./gradlew spotlessApply
```
