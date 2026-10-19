---
status: pending
priority: p3
issue_id: "082"
tags: [bytea, settings, regress-suite]
dependencies: []
---

# SET bytea_output is refused

Found on 2026-10-19 by running PostgreSQL 16's regression suite through `difftest/regress`; examples checked
on both servers.

## Problem Statement

`SET bytea_output TO escape` fails with 42704, so a client that relies on escape output gets hex.

## Findings

- strings.sql #37, #48 (refused), #49, #51, #52 (output then differs)
- After the SET, `SELECT E'\\xDeAdBeEf'::bytea`: PostgreSQL `\336\255\276\357`, frontend `\xdeadbeef`

## Proposed Solutions

1. Add bytea_output (hex, escape) to the settings and use it when writing bytea as text.
2. Until then, refuse the construct with 0A000 where possible, so the answer is an error rather than a wrong
   result.

## Acceptance Criteria

- [ ] The statements above give PostgreSQL 16's answer, or an error.
- [ ] `difftest/regress` shows no DIFF or PG_ERR for them.

## Work Log

- 2026-10-19: Filed from the regression-suite run (39 files after the 5-file calibration).
