---
status: complete
priority: p2
issue_id: "145"
tags: [update, primary-key, step-4, differential]
dependencies: []
---

# UPDATE of a primary-key column (step 4)

ScalarDB cannot change a key in place, so `UPDATE t SET id = ...` was refused. Agreed with the user
on 2026-10-20: emulate it as a Delete and an Insert; CREATE TABLE AS and temporary tables stay
refused.

## Acceptance Criteria

- [x] A row whose key changes becomes a Delete of the old key and an Insert of the whole row under
      the new key, in the statement's transaction; the command tag counts rows, not mutations.
- [x] 23505 for a new key another row holds (looked up before writing) or that another row of the
      statement moves to or from (PostgreSQL's result is order-dependent there); 23502 for a NULL
      key; `SET id = id` updates the row in place.
- [x] Checked against PostgreSQL 18: single and composite keys, RETURNING, numeric columns,
      collisions, ROLLBACK, command tags.

## Work Log

- 2026-10-20: QueryParser.keyMove, Plan.Effect with several mutations; LIMITATIONS.md.
