# One already-committed record leaves a transaction's other records PREPARED

**Component:** ScalarDB core: Consensus Commit, the commit-records phase (`ParticipantCommitHandler.doCommitRecords`)
**Version:** core `master` at af3a191f5 (2026-11-10), as embedded in the frontend jar of 2026-11-09
**Found by:** the XA vs ScalarDB benchmark driver (`bench/xa` on branch `cloud-bench`), locally, 2026-11-15

## Summary

After writing COMMITTED to the coordinator table, a transaction commits its records by storage group: the
mutations are grouped by `MutationsGrouper` (for JDBC storage, one group per database), and each group is
one atomic `mutate` whose puts are each conditional on `tx_id = <this transaction> AND tx_state = PREPARED`.

Under contention, a reader often finds one of those records still PREPARED, sees the coordinator state
COMMITTED, and rolls it forward itself (lazy recovery). The transaction's own commit for that group then fails
its condition on that one record (`NoMutationException`), the whole group rolls back, and the error is logged
at INFO and ignored ("records are recovered lazily"). The group's other records stay PREPARED, although their
transaction committed, until a later reader rolls each of them forward.

This is not a correctness problem: the coordinator state decides, and every reader resolves the record. It
is a cost on hot keys.

## Observed

YCSB-style `rmw-10` (10 reads, then 10 updates; 5 keys in each of two databases), zipfian keys (0.99) over
100,000 rows per database, 4 threads, 3 s, `core` arm (embedded Core, multi-storage, SNAPSHOT):

- 30 `commitRecords` tasks failed with `NoMutationException`; every one of them was a batch of 5 puts
  (`grep -A3 "Failed to run a commitRecords task" | grep NoMutationException`).
- 63–120 records were left PREPARED per 3-second run, all of committed transactions.
- Readers then retried with `UncommittedRecordException` 77–201 times per run, on top of the
  `CommitConflictException` retries.

## Cost

Each stranded record costs a later reader a failed read (an `UncommittedRecordException` and a retry of its
whole transaction, if the writer has not expired yet, or a roll-forward write). On hot keys that feeds back:
more PREPARED records, more readers that roll forward, more failed commit groups.

## Options to discuss

- Commit each record with its own conditional put when a group fails, or retry the group without the records
  that are already COMMITTED by this transaction (`tx_id` equal, `tx_state` COMMITTED is success).
- Treat a put whose record is already COMMITTED by this same transaction as a no-op in the condition
  (`tx_id = T AND tx_state IN (PREPARED, COMMITTED)`), so the group cannot fail on it.

## Reproduce

```sh
cd bench/xa   # branch cloud-bench
./xa.sh setup  # with ROWS=100000
java -cp "$FRONTEND_JAR:build/install/xabench/lib/*" -Dxa.db1=... -Dxa.db2=... -Dxa.user=postgres \
  -Dxa.password=... -Dxa.scalardb=run/scalardb.properties XaBench run core A rmw-10 cross zipf 4 1 3 100000
```

With the frontend jar first on the class path, its logging binding is used and the
`ParallelExecutor - Failed to run a commitRecords task` warnings appear.
