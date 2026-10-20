---
status: complete
priority: p1
issue_id: "144"
tags: [security, read-only, transactions]
dependencies: ["143"]
---

# A write starting with WITH escaped the read-only check and the write tracking

Found on 2026-10-20 by a background security review of todo 143: QueryProcessor took a statement
for a write by its first word, so `WITH ... INSERT/UPDATE/DELETE` and a data-modifying WITH query
before a SELECT ran in a READ ONLY transaction (or under default_transaction_read_only) instead of
failing with 25006, and were not counted as writes of the block (the unique-violation reading of
an INSERT-only block at COMMIT relies on that count).

## Acceptance Criteria

- [x] After planning, a plan that writes (Plan.writes: its own writes or data-modifying WITH
      queries) gets the read-only check and the write tracking whatever its first word.

## Work Log

- 2026-10-20: QueryProcessor.statement; QueryProcessorTest covers both WITH forms under READ ONLY.
