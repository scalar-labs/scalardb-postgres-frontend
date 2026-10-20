---
status: complete
priority: p1
issue_id: "146"
tags: [similar-to, like, wrong-result, differential]
dependencies: []
---

# A trailing escape in a SIMILAR TO pattern matched a backslash

Found on 2026-10-20 by differential testing (NoREC): `x SIMILAR TO 'ᐳZP&\'` matched the text
`ᐳZP&\`, so a LEFT JOIN on it kept rows PostgreSQL drops (9152 against 8008). PostgreSQL drops a
trailing escape that escapes nothing (similar_to_escape('ab\') is ^(?:ab)$); the frontend took it
as a literal backslash. LIKE did the same where PostgreSQL fails with 22025.

## Acceptance Criteria

- [x] SIMILAR TO drops a trailing escape, as PostgreSQL does.
- [x] LIKE with a pattern ending in its escape fails with 22025, in memory and never pushed down
      to ScalarDB (PostgreSQL fails only once matching reaches it: 'ab' LIKE 'ab\' is false there,
      an error here).

## Work Log

- 2026-10-20: Evaluator.similarPattern, likePattern, endsWithEscape; QueryParser's LIKE pushdown;
      HelpersTest; the NoREC query checked against PostgreSQL 18.
