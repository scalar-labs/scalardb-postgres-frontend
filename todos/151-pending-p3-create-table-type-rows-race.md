---
status: pending
priority: p3
issue_id: "151"
tags: [security, authorization, toctou, type-store, ddl]
dependencies: []
---

# CREATE TABLE can briefly overwrite a concurrently created table's type rows

Found by the automated security review of 0fa3df2 (DDL privilege check before writing type rows).

`CREATE TABLE s.f` with declared types runs, in `QueryParser` (CREATE TABLE plan, then `withTypes`):

1. `admin.tableExists(s, f)` is false;
2. `checkPrivilege`: CREATE on the namespace `s`;
3. `typeStore.write(s, f, types, scope = null, token)`: replaces every row of `s.f`, removing
   leftovers of an earlier, dropped `s.f`;
4. `admin.createTable(...)`.

If another user creates `s.f` between 1 and 3, step 3 overwrites the new table's rows with the
caller's types. Step 4 then fails (the table exists), and `undo` with `other = true` puts the
previous (the winner's) rows back (`createTableIfNotExists_LosingARace_ShouldPutBackTheWinnersRows`).

## Impact

- Needs CREATE on the namespace and winning a race against a `CREATE TABLE` of that exact name by
  another session.
- Until the undo, a few milliseconds, sessions read the new table with the caller's declared types.
  The table was created at that moment, so it holds next to no rows.
- Persistent only if the frontend dies between the write and the undo.
- Narrower than before 0fa3df2, when the service user wrote rows for any DDL with no privilege check.

## Why not fixed now

- `admin.tableExists` is not part of the type store's transaction, so re-checking inside it only
  narrows the window.
- Never replacing existing rows on CREATE would keep the inert rows of a dropped table, which cannot
  be told from a concurrent creator's rows without the same racy existence check.
- A complete fix needs creating the table and writing its type rows atomically, which ScalarDB's
  admin API does not offer.

## Options

1. Re-check `tableExists` right before the write (and in the node, inside the privileged call):
   narrows the window, small.
2. Mark rows with the creating table's identity once ScalarDB exposes one (a creation id or
   timestamp in table metadata), so a CREATE replaces only rows of a table that no longer exists.
3. ScalarDB core keeping declared types in its own metadata (docs/type-encoding.md, "Moving to ScalarDB
   core"): the write becomes part of `createTable`, and the race disappears.

## Acceptance Criteria

- [ ] A concurrent `CREATE TABLE` of the same name by a user without ALTER on the winner's table never
      changes how the winner's columns are read, not even briefly.
