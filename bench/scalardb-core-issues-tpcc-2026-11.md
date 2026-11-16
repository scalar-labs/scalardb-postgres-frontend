# ScalarDB core changes found by the PostgreSQL frontend's TPC-C work (2026-11)

**Base:** core `master` at 416cdc22f (2026-11-02). **Prototype:** `build/core-proto/scalardb-all`; the
changes on top of the scan-LIMIT prototype are in `build/core-proto/prototype-all-2026-11-10.patch`
(25 files, +1113/-63; items 13 and 14 are in it behind constants set to false), the scan-LIMIT one in [scalardb-core-prototype-scan-limit.patch](scalardb-core-prototype-scan-limit.patch).
Every change is marked `PROTOTYPE` in the source.

## Summary

The same frontend (main 9d61fe0), the same configuration, the same VMs, measured with and without the core
changes below (run `20261109-sc`, two repetitions each; native PostgreSQL = 100%):

| Terminals | 1 | 8 | 16 | 32 | 64 | 128 |
|---|---|---|---|---|---|---|
| Frontend on core 416cdc22f (today's core) | 41.5% | 40.2% | 42.0% | 45.2% | 49.9% | 49.4% |
| Frontend on core with the kept changes | 50.8% | 54.7% | 58.4% | 64.0% | 77.8% | 77.7% |
| Gain from the core changes | +22% | +36% | +39% | +42% | +56% | +57% |

Per transaction at 64 terminals the core changes take PostgreSQL's CPU from 3.15 to 1.79 ms (native 1.64)
and the client's (BenchBase plus the frontend) from 4.88 to 3.38 ms (native 0.48); Delivery's p99 at 128
terminals falls from 812 to 112 ms. The two repetitions agree within a few percent except at one
terminal with the core changes (210 and 169 tps).

Where the whole improvement since 2026-11-04 (about 40% of native at 64-128 terminals) came from,
estimated from the step-by-step A/B runs (several steps combined a core and a frontend change, so the split
is approximate):

- **Core changes:** about 28 points at 64-128 terminals (measured above).
- **Configuration:** about 10 points: no TLS on ScalarDB's connections to PostgreSQL, as on BenchBase's own
  (+6-9%), composite indexes in PostgreSQL for the lookups Consensus Commit widens to `c OR before_c`
  (+4.5-6.5%), `plan_cache_mode=force_generic_plan` on ScalarDB's JDBC URL (part of a +3.5-5.7% step).
- **The frontend's own changes:** about 1-3 points at high load and 3-4 at low concurrency (estimated; the
  only one measured alone gave +4.3% at 1 terminal and nothing measurable at 64-128).

What to take upstream, what was dropped, and what needs a decision:

- **Propose:** 1 (scan LIMIT), 2, 3, 10 and 12 (multi-row and array writes with a global lock order), 4
  (commit-path costs), 5 (deterministic conjunction order), 7 (`col = ANY(?)`), 8 (no NULL before images
  in one-phase commit), 9 (hashing and result conversion), 11 (bounded scans in autocommit). 2, 7 and 10
  use PostgreSQL-only SQL (`ON CONFLICT`, `ANY(array)`, `unnest`): upstream they belong behind an
  `RdbEngineStrategy` capability whose default keeps today's statements, so other engines are unchanged.
  6 (padding) is superseded by 7 for the common case.
- **Measured and dropped:** 13 (one statement per commit: no latency gain, more database CPU,
  PostgreSQL-only) and 14 (narrow reads: no gain).
- **Needs the core team's decision:** the hot-row retries
  ([scalardb-core-issue-hot-row-retries.md](scalardb-core-issue-hot-row-retries.md)): at 128 terminals
  about a quarter of Payments and an eighth of Deliveries retry, where PostgreSQL's own `w_ytd = w_ytd + ?`
  waits and succeeds.

## Setting and method

**Setting:** BenchBase TPC-C, full mix, 64 warehouses, GCP (client n2-standard-32 running BenchBase and
the frontend, PostgreSQL 16 on n2-standard-16), Consensus Commit with one-phase commit, SNAPSHOT
isolation, cross-partition scan with filtering and ordering, JDBC storage. Each change was measured as a
cloud A/B against the build before it, two repetitions each, 180 s measured; results in
`build/cloud/results/<date>-<name>`. The A/B gains in the table below hold within their run; the runs
before 2026-11-08 had TLS on ScalarDB's connections (see "Benchmark setting found later: TLS"), so their
share-of-native figures are low by 6-9%.

Checks for the combined prototype: core unit tests (only expected failures: tests asserting the old
behaviour), the JDBC integration tests `ConsensusCommitIntegrationTestWithJdbcDatabase`,
`ConsensusCommitSpecificIntegrationTestWithJdbcDatabase` (incl. its one-phase-commit cases) and
`ConsensusCommitCrossPartitionScanIntegrationTestWithJdbcDatabase` on PostgreSQL 18 (1,849 tests, 0
failures), and the frontend's differential tests against real PostgreSQL (read, write, bind,
autocommit-write sets unchanged against their baselines).

| # | Change | Where | Cloud A/B (64 / 128 terminals) | Upstream difficulty |
|---|---|---|---|---|
| 1 | Scan LIMIT to storage, rescan if short | Consensus Commit | +11% / +10% | Medium (own issue) |
| 2 | Multi-row INSERT for PutIfNotExists | JDBC storage | +6% / +6% | Low-medium |
| 3 | Multi-row conditional UPDATE / DELETE | JDBC storage | +2-3% (with 4) | Medium |
| 4 | Commit path: no copies, no re-check, SQL text cache | storage, checker | (with 3) | Low |
| 5 | Deterministic conjunction order | API builders, CC | +4-6% (with 6, 7) | Low |
| 6 | OR-list padding per shape | JDBC storage | (with 5) | Low |
| 7 | Cross-product OR as `col = ANY(?)` | JDBC storage (PostgreSQL) | +13% / +17% (with a frontend change) | Low-medium |
| 8 | No NULL before-image columns in one-phase commit | Consensus Commit | +5% / +5% (with 9) | Low (needs review) |
| 9 | Hashing, key and result conversion costs | io, JDBC storage | (with 8) | Low |
| 10 | Array (`unnest`) multi-row DML | JDBC storage (PostgreSQL) | +2% / +3% (with 11) | Medium |
| 11 | Bounded scans in autocommit | JDBC storage | (with 10) | Low-medium |
| 12 | Lock order for multi-row writes | JDBC storage | fixes a regression of 3/10 | Low |

Plus one behaviour to discuss, not a change: [scalardb-core-issue-hot-row-retries.md](scalardb-core-issue-hot-row-retries.md).

## 1. Scan LIMIT to storage

See [scalardb-core-issue-scan-limit.md](scalardb-core-issue-scan-limit.md) and
[scalardb-core-issue-scan-round-trips.md](scalardb-core-issue-scan-round-trips.md).

## 2. Multi-row INSERT for PutIfNotExists

**Problem.** `JdbcCrudService.mutate` batches same-SQL mutations with `executeBatch`, but excludes
PutIfNotExists (`ConditionalMutationQuery.isBatchable`), because a duplicate key must be detected per row.
TPC-C NewOrder's 10 order_line rows go out as 10 INSERTs. (Even `executeBatch` sends N statements that
PostgreSQL executes one by one; pgJDBC's `reWriteBatchedInserts` does not apply and returns no counts.)

**Change.** On PostgreSQL, a table's PutIfNotExists rows go out as one
`INSERT ... VALUES (...), (...) ON CONFLICT DO NOTHING` (later: one `unnest` INSERT, item 10); fewer rows
inserted than sent means a mutation was not applied, as a duplicate key error means for one INSERT
(`mutate` returns false, `JdbcDatabase` rolls back and throws `NoMutationException`).

**Effect.** NewOrder: 47.9 -> 41.2 statements, PostgreSQL CPU -14% (local); cloud +6%.

## 3. Multi-row conditional UPDATE and DELETE

**Change.** Same-shape conditional updates (`UPDATE ... WHERE key AND tx_id = ?`) of a table run as one
`UPDATE t SET ... FROM (VALUES ...) v WHERE t.k = v.k AND t.tx_id = v.tx_id`, deletes likewise with
`DELETE ... USING`; the row count must equal the rows sent (the keys of a write set are distinct).
**Effect.** NewOrder's 10 stock updates and Delivery's per-row updates become one statement each;
statements per mix transaction down by about 2. See item 12 for the lock order this needs.

## 4. Commit-path costs

`JdbcCrudService.mutate` rebuilt each mutation's SQL text only to group by it (4.7% of frontend-process
CPU), `ScalarDbUtils.copyAndSetTargetToIfNot` copied every operation although namespace and table were set
(4%), and storage re-ran `OperationChecker` on mutations Consensus Commit had composed from metadata itself
(4%). Prototype: SQL text cached per statement shape (`UpdateQuery`, `InsertQuery`), no copy when the
target is set (operations are immutable), and a thread-local flag around the one-phase commit's
`storage.mutate` that skips the re-check (a real fix would pass this through an internal storage API).
Client CPU -5-6% in the cloud.

## 5. Deterministic conjunction order

`ConsensusCommitUtils.convertConjunctions` and `convertConjunctionToBeforeImage` collect into `HashSet`s,
and the Scan/Get/selection builders hold conjunctions in `HashSet`s too, so the order of OR terms (and of
the conditions inside them) depends on the bound values: the same scan produces a different SQL text
almost every call, which PostgreSQL parses and plans from scratch. TPC-C StockLevel produced about 9,700
distinct texts in 60 s locally. `LinkedHashSet` (insertion order) instead; equality and hashing of the sets
are unchanged.

## 6. OR-list padding per shape

With a varying number of OR terms (a batched lookup), each count is another text. More than two
conjunctions are padded per shape to a power of two by repeating the shape's last conjunction (the OR is
unchanged), so a few texts cover all counts. Superseded for the common case by item 7.

## 7. Cross-product OR as `col = ANY(?)`

A batched lookup `OR_i (key = k_i AND q < x)` is widened by Consensus Commit with each arm's before-image
twin `(key = k_i AND before_q < x)`. When the arms are exactly the cross product of one column's
equalities and a few condition sets, JDBC storage sends `key = ANY(?) AND (q < x OR before_q < x)` with the
keys as one array: identical rows (verified on real data), one SQL text for any number of keys, one index
scan. PostgreSQL only in the prototype (a class-name check; a real fix adds an `RdbEngineStrategy`
capability). StockLevel's stock lookups went from one new 5-8 KB text per call to one text; cloud +13% /
+17% together with the frontend's larger first lookup batch.

## 8. No NULL before-image columns in one-phase commit

`OnePhaseCommitMutationComposer` sets every before-image column to NULL when it overwrites a committed
record, though every writer of a committed record already leaves them NULL. Safety review (all readers of
before-image columns): prepare (`PrepareMutationComposer.createBeforeColumns`) overwrites all of them,
transaction metadata included, before a record can be PREPARED; rollback and lazy recovery use them only
for PREPARED/DELETED records (asserted); reads re-check widened filters against current values
(`CrudHandler.recoverAndFilter`); the before-image index scan and SERIALIZABLE validation look only at
uncommitted records. Removing the block cuts the stock UPDATE from about 30 to 10 SET columns. NewOrder
PostgreSQL CPU -5% locally. Needs confirmation that nothing outside Consensus Commit reads raw before-image
columns of committed records. `OnePhaseCommitMutationComposerTest` asserts the old columns.

## 9. Hashing, key and result conversion

`Key.getColumns` copied the list on every call (`ImmutableList.copyOf`); `Key.hashCode` was recomputed
each time; `IntColumn`/`BigIntColumn`/`TextColumn.hashCode` used `Objects.hash` (varargs array and boxing);
`ResultInterpreter` built a `HashMap` per row that `ResultImpl` copied into an `ImmutableMap`, read every
column by name (pgJDBC's name lookup per column and row) and looked up its type per row. Prototype:
unmodifiable view, cached hash, direct hash formulas, the immutable map built directly, types once per
interpreter and positions once per result set. Client CPU -7-8% in the cloud together with item 8 and the
frontend's timestamp fast path.

## 10. Array (`unnest`) multi-row DML

Items 2 and 3 with one array parameter per column:
`INSERT ... SELECT * FROM unnest(?::int[], ...)`, `UPDATE ... FROM unnest(...)`, `DELETE ... USING unnest(...)`:
one SQL text for any row count (the VALUES forms needed power-of-two chunks to keep texts few). Used when
every column has a PostgreSQL array type here (int, bigint, text, boolean, float, double); otherwise the
VALUES form.

## 11. Bounded scans in autocommit

A storage scan without a limit ran inside `BEGIN READ ONLY ... COMMIT` so that pgJDBC can fetch with a
cursor. The prototype first reads it in autocommit with `LIMIT fetch_size + 1`; if the rows fit in one
fetch they are the whole result (one round trip, no BEGIN/COMMIT), otherwise the scan runs again with the
cursor as before. Removes about 2 statements per mix transaction (StockLevel, Delivery, Payment by name).

## 12. Lock order for multi-row writes (a regression of items 3 and 10)

A multi-row UPDATE locks its rows in statement order (PostgreSQL plans it as a nested loop over the
VALUES/array rows). Two Delivery transactions of a warehouse write overlapping rows; with the rows in
write-set order per transaction they deadlocked (98 deadlocks locally in about 2.5 M transactions, native
0), each detected only after `deadlock_timeout` (1 s): Delivery's p99 at 128 terminals was about 1.05 s in
most runs. Fix: groups in SQL-text order and the rows of each multi-row statement sorted by key, so every
transaction takes its locks in one global order. Locally (8 terminals on 2 warehouses, mix, 60 s): 23 and 44
deadlocks per run before, 0 and 0 after, throughput 1,050 -> 1,227-1,419 tps; the Consensus Commit JDBC
integration tests still pass (1,849). Cloud result: see the results page.

## 13. One statement per commit

With items 2, 3, 10 and 12 a transaction's writes are one array DML statement per (table, shape), each
its own round trip inside the commit's PostgreSQL transaction (NewOrder: 5, Delivery: 4 plus VALUES
chunks). The prototype sends them as one statement when every write is a conditional insert, update or
delete of array-capable columns: each group as a data-modifying CTE with `RETURNING 1`, and one row of
counts (`SELECT (SELECT count(*) FROM g0), ...`) that `mutate` checks against the rows sent, as it did
per statement; a short count returns false and the transaction rolls back as before. TIMESTAMP,
TIMESTAMPTZ, DATE and TIME columns join the array path as text arrays cast to the column type. The groups
are read in the global lock order of item 12 (PostgreSQL runs a data-modifying CTE as it is read; the
SQL standard does not promise an order, so this needs the deadlock test, below). Locally: NewOrder 31 ->
27.5 statements per transaction (BEGIN, one statement, COMMIT around its reads), Delivery 46.7 (its 40
reads plus 3); no deadlocks in the item-12 test, throughput +1 to +13%; the Consensus Commit JDBC
integration tests pass (1,849).

**Cloud result (20261108-c1, no TLS): not kept.** +1.2%, +3.4%, +3.0%, +2.0% at 1, 8, 16 and 32
terminals, -1.2% at 64 and -4.0% at 128 against the build without it. NewOrder's median latency at one
terminal did not move (5.2 ms both): the commit's round trips inside one cloud zone cost less than
estimated. Database CPU per transaction rose 4-7% (1.68 -> 1.79 ms at 128 terminals), so where the
database limits throughput the change loses. Together with being PostgreSQL-only (data-modifying CTEs
exist there and in PostgreSQL-compatible databases, not in MySQL, Oracle, SQL Server, Db2 or SQLite; a
portable form would be an `RdbEngineStrategy` capability with a per-engine implementation and today's
statements as the default), it is not worth upstreaming as is.

## 14. Narrow reads (tried, not kept)

Storage reads name every column except the before-image ones (`ConsensusCommitUtils.columnsToRead`), and
`CrudHandler.recoverAndFilter` re-reads an uncommitted record in full (`rereadRecord`) before recovery,
which needs its before image. Safe by the review in item 8; the JDBC integration tests pass (1,849); 133
mock-based unit tests fail because they expect the Get/Scan without projections. Cloud (20261108-nr, no
TLS, together with a frontend change sending each protocol message in one write): -1.3%, -3.7%, -1.6%,
+0.3%, -0.1%, -1.7% at 1, 8, 16, 32, 64 and 128 terminals; database CPU -1 to -3% per transaction, client
CPU about +3%. Wide `SELECT *` rows are not where PostgreSQL's time goes. Not kept.

## Benchmark setting found later: TLS

The frontend's JDBC URL for ScalarDB set no `sslmode`, so pgJDBC's default `prefer` encrypted every
ScalarDB connection to PostgreSQL, while BenchBase's native connections use `sslmode=disable`.
libssl/libcrypto appear in the database's perf profile only for the frontend's runs. All runs above had
it on both sides of each A/B, so their gains stand; their share-of-native figures are low. Measured in
20261108-tls (same build with and without TLS): +8%, +6%, +7%, +9%, +6% at 1, 16, 32, 64 and 128
terminals (8 terminals inside noise), database CPU -13% and client CPU -9% per transaction at 64.
Without TLS the current build reaches about 75-78% of native at 64-128 terminals and 56-63% at 1-32
(20261108-c1).

## The frontend's own changes (this repository, committed on main)

- 9dbaacb: a lookup join under a sort, aggregate or DISTINCT reads its outer rows in batches of 128 from
  the start (StockLevel: two scans instead of four).
- 40dc017: a statement's OR conditions keep their written order (needed for item 5 to give stable SQL).
- de60002: timestamp parameters in pgJDBC's form are read directly.
- 9d61fe0: per-statement and per-row lookups cut (column references resolved once per plan, pre-sized
  row maps, client_min_messages and statement_timeout read only when needed, described types once per
  Bind, float parameters without an exception, one write per protocol message).

Tried and not kept: a read fast path for prepared keyed reads (`build/fast-lookup.patch`): correct, but the
operator tree it skipped costs about 2% of the frontend's CPU; the time is in the parameter conversion,
the ScalarDB read and the result conversion, which it had to keep.

Configuration used throughout: composite indexes in PostgreSQL for the by-last-name and latest-order
lookups (Consensus Commit widens them to `c OR before_c`, so both columns need one),
`plan_cache_mode=force_generic_plan` on ScalarDB's JDBC URL, and from 2026-11-08 `sslmode=disable` on it,
matching BenchBase's native connections. TLS costs 6-9% on either side; a deployment keeps it where its
network requires encryption.
