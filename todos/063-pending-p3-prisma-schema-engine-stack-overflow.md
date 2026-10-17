---
status: pending
priority: p3
issue_id: "063"
tags: [prisma, compatibility]
dependencies: []
---

# Prisma's schema engine crashes after connecting

Out of scope for todo 057 (`docs/plans/2026-10-17-001-fix-catalog-truth-or-error-plan.md`, "Dependencies &
Risks"), which said it gets a separate todo.

## Problem Statement

Prisma's schema engine (`prisma db pull`) crashes with a stack overflow after its connect probe, even with
`current_setting('server_version_num')` fixed. So Prisma cannot introspect a database through the frontend.

## Findings

- The plan named a likely cause: `reg*` types printed OIDs instead of names, and Describe reported
  undeclared parameters as type 0, which sent asyncpg into a RecursionError. Phase 1 fixed both, but
  Prisma has not been run since, so it may already work.

## Proposed Solutions

1. **Re-run first.** Run `prisma db pull` on the merged build. Effort: small.
2. **Capture and replay.** Capture the queries Prisma sends after connecting, find the one that overflows,
   and fix the recursion or answer it. Effort: unknown until captured.

## Recommended Action

## Acceptance Criteria

- [ ] `prisma db pull` against the frontend ends with a schema, or with an error the frontend reports
      instead of a crash.

## Work Log

- 2026-10-18: Filed when closing todo 057.
