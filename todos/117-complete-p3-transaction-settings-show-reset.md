---
status: complete
priority: p3
issue_id: "117"
tags: [transactions, settings, regress-suite]
dependencies: []
---

# SHOW and RESET of the transaction_* settings

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`SHOW transaction_read_only` and `SHOW transaction_deferrable` fail with 42704, which aborts a transaction block; `RESET transaction_isolation` is accepted where PostgreSQL raises 0A000.

## Findings

- transactions.sql #15, #19, #23, #308–369 (SHOW failures inside blocks, then COMMIT AND CHAIN becomes ROLLBACK at #351)

## Proposed Solutions

1. Add transaction_read_only and transaction_deferrable to the settings; RESET of transaction_* is 0A000.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Also: `10 % 3 ^ 2` (integer % double precision) answers here; PostgreSQL has no such operator (42883).
- 2026-10-20: SHOW transaction_read_only and transaction_deferrable answer from the transaction (BEGIN/SET TRANSACTION) or, outside one, the session defaults (default_transaction_read_only, SET SESSION CHARACTERISTICS ... DEFERRABLE); RESET of transaction_isolation, _read_only and _deferrable fails with 0A000. transactions.sql #15, #19, #23, #348, #349, #351 now SAME (vs PostgreSQL 18). The DIFFs left from #306 on follow from START TRANSACTION ISOLATION LEVEL REPEATABLE READ being refused (above the configured isolation), so no block starts there and default_transaction_read_only = on applies.
