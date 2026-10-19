---
status: pending
priority: p3
issue_id: "084"
tags: [strings, wrong-result, regress-suite]
dependencies: []
---

# SIMILAR TO ... ESCAPE mishandles NULL and invalid escapes

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

A NULL escape makes SIMILAR TO NULL, and an escape longer than one character is 22025. The frontend ignores both.

## Findings

- strings.sql #101, #102
- `'abcdefg' SIMILAR TO '_bcd%' ESCAPE NULL`: PostgreSQL NULL, frontend t
- `'abcdefg' SIMILAR TO '_bcd#%' ESCAPE '##'`: PostgreSQL 22025, frontend f

## Proposed Solutions

1. Return NULL for a NULL escape; raise 22025 for an escape that is not one character.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
