---
status: complete
priority: p2
issue_id: "115"
tags: [protocol, clients, regress-suite]
dependencies: []
---

# DEALLOCATE ALL is refused

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

psycopg 3 sends `DEALLOCATE ALL` by itself after DROP or ROLLBACK statements once it holds prepared statements. The frontend answers 42601, so the client sees an error, and inside a block the transaction aborts.

## Findings

- transactions.sql #58 (then #59–65, COMMIT becomes ROLLBACK), #142, #196, #288, #361; insert_conflict.sql #191
- `DEALLOCATE ALL`: PostgreSQL `DEALLOCATE ALL`, frontend `42601 Invalid SQL`

## Proposed Solutions

1. Accept `DEALLOCATE [PREPARE] ALL | name` (dropping the named statements), next to DISCARD.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [x] The statements above give PostgreSQL 16's answer, or an error.
- [x] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
- 2026-10-19: Done. PostgresServer handles DEALLOCATE [PREPARE] name | ALL itself, where the session's prepared statements live: ALL drops every one (the Close message's code, now PostgresServer.forget), a name drops it or fails with 26000. It works by the simple protocol and the extended one (Describe answers no parameters and NoData; Execute runs it), which psycopg uses. A live psycopg run against PostgreSQL 16 and the frontend gives the same answers: prepared statements keep working after DEALLOCATE ALL, also inside a transaction and after ROLLBACK. PostgresServerTest covers it with pgjdbc.
