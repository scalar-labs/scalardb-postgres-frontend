---
status: pending
priority: p2
issue_id: "087"
tags: [arrays, ddl, wrong-result, regress-suite]
dependencies: []
---

# An array column is created as its element type

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`CREATE TABLE t (a int[])` succeeds, but the column is created as integer, so array values are refused and the catalog says integer.

## Findings

- Found on 2026-10-19 while checking the subscripted UPDATE
- `CREATE TABLE zz (id int PRIMARY KEY, a int[])`, then `format_type` of a: PostgreSQL `integer[]`, frontend `integer`; `INSERT ... '{1,2,3}'` then fails with 22P02

## Proposed Solutions

1. Refuse array column types with 0A000 at CREATE TABLE and ADD COLUMN until they are stored, for example as text recorded in the type store.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
