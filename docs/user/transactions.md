# Transactions

Every statement that the frontend runs is a ScalarDB transaction, or part of one. ScalarDB's transactions
are ACID, and they can span several backing databases when ScalarDB is configured with more than one
storage, which PostgreSQL alone cannot do. They work differently from PostgreSQL's in a few ways that
affect how you write applications. This page explains those differences.

## Transactions are optimistic

PostgreSQL makes a transaction wait when it writes a row that another open transaction has written.
ScalarDB does not lock rows: concurrent transactions run without waiting, and when two of them conflict,
one of them fails when it commits. The frontend reports that failure as SQLSTATE `40001`
(`serialization_failure`), as PostgreSQL does under its `SERIALIZABLE` isolation level.

For example, if two sessions update the same row, the one that commits second fails:

```sql
-- Session A                                    -- Session B
BEGIN;
UPDATE accounts SET balance = 1 WHERE id = 5;
                                                BEGIN;
                                                UPDATE accounts SET balance = 2 WHERE id = 5;
                                                COMMIT;
COMMIT;  -- fails with 40001
```

`SELECT ... FOR UPDATE` and `FOR SHARE` are accepted but take no lock, so they do not make a concurrent
writer wait; the conflict is still found at commit. `lock_timeout` is accepted and has no effect.

### Retry on 40001

An application must be ready to retry a transaction that fails with `40001`. Run the whole transaction
again from `BEGIN`, including its reads, because the data that it read may have changed. The following
example retries a transfer with JDBC:

```java
static void transfer(Connection c, int from, int to, BigDecimal amount) throws SQLException {
  c.setAutoCommit(false);
  for (int attempt = 1; ; attempt++) {
    try (PreparedStatement debit =
            c.prepareStatement("UPDATE accounts SET balance = balance - ? WHERE id = ?");
        PreparedStatement credit =
            c.prepareStatement("UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
      debit.setBigDecimal(1, amount);
      debit.setInt(2, from);
      debit.executeUpdate();
      credit.setBigDecimal(1, amount);
      credit.setInt(2, to);
      credit.executeUpdate();
      c.commit();
      return;
    } catch (SQLException e) {
      c.rollback();
      if (!"40001".equals(e.getSQLState()) || attempt == 5) {
        throw e;
      }
      // back off before retrying, so that the conflicting transaction can finish
      try {
        Thread.sleep(10L * attempt);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw e;
      }
    }
  }
}
```

Some frameworks can retry on `40001` for you, such as Spring Retry's `@Retryable`. Keep transactions short,
so that fewer of them conflict.

### Unknown outcomes

When a backing database is unreachable, `COMMIT` waits 60 to 150 seconds and then fails with SQLSTATE
`40003` if ScalarDB cannot tell whether the transaction committed. The data stays consistent, but the
application does not know whether its changes were applied. Treat `40003`, and a client-side timeout on
`COMMIT`, as an unknown outcome: check the data before you retry, or make the transaction safe to run twice.

## Isolation levels

ScalarDB's isolation level is set for the whole deployment by `scalar.db.consensus_commit.isolation_level`,
not per transaction. The frontend reports it as PostgreSQL's closest level:

| ScalarDB isolation level | `SHOW transaction_isolation` | What a transaction sees |
|--------------------------|------------------------------|-------------------------|
| `READ_COMMITTED` | `read committed` | Each record as last committed when it is read, every time it is read. Reading a record again in the same transaction can return a value that another transaction committed in between. |
| `SNAPSHOT` (default) | `read committed` | Each record as last committed when it is read. Running the same query again in the transaction returns the same rows, but a different query that reads the same record, such as a scan after a read by primary key, can see a newer value. |
| `SERIALIZABLE` | `serializable` | A result as if transactions ran one at a time. A transaction whose reads were changed by another committed transaction fails at commit with `40001`. |

Under `READ_COMMITTED` and `SNAPSHOT`, a query that reads many records while another transaction commits can
see some records from before that commit and some from after. For example, a query such as
`SELECT sum(balance) FROM accounts` that runs during a transfer can return a total that never existed,
because it can read one account before the transfer and the other after it. Use `SERIALIZABLE` if your
application needs consistent reads across many records.

At every level, a transaction that writes a record that another transaction changed after this transaction
read it fails at commit with `40001`, so a concurrent update is never lost.

A request for a stronger level than the deployment runs at, such as `BEGIN ISOLATION LEVEL SERIALIZABLE`
under `SNAPSHOT`, fails with SQLSTATE `0A000` rather than being ignored. A request for the same or a weaker
level is accepted.

## Statements outside a transaction block

A statement that you run outside `BEGIN` ... `COMMIT` is one ScalarDB transaction, with all of its reads and
writes. A read-only statement commits when its last row is returned; under `SERIALIZABLE`, it can also fail
with `40001`.

Several statements sent in one message, such as `psql -c "INSERT ...; INSERT ..."`, or as one JDBC batch, run
in one transaction, as in PostgreSQL: if one of them fails, the others are undone. The exception is a read
among them, which commits the writes before it and runs on its own.

## Reading rows that the transaction wrote

Within a transaction, a query that scans a table can fail when the transaction has already written to the
part of the table that the scan reads. ScalarDB refuses such a scan, and the error message contains
`DB-CORE-10106`. A read by primary key works.

For example, the `SELECT` in the following transaction fails, because it scans the partition that the
`INSERT` wrote to:

```sql
BEGIN;
INSERT INTO orders VALUES (1, 3, 'cable', 9.90);
SELECT * FROM orders WHERE customer_id = 1;  -- fails: DB-CORE-10106
```

To avoid this, read before you write, read the rows by their primary key, or use `RETURNING` to get the values
that a write produced:

```sql
BEGIN;
INSERT INTO orders VALUES (1, 3, 'cable', 9.90) RETURNING *;
SELECT * FROM orders WHERE customer_id = 1 AND order_id = 3;  -- works: a read by primary key
COMMIT;
```

ORMs often trigger this when they insert a row and then reload a collection in the same transaction.

## Savepoints

`SAVEPOINT` and `RELEASE SAVEPOINT` are supported. `ROLLBACK TO SAVEPOINT` works only if the transaction has
written nothing since the savepoint; it then also undoes the `SET` commands since the savepoint. After a write,
`ROLLBACK TO SAVEPOINT` fails, and that failure aborts the whole transaction.

## DDL in a transaction

`CREATE TABLE`, `ALTER TABLE`, `DROP TABLE` and other DDL take effect immediately, even inside a transaction
block, and `ROLLBACK` does not undo them. Run schema changes outside the transactions that use the data.

## Read-only transactions

`BEGIN READ ONLY` and `SET TRANSACTION READ ONLY` work as in PostgreSQL: a statement that writes fails with
SQLSTATE `25006`. The check depends on what a statement does, so a `SELECT` with an `INSERT` inside `WITH`
also fails.

## See also

- [SQL reference](sql-reference.md#error-codes): the SQLSTATE codes that the frontend returns.
- [Limitations](../../LIMITATIONS.md): the full list of differences from PostgreSQL.
