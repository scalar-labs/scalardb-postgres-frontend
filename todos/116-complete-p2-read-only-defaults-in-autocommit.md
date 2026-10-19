---
status: complete
priority: p2
issue_id: "116"
tags: [transactions, settings, regress-suite]
dependencies: []
---

# Read-only session defaults are not applied to single statements

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` and `default_transaction_read_only = on` apply to every transaction, including a single statement's. The frontend applies them only after BEGIN, and there with SQLSTATE 42601 instead of 25006.

## Findings

- transactions.sql #67 (DROP TABLE runs), #70 (DELETE runs), and their fallout
- `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; DELETE FROM t WHERE a = 2`: PostgreSQL 25006, frontend `DELETE 1`

## Proposed Solutions

1. Apply the read-only default to implicit transactions and DDL; use 25006.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. QueryProcessor.checkWritable refuses a write (INSERT, UPDATE, DELETE, TRUNCATE) or DDL (CREATE, DROP, ALTER, GRANT, REVOKE, COMMENT) with 25006 and PostgreSQL's wording ('cannot execute DROP TABLE in a read-only transaction') when the transaction is read-only: the block's READ ONLY inside BEGIN, default_transaction_read_only (SET SESSION CHARACTERISTICS) for a single statement. Before, only some autocommit writes used a read-only ScalarDB transaction, and BEGIN READ ONLY writes got 42601. The aborted-transaction message is now lower case, as PostgreSQL's. A 23-statement script gives the same output as PostgreSQL 16; QueryProcessorTest covers it.
