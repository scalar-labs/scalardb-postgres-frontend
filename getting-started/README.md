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

- **JDK 25** to build the frontend (the build's toolchain, as in ScalarDB Saga) and **Java 17** or
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

The rule the frontend follows: what it cannot do raises an error, and what it accepts but cannot fully
honor is announced with a `NOTICE`. It does not silently return a different result from PostgreSQL.

- **No authentication or TLS yet.** `current_user` returns a fixed name, not the user you
  connected as.
- **`COPY` is not supported;** load data with INSERTs.
- **Window functions and recursive CTEs** work, except `RANGE`/`GROUPS` frames with offsets,
  `DISTINCT` inside a window aggregate, and a recursive CTE whose body is not
  `anchor UNION [ALL] step`.
- **Key conditions written with arithmetic** (`order_id >= $1 - 20`) are evaluated in the
  frontend instead of being pushed down to ScalarDB.
- **Large sorts and aggregations run in the frontend's memory.** A query that sorts or groups a
  very large table without a key holds all its rows in the frontend.
- **Inside a transaction, a query that scans rows the transaction already wrote fails**
  (`DB-CORE-10106`). This is a ScalarDB limitation; reads by primary key work.
- **DDL inside a transaction takes effect at once** and is not undone by `ROLLBACK`.
- **`SAVEPOINT` is accepted, but `ROLLBACK TO SAVEPOINT` works only while nothing was written
  since the savepoint.** After a write it fails, and that error aborts the whole transaction, so
  the writes before the savepoint are lost too when the transaction ends.
- **Constraints other than the primary key are not enforced.** `NOT NULL` on non-key columns,
  `UNIQUE`, `CHECK`, `REFERENCES` and `DEFAULT` are accepted by `CREATE TABLE` with a `NOTICE`
  (visible in psql and as a driver warning) and then ignored: violating rows are stored, and an
  omitted column with a `DEFAULT` reads `NULL`. `serial` columns do not generate values.
- **Type modifiers are not kept.** `CHAR(n)` is text without blank padding, so `'ab'` and
  `'ab   '` differ; `VARCHAR(n)` lengths are not enforced; `NUMERIC(p,s)` and `DECIMAL` are stored
  as `double precision`, so money arithmetic is approximate; `SMALLINT` is an `integer` without a
  range check. Each is reported with a `NOTICE` at `CREATE TABLE`. A modifier PostgreSQL itself
  rejects (`VARCHAR(10485761)`, `CHAR(0)`, `NUMERIC(1001)`) is refused with `22023`.
- **`TIMESTAMP` and `TIMESTAMPTZ` keep milliseconds, not microseconds,** which is ScalarDB's
  precision: a finer fraction is rounded half up, and `CREATE TABLE` says so with a `NOTICE` unless
  the column is declared `TIMESTAMP(3)` or coarser. `TIME` keeps microseconds as in PostgreSQL.
  `DATE` and `TIMESTAMP` values must fall in the years 1000 to 9999 (`22008` otherwise).
- **Names are shorter than PostgreSQL's.** A table or schema name over 63 bytes, or a column name
  over 56, is refused (`42622`) rather than truncated as PostgreSQL does: ScalarDB keeps the full
  name and reads columns back by it, and stores a `before_` copy of each column, so a truncated
  name would leave a table that cannot be read. A table has at most 1600 columns as in PostgreSQL,
  and on a PostgreSQL backend the `before_` copies halve what fits.
- **Isolation levels.** ScalarDB's `SNAPSHOT` reads each record's committed version without a
  snapshot across records, which `SHOW transaction_isolation` reports as `read committed`;
  `scalar.db.consensus_commit.isolation_level=SERIALIZABLE` reports `serializable`. A request for a
  stronger level than the engine runs at (`BEGIN ISOLATION LEVEL SERIALIZABLE`,
  `SET TRANSACTION ...`) is refused with `0A000` rather than ignored.
- **TEXT comparison follows the backing database's collation.** On MySQL (default
  `utf8mb4_0900_ai_ci`) and on PostgreSQL databases created with a locale other than `C`, keys and
  predicates compare case- and accent-insensitively, so `UPDATE ... WHERE k = 'A'` can change the
  row `'a'`. Create the backing database with a binary collation (`C` locale, `utf8mb4_bin`) until
  ScalarDB's collation support lands.
- **Cursors (`DECLARE`, `FETCH`, `WHERE CURRENT OF`), `COPY`, views and `GRANT` are not
  supported** and fail with a clear error.
- **Refused rather than approximated:** `TABLESAMPLE`, a `COLLATE` other than `"C"`/`POSIX`,
  `CREATE TEMP TABLE` (a table created here would be permanent), `SELECT ... INTO table`, a
  data-modifying statement inside `WITH`, an expression index (`CREATE INDEX ... ON t (lower(name))`),
  a column `DEFAULT` other than `DEFAULT NULL` (an omitted column always reads NULL here, so the
  default would be lost silently), and a comparison of a text column with a number (`name = 1`),
  which PostgreSQL rejects too.
  `CREATE UNIQUE INDEX` and a partial index (`... WHERE ...`) are created as plain indexes with a
  `NOTICE`: uniqueness is not enforced and the `WHERE` is not kept.
- **Session settings.** `SET TIME ZONE` shapes how `timestamptz` values are read and printed, as in
  PostgreSQL; `search_path` decides where an unqualified table name is looked up; `SET LOCAL`
  reverts at the end of the transaction and only warns outside one. `statement_timeout` and
  `lock_timeout` are accepted but not enforced.
- **`SELECT ... FOR UPDATE` / `FOR SHARE` take no lock.** ScalarDB is optimistic: the statement
  is accepted, and a concurrent writer is found out at commit time (`40001`), not blocked.
