---
status: complete
priority: p1
issue_id: "136"
tags: [arrays, wrong-result, differential]
dependencies: []
---

# Array text lost the spaces inside its quotes

Found on 2026-10-20 while testing jsonb_set paths against PostgreSQL 18: `'{" a "}'::text[]` was
`{a}`, so `'x ' = ANY('{"x ",y}')` was false and `'x' = ANY('{"x ",y}')` true, the other way round
from PostgreSQL. QueryParser.parseArrayText trimmed every element.

## Acceptance Criteria

- [x] As array_in: whitespace before an element and unquoted whitespace after it are dropped;
      quoted or escaped whitespace, and spaces between words (`{a b}`), are kept.

## Work Log

- 2026-10-20: Fixed in QueryParser.parseArrayText; checked live against PostgreSQL 18.
