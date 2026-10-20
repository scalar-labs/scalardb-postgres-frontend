---
status: complete
priority: p1
issue_id: "135"
tags: [join, optimizer, wrong-result, differential]
dependencies: []
---

# A WHERE equality moved into an inner join under a later FULL/RIGHT JOIN

Found on 2026-10-20 by differential testing (NoREC): in `a JOIN c ON true FULL JOIN b ON false
WHERE a.x = c.z`, Optimizer.hashJoins made `a.x = c.z` the hash key of the a-c join. The FULL JOIN
then null-extended b's rows, which the WHERE would have removed (NULL = NULL is not true): extra
rows, 196 in the reported query.

## Acceptance Criteria

- [x] WHERE conjuncts become hash keys only of a join no later RIGHT/FULL JOIN null-extends
      (LogicalPlan.nullable, as keyedLookups already checked).

## Work Log

- 2026-10-20: Fixed in Optimizer.hashJoins; QueryParserTest; FULL, RIGHT, LEFT and a trailing
      CROSS JOIN checked live against PostgreSQL 18.
