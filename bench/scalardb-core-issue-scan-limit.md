# Consensus Commit scans with a limit read and sort the whole range in PostgreSQL

**Component:** ScalarDB core: Consensus Commit (`ConsensusCommitUtils.prepareScanForStorage`, `CrudHandler`)
**Version:** core `master` at 416cdc22f (2026-11-02)
**Found by:** the ScalarDB PostgreSQL frontend's TPC-C diagnosis (BenchBase, local and GCP), 2026-11-04
**Prototype:** [scalardb-core-prototype-scan-limit.patch](scalardb-core-prototype-scan-limit.patch), applies to 416cdc22f

## Summary

`prepareScanForStorage` removes a scan's limit before Consensus Commit queries storage, and
`CrudHandler` applies the limit after reading. On JDBC storage the query then reaches PostgreSQL as
`ORDER BY ...` without `LIMIT`. PostgreSQL plans for the whole result, so for a "first row in
order" query it reads and sorts every row of the range before returning the first one, however
few rows the caller takes. The cost grows with the size of the range.

## Observed

TPC-C's Delivery reads the oldest undelivered order of each district:
`SELECT NO_O_ID FROM new_order WHERE NO_D_ID = ? AND NO_W_ID = ? ORDER BY NO_O_ID ASC LIMIT 1`.
The frontend turns it into a Consensus Commit Scan with `limit=1` (its EXPLAIN shows
`ScalarDB Scan tpcc.new_order partitionKey={no_w_id=1, no_d_id=1} ordering=[no_o_id ASC] limit=1`),
and PostgreSQL receives:

```
SELECT * FROM "tpcc"."new_order" WHERE "no_w_id"=$1 AND "no_d_id"=$2 ORDER BY "no_o_id" ASC
```

The plans PostgreSQL 18 chooses for the same predicate on one district (5,235 rows):

| Query | Plan | Time |
|---|---|---|
| without `LIMIT` (what core sends) | Bitmap Heap Scan of every row, then Sort | 4.6 ms |
| `LIMIT 100` | Index Scan on the primary key, stops after 100 rows | 0.09 ms |
| `LIMIT 1` | Index Scan on the primary key, stops after 1 row | 0.02 ms |

The fetch size does not help: PostgreSQL chooses the plan from the SQL text, so a smaller first
fetch only changes how many of the sorted rows are sent per round trip.

## Why the limit is removed

The comment in `prepareScanForStorage`: recovery of uncommitted records and the conjunctions
widened to the before-image columns can exclude rows that storage returned, so a storage-side
`LIMIT n` could leave fewer than n surviving rows while more exist. That reasoning holds; it only
calls for a second query in that case, not for an unlimited query every time.

## Suggested fix

Send the limit to storage, and scan again only when the limited result might be short:

1. Query storage with the scan's limit.
2. Process the rows as now (recovery, conjunction filter, write and delete sets).
3. If fewer than `limit` rows survived and storage returned `limit` rows, more rows may qualify:
   scan again without the limit (the lazy scanner skips the keys it already processed).

In the common case, where nothing is filtered out, this is one query that PostgreSQL can run as an
index scan that stops at the limit.

## Prototype and measured effect

The patch above does this for both scan paths (`scanInternal` and `ConsensusCommitStorageScanner`),
plus the autocommit change in [scalardb-core-issue-scan-round-trips.md](scalardb-core-issue-scan-round-trips.md)
(fix 1 there), which only becomes reachable with this one: storage scans carry no limit today.

TPC-C Delivery alone, 1 terminal, 2 warehouses, freshly loaded data, PostgreSQL 18 on a laptop; the
frontend at main a93ede6 built against core 416cdc22f with and without the patch; counts from
`log_min_duration_statement = 0`:

| Per Delivery transaction | Core 416cdc22f | With the patch |
|---|---|---|
| Statements | 223 | 203 (one `BEGIN READ ONLY` and `COMMIT` fewer per district) |
| PostgreSQL time | 5.97 ms | 3.05 ms (−49%) |
| of which the `new_order` scans | 3.47 ms | 0.11 ms (−97%) |

On GCP (64 warehouses, 32-vCPU client, 16-vCPU PostgreSQL 16 VM, 64 terminals, unpatched core),
`pg_stat_statements` over the measured window puts this scan first among the frontend path's
statements: 612 µs of execution time per TPC-C transaction, a third of the 1.84 ms all its statements
take, at 935 rows returned per call (the district's undelivered orders).

The patch on the same VMs, the frontend built against core 416cdc22f with and without it, BenchBase
TPC-C with 64 warehouses, 180 s measured, 2 repetitions each (averages; the repetitions agree):

| | 64 terminals: unpatched → patched | 128 terminals: unpatched → patched |
|---|---|---|
| Throughput | 3,142 → 3,485 tps (+11%) | 3,355 → 3,697 tps (+10%) |
| PostgreSQL CPU per transaction | 4.13 → 3.61 ms (−13%) | 4.41 → 3.94 ms (−11%) |
| Statement execution per transaction | 1.95 → 1.25 ms (−36%) | 3.55 → 2.52 ms (−29%) |
| The `new_order` scan, per transaction | 610 → 16 µs (930 → 1 row per call) | 970 → 20 µs (990 → 1 row per call) |
| Delivery p50 latency | 85 → 59 ms | 132 → 83 ms |

Native PostgreSQL's own query costs 16-20 µs per transaction there, so the patched scan matches it. (An
earlier cloud attempt is void: a benchmark-script error left the unpatched build serving both
configurations.)

The benefit grows with the range a limited scan would otherwise read: the latest record, top N, the
first page of a listing over a large partition. OrderStatus's "latest order of a customer" carries
the `LIMIT` too with the patch, but gains little, since PostgreSQL still walks the district's orders
to find the customer's (natively an index on `(o_w_id, o_d_id, o_c_id)` answers it).

The 9 failing unit tests are the 3 `CrudHandlerTest` cases (in each scan mode) that assert storage
is called with `limit=0`; the other 380 tests of the touched classes pass.

## Difficulty

Medium. The change is small, but it sits in correctness-critical code:

- **Scan paths:** both the batch scan and the lazy scanner, which delivers rows as it reads them and
  so has to skip, on the second query, every key the first one processed, delivered or filtered.
- **Interactions to review:** lazy recovery during the first pass, the before-index recheck and its
  retries, the scan and scanner sets recorded for SERIALIZABLE validation (the recorded scan keeps
  its own limit; validation re-runs it), and the overlap check against the transaction's own writes.
- **Other storages:** the limit now reaches every storage. Where a storage counts its limit before
  filtering (DynamoDB evaluates `Limit` before its filter expression), "storage returned `limit`
  rows" has to mean "storage may have more", which the retry condition already treats as such.
- **Continuing instead of rescanning:** resuming after the last key instead of rescanning from the
  start saves the repeated prefix, but needs an ordering on the clustering key; ordering on other
  columns would keep the rescan.

## Reproduce

`bench/tpcc/README.md` loads TPC-C through the frontend. With `log_min_duration_statement = 0` on
the backing PostgreSQL, run Delivery alone (BenchBase weights `0,0,0,100,0`) and compare the
`new_order` statement and its time; `EXPLAIN ANALYZE` the statement with and without `LIMIT 1` for
the plans above. Statements per transaction are the log's `execute` and `statement` lines over the
run, divided by BenchBase's measured transactions; keep the run short (5 s at 1 terminal here) so
that Delivery does not run out of undelivered orders, which makes later transactions cheaper.
