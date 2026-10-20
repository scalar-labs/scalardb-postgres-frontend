---
status: complete
priority: p2
issue_id: "131"
tags: [text, collation, wrong-result, differential]
dependencies: []
---

# upper() and lower() mapped every letter under the C collation

Found on 2026-10-20 by differential testing: `lower('Ⴄ' COLLATE "C")` was `ⴄ`; PostgreSQL, under the C
collation the frontend reports with scalar.db.collation=BINARY, changes only ASCII letters.

## Acceptance Criteria

- [x] Under BINARY, upper() and lower() change only a-z and A-Z; under ICU, every letter (Unicode rules).

## Work Log

- 2026-10-20: Fixed in Evaluator; checked live against PostgreSQL 18 (C database).
