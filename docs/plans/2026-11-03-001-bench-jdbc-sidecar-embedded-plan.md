---
title: "bench: JDBC on PostgreSQL vs the frontend as a sidecar vs ScalarDB Core embedded, on GCP"
type: bench
status: completed
date: 2026-11-03
---

# JDBC on PostgreSQL vs the frontend as a sidecar vs ScalarDB Core embedded

## Outcome

Run on 2026-11-03 with frontend 3ef546b, three runs per point. The sidecar (`fe`) reached 76% of
embedded Core's throughput on read-modify-write at 32 threads and 65% at 64; on point reads, 46% and
37%. The gap is the sidecar's own CPU, about 250 µs per read and 500 µs per read-modify-write
transaction (parsing, planning and the wire protocol), against Core's 80–90 µs and 200–240 µs in the
application. pgJDBC's simple query protocol (no server-side prepared statements) cost the sidecar about
four times as much CPU again. Embedded Core beat JDBC on PostgreSQL on reads at every thread count and
was within 5% of it on read-modify-write. Later frontend changes cut its per-statement cost; the TPC-C
numbers since are in `bench/scalardb-core-issues-tpcc-2026-11.md`.

## Goal

Benchmark three ways an application reaches the same PostgreSQL, on GCP:

| # | Configuration | Path |
|---|---|---|
| 1 | `pg` | JDBC app → PostgreSQL |
| 2 | `fe` | JDBC app → frontend sidecar on localhost → ScalarDB Core → PostgreSQL |
| 3 | `core` | app embedding ScalarDB Core → PostgreSQL |

The main question is what the sidecar costs over embedding Core (2 vs 3): SQL parsing, the PostgreSQL
wire protocol and the localhost hop. 1 vs 3 shows what ScalarDB itself costs once the coordinator write
is gone.

## What each difference measures

| Difference | Cost it isolates |
|---|---|
| 3 − 1 | ScalarDB: metadata columns and before-images on every write, conditional updates, separate database calls for gets and for the commit, Consensus Commit CPU in the app. No coordinator write (one-phase commit, read-only omission). |
| 2a − 3 | The sidecar with prepared statements: pgJDBC's encoding, the localhost round trip, the frontend's wire protocol and binding a cached plan for each execution. |
| 2b − 2a | Parsing and planning: the frontend caches a plan for each prepared statement in a session (`bench/README.md`, "`-M prepared`"); simple query mode parses and plans every statement. |

## Configurations

### 1. `pg`

pgJDBC with autocommit off at `REPEATABLE READ` (PostgreSQL's snapshot isolation). This is the closest
PostgreSQL level, not an exact match: ScalarDB's `SNAPSHOT` reads the latest committed version of each
record as it reads it (per record, much like read committed, so read skew is possible) and prevents lost
updates by validating writes at commit, while `REPEATABLE READ` reads every record from one snapshot taken
at the transaction's first statement (correction, 2026-11-10). Default pgJDBC settings (server-side prepared statements after 5 executions).

### 2. `fe`, two variants

- **2a:** pgJDBC defaults, so the frontend's plan cache is used.
- **2b:** `preferQueryMode=simple`, so every statement is parsed and planned.

The sidecar is the standalone server on the client VM:

```properties
scalar.db.postgres.listen_address=127.0.0.1
scalar.db.postgres.auth=none
scalar.db.postgres.ssl.require=false
scalar.db.postgres.timing_log_interval=10
```

### 3. `core`

The same workload through `DistributedTransactionManager`, configured as Consensus Commit on JDBC
storage.

### ScalarDB settings for 2 and 3

The same file for both:

```properties
scalar.db.storage=jdbc
scalar.db.transaction_manager=consensus-commit
scalar.db.consensus_commit.isolation_level=SNAPSHOT
scalar.db.consensus_commit.one_phase_commit.enabled=true
scalar.db.consensus_commit.coordinator.write_omission_on_read_only.enabled=true
scalar.db.metadata.cache_expiration_time_secs=-1
scalar.db.cross_partition_scan.enabled=true
scalar.db.cross_partition_scan.filtering.enabled=true
scalar.db.cross_partition_scan.ordering.enabled=true
```

Plus the same JDBC connection pool size in both.

What the core source shows (`ParticipantCommitHandler.canOnePhaseCommit`):
- Under `SNAPSHOT`, a transaction with writes commits in one phase, because JDBC storage makes one
  database one atomic unit.
- Read-only transactions skip the coordinator by default.

Group commit, async commit and the parallel options have nothing to act on, so they stay at their
defaults.

## Workloads

One table of 1,000,000 rows (`id` key, one text field), loaded the same way in each database. Keys are
uniform, so conflicts stay rare.

| Workload | Transaction |
|---|---|
| `read` | 2 point reads |
| `rmw` | 2 point reads, then an update of each |

Expected round trips per `rmw`, all to be checked with `bench/statements.py` on a PostgreSQL log
written with `log_min_duration_statement=0` during a short run:
- **1:** 5 to PostgreSQL.
- **3:** 2 gets plus one database transaction for the one-phase commit, about 4–5 to PostgreSQL.
- **2:** the same as 3 to PostgreSQL, plus 5 to the sidecar.

Conflicts are retried and counted on every path: SQLSTATE 40001 on PostgreSQL at `REPEATABLE READ`,
and ScalarDB's conflict exceptions.

## Driver

A single driver, `bench/cloud/AppBench.java`, runs the same workload for all three configurations, so
they do the same work:
- JDBC mode takes a URL and covers 1, 2a and 2b.
- Core mode takes a properties file and covers 3.
- It reports tps, p50 and p99 latency, retries, and the process's CPU per committed transaction.
- It is a rewrite of the ClusterBench used for Cluster PR #2114, whose source was lost when the
  scratchpad was pruned; the Core mode is its CRUD session with Core properties.

BenchBase is not used, because it has no ScalarDB API implementation. A second implementation of its
YCSB would have to match its key distribution and transaction mix exactly.

## Environment

Reuse `bench/cloud` on branch `cloud-bench`: `create-env.sh`, `setup.sh`, `cpulog.sh`, `collect.sh`
and `destroy-env.sh`.

| VM | Type | Runs |
|---|---|---|
| client | n2-standard-16 | the app and, for 2, the sidecar (both JDK 21, same heap and GC flags) |
| db | n2-standard-16 | PostgreSQL 16, with the `bench/cloud` settings |

The database VM is larger than the earlier 8 vCPUs because, in the Cluster PR #2114 run on GKE,
read-and-update saturated an 8-vCPU database and every path converged.

Each configuration gets its own database on the same instance. The data is reloaded and vacuumed
before each configuration.

## Measurements

For every run:
- tps, p50 and p99, and retries.
- CPU per committed transaction, separately for the app process, the sidecar process and the database
  VM (from `/proc`, over the measured window).
- For 2, the frontend's timing log: the share of its time spent on its own work against waiting for
  ScalarDB.

Threads: 1, 8, 32 and 64.
- The 1-thread runs give the added latency per transaction without queueing.
- The rest show where each configuration saturates.

Each run has a 30 s warm-up and a 60 s measurement, with 3 repetitions. That is 4 configurations ×
2 workloads × 4 thread counts × 3 repetitions = 96 runs, about 2.5 hours with loads.

Then one profiling pass with async-profiler, CPU mode, at 32 threads:
- **2a and 2b:** profile the sidecar and the app.
- **3:** profile the app.

Split the time into socket and wire protocol, parsing and planning, the frontend's executor, ScalarDB
Core, pgJDBC to PostgreSQL, and JIT/GC, using the classifier from the Cluster PR #2114 analysis. The
user runs the profiler: the auto-mode classifier refuses to run a downloaded binary.

## Reading the results

- **Sidecar overhead (2a vs 3):**
  - Client-VM CPU per transaction: app plus sidecar for 2a, app only for 3.
  - Latency at 1 thread.
- **Parsing (2b vs 2a):** sidecar CPU per transaction and latency at 1 thread.
- **ScalarDB (3 vs 1):** app CPU and database CPU per transaction.
- **Throughput at 32–64 threads:** only meaningful while client CPU stays below about 70% and the
  database VM is not saturated; check both before quoting a ratio.

## Steps

1. Write `AppBench.java` and a `remote/sweep-3way.sh` on `cloud-bench`, and check them locally on a
   small table.
2. `build.sh`, `create-env.sh`, `setup.sh` (database VM type overridden to n2-standard-16), then load
   the three databases.
3. Short run with the PostgreSQL statement log; check the round trips above.
4. The sweep, then `collect.sh`.
5. The profiling pass (the user runs async-profiler).
6. Write up the results: tables, the differences above and the profile split.
7. `destroy-env.sh` and check that nothing is left.

## Not covered

- The frontend inside a Cluster node, and the ScalarDB Cluster SDK (measured in Cluster PR #2114).
- Scans, joins and secondary indexes (`bench/` microbenchmarks and BenchBase YCSB-E, TPC-C).
- `SERIALIZABLE`: a later run if needed. One-phase commit still applies to `rmw`, because it writes
  every record it reads.
