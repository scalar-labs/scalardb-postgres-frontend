---
status: complete
priority: p3
issue_id: "062"
tags: [catalog, cluster, auth]
dependencies: ["057"]
---

# Re-run the catalog audit as a low-privilege ScalarDB Cluster user

Carried over from todo 057 (`docs/plans/2026-10-17-001-fix-catalog-truth-or-error-plan.md`, Phase 0's client
matrix and acceptance flow 4), which closed without it because it needs a Cluster node.

## Problem Statement

Every catalog answer of todo 057 was checked as the frontend's one `scalardb` role. Under Cluster
authentication, `pg_roles`, the ACLs, `getTablePrivileges` and `has_*_privilege` depend on the user's grants,
and a user may see only some namespaces. Nobody has checked that a low-privilege user gets the truth or an
error there, and not another user's view.

## Proposed Solutions

1. **Run it.** Run `difftest/catalog/meta.zsh` (psql 18) and `\dt *.*` as a user with SELECT on one table,
   under `difftest/cluster-auth`, and compare with PostgreSQL 16 given the same grants. Effort: small, once a
   Cluster node is built (see the cluster-embedding build notes).

## Recommended Action

Solution 1. The audit was run on the Cluster node already built in `difftest/cluster-auth/node`.
Results and a summary are in `difftest/catalog/results/cluster-lowpriv/`. `meta.zsh` now takes each
side's user, password and sslmode from the environment.

The frontend gives carol the same answers as the superuser, apart from `current_user`, and so does
PostgreSQL, whose catalog every user can read. So no user sees another user's view. One answer was not
true: the tables' owner, OID 10 `scalardb`, was missing from `pg_roles` in cluster mode. Fixed, as the
user chose (option 1):
- **The owner.** `pg_roles` lists `scalardb` as a superuser nobody logs in as, and each table's ACL lists
  the owner's entry first.
- **`has_table_privilege`.** Added at the user's request. It answers from the same ACLs as `\dp`: a
  superuser or the owner holds everything; other roles hold what is granted to them, to a role they are
  a member of (transitively), or to PUBLIC. Without Cluster, every session holds everything.

## Acceptance Criteria

- [x] The run's answers are recorded under `difftest/catalog/results/`, each one true for that user or an
      error. `has_table_privilege` gives the same six answers as PostgreSQL 16 for carol. Still errors:
      `has_schema_privilege` and `information_schema.table_privileges` / `role_table_grants`.

## Work Log

- 2026-10-18: Filed when closing todo 057.
- 2026-10-19: Done. `builtins.py` gained a `'SELECT'` text sample and `10::oid` as the first OID sample
  (the bootstrap superuser on both servers). With those it lists four of `has_table_privilege`'s six
  signatures. The two taking a role name have no role name common to both servers; the gate only
  counts arguments, so they run anyway, and PostgresServerTest covers them. `builtins-calls.txt`
  changed where the new samples now prove other functions; 0 differences from PostgreSQL 16. The
  function-count fixtures went from 250 to 254 and from 180 to 184.
