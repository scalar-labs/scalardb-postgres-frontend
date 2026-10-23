# Designing tables and queries

The frontend accepts PostgreSQL's SQL, but it stores and reads data through ScalarDB, which reads by key.
A query that names a table's key reads only the records it needs. A query that does not name a key reads
the whole table and filters it. Both return the same rows, but at very different costs. This page explains
how tables map to ScalarDB, which parts of a query run in the database, and how to check a query with
`EXPLAIN`.

## How a table maps to ScalarDB

A ScalarDB table has a partition key, an optional clustering key and optional secondary indexes. ScalarDB
stores the records of one partition together, ordered by the clustering key, and can read a partition, a
range of it or one record of it without touching other partitions.

When you create a table, the first column of the primary key becomes the partition key, and the remaining
primary key columns become the clustering key, in ascending order:

```sql
CREATE TABLE orders (
  customer_id INT,
  order_id    INT,
  item        VARCHAR(40),
  amount      NUMERIC(10,2),
  PRIMARY KEY (customer_id, order_id)
);
```

In this table, each customer's orders form one partition, ordered by `order_id`. Reading one customer's
orders, or a range of them, is cheap. Reading every order whose `order_id` is 1 is not, because those
records are in every partition.

To choose a partition key of more than one column, or a descending clustering order, add `WITH` options:

```sql
CREATE TABLE order_lines (
  warehouse_id INT,
  district_id  INT,
  order_id     INT,
  line_number  INT,
  amount       NUMERIC(10,2),
  PRIMARY KEY (warehouse_id, district_id, order_id, line_number)
) WITH (partition_key = 'warehouse_id, district_id', clustering_key = 'order_id DESC, line_number');
```

The key columns must come first in the column list, partition key before clustering key, because ScalarDB
stores them first; otherwise, `CREATE TABLE` is refused. psql's `\dt+` shows each table's keys in
its description:

```console
 Schema |  Name  | Type  |  Owner   | ... |                          Description
--------+--------+-------+----------+-----+-----------------------------------------------------------------
 public | orders | table | scalardb | ... | ScalarDB partition key (customer_id), clustering key (order_id)
```

A table can have secondary indexes on single columns, which let a query find records by that column's value
without scanning the table:

```sql
CREATE INDEX customers_region_idx ON customers (region);
```

An index covers one column. Expression indexes are refused, and a `UNIQUE` or partial index is created as a
plain index with a `NOTICE`, because ScalarDB does not enforce uniqueness or keep the `WHERE`.

### Choosing keys

Choose the partition key from the value that your most frequent queries and transactions fix, such as a
customer ID or a tenant ID, so that each of them reads one partition. Use the clustering key for the order
in which you read records within a partition, such as an order number or a timestamp. A partition key with
few distinct values, such as a status or a country code, puts many records in one partition and limits how
well ScalarDB spreads the load.

Key columns, and indexed columns, must have a type that ScalarDB can compare in the database. A `NUMERIC`
column without a precision, or with a precision over 18, is stored as text and cannot be a key or an index;
declare it as `NUMERIC(p,s)` with `p` up to 18. For which types can be keys, see
[SQL reference](sql-reference.md#data-types).

## What runs in the database

The frontend turns each query into ScalarDB operations and runs whatever ScalarDB cannot do in its own
memory: joins, aggregates, sorts other than by the clustering key, window functions, and conditions that
ScalarDB cannot evaluate. The cost of a query is mostly how many records its ScalarDB operations read.

| ScalarDB operation | When the frontend uses it | Records read |
|--------------------|---------------------------|--------------|
| `Get` | The query fixes the whole primary key, such as `WHERE id = 1` | One |
| `Scan` of a partition | The query fixes the whole partition key, such as `WHERE customer_id = 1`. A range on the clustering key, `ORDER BY` on it and `LIMIT` go to ScalarDB too. | The partition, or the range of it |
| `Scan` of an index | The query fixes an indexed column, such as `WHERE region = 'EU'` | The records with that value |
| `ScanAll` | No key or index applies, such as `WHERE tier = 2` | The whole table. ScalarDB applies the conditions that it can, but still reads every record in the backing database. |

A `ScanAll` needs `scalar.db.cross_partition_scan.enabled=true`, and its conditions and ordering need the
`filtering` and `ordering` variants of that property. Without them, such queries fail. For details, see
[Configuration](configuration.md#scalardb-properties-that-matter).

`IN` on a key becomes one lookup per value, and a join on the inner table's key becomes one lookup per row of
the outer table. The frontend sends those lookups to ScalarDB in batches, which matters most through ScalarDB
Cluster, where each batch is one round trip.

### Conditions that stay in the frontend

A condition goes to ScalarDB only when ScalarDB can evaluate it as written. The following conditions are
evaluated in the frontend, after the rows are read:

- A condition that calls a function or does arithmetic on a column, such as `lower(name) = 'ann'`.
- A key condition written with arithmetic on the value, such as `order_id >= $1 - 20`. Compute the value in
  the application instead.
- A condition on a `NUMERIC` column stored as text, that is, without a precision or with a precision over 18.
- A condition on an encrypted column, in ScalarDB Cluster.
- A comparison between columns of the same table.

Such a condition does not make a query wrong, but if nothing else in the query limits what is read, the
query reads the whole partition or table.

## Check a query with EXPLAIN

`EXPLAIN` shows the ScalarDB operations that a query turns into and what runs in the frontend. Lines that
start with `ScalarDB` run in the database; every other line runs in the frontend over the rows that those
operations return.

A query that fixes the primary key is one `Get`:

```sql
EXPLAIN SELECT * FROM customers WHERE id = 1;
```

```console
 Project [id, name, region, tier]
   -> ScalarDB Get demo.customers partitionKey={id=1} projections=[id, name, region, tier] AS customers
```

A query on one partition, with a range, an order and a limit, is one `Scan` that does all of them:

```sql
EXPLAIN SELECT * FROM orders
  WHERE customer_id = 1 AND order_id >= 2 ORDER BY order_id DESC LIMIT 10;
```

```console
 Project [customer_id, order_id, item, amount]
   -> ScalarDB Scan demo.orders partitionKey={customer_id=1} start={order_id=2} (inclusive) ordering=[order_id DESC] limit=10 projections=[customer_id, order_id, item, amount] AS orders
```

A query on an indexed column reads by the index, and ScalarDB applies the other conditions:

```sql
EXPLAIN SELECT * FROM customers WHERE region = 'EU' AND tier = 2;
```

```console
 Project [id, name, region, tier]
   -> ScalarDB Scan demo.customers indexKey={region='EU'} conditions=[tier = 2] projections=[id, name, region, tier] AS customers
```

A condition that ScalarDB cannot evaluate appears under `Filter`, above a `ScanAll` that reads the whole
table:

```sql
EXPLAIN SELECT * FROM customers WHERE lower(name) = 'ann';
```

```console
 Project [id, name, region, tier]
   -> Filter lower(customers.name) = 'ann'
     -> ScalarDB ScanAll demo.customers projections=[id, name, region, tier] AS customers
```

A join reads the outer table, then looks up the inner table by key for each outer row:

```sql
EXPLAIN SELECT c.name, o.amount
  FROM customers c JOIN orders o ON o.customer_id = c.id
  WHERE c.region = 'EU';
```

```console
 Project [name, amount]
   -> Lookup Join (per outer row): ScalarDB Scan demo.orders partitionKey={customer_id=c.id} projections=[customer_id, amount] AS o
     -> ScalarDB Scan demo.customers indexKey={region='EU'} projections=[region, id, name] AS c
```

`EXPLAIN ANALYZE` runs the query and adds, for each operation, the rows it returned and the ScalarDB reads
it made:

```console
 Project [name, amount] (actual rows=2)
   -> Lookup Join (per outer row): ScalarDB Scan demo.orders partitionKey={customer_id=c.id} projections=[customer_id, amount] AS o (actual rows=2 ScalarDB reads=2)
     -> ScalarDB Scan demo.customers indexKey={region='EU'} projections=[region, id, name] AS c (actual rows=2 ScalarDB reads=1)
 Execution Time: 6.239 ms
```

:::note

`EXPLAIN ANALYZE` runs the statement. For an `INSERT`, `UPDATE` or `DELETE`, run it inside a transaction
that you roll back.

:::

## Work held in memory

Sorts, aggregates, window functions and the inputs of joins that are not lookups hold their rows in the
frontend's memory. One of them may hold at most `scalar.db.postgres.max_rows_in_memory` rows (one million by
default); a query that needs more fails with SQLSTATE `54000` rather than taking memory from every other
session. Narrow such a query with a key, an index or `LIMIT`, or sort by the clustering key so that ScalarDB
returns the rows in order.

Writes are limited too. One `INSERT`, `UPDATE` or `DELETE` may write at most 10,000 rows by default, and a
statement that would write more is refused before it writes anything. A session can change the limit with
`SET scalardb.max_rows_per_write`. For details, see [Configuration](configuration.md#session-settings).

## Constraints

ScalarDB enforces the primary key only. `NOT NULL` on columns outside the key, `UNIQUE`, `CHECK`,
`REFERENCES` and `serial` are accepted with a `NOTICE` and not enforced: rows that violate them are stored,
and a `serial` column does not generate values. A column `DEFAULT` other than `DEFAULT NULL` is refused,
because an omitted column always reads `NULL`. Enforce these rules in the application.

## Changing tables

`ALTER TABLE` supports `ADD COLUMN` and `RENAME COLUMN`. A column's type cannot be changed, and a column cannot
be dropped. ScalarDB caches table metadata, so a change to an existing table is visible only after the cache
expires; for how to set it, see [Configuration](configuration.md#scalardb-properties-that-matter).

## See also

- [SQL reference](sql-reference.md): the statements, types and functions that the frontend supports.
- [Transactions](transactions.md): how transactions read and write.
- [Limitations](../../LIMITATIONS.md): the full list of differences from PostgreSQL.
