# Limitations to know

The rule the frontend follows: what it cannot do raises an error, and what it accepts but cannot fully
honor is announced with a `NOTICE`. It does not silently return a different result from PostgreSQL,
except in the cases gathered under [Results that can differ without an error](#results-that-can-differ-without-an-error)
at the end of this section.

- **Authentication only in front of ScalarDB Cluster.** With `scalar.db.postgres.auth=userpass` or
  `oidc_jwt` (the getting-started guide's [Connect as a
  user](getting-started/README.md#connect-as-a-user)), clients log in as Cluster users over TLS, every
  call of the session runs as that user, and Cluster enforces privileges (`42501`). The password crosses
  the wire in clear text inside TLS, as PostgreSQL itself does for LDAP and RADIUS, because Cluster must
  see it; libpq's `require_auth=scram-sha-256` refuses that. Without Cluster there is no user store:
  `auth=none` trusts every connection and `current_user` echoes the name the client gave. With
  authentication on, the catalog lists every table whatever the user's privileges, by design: PostgreSQL
  does the same, and reading the data is what fails. `\du`, `\drg` and `\dp` list Cluster's users, roles
  and grants, but `\dp` shows only what has a PostgreSQL table privilege letter (not `CREATE`, `DROP` or
  `ALTER`), merges namespace grants into each table's, names `scalardb` as every grantor, and costs
  Cluster two calls per grantee and table. Still open: an OIDC session outlives its token and ends with
  `28000` when it expires. A Cluster bug lets an old password keep logging in for up to the token
  lifetime (`scalar.db.cluster.auth.auth_token_expiration_time_minutes`, a day by default) after `ALTER
  USER ... PASSWORD` or after the user is dropped and re-created, as long as it logged in through this
  frontend process before: shorten that setting on the nodes, or restart the frontend after such a
  change ([details](difftest/cluster-auth/cluster-issue-stale-passwords.md)).
- **Users, roles and privileges are Cluster's.** With authentication on, `CREATE USER`/`ROLE`,
  `ALTER USER`/`ROLE`, `DROP USER`/`ROLE`, `GRANT` and `REVOKE` act on Cluster's users through
  `AuthAdmin`; with `auth=none` they fail with `0A000`. Privileges mean what they mean in ScalarDB
  SQL: `INSERT` and `UPDATE` are one ScalarDB privilege (WRITE) and must be granted and revoked
  together, `UPDATE` and `DELETE` need `SELECT`, and `CREATE`, `DROP` and `ALTER` may be granted on a
  table (`0LP01` otherwise). `GRANT ... ON ALL TABLES IN SCHEMA` is a namespace grant, which also
  covers tables created later; `USAGE` on a schema is accepted and does nothing. A role without
  `LOGIN` is a ScalarDB role, which cannot have a password or be a superuser. psql's `\password`
  sends a client-hashed verifier, which is refused: use `ALTER USER name PASSWORD '...'`. Not
  supported (`0A000`): `PUBLIC`, column privileges, `CASCADE`, `GRANTED BY`, other object kinds than
  tables and schemas, and role options other than `PASSWORD`, `SUPERUSER` and `LOGIN`. Dropping
  several names is not atomic.
- **Encryption and ABAC are Cluster's, and partly outside SQL.** `CREATE TABLE ... WITH (encrypted =
  'a, b')` encrypts columns, but `ALTER TABLE ADD COLUMN` cannot add an encrypted one, and an encrypted
  column cannot be a key, be indexed, be renamed or change type. A condition or sort on an encrypted
  column runs in the frontend, after the read: `WHERE ssn = '...'` alone reads the whole table. ABAC
  policies and user tags are managed with ScalarDB SQL or `AbacAdmin`, not SQL here, and the data tag
  column cannot be filtered or sorted on (`0A000`, Cluster's refusal). Per-statement read and write
  tags (Cluster's `abac-read-tag-*` and `abac-write-tag-*` operation attributes) cannot be set: every
  statement runs with the user's own tags. See [Encrypted columns and row-level
  access](getting-started/README.md#encrypted-columns-and-row-level-access).
- **`COPY` is not supported;** load data with INSERTs.
- **Resource limits PostgreSQL does not have.** A sort, aggregate, window or join side holds its
  rows in memory, so one of them may hold at most a million rows
  (`scalar.db.postgres.max_rows_in_memory`); past that the statement fails with `54000` rather than
  exhausting the memory every session shares. A session may hold at most 64 MiB of prepared
  statements and portals, a statement may spend at most 5 seconds matching regular expressions
  over all its rows, `lpad` and `rpad` may build at most 10 million characters, and a connection
  must finish its startup and login within 60 seconds. The
  [settings table](getting-started/README.md#2-configure) lists them all.
- **Window functions and recursive CTEs** work, except `RANGE`/`GROUPS` frames with offsets,
  `DISTINCT` inside a window aggregate, and a recursive CTE whose body is not
  `anchor UNION [ALL] step`.
- **Key conditions written with arithmetic** (`order_id >= $1 - 20`) are evaluated in the
  frontend instead of being pushed down to ScalarDB.
- **Which key columns are the partition key** is not part of PostgreSQL's catalog: a query
  reads one partition only when it fixes the whole partition key, and scans the table otherwise.
  The table comment says which they are, as `\dt+` and `obj_description(oid, 'pg_class')` show
  (`ScalarDB partition key (w_id, d_id), clustering key (o_id DESC)`). Table sizes are unknown
  and show empty.
- **psql's describe commands answer from that catalog.** `\l`, `\dn`, `\dt`, `\d`, `\di`, `\du`,
  `\dp` and the rest (with `+`) work; database and table sizes show empty, and ScalarDB has no
  domains, collations, publications or tablespaces beyond PostgreSQL's two built-in ones, so those
  lists come back empty.
- **JDBC metadata and DBeaver's PostgreSQL driver read the same catalog.** pgjdbc's
  `DatabaseMetaData` (tables, columns, primary keys, indexes, keywords, type info) and the queries
  DBeaver's PostgreSQL mode sends to connect and browse a table are tested against the frontend,
  and DBeaver 26.2 browses tables, columns, keys and indexes and edits rows in its data grid;
  pgAdmin and DataGrip have not been tried. A result set does not say which table its columns
  come from (`ResultSetMetaData.getTableName` is empty, as for a computed column); DBeaver finds
  the table from the query instead, but a tool that relies on the driver for it treats a result
  grid as read-only. `pg_settings`
  shows the server's defaults, not a session's `SET` values, which `SHOW` gives.
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
- **Declared types are recorded in the frontend's metadata table** (`docs/type-encoding.md`): `CHAR(n)`,
  `VARCHAR(n)`, `SMALLINT` and `NUMERIC` keep their declaration and PostgreSQL's rules for it
  (`amount NUMERIC(10,2)` is a ScalarDB `BIGINT` column `amount` holding cents, and a row in
  `scalardb_postgres_frontend.column_types` saying so). The table is created once with `CREATE FRONTEND
  METADATA TABLES`; until then such a declaration is refused (`55000`). With Cluster authentication the
  frontend reads and writes it as a service user, whose password is the `scalar.db.postgres.metadata_password`
  property. Other ScalarDB clients see the stored values (1990, not 19.90) unless they read that table. A
  column's type cannot be changed afterwards
  (`ALTER COLUMN ... TYPE` is refused with `0A000`; RENAME COLUMN keeps the type). Columns created by an
  earlier release keep their plain ScalarDB types. A modifier PostgreSQL itself rejects
  (`VARCHAR(10485761)`, `CHAR(0)`, `NUMERIC(1001)`) is refused with `22023`. Known differences for
  `CHAR(n)`: it is stored without its padding and padded back when a column is returned as such, so
  `octet_length`, `concat` and `to_json` see no padding, and a value passed through an aggregate,
  expression or `UNION` (`max(c)`, `COALESCE(c, 'x')`) comes back unpadded; a parameter declared `text`
  compares with it as if it were untyped, ignoring trailing spaces.
- **`NUMERIC` and `DECIMAL` are exact, with two limits** (`docs/type-encoding.md`). Up to 18 digits
  (15 on Cosmos DB, object storage and in Cluster mode) a column is a scaled `BIGINT`, which cannot
  hold `NaN` (`0A000`). Without a precision, or with more digits, it is stored as text: every
  comparison, `ORDER BY` and `GROUP BY` on it runs in the frontend rather than in the database, so a
  filter on it reads the whole partition or table, and it cannot be a key or index column (`0A000`,
  naming the fix: declare `NUMERIC(p,s)` with p up to 18). ORMs often declare such columns by default:
  Prisma's `Decimal` is `DECIMAL(65,30)` and Rails' `decimal` without options is `NUMERIC`; give them a
  precision of 18 or less (`@db.Decimal(12,2)`, `precision: 12, scale: 2`) where a filter on the column
  should be pushed down. Columns created before this release stay `double precision`. This changes
  earlier releases' behaviour: `id NUMERIC PRIMARY KEY` and `id NUMERIC(20) PRIMARY KEY`, which used to
  be accepted as `double precision`, are now refused.
- **`TIMESTAMP` and `TIMESTAMPTZ` keep milliseconds, not microseconds,** which is ScalarDB's
  precision: a finer fraction is rounded half up, and `CREATE TABLE` says so with a `NOTICE` unless
  the column is declared `TIMESTAMP(3)` or coarser. `TIME` keeps microseconds as in PostgreSQL.
  `DATE` and `TIMESTAMP` values must fall in the years 1000 to 9999 (`22008` otherwise).
- **Names are shorter than PostgreSQL's.** A table or schema name over 63 bytes, or a column name
  over 56, is refused (`42622`) rather than truncated as PostgreSQL does: ScalarDB keeps the full
  name and reads columns back by it, and stores a `before_` copy of each column, so a truncated
  name would leave a table that cannot be read. A table has at most 1600 columns as in PostgreSQL,
  and on a PostgreSQL backend the `before_` copies halve what fits. A schema, table, column or index
  name may not contain a quote (`'`, `"`, a backquote), a bracket, a backslash, a semicolon or a
  control character (`42602`): PostgreSQL allows them in quoted identifiers, but ScalarDB's JDBC
  storage writes names into SQL without escaping them. A table or schema name may not contain a dot
  either (`42602`): ScalarDB, and the frontend's metadata table, key tables by `namespace.table`.
- **Keys, and the MySQL backend.** ScalarDB refuses an empty string as a partition key value
  (`22023`). On MySQL, ScalarDB creates key and indexed `TEXT` columns as `varchar(128)`
  (`scalar.db.jdbc.mysql.variable_key_column_size`), so a longer value fails at commit (`22001`),
  `CREATE INDEX` on a column already holding longer values fails, and a composite `TEXT` key can
  exceed MySQL's 3072-byte index limit; `NaN`, `Infinity` and `-0` cannot be stored in `DOUBLE`
  columns there.
- **While a backing database is unreachable, `COMMIT` waits** 60 to 150 seconds before it fails,
  with `40003` when the outcome is unknown. Data stays consistent, but a client timeout shorter than
  that must be treated as an unknown outcome, not a failure.
- **Isolation levels.** ScalarDB's `SNAPSHOT` reads each record's committed version without a
  snapshot across records, which `SHOW transaction_isolation` reports as `read committed`;
  `scalar.db.consensus_commit.isolation_level=SERIALIZABLE` reports `serializable`. A request for a
  stronger level than the engine runs at (`BEGIN ISOLATION LEVEL SERIALIZABLE`,
  `SET TRANSACTION ...`) is refused with `0A000` rather than ignored.
- **One collation for all text: ScalarDB's.** Text compares in `scalar.db.collation`: byte order
  (`BINARY`, the default) or an ICU locale (`ICU`), and the backing database must be set up to
  compare the same way, since keys and pushed-down conditions are compared there. With Cluster, set the same
  `scalar.db.collation` in the frontend's properties as on the nodes.
- **Under `ICU`, pattern matching on table text is refused** with `0A000`, as PostgreSQL 16 refuses it
  on a nondeterministic collation: `LIKE`, `ILIKE`, `SIMILAR TO`, regular expressions, and `position`,
  `strpos`, `replace` and `starts_with`. Literals, parameters and catalog names are not affected, so
  psql's and drivers' catalog queries work.
- **Cursors (`DECLARE`, `FETCH`, `WHERE CURRENT OF`), `COPY` and views are not supported** and
  fail with a clear error.
- **Refused rather than approximated:** `TABLESAMPLE`, a `COLLATE` naming a collation other than
  ScalarDB's (`"C"`/`POSIX` under `BINARY`, the ICU one that `pg_collation` lists under `ICU`), a
  `COLLATE` other than `"default"` in a column definition,
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
  a cancel request (psql's Ctrl-C, JDBC's `Statement.cancel()`) stop a statement with `57014`, as in
  PostgreSQL; `lock_timeout` is accepted but has nothing to time, since nothing waits for a lock.
- **`SELECT ... FOR UPDATE` / `FOR SHARE` take no lock.** ScalarDB is optimistic: the statement
  is accepted, and a concurrent writer is found out at commit time (`40001`), not blocked.

### Results that can differ without an error

Every other limitation above shows up as an error or a `NOTICE`. These do not: the statement succeeds and
the result is not what PostgreSQL would return.

- **`NUMERIC` and `DECIMAL` columns created by an earlier release**, which are `double precision`
  (new ones are exact). On such a column `SELECT price * qty`
  carries floating-point noise (`19.90 * 3` is `59.699999999999996`), `WHERE price * 3 = 0.3` finds
  nothing, a value is stored unrounded (`1.005` stays `1.005` in `NUMERIC(10,2)`) and prints without
  its scale (`19.9`). `sum()` and `avg()` over any `double precision` column are computed exactly and
  rounded once, so their last digit can differ from PostgreSQL's running float sum. `SMALLINT`
  arithmetic does not overflow at 32767 (`s + s` on 20000 is 40000, where PostgreSQL errors).
- **A RENAME COLUMN interrupted midway.** ScalarDB renames a column and its transaction before-image
  column in two steps. If the frontend or the database stops between them, the table is left with
  the new column and the old before-image: clients see an extra column named `before_<old name>`,
  and writes to the table can fail. Repair it outside the frontend by renaming that before-image column
  to `before_<new ScalarDB name>` in the backing database (todo 054).
- **Text when the backing database's collation is not ScalarDB's.** On MySQL (default
  `utf8mb4_0900_ai_ci`), or on PostgreSQL databases created with a locale other than `C`, under the
  default `BINARY`, whatever is pushed down follows the database's collation while the same expression
  evaluated in the frontend follows ScalarDB's, so one query can answer differently depending on the
  path it takes. Affected shapes: a range predicate on text (`WHERE name < 'a'`), `ORDER BY`
  on a clustering key, a lookup by text key or secondary index (`WHERE k = 'a'` matches the row `'A'`
  on MySQL), and a `JOIN` whose inner side is such a lookup (`JOIN t ON t.k = s.k` pairs `'a'` with
  `'A'`, while the same join with the sides swapped pairs nothing). Set the backing database up to
  match `scalar.db.collation`. Under `ICU`, a comparison of two literals (`SELECT 'alice' < 'BOB'`)
  also follows ScalarDB's collation, where PostgreSQL uses the database's.
- **Sub-millisecond timestamps.** A `TIMESTAMP` or `TIMESTAMPTZ` value is rounded to milliseconds on
  write, so `WHERE ts = '2024-01-01 00:00:00.123456'` finds nothing after inserting that value.
- **Reads are per record, not per statement, under `SNAPSHOT`.** A multi-row read next to a
  concurrent writer can see some rows from before that writer's commit and some from after:
  `SELECT sum(balance) FROM accounts` during a transfer can report a total that never existed.
  PostgreSQL's `READ COMMITTED` gives each statement one snapshot. ScalarDB's `SERIALIZABLE`
  (`scalar.db.consensus_commit.isolation_level`) validates the read at commit and fails it instead.
- **A read inside a batch splits it.** Writes outside `BEGIN` sent together, as one Query string
  (`psql -c "INSERT ...; INSERT ..."`) or as a pgjdbc batch, run in one implicit transaction, as in
  PostgreSQL: one failing write undoes the others. A read or any other statement among them commits
  the writes before it and runs on its own, so a later failure no longer undoes those earlier writes.
  The reason is that ScalarDB refuses a scan over records its transaction already wrote
  (`DB-CORE-10106`). Inside `BEGIN` ... `COMMIT` the mix stays atomic, but such a scan fails.
- **A value where PostgreSQL raises an error.** `REAL` arithmetic runs in double precision, so a
  product beyond `3.4e38` is returned rather than refused; an aggregate that would overflow in a
  group that `HAVING` discards is never evaluated; `round(double, n)` and `max(boolean)` are
  accepted.
- **`||` between an array and a typed NULL.** A NULL's declared type is not kept, so
  `NULL::int[] || 5` is NULL where PostgreSQL gives `{5}`, and `ARRAY[1] || NULL::int` is `{1}`
  where PostgreSQL gives `{1,NULL}`.
- **Character case follows Unicode, not the database's ctype.** `upper('é')` is `'É'` here;
  PostgreSQL under the `C` locale leaves it unchanged.
- **Unenforced constraints and locks**, listed above: a row violating `UNIQUE`, `CHECK` or a non-key
  `NOT NULL` is stored, and `SELECT ... FOR UPDATE` does not block a concurrent writer.
