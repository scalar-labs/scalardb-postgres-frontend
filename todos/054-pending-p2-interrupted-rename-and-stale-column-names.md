---
status: pending
priority: p2
issue_id: "054"
tags: [type-modifiers, ddl, consensus-commit]
dependencies: []
---

# An interrupted RENAME COLUMN, and column names another process changed

Deferred from Phase 5 of `docs/plans/2026-10-14-001-feat-keep-type-modifiers-exact-numeric-plan.md` on 2026-10-15.

## Problem Statement

Two cases the plan put in Phase 5 are not handled:

1. **Orphan before-image after an interrupted rename.** `ConsensusCommitAdmin.renameColumn` renames a non-key
   column, then its `before_` column, as two separate storage renames. A stop between them leaves the new column
   and `before_<old stored name>`. ScalarDB's `removeTransactionMetaColumns` hides a `before_` column only when its
   base column exists, so clients see `before_<old>` as a column (decoded, if the old name was encoded), and
   Consensus Commit writes can fail because `before_<new>` is missing.
2. **Stale storage names.** When another process renames or drops a column after this session cached the table,
   ScalarDB rejects the operation with one of several operation-check errors (DB-CORE-10006 projection, 10014
   ordering, 10015 condition, and the like), possibly only at commit. The plan maps these to 42703 with the client
   name and invalidates the table's metadata; today the ScalarDB error passes through.

## Why deferred

- The plan itself notes that an orphan cannot be told apart from a legitimate client column named `before_x`.
- Repairing through the transaction admin is not reliable: `renameColumn(before_old, before_new)` would again run
  the two-step rename, renaming `before_before_old`, which does not exist. Whether
  `getBeforeImageColumnName` accepts a `before_`-prefixed name is unverified. A storage-level admin would be needed,
  which the frontend does not hold (in Cluster mode it acts with the user's identity).
- Neither case can be exercised without fault injection or a second process, so no test could show it working.

## Proposed Solutions

- **A. Document and repair by hand** (done in LIMITATIONS.md): rename `before_<old>` to `before_<new stored name>`
  in the backing database.
- **B. Core fix**: make ScalarDB's rename atomic or idempotent, or let `removeTransactionMetaColumns` report
  orphans. Filed as `core-todo.md` item 16 (2026-10-18).
- **C. Stale names**: catch the operation-check codes at the Reader and commit sites, compare the column with fresh
  metadata, and raise 42703 naming the client column. Needs a two-session test against a real backend.

## Acceptance Criteria

- [x] A second session's RENAME makes the first session's next statement fail with 42703 naming the client
      column, and the statement after that succeeds with the new name. (Done differently: within a second the
      session sees the new names; a statement inside that second may still get ScalarDB's error.)
- [ ] An orphan `before_` column is either hidden with a clear error naming the repair, or cannot arise.

## Work Log

- 2026-10-15: deferred from Phase 5; LIMITATIONS.md documents the manual repair.
- 2026-10-18: Case 2 was worse than described: a session cached its column maps until DDL in its own process,
  so after a rename by another frontend process it kept them for its whole life (`SELECT total` failed with
  "Unknown column", `SELECT amount` returned NULL). Now each session compares its cached tables' ScalarDB
  metadata with `admin.getTableMetadata` once a second (`QueryParser#changedElsewhere`) and on a change drops
  maps and plans in every session of the process. Verified live with two frontends on one backend, and by
  `QueryProcessorTest#plan_AfterAnotherProcessesDdl_ShouldSeeTheNewColumns`. Case 1 (orphan `before_` column)
  stays open: it needs the core fix (solution B).
