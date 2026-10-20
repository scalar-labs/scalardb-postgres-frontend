---
status: complete
priority: p1
issue_id: "134"
tags: [numeric, join, wrong-result, differential]
dependencies: []
---

# A real joined a float8 that PostgreSQL holds unequal

Found on 2026-10-20 by differential testing (TLP): `t0.c0 = t3.c0` with t0.c0 real and t3.c0
float8 matched 0.16519462::real with 0.16519462::float8, one row more than PostgreSQL. Hash join,
GROUP BY, DISTINCT and set-operation keys (Operators.normalize) were built from the value's
printed text, and a real prints as its shortest float4 text; PostgreSQL widens it to float8
(0.16519461572170258) before comparing.

## Acceptance Criteria

- [x] Keys widen real to float8, so the join, UNION, IN (subquery) and DISTINCT agree with
      PostgreSQL 18 on real/float8 pairs.

## Work Log

- 2026-10-20: Fixed in Operators.normalize; HelpersTest; checked live against PostgreSQL 18.
