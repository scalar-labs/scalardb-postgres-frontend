# Declared types in the frontend's metadata table

ScalarDB has no decimal type, no type modifiers and no metadata a client can attach to a column. So when a
PostgreSQL client declares a type ScalarDB cannot hold as is, the frontend stores the value in a form that keeps
it exact, under the column's own name, and records the declaration in a table of its own,
`scalardb_postgres_frontend.column_types`. This document is the contract for anyone else who reads or writes
those columns (other ScalarDB applications, the Data Loader, ScalarDB Analytics), and the proposed starting point
for ScalarDB core to keep declared types itself: the table is laid out like core's own metadata table.

A column without a row is an ordinary ScalarDB column and means exactly its ScalarDB type: tables created before
this feature, and tables created by other clients, are not affected.

## Setup

`CREATE FRONTEND METADATA TABLES`, run once through the frontend, creates the namespace and the table. With
Cluster authentication on (`scalar.db.postgres.auth`), it must be run by a superuser, and it also creates the
service user `scalardb_postgres_frontend` with a random password, grants it `SELECT, INSERT, UPDATE, DELETE` on
this one table and nothing else, and prints the two properties every frontend then needs:

```
scalar.db.postgres.metadata_user=scalardb_postgres_frontend
scalar.db.postgres.metadata_password=<shown once>
```

Run again, it creates only what is missing and leaves an existing user's password alone. Rotate the password with
`ALTER USER scalardb_postgres_frontend PASSWORD '...'`, then update the property and restart each frontend.
Without authentication, the configured ScalarDB user reads and writes the table like every other.

Until the table exists, DDL declaring one of the types below fails with `55000`; every other statement works.
The frontend hides the namespace from its catalog and refuses DDL and DML in it (`42501`); outside the
frontend, nobody but the service user should hold privileges on the table, since a forged row changes how a
column is read.

## The table

| Column | Type | Key | Value |
|---|---|---|---|
| `full_table_name` | TEXT | partition | `namespace.table`, as in ScalarDB's metadata table |
| `column_name` | TEXT | clustering | the column's name, the same in ScalarDB and to clients |
| `sql_type` | TEXT | | `NUMERIC`, `VARCHAR`, `CHAR`, `SMALLINT`, `JSON` or `JSONB` |
| `type_precision` | INT | | NUMERIC digits; null for NUMERIC without a precision |
| `type_scale` | INT | | NUMERIC scale; null when `type_precision` is |
| `type_length` | INT | | n of `VARCHAR(n)` and `CHAR(n)` |
| `encoding` | TEXT | | how the value is stored, below |
| `data_type` | TEXT | | the ScalarDB type the encoding needs: `BIGINT`, `TEXT` or `INT` |
| `created_by` | TEXT | | the DDL statement that wrote the row (frontend bookkeeping, not part of the type) |

One row per column with a declared type; a table with none has no rows.

| Declared | `sql_type` | `encoding` | `data_type` | Value stored |
|---|---|---|---|---|
| `NUMERIC(p,s)`, `DECIMAL(p,s)`, p ≤ 18 (15 on Cosmos DB, object storage and in Cluster mode) | NUMERIC | `SCALED_BIGINT` | BIGINT | the value × 10^s, an integer below 10^p in absolute value |
| `NUMERIC(p,s)` with more digits than that | NUMERIC | `DECIMAL_TEXT` | TEXT | the decimal, plain notation, exactly s fraction digits; or `NaN` |
| `NUMERIC`, `DECIMAL` without a precision | NUMERIC | `DECIMAL_TEXT` | TEXT | the decimal, plain notation, the scale it was written with; or `NaN`, `Infinity`, `-Infinity` |
| `VARCHAR(n)` | VARCHAR | `TEXT` | TEXT | at most n characters |
| `CHAR(n)`; `CHAR` is `CHAR(1)` | CHAR | `TRIMMED_TEXT` | TEXT | at most n characters, without trailing spaces |
| `SMALLINT`, `INT2` | SMALLINT | `INT` | INT | −32768 to 32767 |
| `JSON` | JSON | `TEXT` | TEXT | valid JSON, as written |
| `JSONB` | JSONB | `TEXT` | TEXT | valid JSON as PostgreSQL normalises jsonb: keys sorted by length then bytes, the last of a duplicate key, `": "` and `", "` separators |

A negative scale is allowed: `NUMERIC(5,-2)` stores 12300 as 123. A `DECIMAL_TEXT` column cannot be a key or
index column: ScalarDB would compare its text, not its number. Neither can a `JSON` or `JSONB` column, and
conditions on one are evaluated in the frontend: jsonb compares by value (`1.0` equals `1.00`), json not at
all. A `JSONB` value another client stored unnormalised still reads as that value. `VARCHAR` without a length is plain `TEXT`, with
no row.

The digit limit for `SCALED_BIGINT` is chosen when the column is created, from the storage the frontend is
configured with. A column created with 16 to 18 digits must not later be used through a storage that keeps a
BIGINT exact only up to 2^53 (Cosmos DB, object storage): ScalarDB refuses the larger values there.

## When a row applies

A row is used only while its column exists and has the row's `data_type`. A row whose column is gone (a dropped
table, a column renamed away) or now has another type is ignored, so leftovers are inert. A row whose `sql_type`
and `encoding` the frontend does not know (a newer frontend's) keeps the column readable as stored and refuses
every write to it.

DDL writes rows before it changes the table and deletes them after: `CREATE TABLE` and `ADD COLUMN` write the
rows, then create; `RENAME COLUMN` writes the row under the new name, renames, then deletes the old row;
`DROP TABLE` drops, then deletes the rows. A failure in between leaves a row nothing uses rather than a column
without its type. The deletes after a DDL remove only rows still carrying the token they had before it, so a
table or column of the same name created meanwhile by another session keeps its rows; and a failure to delete
them is logged, not reported, since the DDL itself took effect. When a `CREATE TABLE` loses a race with another
session's, the rows that were there are put back. `ALTER COLUMN TYPE` is refused (`0A000`).

ScalarDB DDL and the rows are not one transaction, so some windows remain:

- a frontend that dies after writing a `CREATE TABLE`'s rows while another client creates a table of the same
  name, with the same column names and ScalarDB types, at that moment;
- a table dropped and created again by another ScalarDB client (not through a frontend) with the same column
  names and ScalarDB types: it inherits the rows of the table it replaced;
- a `CREATE TABLE` or `ADD COLUMN` that reports a failure (a timeout, say) although ScalarDB applied it: the
  frontend cannot tell it from another session's DDL, so it takes its rows back, and the new columns read as
  their ScalarDB types. Drop and create the table again, or record the rows by hand.

Table and schema names may not contain a dot (`42602`): `namespace.table` is the key here, as in ScalarDB's own
metadata.

## Reading

| `encoding` | Decoded value |
|---|---|
| `SCALED_BIGINT` | `stored / 10^s`, with s fraction digits: 1990 in a `NUMERIC(10,2)` column is 19.90 |
| `DECIMAL_TEXT` | the text as a decimal; `NaN` and the infinities as such |
| `TEXT` | the text |
| `TRIMMED_TEXT` | the text; a client pads it with spaces to n characters on output |
| `INT` | the integer |

A view per table gives other SQL tools the client's values. On the JDBC storage, for example:

```sql
CREATE VIEW orders_v AS
SELECT id,
       amount / 100.0 AS amount,   -- NUMERIC(10,2), SCALED_BIGINT: divide by 10^2
       rpad(code, 3)  AS code,     -- CHAR(3), TRIMMED_TEXT: pad back to 3 (or leave trimmed)
       email,
       qty
FROM ns.orders;
```

The frontend validates every value it reads against the row: a value that does not fit (an unscaled number of
10^p or more, text that is not a plain decimal, a string longer than n, an integer outside the smallint range)
fails the statement with SQLSTATE XX001 rather than being misread.

## Writing from outside the frontend

Write the stored form, not the client value: `19.90` into a `NUMERIC(10,2)` column is the BIGINT 1990, not 19.90
or 20. Round as PostgreSQL does (half away from zero) before scaling. Write `CHAR` values without trailing spaces;
padded values are read correctly but may not match equality lookups. Imports that cannot follow these rules
should go through the frontend.

Nothing in the column's name or ScalarDB type shows that it is encoded: a client that does not read
`column_types` sees `amount BIGINT` holding 1990. A frontend released before this feature is such a client, and
must not write to tables that have rows here.

## Moving to ScalarDB core

The table is keyed and named like ScalarDB's metadata table, keeps the declaration (`sql_type`, `type_*`) apart
from the representation (`encoding`, `data_type`), and records nothing PostgreSQL-specific. ScalarDB can take it
over by reading these rows into its own metadata, with the stored values kept as they are; no column is renamed
and no value rewritten. Once ScalarDB has a native decimal type, a storage that can convert columns may also
rewrite `SCALED_BIGINT` and `DECIMAL_TEXT` values into it.
