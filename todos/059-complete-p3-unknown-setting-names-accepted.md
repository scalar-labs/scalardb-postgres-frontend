---
status: complete
priority: p3
issue_id: "059"
tags: [settings, compatibility]
dependencies: ["057"]
---

# SET accepts setting names PostgreSQL does not know

Found on 2026-10-17 in the review of todo 057's Phase 1 (commit ee09ec4).

## Problem Statement

`SET foo = 1` succeeds, and `SHOW foo` then answers 1. PostgreSQL 16 answers 42704 `unrecognized
configuration parameter "foo"` for a name without a dot; only dotted names (`myapp.x`) are custom
settings it accepts.

Startup parameters behave the same way. A startup parameter the frontend does not list, such as pgjdbc's
`extra_float_digits=3`, is stored as the client sent it. `SHOW ALL` then lists it with no description,
and `pg_settings` leaves it out, so the two views disagree.

## Findings

- `QueryProcessor.setting` and `setClientDefault` store any name `settingKey` does not know.
- Clients send settings on connect that the frontend does not list: pgjdbc sends `extra_float_digits`,
  and others send `DateStyle` and `IntervalStyle`. Rejecting unknown names outright would break those
  connections.
- `DateStyle` is not canonicalised: PostgreSQL turns `ISO` into `ISO, MDY`.
- `SHOW ALL` is in insertion order; PostgreSQL sorts it by name.

## Proposed Solutions

1. **List and reject.** Add the settings clients send to `Catalog.SETTINGS`, with PostgreSQL 16's
   metadata, and answer 42704 for any other name without a dot. Before choosing the list, capture the
   startup parameters and SETs of the compat sweep's clients. Effort: medium.
2. **Reject only in SET.** Keep accepting startup parameters, and reject unknown non-dotted names in SET
   and set_config. Effort: small, but the two paths then behave differently.

## Recommended Action

Solution 1. Capturing what clients send through a logging proxy to PostgreSQL 16 found only names the
frontend already listed, plus `extra_float_digits` (pgjdbc's startup message) and `IntervalStyle`
(ActiveRecord's SET). The clients were pgjdbc 42.7.4, psycopg 3, psycopg2, asyncpg, SQLAlchemy 2,
Django 5.2, node-postgres, Sequelize 6, pgx v5, lib/pq, Ruby pg, ActiveRecord 7.2, psql 14 and 18, and
Prisma 6. None of them sent a dotted name.

## Acceptance Criteria

- [x] `SET foo = 1` fails with 42704; `SET myapp.x = 1` succeeds. The same holds for `SET foo TO
  DEFAULT`, `RESET foo`, `set_config('foo', ...)`, and `-c foo=1` in the startup options (FATAL). A
  live run gives the same output as PostgreSQL 16.
- [x] Every setting SHOW ALL lists is also in pg_settings. SHOW ALL lists exactly `Catalog.SETTINGS`,
  in name order, as PostgreSQL sorts it. Custom dotted settings are in neither view, which is also what
  PostgreSQL 16 does.
- [x] The compat clients are unchanged: every client above connected to the new jar through the
  proxy and ran its statements without error. The difftest session, read and types sets keep their
  baselines.

## Work Log

- 2026-10-17: Filed from the review of todo 057 Phase 1 (finding C5); left out of that merge, since
  rejecting names could break a client's connection.
- 2026-10-19: Done. Unknown names without a dot fail with 42704 on every path. Added
  `extra_float_digits`, `IntervalStyle` and `lock_timeout` (which LIMITATIONS already said was
  accepted), using PostgreSQL 16's metadata. `lock_timeout` is validated as a duration, as
  `statement_timeout` is. `IntervalStyle` is stored but intervals are still printed in the postgres
  style; this is noted in LIMITATIONS. `DateStyle` canonicalisation (`ISO` to `ISO, MDY`) is still
  open.
