---
status: complete
priority: p2
issue_id: "056"
tags: [cluster, ddl, metadata-cache]
dependencies: []
---

# In Cluster mode, a read right after RENAME COLUMN fails on the node's stale metadata

Found on 2026-10-15 while checking the metadata-table change live against a standalone Cluster node (Cluster
`main` 6e2cb011, `difftest/cluster-auth/node.properties`). Reproduced on `main` with an untyped table, so it
predates the change.

## Problem Statement

```sql
CREATE TABLE plain (id INT PRIMARY KEY, amount INT);
INSERT INTO plain VALUES (1, 5);
ALTER TABLE plain RENAME COLUMN amount TO total;
SELECT total FROM plain;
-- ERROR: INTERNAL: DB-CORE-30040: Scanning records from the underlying storage failed. ...
--        The column name amount was not found in this ResultSet.
```

The frontend shortens ScalarDB's table-metadata cache to 1 s and waits it out after DDL on an existing table
(`PostgresServer#configure`, `ddlSettleMillis`), but in Cluster mode the reads run on the node, whose own cache
(`scalar.db.metadata.cache_expiration_time_secs`, 60 s by default) still has the old column, so it selects
`amount` from the renamed table. ADD COLUMN presumably shows the same lag as a missing column.

## Proposed Solutions

1. Document it: set `scalar.db.metadata.cache_expiration_time_secs` low on the nodes that serve the frontend, and
   say so in getting-started's Cluster section and LIMITATIONS.md. Small.
2. Ask Cluster to drop its cached metadata for a table after DDL through the node (the node runs the DDL, so it
   knows). The right fix; outside this repository.

## Recommended Action

Solution 1: the node setting, documented in getting-started and LIMITATIONS.md and set in
`difftest/cluster-auth/node.properties`. Solution 2 is Cluster's to make.

## Acceptance Criteria

- [x] The limitation is documented with the node setting that avoids it, or the node invalidates its cache
- [x] `run.sh` in `difftest/cluster-auth` covers RENAME COLUMN followed by a read

## Work Log

- 2026-10-18: Reproduced on Cluster `main` (node of 2026-10-09) with the node at its default 60 s: DB-CORE-30040,
  column amount not found. With `scalar.db.metadata.cache_expiration_time_secs=1` on the node, RENAME COLUMN and
  ADD COLUMN are both read right away. `run.sh` checks the rename: 83 of 85 pass, the 2 failures being the known
  stale-password Cluster bug.
