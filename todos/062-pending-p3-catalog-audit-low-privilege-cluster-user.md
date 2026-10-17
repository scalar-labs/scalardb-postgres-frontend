---
status: pending
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

## Acceptance Criteria

- [ ] The run's answers are recorded under `difftest/catalog/results/`, each one true for that user or an
      error.

## Work Log

- 2026-10-18: Filed when closing todo 057.
