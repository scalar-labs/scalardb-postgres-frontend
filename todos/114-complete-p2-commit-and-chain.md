---
status: complete
priority: p2
issue_id: "114"
tags: [transactions, wrong-result, regress-suite]
dependencies: []
---

# COMMIT AND CHAIN and ROLLBACK AND CHAIN do not start a new transaction

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`AND CHAIN` opens a new transaction with the same characteristics. The frontend falls back to autocommit, so the next statement commits on its own and a later ROLLBACK cannot undo it. Outside a block PostgreSQL raises 25P01; the frontend warns.

## Findings

- transactions.sql #312, #366 (masked), #371, #372 (outside a block)
- `BEGIN; INSERT INTO t VALUES (10); COMMIT AND CHAIN; INSERT INTO t VALUES (11); ROLLBACK;`: PostgreSQL keeps 10, frontend keeps 10 and 11

## Proposed Solutions

1. Reopen a block with the same characteristics after AND CHAIN; raise 25P01 outside a block.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. QueryProcessor.chained handles COMMIT/END/ROLLBACK/ABORT AND CHAIN: it ends the block through the plain command, then begins a new block with the same isolation level and read-only status, as PostgreSQL does; outside a block it is 25P01 ('COMMIT AND CHAIN can only be used in transaction blocks'). AND NO CHAIN is the plain command. A 17-statement script (chained commit and rollback, a read-only chain, both outside a block, AND NO CHAIN) gives the same output as PostgreSQL 16; QueryProcessorTest covers it.
