# Driver and ORM compatibility runs

Every driver tested works, and every ORM tested works once the application supplies primary keys. The limits
below apply to all of them. For the user-facing version, see [Clients and tools](../docs/user/clients.md).

Results are from the runs on 2026-11-10 against 9d61fe0; each script prints `ok` or `FAIL` per step.

## Drivers

| Driver | Version | Result | What the run covers |
|---|---|---|---|
| psycopg (Python) | 3.3.8 | all steps | `executemany`, `ANY(list)`, transaction, auto-prepared statements, binary types |
| node-postgres (Node.js) | 8.23.1 | all steps | `ANY(array)`, transaction, named prepared statements, `RETURNING` |
| pgx (Go) | 5.12.0 | all steps | binary parameters and results, `ANY(array)`, transaction, batch pipeline |
| pg (Ruby) | 1.7.0 | all steps | through ActiveRecord |
| pgjdbc (Java) | 42.7.8 | all steps | through the three Java frameworks, plus `DatabaseMetaData.getTables` |

## ORMs and frameworks

| Framework | Version | Primary keys | Schema features | Notes |
|---|---|---|---|---|
| SQLAlchemy | 2.1.8 | Default (`SERIAL`) fails. Supply the id. | `create_all`, `drop_all`, reflection of tables, columns, primary key, indexes and foreign keys | |
| Sequelize | 6.37.8 | Default (`SERIAL`) fails. Declare `primaryKey: true` and supply the id. | `sync({ force: true })`, `describeTable`, `showIndex`, `showAllTables` | |
| ActiveRecord | 7.2.4 | Default (`bigserial`) fails. Use `id: :integer` and supply the id. | `create_table`, `drop_table`, `columns`, `indexes`, `primary_key` | Default timestamps fail; declare `precision: 3`. |
| Spring Data JDBC | 3.5.5 | `save` with a null id fails. Insert with `JdbcAggregateTemplate.insert` and an assigned id, as `save` with an id updates. | not used (DDL by hand) | |
| Spring Data JPA (Hibernate) | 3.5.5 (6.6.33) | `IDENTITY` and `SEQUENCE` (the default) fail. Assign the id, or use `GenerationType.UUID`. | `ddl-auto=update` creates tables | A `uuid` column is reported as `text`, so each start re-issues an `ALTER` that fails and is logged. |
| MyBatis | 3.5.19 | You write the INSERT. `useGeneratedKeys` works when the id is supplied. | not used (DDL by hand) | |

## Limits every stack hits

- **A query over rows the transaction already wrote fails** with `DB-CORE-10106`, in every ORM above: a
  query after an insert in one transaction, including Hibernate's flush before JPQL. A read by primary key
  works. Read by key, use `RETURNING`, or commit first.
  ([details](../docs/user/clients.md#reading-rows-that-the-transaction-wrote))
- **The database generates no ids**: `serial`, identity columns and sequences give a null id (`nextval` is
  unsupported). `GENERATED ... AS IDENTITY` is accepted at `CREATE TABLE` and fails only at the insert.
  Supply ids, for example UUIDs. ([details](../docs/user/clients.md#generated-values-and-defaults))
- **Timestamps finer than a millisecond are refused**, which is what an ORM sends for "now" unless the
  column is declared `timestamp(3)`. ([details](../docs/user/sql-reference.md))
- **DDL takes effect at once** and is not undone by `ROLLBACK`; `ALTER TABLE` supports only `ADD COLUMN`
  and `RENAME COLUMN`. ([details](../docs/user/clients.md#schema-migrations))
- **`ROLLBACK TO SAVEPOINT`** is honored only while nothing was written since the savepoint.
- **Unsupported features fail with SQLSTATE `0A000`**, which HikariCP treats as a broken connection and
  closes.

## Running

The scripts expect a frontend on port 15444 with database (namespace) `orm`, and
`scalar.db.metadata.cache_expiration_time_secs=1` in its properties file (see the findings log).

```sh
java -jar ../server/build/libs/scalardb-postgres-frontend.jar scalardb.properties 15444 &   # Java 17 or later
psql -h localhost -p 15444 -U postgres -d orm \
  -c "CREATE COORDINATOR TABLES IF NOT EXISTS" -c "CREATE FRONTEND METADATA TABLES" -c "CREATE SCHEMA IF NOT EXISTS orm"

# Python: psycopg 3 + SQLAlchemy 2
python3 -m venv venv && ./venv/bin/pip install 'psycopg[binary]' sqlalchemy && ./venv/bin/python python/test_py.py

# Node: pg (node-postgres) + Sequelize 6
(cd node && npm install pg sequelize && node test_node.js)

# Go: pgx v5
(cd go && go mod init compat && go get github.com/jackc/pgx/v5 && go run .)

# Ruby: pg + ActiveRecord 7
gem install pg activerecord && ruby ruby/test_rb.rb

# Java: Spring Boot 3.5 with Spring Data JDBC, Spring Data JPA (Hibernate 6.6) and MyBatis 3 (JDK 21)
(cd java && gradle -q run)
```

The steps that fail on purpose, to show a limit: SQLAlchemy `create_all + insert` and Sequelize
`transaction` (DB-CORE-10106), each ORM's default-id step, ActiveRecord's default-precision timestamps, and
the Java steps named after a limit.

## Findings log

What the runs taught about the frontend itself, oldest first.

2026-10-04. ScalarDB caches table metadata and its admin does not refresh the cache, so a table dropped and
recreated with other columns (two test suites sharing a name, or `ALTER TABLE ADD COLUMN` followed by an
insert) was rejected with DB-CORE-10017 until the cache expired. Run these with
`scalar.db.metadata.cache_expiration_time_secs=1`: with a cache that short the frontend answers
DROP/ALTER/index DDL only after it has expired. And a failed statement inside a transaction left the ORM's
ROLLBACK reporting "No active transaction" instead of the real error; COMMIT and ROLLBACK outside a
transaction are now no-ops, as in PostgreSQL, and statement failures are logged at INFO. The connected
namespace is presented as schema `public`, as PostgreSQL would, so tools that assume `public` find the
tables; the namespace name still works as a qualifier.

2026-10-08. Sequelize opens every connection with `SET TIME ZONE INTERVAL '+00:00' HOUR TO MINUTE`, and its
pool retries a connection whose setup statement fails, without end: when the frontend started to validate
time zone names that spelling had to be accepted along with the numeric (`SET TIME ZONE 9`) and POSIX
(`'+05:30'`, west-positive) ones PostgreSQL takes, or the Node run never finished.

A catalog query the engine cannot plan fails with its error, as any other query does. For a while such a
query was answered with no rows, which kept psycopg from reporting no result at all (SQLAlchemy's
ResourceClosedError) but let SQLAlchemy's reflection read an empty answer as the truth; the queries the
clients here send now plan, and their answers are checked against PostgreSQL 16's
(`engine/src/test/resources/clients`, captured with `difftest/catalog`). A Java Error inside a statement (a
stack overflow on deeply nested SQL, a missing class) is reported as SQLSTATE XX000 and the connection stays
open; before, the connection thread died without a message.

2026-11-09. The Java stacks were added and every stack was rerun, with a default-id step per ORM. Since the
frontend began refusing sub-millisecond timestamps (777a6db), ActiveRecord's automatic `created_at` fails
unless declared `precision: 3`; the October notes had all ActiveRecord steps passing. Hibernate's
`ddl-auto=update` re-issues `ALTER COLUMN id SET DATA TYPE uuid` on every start, because a `uuid` column is
stored and reported as `text`. A `SEQUENCE` id failed with `0A000` on `nextval`, HikariCP closed the
connection, and the rollback after it failed with "Connection is closed". `DB-CORE-10106` arrives with
SQLSTATE `42601`, so psycopg raises it as a `SyntaxError`.
