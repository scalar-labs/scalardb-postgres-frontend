# Declared types in ScalarDB column names

Status: the frontend creates `n` columns for `NUMERIC(p,s)` and `DECIMAL(p,s)`. The other codes are decoded
and their columns shown, but not created or written yet
(`docs/plans/2026-10-14-001-feat-keep-type-modifiers-exact-numeric-plan.md`, Phases 4 and 5).

ScalarDB has no decimal type, no type modifiers and no metadata a client can attach to a column. So when a
PostgreSQL client declares a type ScalarDB cannot hold as is, the frontend records the declaration in the
ScalarDB column name, and stores the value in a form that keeps it exact. This document is the contract for
anyone else who reads or writes those columns: other ScalarDB applications, the Data Loader, ScalarDB Analytics,
and a later ScalarDB release that takes the declaration over.

## The name

```
<client name>__pg_<code>
```

| Code | Declared | Stored as | Value stored |
|---|---|---|---|
| `n<p>_<s>` | `NUMERIC(p,s)`, `DECIMAL(p,s)`, p ≤ 18 (15 on Cosmos DB, object storage and in Cluster mode) | `BIGINT` | the value × 10^s, an integer below 10^p in absolute value |
| `t<p>_<s>` | `NUMERIC(p,s)` with more digits than that | `TEXT` | the decimal, plain notation, exactly s fraction digits; or `NaN` |
| `t` | `NUMERIC`, `DECIMAL` without a precision | `TEXT` | the decimal, plain notation, the scale it was written with; or `NaN`, `Infinity`, `-Infinity` |
| `v<n>` | `VARCHAR(n)` | `TEXT` | at most n characters |
| `c<n>` | `CHAR(n)`; `CHAR` is `c1` | `TEXT` | at most n characters, without trailing spaces |
| `s` | `SMALLINT`, `INT2` | `INT` | −32768 to 32767 |

A negative scale is written `m<digits>`: `NUMERIC(5,-2)` is `n5_m2`, storing 12300 as 123.

The digit limit is chosen when the column is created, from the storage the frontend is configured with. A
column created as `n16` to `n18` must not later be used through a storage that keeps a BIGINT exact only up to
2^53 (Cosmos DB, object storage): ScalarDB refuses the larger values there.

The code uses only `[a-z0-9_]`, so the name is valid unquoted on every storage. A client name never contains
`__pg_`, so the code is always the part after the last `__pg_`. A column without the marker is an ordinary
ScalarDB column and means exactly its ScalarDB type: tables created before this feature, and tables created by
other clients, are not affected.

`VARCHAR` without a length is plain `TEXT`, with no code.

## Reading

| Code | Decoded value |
|---|---|
| `n<p>_<s>` | `stored / 10^s`, with s fraction digits: 1990 in `amount__pg_n10_2` is 19.90 |
| `t…` | the text as a decimal; `NaN` and the infinities as such |
| `v<n>` | the text |
| `c<n>` | the text; a client pads it with spaces to n characters on output |
| `s` | the integer |

A view per table gives other SQL tools the client's names and values. On the JDBC storage, for example:

```sql
CREATE VIEW orders_v AS
SELECT id,
       amount__pg_n10_2 / 100.0 AS amount,   -- n10_2: divide by 10^2
       rpad(code__pg_c3, 3)     AS code,     -- c3: pad back to 3 characters (or leave trimmed)
       email__pg_v255           AS email,
       qty__pg_s                AS qty
FROM ns.orders;
```

The frontend validates every value it reads against the code: a value that does not fit (an unscaled number of
10^p or more, text that is not a plain decimal, a string longer than n, an integer outside the smallint range)
fails the statement with SQLSTATE XX001 rather than being misread.

## Writing from outside the frontend

Write the stored form, not the client value: `19.90` into `amount__pg_n10_2` is the BIGINT 1990, not 19.90 or
20. Round as PostgreSQL does (half away from zero) before scaling. Write `CHAR` values without trailing spaces;
padded values are read correctly but may not match equality lookups. Imports that cannot follow these rules
should go through the frontend.

A frontend that does not know a code (an older release meeting a newer code) shows the column under its full
name and refuses every write to it. A frontend released before this feature cannot tell an encoded column from
an ordinary one and must not write to tables that have them.

## Moving to ScalarDB core

Several storages cannot rename columns (Cosmos DB, DynamoDB, object storage, Spanner), so a migration must not
depend on renaming. The intended path is for ScalarDB to read the suffix as the column's declared type, an alias
for the client name with the scaled BIGINT kept as the representation, while the frontend keeps decoding the
names it wrote. Where a storage can rename and convert, a migration may also rename each column to its client
name and rewrite the values into a native decimal type once one exists.
