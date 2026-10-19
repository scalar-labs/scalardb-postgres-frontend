---
status: complete
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

Solutions 1 and 2. Re-running showed the stack overflow was gone: Prisma 6.19.3 reached its
introspection queries. Capturing them through a logging proxy found three gaps, each a Rust panic or
an empty introspection:

- **Missing view columns.** `information_schema.columns` lacked `udt_schema`, which made the columns
  query fail. Added `udt_catalog`, `udt_schema`, `is_identity` (NO), `is_generated` (NEVER) and
  `is_updatable`.
- **No row types.** `pg_class.reltype` was 0, and Prisma keeps only `reltype > 0`, so every model was
  "commented out as we could not retrieve columns". Each user table now has its row type in `pg_type`
  (`typtype` c, `record_in`/`record_out`), plus that type's array type, as PostgreSQL creates them.
  Catalog relations still have `reltype` 0 (marked ponytail).
- **`int2vector` not seen as an array.** `generate_subscripts(indkey, 1)` was described as text, and
  Prisma panicked reading `column_index`. `Builtins` now reads `int2vector` and `oidvector` as `int2[]`
  and `oid[]` when it resolves a call, as PostgreSQL's type coercion does.

## Acceptance Criteria

- [x] `prisma db pull` against the frontend ends with a schema. For `items`, the model matches what
      PostgreSQL 16 gives. Each model also carries Prisma's notice about database comments, because of
      the frontend's `ScalarDB partition key (...)` table descriptions.

## Work Log

- 2026-10-18: Filed when closing todo 057.
- 2026-10-19: Done. Prisma's 13 catalog queries are now a ClientCatalogTest fixture
  (`engine/src/test/resources/clients/prisma.sql`, PostgreSQL 16's answers from `replay.py`). Three
  pgjdbc fixture counts grew with the new view columns and row types, which PostgreSQL 16 has too.
