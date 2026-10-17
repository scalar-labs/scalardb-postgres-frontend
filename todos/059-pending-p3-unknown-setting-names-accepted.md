---
status: pending
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

## Acceptance Criteria

- [ ] `SET foo = 1` fails with 42704; `SET myapp.x = 1` succeeds.
- [ ] Every setting SHOW ALL lists is also in pg_settings.
- [ ] The compat sweep is unchanged.

## Work Log

- 2026-10-17: Filed from the review of todo 057 Phase 1 (finding C5); left out of that merge, since
  rejecting names could break a client's connection.
