# Consensus Commit retries about a quarter of TPC-C Payments when two clients share a warehouse

**Component:** ScalarDB core: Consensus Commit (conditional writes at commit; here with one-phase commit on JDBC storage)
**Version:** core `master` at 416cdc22f (2026-11-02) plus the prototypes in `build/core-proto` (none of them changes this behaviour)
**Found by:** the ScalarDB PostgreSQL frontend's TPC-C benchmarks (BenchBase, GCP), 2026-11-06 and 2026-11-07

## Summary

TPC-C's Payment adds the payment amount to its warehouse's year-to-date total. Natively this is
`UPDATE warehouse SET w_ytd = w_ytd + ? WHERE w_id = ?`: a second Payment on the same warehouse waits for the
first one's row lock and then updates the newer row, so both succeed. Through Consensus Commit, a Payment reads
the warehouse row and writes the new total with a conditional update (`... WHERE w_id = ? AND tx_id = <the
version it read>`). When another Payment commits the same row in between, the condition no longer holds, the
commit fails with a conflict, and the client retries the whole transaction.

This is Consensus Commit preventing a lost update, as it should. The point of this issue is its cost on a
hot row, which TPC-C has by design.

## Observed

BenchBase binds every terminal to a home warehouse, dividing the terminals evenly over the warehouses
(`TPCCBenchmark.createTerminals`, "w_id %d = %d terminals"). With 64 warehouses:

| Terminals | Terminals per warehouse | Payments retried per committed Payment (frontend) | Native |
|---|---|---|---|
| 64 | 1 | 0.00 | 0.00 |
| 128 | 2 | 0.25-0.29 (every run of 2026-11-06/07) | 0.00 |

The district row (`d_ytd`) is updated by every Payment too, but BenchBase also spreads each warehouse's
districts over its terminals, so the warehouse row is where two terminals meet.

## Cost

A retried Payment repeats its reads and writes, and the failed attempt has already run its reads, its
conditional writes up to the failing one and the rollback. At 128 terminals that is roughly a quarter more
Payment work, about 0.1 ms of database CPU per mix transaction, and it lengthens tail latency (Delivery's
p99 reached about 1 s in one run). It does not affect the 64-terminal results, where no warehouse is shared.

## Options to discuss

- **Accept it.** It is the expected price of optimistic concurrency on a hot row; applications that update a
  counter row from many clients can partition it.
- **A pessimistic read for read-modify-write of a known-hot row**, if Consensus Commit offers (or could offer)
  one: lock the row at read time so the second writer waits instead of failing, as PostgreSQL does. This
  trades the retry for blocking and needs care with deadlocks.
- **Retry inside the frontend**, not in the client, for a statement-level conflict in autocommit or a
  transaction that has done nothing else visible. This saves the client round trips but not the database
  work, and changes when errors surface; it would be a frontend decision, not a core change.

## Reproduce

BenchBase TPC-C with 64 warehouses at 64 and 128 terminals through the frontend (bench/cloud, `sweep*.conf`
with `TERMINALS="64 128"`); `summary.jsonl` records `retried` per transaction type. Results:
`build/cloud/results/20261107-cl` and `20261107-arr`.
