# 2026-10-10: three cheap fixes, measured old jar against new

Todos 033 (count(*) projected every column), 035 (a flush per protocol message, no TCP_NODELAY)
and 039 (the catalog reloaded for every statement). The old jar is main@d21edec, the new one adds
the three fixes; both ran against the same `bench` data (20,000 customers, 10,000 items, 200,000
orders) on ScalarDB over the local PostgreSQL, alternating old and new three times, each after a
warm-up pass. `run.sh` is the harness: pgbench, one client, `-M prepared`, 8 s per script.
`count_items.sql` and `catalog.sql` are the two scripts added for this (the catalog query is the
one psql's `\d customers` sends first); the others are `bench/scripts`.

Median latency of three runs (`ab.tsv` has every run):

| Script | Old | New | Change |
|---|---|---|---|
| point_get | 0.158 ms | 0.124 ms | -22% |
| in_list | 0.313 ms | 0.282 ms | -10% |
| scan_filter | 2.42 ms | 2.39 ms | noise (no fix applies) |
| count_items | 77.5 ms | 70.5 ms | -9% |
| catalog | 1.89 ms | 0.20 ms | -90% |

Native PostgreSQL on the same machine, for scale: `native.tsv`.

- The point reads gain from one write per reply instead of four; on loopback Nagle does not stall,
  so the gain over a real network, where a delayed ACK can hold a reply back by tens of
  milliseconds, was not measured here.
- `count(*)` now asks ScalarDB for the partition key only. The items table is narrow, so the gain
  is small; Consensus Commit still reads its own metadata columns for every record.
- The catalog is kept until DDL in this process or for one second, so psql's `\d` and ORM
  introspection load the table metadata once per burst. The gain grows with the table count.
- Todo 034 was not real for its main case: `IN ($1, $2, ...)` already becomes one Get per value.
