---
status: complete
priority: p3
issue_id: "058"
tags: [catalog, protocol, sessions]
dependencies: ["057"]
---

# pg_stat_activity is undeclared, and pg_backend_pid() says 0

Found on 2026-10-17 while doing todo 057's Phase 1 (`docs/plans/2026-10-17-001-fix-catalog-truth-or-error-plan.md`).

## Problem Statement

`pg_stat_activity` fails with 42P01, as the plan allows when the server has no session registry. DBeaver's
session manager, pgAdmin's dashboard and monitoring scripts read it, so they fail where PostgreSQL lists the
sessions.

`pg_backend_pid()` returns 0 (`Evaluator.java`, `case "PG_BACKEND_PID"`). PostgreSQL returns the process ID
that it sent in BackendKeyData. The frontend sends a random ID there (`PostgresServer.java`, `KEYS.nextInt()`),
so a client that compares the two, or that passes the PID to `pg_cancel_backend`, gets a wrong answer.

## Findings

- `PostgresServer` keeps only `CANCELS` (cancel key → flag) and `sessions` (sockets). It keeps no
  per-session user, database, application name, start time, state or current query.
- `Catalog` is per session, so it would need a server-wide registry handed to it to list the others.

## Proposed Solutions

1. **Session registry.** A `ConcurrentMap<Integer, SessionInfo>` in `PostgresServer`, keyed by the
   BackendKeyData PID, holding `datname`, `usename`, `application_name`, `client_addr`, `backend_start`,
   and a `state`/`query` updated around each statement. `pg_stat_activity` is built from it per read, and
   `pg_backend_pid()` returns the session's own PID through a thread-local, as the cancel flag is passed.
   Columns the frontend cannot know are NULL, as PostgreSQL shows them to unprivileged users.
   Effort: medium.
2. **PID only.** Return the BackendKeyData PID from `pg_backend_pid()` and leave `pg_stat_activity`
   undeclared. Effort: small.

## Recommended Action

1, the session registry (`Catalog.ACTIVITY`, which `PostgresServer` keeps current).

## Acceptance Criteria

- [x] `SELECT pg_backend_pid()` equals the PID in BackendKeyData (pgjdbc: `PGConnection.getBackendPID()`).
- [x] `pg_stat_activity` lists this frontend's sessions, with this session's row showing its own query, or
      stays undeclared.

## Work Log

- 2026-10-17: Filed from todo 057 Phase 1; `pg_stat_activity` left undeclared.
- 2026-10-18: Done. Each session registers a `Catalog.Activity` under a positive, unique process ID, which
  BackendKeyData sends and `pg_backend_pid()` returns. It is active from Parse, Bind or a Query, as in
  PostgreSQL, and idle (in a transaction, or a failed one) at ReadyForQuery. `pg_stat_activity` is built
  from the registry per read; another user's session shows only who and where unless the reader is a
  superuser, as PostgreSQL 16 shows it. pgAdmin's dashboard query still fails on `to_char` and
  `pg_blocking_pids` (todo 064).
