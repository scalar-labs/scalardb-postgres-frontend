---
status: complete
priority: p1
issue_id: "122"
tags: [ddl, insert, wrong-result, data-integrity]
dependencies: []
---

# A table whose key is not declared first stores INSERT values in the wrong columns

Found on 2026-10-20 while working on todo 118.

## Problem Statement

ScalarDB's JDBC metadata stores a table's partition key columns first, then its clustering key columns, then
the others in declared order. The frontend lists a table's columns in that stored order, so for
`CREATE TABLE t (a int, k int PRIMARY KEY)`:

- `INSERT INTO t VALUES (10, 1)` stored k = 10, a = 1 (PostgreSQL: a = 10, k = 1), silently when the types fit
- `SELECT *` returned `k, a`, and `pg_attribute.attnum` numbered k first

## Findings

- `scalardb.metadata` for the table: k ordinal 1, a ordinal 2; `TableMetadata.Builder` itself keeps the
  declared order, the stored metadata does not.

## Proposed Solutions

1. Refuse a CREATE TABLE whose declared order differs from the stored one, naming the order to declare.
2. Record each column's declared position in the frontend's metadata table and order by it (needs a
   position for every column and a way to fill it for existing tables).

## Recommended Action

Solution 1: the order is then the same everywhere. Tables created earlier, or by other ScalarDB clients, keep
ScalarDB's order (documented in LIMITATIONS.md).

## Acceptance Criteria

- [x] `CREATE TABLE t (a int, k int PRIMARY KEY)` fails with 0A000 and names `(k, a)`.
- [x] A table declared key-first behaves as in PostgreSQL for `SELECT *` and positional `INSERT`.

## Work Log

- 2026-10-20: Found and fixed (refused) while closing todo 118; checked live against PostgreSQL 18.
