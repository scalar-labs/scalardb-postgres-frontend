---
status: pending
priority: p3
issue_id: "118"
tags: [dml, regress-suite]
dependencies: []
---

# INSERT with fewer values than columns is refused

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`INSERT INTO t VALUES (20)` on a two-column table fills the missing trailing columns with their defaults (NULL here). The frontend raises 42601.

## Findings

- insert_conflict.sql #107, #108

## Proposed Solutions

1. Fill missing trailing columns as an omitted column is filled.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
