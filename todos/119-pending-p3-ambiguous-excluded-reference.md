---
status: pending
priority: p3
issue_id: "119"
tags: [dml, validation, regress-suite]
dependencies: []
---

# A table named excluded makes excluded.col ambiguous

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

In ON CONFLICT DO UPDATE on a table named `excluded` without an alias, `excluded.col` is ambiguous (42P09). The frontend picks one.

## Findings

- insert_conflict.sql #150

## Proposed Solutions

1. Raise 42P09 when the target is named excluded and has no alias.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
