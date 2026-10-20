---
status: complete
priority: p1
issue_id: "128"
tags: [joins, wrong-result, sqlancer]
dependencies: []
---

# Comma and JOIN precedence; text as a condition

Found on 2026-10-20 by SQLancer (NoREC x4, TLP x3) once FROM ONLY / t* parsed (todo 127).

## Problem Statement

1. In PostgreSQL a comma binds more loosely than JOIN: `FROM a, b RIGHT JOIN c ON b.x = c.x` is
   a x (b RIGHT JOIN c), and an ON naming `a` fails (42P01). The frontend joined left to right, giving
   (a x b) RIGHT JOIN c (other rows for RIGHT and FULL joins) and answering ON clauses PostgreSQL rejects.
2. Todo 126 still read a text value used as a condition as boolean input; PostgreSQL rejects a text column
   there, and the leniency answered inconsistently (NoREC).

## Acceptance Criteria

- [x] An ON naming a table from an earlier comma-separated item fails with 42P01.
- [x] A RIGHT or FULL JOIN after a comma is refused (0A000); INNER and LEFT joins, which are the same either
      way, run.
- [x] A text value used as a condition fails with 42804 (a bare `WHERE 't'` literal too, which PostgreSQL
      accepts: an error, not a wrong answer).

## Work Log

- 2026-10-20: Fixed; checked live against PostgreSQL 18; join.sql, select.sql, subselect.sql: nothing newly
  refused.
