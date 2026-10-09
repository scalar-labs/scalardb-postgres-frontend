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
- **A checkout of [ScalarDB](https://github.com/scalar-labs/scalardb)** (`master`): the frontend
  builds against ScalarDB `4.0.0-SNAPSHOT`, which is not published, so it is installed into your
  local Maven repository first.
- **A PostgreSQL server** to hold ScalarDB's data, with an empty database for it, e.g.
  `createdb scalardb`. Any database ScalarDB supports works; PostgreSQL is used here.
- **psql** (any recent version), and optionally a Java application with the PostgreSQL JDBC driver.

## 1. Build

Install ScalarDB core into your local Maven repository (`~/.m2`), from the ScalarDB checkout:

```sh
./gradlew :core:publishToMavenLocal
```

Repeat this whenever you update the ScalarDB checkout. Then build the frontend, from the root of
this repository:

```sh
./gradlew shadowJar
```

This creates `server/build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar`, a self-contained jar.

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

## 3. Start the frontend

From this directory (`getting-started`), give it the properties file and the
port to listen on:

```sh
java -jar ../server/build/libs/scalardb-postgres-frontend-4.0.0-SNAPSHOT.jar scalardb.properties 15432
```

It logs `Listening on port 15432` when it is ready. Leave it running in its own terminal.

If the checkout is in a folder that a sync tool such as Dropbox rewrites, copy the jar elsewhere
first and run the copy: a running jar that gets rewritten fails with class loading errors.

## 4. Create the tables and load the data

In another terminal, from this directory:

```sh
psql -h localhost -p 15432 -d demo -f schema.sql -f data.sql
```

The database name in the connection (`demo`) is the ScalarDB namespace. `schema.sql` creates
ScalarDB's coordinator tables and the namespace if needed, and it drops and recreates the three
tables, so running this step again starts over. Loading takes 5 to 10 seconds on a local database, longer against a container or a remote one.

The frontend does not authenticate yet: any user name works, and no password is asked for.

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

## Limitations to know

What the frontend refuses, announces with a `NOTICE`, or does differently from PostgreSQL is listed
in [LIMITATIONS.md](../LIMITATIONS.md).
