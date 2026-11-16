---
title: "bench: XA transaction managers vs ScalarDB (Core and the frontend) across two PostgreSQL databases, on GCP"
type: bench
status: planned
date: 2026-11-15
---

# XA transaction managers vs ScalarDB across two PostgreSQL databases

## Goal

Measure what it costs to make a transaction atomic and durable across two PostgreSQL databases, with:
- three XA transaction managers: Narayana, Atomikos and Seata (XA mode);
- ScalarDB Consensus Commit, embedded (Core) and behind the frontend;
- a baseline with no coordination, which shows the cost of coordination itself.

Every coordinated arm keeps the same durability: the decision to commit is synced to disk before phase 2, and
every database commit is synced. Isolation is matched in two tiers (see [Isolation](#isolation)), because XA
and ScalarDB isolate transactions differently.

YCSB isolates one factor at a time. TPC-C then shows how much of the cost survives in a realistic mix.

This builds on `docs/plans/2026-11-03-001-bench-jdbc-sidecar-embedded-plan.md`. That plan uses one database
and measures what the frontend costs over embedded Core. This plan uses two databases and measures what
coordinating them costs.

## Arms

| Arm | Path | Commit across the two databases | Decision log |
|---|---|---|---|
| `none` | app → 2 × PostgreSQL | commit db1, then commit db2; a crash in between leaves them out of step | none |
| `narayana` | app (Narayana embedded) → 2 × PostgreSQL | XA 2PC | file on the app VM |
| `atomikos` | app (Atomikos embedded) → 2 × PostgreSQL | XA 2PC | file on the app VM |
| `seata` | app (Seata RM) → Seata TC server; app → 2 × PostgreSQL | XA 2PC, driven through the TC | TC's store, mode `db`, in db1 |
| `core` | app (ScalarDB Core embedded, multi-storage) → 2 × PostgreSQL | Consensus Commit | coordinator table in db1 |
| `fe` | app → frontend sidecar (multi-storage) → 2 × PostgreSQL | Consensus Commit | coordinator table in db1 |

The XA arms use pgjdbc's `PGXADataSource`. `core` and `fe` map namespace `ycsb1` (or `tpcc1`) to db1 and
`ycsb2` to db2. Through the frontend, one connection reaches both namespaces with qualified table names.

Seata's TC log and ScalarDB's coordinator table both live in db1, so those two arms keep the decision in the
same place. Narayana and Atomikos keep it on the app VM's disk, which is how they are normally deployed.
The report says so.

## What each difference measures

| Difference | Cost it isolates |
|---|---|
| XA arm − `none` | XA 2PC: prepare on each database, the decision log, commit on each. On the cross-database rows only. |
| `seata` − `narayana` | A coordinator server (RPCs to register branches, report their results and commit) against an embedded one |
| `narayana` − `atomikos` | The implementation, chiefly its log |
| `core` − `none` | Consensus Commit: per-record metadata and before-images, conditional writes, the coordinator write |
| `fe` − `core` | The frontend: wire protocol, parsing, the localhost hop. Should match plan 2026-11-03-001's result. |
| `core` − XA arm | The two protocols, at the same durability and the same isolation tier |
| single-database row − cross-database row, per arm | The cost each arm pays only when a transaction spans both databases |

## Isolation

XA guarantees atomicity only. Each PostgreSQL database isolates on its own:
- At `REPEATABLE READ`, each database takes its own snapshot, so a reader can see another transaction's
  write in db1 but not its write in db2.
- At `SERIALIZABLE`, PostgreSQL uses SSI. Each database is serializable on its own, but the two can order
  T1 and T2 differently, so the whole is not serializable.
- A deadlock that spans the two databases is broken only by a lock timeout.

ScalarDB's isolation levels behave differently:
- `SNAPSHOT` reads each record's latest committed version at the moment it reads it, so read skew is
  possible, even within one database (plan 2026-11-03-001, "Configurations").
- `SERIALIZABLE` validates the read set at commit, across all storages.

The two tiers:

| Tier | Guarantee | `none` | XA arms | `core`, `fe` |
|---|---|---|---|---|
| A | atomic commit; read skew possible; no lost updates | `REPEATABLE READ` | `REPEATABLE READ` | `SNAPSHOT` |
| B | serializable across both databases | `SERIALIZABLE` (each database serializable on its own; reference only) | `READ COMMITTED` with `SELECT ... FOR SHARE` on every read and `FOR UPDATE` on reads followed by a write (strict 2PL on rows; no predicate locks) | `SERIALIZABLE` |

Tier B for XA is strict two-phase locking on rows, which together with 2PC is serializable across both
databases. YCSB reads only by primary key, so the missing predicate locks don't matter there.

## Durability settings

Same on both database VMs and on the TC's store:

```
fsync = on
synchronous_commit = on
full_page_writes = on
max_prepared_transactions = <max_connections>   # 0 disables XA in PostgreSQL
```

Everything else follows `bench/cloud`'s settings.

The transaction managers' logs go on a PD-SSD, the same disk type as the databases, never on tmpfs:

| Arm | Setting | Status |
|---|---|---|
| `narayana` | Defaults: `ShadowNoFileLockStore` with `transactionSync=true`, so one file per transaction, synced with `FileDescriptor.sync()` (`ShadowingStore`) | source, 7.3.4 |
| `atomikos` | Defaults: `enable_logging=true`; `FileSystemRepository.put` writes and `force`s every record (committing and terminated), under a lock shared by all transactions (`CachedRepository.put` is `synchronized`). `max_actives` raised from 50 to unlimited. | source, 6.0.1 |
| `seata` | `store.mode=db` on db1: each session write is an autocommit statement, so a synced PostgreSQL commit. The TC answers the global commit after phase 2. In `db` mode it deletes the session later, from its retry job (`DELAY_HANDLE_SESSION`). | source, 2.6.0 |
| `core`, `fe` | Coordinator table on db1. No group commit and no async commit, so every coordinator write is its own synced PostgreSQL commit. | source, below |

Seata settings changed from the defaults, in `bench/xa/conf/seata/server.yml.in`:
- `enable-parallel-handle-branch: true`: phase 2 commits the branches in parallel.
- `retry-dead-threshold: 10000` (default 70 s): the retry job picks a session up for cleanup only after this,
  so with the default the first 40 s of a 90 s measured window would carry none of the deletes.
- `store.db.query-limit: 10000` (default 1000): the retry job reads at most this many sessions per 1 s pass,
  so the default caps the cleanup at about 1,000 transactions a second and `global_table` grows without end.
- Logging at WARN: at INFO the TC logs about 16 lines per transaction, and no other arm logs per transaction.

ScalarDB Core at `af3a191f5`, `ConsensusCommitConfig`:
- `parallel_preparation.enabled` and `parallel_commit.enabled` default to `true`; parallel validation follows
  parallel commit.
- `async_commit.enabled` defaults to `false`.
- `one_phase_commit.enabled` defaults to `false`. It is set to `true` here, as in plan 2026-11-03-001.
- `coordinator.write_omission_on_read_only.enabled` defaults to `true`.
- `coordinator.group_commit.enabled` defaults to `false`.

`ParticipantCommitHandler.canOnePhaseCommit` commits in one phase only when:
- no validation is needed, so never under `SERIALIZABLE` with reads; and
- every mutation can go in one storage call, so never when a transaction writes both databases.

So ScalarDB takes this shortcut only on the single-database rows. The XA arms have their own equivalent:
a transaction manager commits in one phase when only one branch enlisted.

Batching (ScalarDB's coordinator group commit, Narayana's journal store, PostgreSQL's `commit_delay`) is
left out of the main runs. An optional last run turns on each arm's batching at 128 terminals.

## Settings to match across arms

| Setting | Rule |
|---|---|
| Prepare and commit of the two branches | in parallel wherever the arm allows it; the report says which arms can't. ScalarDB: both parallel by default. Narayana: `asyncPrepare=true` prepares in parallel; phase 2 runs one branch after another (its `asyncCommit` only moves all of phase 2 to a background thread, like ScalarDB's async commit, so it stays off). Atomikos 6.0.1: one after another, hard-coded (`AssemblerImp` passes `single_threaded_2pc=true`). Seata: phase 1 happens as the app closes each connection, one after another; phase 2 parallel with the setting above. |
| Read-only branches | pgjdbc always runs `PREPARE TRANSACTION`, and returns `XA_RDONLY` only for a connection marked read-only, after which the TM skips phase 2 and the transaction stays prepared. So XA connections are never marked read-only, and read-only XA transactions pay the full 2PC. ScalarDB skips the coordinator write. |
| Connection pools | the same size per database in every arm |
| JVM | JDK 21, same heap and GC flags for the app, the sidecar and the TC |
| Retries | conflicts retried until the transaction commits, and counted by cause: SQLSTATE 40001, 40P01, `55P03`, XA rollback codes, and ScalarDB's conflict exceptions |
| Key order | each transaction reads and writes its keys in (database, id) order, so no arm deadlocks, within or across databases. A lock timeout isn't needed. |
| Isolation | set at connect time through pgjdbc's `options` (`default_transaction_isolation`), so no arm pays a `SET` per transaction |
| Product settings | fixed and recorded in `bench/xa/<arm>/` before the runs, not changed after the first result |

## Driver

`bench/xa` on branch `cloud-bench`: a Gradle project (`XaBench`, with the arms in `SeataSession`,
`ScalarDb` and `Recovery`) and `xa.sh`, which sets up the databases, starts the frontend and Seata's TC, and
runs one measurement. It follows `AppBench` from plan 2026-11-03-001, but is a project of its own, because the
transaction managers bring their own dependencies. ScalarDB comes from the frontend's jar on the class path,
so the `core` arm embeds the same core the frontend runs.

| Arm | How it commits |
|---|---|
| `none` | two JDBC connections, committed one after the other |
| `narayana` | JTA `TransactionManager`, with each database's `PGXAConnection` enlisted on first use, so a single-database transaction has one branch and commits in one phase |
| `atomikos` | JTA through `AtomikosDataSourceBean`s over `PGXADataSource`, which enlist on use and register for recovery |
| `seata` | Seata's `DataSourceProxyXA` over a HikariCP pool, with the global transaction begun and committed through its API |
| `core` | ScalarDB `DistributedTransactionManager`, multi-storage |
| `fe` | one pgjdbc connection to the frontend, with qualified table names (`ycsb1.usertable`, `ycsb2.usertable`) |

Each database has its own table, with ids 1..ROWS; a key is (database, id). The none and XA arms use
databases `native1` and `native2`; ScalarDB's arms use `sdb1` and `sdb2`, with namespaces `ycsb1` and `ycsb2`.

BenchBase isn't used, because it can't route to two databases or run XA.

## Workloads

### YCSB

There's one `usertable` per database, with 1,000,000 rows of a key plus ten 100-byte fields, so the table fits
in memory, as in plan 2026-11-03-001.

| Factor | Values |
|---|---|
| Transaction | `read-2`: 2 reads. `rmw-2`: 2 reads, then an update of each. `rmw-10`: 10 reads, then 10 updates. |
| Placement | `local`: every key in one database, chosen at random. `cross`: the keys split evenly over the two databases. |
| Skew | uniform; zipfian 0.99 |
| Terminals | 1, 8, 16, 32, 64, 128 |

Run grid. Each cell is all 6 terminal counts:

| Tier | Transaction | Placement | Skew | Arms |
|---|---|---|---|---|
| A | `read-2`, `rmw-2` | `local`, `cross` | uniform | all 6 |
| A | `rmw-2` | `cross` | zipf 0.99 | all 6 |
| A | `rmw-10` | `cross` | uniform | all 6 |
| B | `rmw-2` | `cross` | uniform, zipf 0.99 | all 6 |

That is 8 cells × 6 arms × 6 terminal counts = 288 runs. Each run has a 30 s warm-up and 90 s measured,
which is about 10 hours. It is split over several sessions, with the environment destroyed after each.

`rmw` updates add 1 to a counter field. After each run, the sum of the counters over both databases must
equal the number of committed updates. That is a cheap atomicity check, and `none` passes it too unless
something crashes.

Expected critical path for one `rmw-2 cross` transaction at 1 terminal. These are fsync layers, not exact
round trips. Round trips are checked with `bench/statements.py` against a PostgreSQL log (phase 2).
- `none`: 2 commits, one after the other. 2 fsyncs.
- XA: `PREPARE TRANSACTION` on both databases, the log write, then `COMMIT PREPARED` on both. 3 fsync layers
  if the branches run in parallel, 5 if not.
- Seata: the XA steps, plus RPCs to the TC for begin, branch registration and reports, and its store writes
  in db1.
- `core`, `fe`: 2 reads; prepare (one conditional write per database, in parallel); the coordinator write;
  commit (per database, in parallel). 3 fsync layers. Tier B adds re-reads to validate before the
  coordinator write.

If a measured latency doesn't fit its model, a setting is not what we think it is. Stop and find out before
the sweep.

### TPC-C

100 warehouses: 1–50 in db1 and 51–100 in db2. `item` is loaded into both, because it's read-only.

TPC-C is implemented in the driver over the same interface, porting BenchBase's statements with
`bench/tpcc`'s patches. These avoid DB-CORE-10106 in Delivery, for example. `core` is left out of TPC-C:
`fe` against `core` is plan 2026-11-03-001's question, and porting TPC-C to the ScalarDB API is a second
implementation to keep correct.

| Remote setting | NewOrder | Payment |
|---|---|---|
| `0%` | every supply warehouse is the home warehouse | the customer is in the home warehouse |
| `spec` | the spec's 1% remote per order line | the spec's 15% remote customers |
| `100%` | at least one order line from a warehouse in the other database | the customer's warehouse is in the other database |

Under `spec`, about half of the remote warehouses land in the same database. The driver reports the measured
cross-database fraction for each transaction type.

Tier A only: 3 remote settings × 5 arms × 6 terminal counts = 90 runs, 60 s warm-up and 180 s measured each.

## Measurements

For every run:
- tps; p50, p95 and p99 latency, separately for single-database and cross-database transactions and for each
  TPC-C transaction type.
- Retries and aborts per committed transaction, by cause.
- CPU per committed transaction for the app, the sidecar, the TC and each database VM (from `/proc`, as with
  `cpulog.sh`).
- WAL syncs per committed transaction on each database, from `pg_stat_wal`, before and after the run.
- Prepared transactions left over at the end (`pg_prepared_xacts`): it should be 0.

Once per arm, in a separate short verification run, because tracing slows the run:
- Log syncs on the app or TC VM per transaction (`strace -f -e trace=fsync,fdatasync` on the process).
- Round trips per transaction (`bench/statements.py` with `log_min_duration_statement=0`).

## Environment

Reuse `bench/cloud` on branch `cloud-bench`: `create-env.sh`, `setup.sh`, `cpulog.sh`, `collect.sh`,
`destroy-env.sh`.

| VM | Type | Runs |
|---|---|---|
| app | n2-standard-16 | the driver, the embedded TMs and ScalarDB Core, the frontend sidecar |
| db1 | n2-standard-16 | PostgreSQL 16; ScalarDB's coordinator table; Seata's TC store |
| db2 | n2-standard-16 | PostgreSQL 16 |
| tc | n2-standard-8 | Seata's TC server (Seata runs only) |

All VMs are in one zone, with PD-SSD for the databases and for the TM logs. The data is reloaded and
vacuumed before each workload and arm.

## Phases

### 0. Check the assumptions (local, no cloud): done 2026-11-15

Answers, from the sources of the released versions:
1. Narayana 7.3.4: see "Durability settings" and "Settings to match".
2. Atomikos 6.0.1: the same.
3. Seata 2.6.0: `setAutoCommit(false)` registers the branch (an RPC) and starts XA; `commit()` does nothing;
   `close()` ends and prepares the branch; the global commit has the TC run phase 2 synchronously (only AT
   branches commit asynchronously), calling back to the app's RM for `COMMIT PREPARED`.
4. pgjdbc 42.7.14: `start` and `end` send nothing; for `prepare`, see "Settings to match".
5. Frontend with multi-storage: one transaction through the frontend wrote `ycsb1` in one database and `ycsb2`
   in another, the coordinator table got a state row, and a single-database transaction committed in one
   phase without one.
6. `Snapshot.toSerializable` re-reads every get and scan through the storage it's given, which is the
   multi-storage, so validation covers both databases.
7. Licences: Narayana Apache 2.0; Atomikos TransactionsEssentials Apache 2.0 for releases signed on Maven
   Central, with no terms on benchmarks; Seata Apache 2.0.

A local-only caveat: on macOS the JDK's `FileChannel.force` (Atomikos) uses `F_FULLFSYNC`, 4 ms here, while
`FileDescriptor.sync` (Narayana) and PostgreSQL's `fsync` don't reach the disk (0.03 ms). So local numbers say
nothing about relative speed. On Linux all of them are a real `fsync` or `fdatasync`.

The original questions:

Each answer gets written into this plan before phase 1. Read the source where there is one.
1. Narayana: which object store syncs every write, its default, and the async prepare and commit options and
   their defaults.
2. Atomikos: how its log syncs, and the setting.
3. Seata XA mode (`~/git/incubator-seata`): the TC's `db` store writes, whether the TC commits them before it
   answers, and whether phase 2 runs before or after the app's commit call returns.
4. pgjdbc: whether `prepare` returns `XA_RDONLY` for a read-only branch, and whether `start`/`end` cost a
   round trip.
5. The frontend with multi-storage: one transaction that writes `ycsb1.usertable` and `ycsb2.usertable`, its
   metadata table placed in a mapped namespace, and DDL in both namespaces. Test against two databases on
   the local PostgreSQL (`build/local-pg`, port 25432).
6. ScalarDB `SERIALIZABLE`: validation covers reads from both storages (`CommitHandler`, `Snapshot`).
7. Licences: none of the three transaction managers forbids publishing benchmark results.

### 1. Driver

Write the arm adapters and YCSB, then TPC-C. Smoke-test every arm locally against two databases on 25432.
Each one must pass the counter-sum check.

Done 2026-11-15:
- YCSB: every arm and workload passes the check. `none` at tier B adds extra updates (partial commits) as
  expected.
- TPC-C: W=4, 4 threads, 10 s, remote 0, spec and 100. `none`, `narayana`, `atomikos`, `seata` and `fe` all
  pass, with 0 errors. The check covers the payment and stock invariants, and fails when nothing commits.
  `cross` tps is about 7% of all at spec and about 88% at 100.
- Fixes made along the way:
  - `h_date` comes from a process-wide clock that never repeats a millisecond. On `none`, two payments to one
    customer could collide in history's key.
  - Atomikos closes its statements before rollback.

### 2. Recovery check (local)

For each coordinated arm:
1. Kill the app (and, for `seata`, the TC) with `kill -9` during a `rmw-2 cross` run.
2. Restart it with recovery on.
3. Check that the counter sum holds and that no prepared transaction is left.

This confirms that the durability settings above really allow recovery. A product that fails this is fixed or
dropped before any cloud run.

Done 2026-11-15 (`./xa.sh crash ARM`): every arm passes.

| Arm | Transactions | Result |
|---|---|---|
| narayana | 20,563 | ok |
| atomikos | 929 | ok |
| seata | 9,622 | ok, with the TC also killed |
| core | 19,306 | ok |
| fe | 26,441 | ok, with the frontend also killed |

Recovery needed these settings:
- Narayana: the recovery properties have to be set before its beans are first read.
- Atomikos: `max_timeout` lowered to 30 s in recovery. Otherwise it presumes abort only after 300 s.
- Seata: the TC's `retry-dead-threshold` lowered to 10 s.

### 3. Verification run (cloud)

`rmw-2 cross` at 1 terminal for every arm. Check fsyncs and round trips against the models above.

### 4. YCSB sweep (cloud)

Tier A, then tier B.

### 5. TPC-C sweep (cloud)

### 6. Report

`bench/xa/README.md`, with:
- the tables and plots;
- the settings of each arm;
- the isolation tiers;
- the decision-log placement (app VM or db1);
- the measured fsync and round-trip counts.

## Out of scope

- ScalarDB Cluster against Seata, both as coordinator servers. That's a natural follow-up once this plan's
  driver exists.
- Throughput during and after recovery.
- A database that locks for reads under XA (MySQL InnoDB at `SERIALIZABLE`).
- More than two databases.
