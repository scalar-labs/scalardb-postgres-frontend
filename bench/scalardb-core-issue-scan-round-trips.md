# Scans on JDBC storage cost two or more round trips, and an index Get four

**Component:** ScalarDB core: JDBC storage (`JdbcDatabase`, `ScannerImpl`) and Consensus Commit (`CrudHandler`)
**Version:** core `master` at 078f4118e (2026-10-08); measured with the frontend's core snapshot of 2026-10-07, same code paths
**Found by:** the ScalarDB PostgreSQL frontend's latency benchmark (`bench/`, `latency-all.sh`), 2026-10-11

## Summary

Under a realistic network between the application and PostgreSQL, ScalarDB's cost is set by round
trips, not CPU. A Get on PostgreSQL is one round trip, as it is natively, but:

1. **Every storage Scan is at least two round trips.** `JdbcDatabase.scan` turns autocommit off and
   marks the connection read-only, so pgjdbc sends `BEGIN READ ONLY` with the query and the scanner's
   `close()` sends a separate `COMMIT`. A scan whose result fills `scan_fetch_size` exactly takes one
   more fetch to see the end, and every further `scan_fetch_size` rows one more.
2. **A Consensus Commit Get by secondary index is two such scans in a row, so four round trips.**
   `CrudHandler` reads the index with a Scan (`resolveIndexGet`), then runs
   `checkAndRecoverBeforeIndexRecords`, a second Scan on the `before_<column>` index.
3. **`scan_fetch_size` defaults to 10**, so any scan of more than a few rows pays a round trip per 10
   rows.

## Observed

PostgreSQL log (`log_min_duration_statement=0`) for one statement each, through the frontend:

```
-- SELECT ... FROM orders WHERE customer_id = 8 AND order_id >= 3 ORDER BY order_id LIMIT 5
statement: BEGIN READ ONLY                                            } round trip 1
execute <unnamed>/C_3: SELECT * FROM "bench"."orders" WHERE ...       }
execute S_2: COMMIT                                                     round trip 2

-- SELECT id FROM items WHERE code = 1000007   (secondary index on code)
statement: BEGIN READ ONLY                                            } 1
execute <unnamed>/C_4: SELECT * FROM "bench"."items" WHERE "code"=$1  }
execute S_2: COMMIT                                                     2
statement: BEGIN READ ONLY                                            } 3
execute <unnamed>/C_5: SELECT * FROM "bench"."items" WHERE "before_code"=$1
execute S_2: COMMIT                                                     4
```

p50 at one client, with a TCP proxy adding 250 µs each way between the frontend and PostgreSQL
(0.7 ms effective round trip); native PostgreSQL over the same proxy for comparison:

| Statement | Native PostgreSQL | ScalarDB through the frontend | Round trips |
|---|---|---|---|
| point read by primary key | 0.64 ms | 0.75 ms | 1 |
| range scan, `LIMIT 5` | 0.65 ms | 1.44 ms | 2 |
| lookup by secondary index | 0.64 ms | 2.69 ms | 4 |
| filtered scan, `scan_fetch_size` 10 | 0.71 ms | 20-25 ms | about 30 |
| the same, `scan_fetch_size` 1000 | 0.71 ms | 3.75 ms | about 5 |

At 2.6 ms round trip every figure scales with the round-trip count (range scan 4.9 ms, index lookup
10.7 ms against 2.3 ms natively). TPC-C through the frontend shows the same: NewOrder, Payment and
OrderStatus are dominated by these scans (`bench/tpcc/README.md`).

## Suggested fixes

1. **Run a scan in autocommit when its result fits one fetch.** `JdbcDatabase.get` already skips the
   explicit transaction on PostgreSQL (`requiresExplicitCommit` is false). A Scan with a limit at or
   below `scan_fetch_size`, and the index scans behind a Get, could do the same: in autocommit pgjdbc
   reads the whole result in the one round trip, so the `COMMIT` disappears. That halves every small
   scan and the index Get. Unbounded scans still need the cursor, and so the transaction.
2. **Do the before-index check in the same round trip as the index read.** Either issue the two
   scans concurrently (two connections, so one round trip of wall time), or read both in one
   statement (`WHERE code = $1 OR before_code = $1`) and split the rows afterwards. With fix 1, an
   index Get becomes one round trip instead of four.
3. **Raise `DEFAULT_SCAN_FETCH_SIZE`** from 10, for example to 1000, which still bounds memory per
   scan. The frontend now sets 1000 unless the operator chose a value.

## Reproduce

`bench/README.md`: load `schema/` and `data.sql`, run `latency-all.sh 250` (or `DelayProxy.java`
by hand), and set `log_min_duration_statement = 0` on the PostgreSQL behind the proxy to see the
statements above.
