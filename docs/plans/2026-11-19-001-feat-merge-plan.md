---
title: "feat: MERGE"
type: feat
status: phase 1 done
date: 2026-11-19
---

# feat: MERGE

## Summary

Support PostgreSQL's `MERGE` (15+; `WHEN NOT MATCHED BY SOURCE` and `RETURNING` from 17): join a source
to a target table and, for each joined row, run the action of the first `WHEN` clause that applies:
`UPDATE`, `DELETE`, `INSERT` or `DO NOTHING`. Today `MERGE` fails with `0A000` "Unsupported statement",
or `42601` for the forms JSQLParser cannot parse (`LIMITATIONS.md`).

The engine already runs every piece of this. `UPDATE ... FROM`, `DELETE ... USING` and `INSERT ... ON
CONFLICT` are read-then-write plans (`Plan.write`): a driving read, then a `Mutator` that turns each
row into an `Effect` (mutations plus the row left behind) or skips it, and may read ScalarDB itself.
`MERGE` is one more of those, with a join as the driving read.

## Status

Phase 1 is done (2026-11-19): every Phase 1 case of `difftest/cases_merge.sql` matches PostgreSQL 18
(37 OK, 22 with the same SQLSTATEs; baseline `difftest/results/baseline/merge.txt`), as do pgJDBC's
parameter types and plan-cache reuse. As built, differing from the design below where marked:

- The driving read names every column `t.c` / `s.c`, and a clause's condition and values are evaluated
  on that row only when the row reaches the clause. A subquery in a clause is planned in the read as
  `CASE WHEN false THEN ... END`, so the plan knows it without running it per row.
- Names and placeholders are checked by planning, but never running, a SELECT of every clause's
  expressions over the same join: 42703 and 42702 come before any row is read, as in PostgreSQL.
- `DO NOTHING` is rewritten to an assignment or insert of a sentinel name JSQLParser parses
  (`QueryParser.mergeActions`), by the kind of clause it ends; `INSERT DEFAULT VALUES` to `INSERT
  VALUES (DEFAULT)`.
- A WHEN NOT MATCHED clause sees only the source: it is checked over the source alone and evaluated
  on the source's columns of the row. Keys are tracked per execution across actions, so an insert
  of a key this statement deleted or moved away writes over it (an Upsert after the Delete in the
  same batch) and one onto a key it inserted or moved to is 23505. (Both from the code review.)
- The cases run in autocommit (`run.py` gained `SNAP_TABLES` and `SNAP_RELOAD`): inside `BEGIN`, a
  scan of rows the transaction wrote fails (DB-CORE-10106), a limit of every write, not of `MERGE`.

## Parser

JSQLParser 5.4, as the frontend uses it (checked 2026-11-19), parses into `statement.merge.Merge`,
with one `MergeOperation` per `WHEN` clause, in order, each with its `AND` condition:

| Form | 5.4 |
|---|---|
| `WHEN MATCHED [AND c] THEN UPDATE SET ...` / `THEN DELETE` | parses |
| `WHEN NOT MATCHED [BY TARGET] [AND c] THEN INSERT [(cols)] VALUES (...)` | parses |
| source: table, subquery, `(VALUES ($1, $2)) AS s(id, v)`; `WITH ... MERGE` | parses |
| `WHEN NOT MATCHED BY SOURCE`; `RETURNING merge_action(), ...` | parses |
| `THEN DO NOTHING` | fails |
| `INSERT DEFAULT VALUES` | fails |

The two failures go through `QueryParser.normalize`, as other syntax JSQLParser rejects does. First
check whether a newer JSQLParser accepts them; otherwise rewrite outside literals to a form that
parses and is recognized after parsing, e.g. `THEN DO NOTHING` to an `UPDATE SET` of a reserved
sentinel name. `INSERT DEFAULT VALUES` inserts all NULLs here (no column defaults), which the key
columns reject with `23502`, as `INSERT ... DEFAULT VALUES` already does.

## Design

### Driving read

Build the join as a JSQLParser `PlainSelect` and plan it with `select(...)`, so the optimizer,
lookup joins, pushed-down conditions and parameters all come for free:

- `FROM source LEFT JOIN target ON cond`; a `FULL JOIN` when there is a `NOT MATCHED BY SOURCE` clause.
- Output: every source column, and every target column under the target's qualifier.
- Matched means the target's key columns are not NULL in the row (key columns never are); a source
  row without a match has them NULL. A `NOT MATCHED BY SOURCE` row has the source side NULL; a
  source with no non-null column needs a marker column (`true AS $matched`) to tell the two apart.

When `cond` binds the target's whole primary key to source values, the planner's `LookupJoin`
(which takes the join kind, LEFT included) reads one target row per source row. Otherwise the target
is scanned across partitions once per statement: correct, slow on large tables. `EXPLAIN` shows which.

### Per-row action (the Mutator)

For each row, in clause order, the first clause whose kind matches (matched / not matched by target
/ not matched by source) and whose `AND` condition is true:

| Action | Effect | Reuses |
|---|---|---|
| `UPDATE SET ...` | an Update of the target row; a key change is a Delete and an Insert | `update(...)`, `keyMove(...)` from `updateByScan` |
| `DELETE` | a Delete of the target row | `deleteByScan`'s effect |
| `INSERT ...` | an Insert (not a Put) of the new row | `insertRows`'s `put(t, columns, false)` |
| `DO NOTHING`, or no clause applies | skip (null) | |

`SET` and `INSERT` expressions see both sides, evaluated with `Evaluator.eval` over the joined row, as
`ON CONFLICT`'s `EXCLUDED` already is. A `DEFAULT` becomes NULL (`defaultToNull`).

### PostgreSQL's errors, to match exactly

- A target row updated or deleted by a second source row: `21000` "MERGE command cannot affect row a
  second time". Keys changed are tracked per execution, as `insertRows` tracks proposed keys for `ON
  CONFLICT DO UPDATE` (`21000` there too). Only a second UPDATE or DELETE of a changed row fails: two
  matches that both `DO NOTHING`, or an UPDATE then `DO NOTHING`, are not errors (difftest
  `results/2026-11-19-merge/summary.txt`, which lists PostgreSQL's parse-time errors too).
- Two inserts of one key in the statement, or an insert of a key that exists: `23505`, as `keyMove`
  words it.
- A key column set to NULL: `23502`; a type mismatch: the same codes `UPDATE`/`INSERT` give.
- Unsupported pieces stay `0A000`: a target that is not a plain table (a view, a catalog relation),
  and anything left to a later phase.

Each is a difftest case against native PostgreSQL 18 (`difftest/cases_merge.sql`, run first on
2026-11-19), so the codes are compared, not assumed.

### Command tag, parameters, EXPLAIN

- Tag `MERGE n`, n the rows inserted, updated or deleted (`DO NOTHING` rows not counted).
- Parameter types for pgJDBC's Describe: infer from the target column a `$n` is assigned or inserted
  to and from comparisons in `ON`/`AND`, extending `inferWriteParameters` (today `INSERT` only).
- `EXPLAIN` / `EXPLAIN ANALYZE`: "Merge ns.t: ..." over the join, as `UPDATE ... FROM` reads.

### Transactions, concurrency, privileges

- One statement, one set of mutations in the statement's transaction (autocommit or explicit), like
  the other read-then-write plans; the write cap of `executeWrite` applies.
- Concurrency differs from native and goes in `LIMITATIONS.md`: under READ COMMITTED, PostgreSQL can
  fail a `NOT MATCHED` insert with `23505` when another session inserts the key concurrently, or
  re-check a concurrently updated row. Here ScalarDB's validation fails the transaction instead, and
  the client retries, as with `ON CONFLICT` today.
- With Cluster authentication, privileges are Cluster's and checked per operation: an action that
  never runs is not checked, where PostgreSQL checks every action named in the statement up front.
  Note it with the other privilege differences.

## Phases

1. **Core forms.** `WHEN MATCHED [AND c] THEN UPDATE / DELETE`, `WHEN NOT MATCHED [AND c] THEN
   INSERT`, `DO NOTHING` (via normalize), sources as table, subquery, `VALUES` with column aliases and
   `WITH`; parameters; the errors above; `EXPLAIN`. Key updates through `keyMove`.
2. **PostgreSQL 17 forms.** `WHEN NOT MATCHED BY SOURCE` (the full join) and `RETURNING` with
   `merge_action()`: `Effect.row` already carries the row left behind; the action name needs to
   travel with it into `Returning`.
3. **Performance pass,** if a workload calls for it: batch the per-row Gets the key-move and insert
   checks issue, as the multi-row insert work did.

Each phase ends with `LIMITATIONS.md` updated and a changie fragment (`enhancement`).

## Tests

- `difftest/cases_merge.sql`, run against native PostgreSQL 18 and the frontend: every row of the
  parser table, each action with and without `AND`, clause order, several source rows per target row
  (`21000`), duplicate inserts (`23505`), NULL keys, key updates, empty source, empty target,
  parameters through `\bind`, `MERGE` inside `BEGIN ... ROLLBACK`, and the final table contents
  after each case.
- `QueryParserTest`: the normalize rewrites, and that a `DO NOTHING` sentinel cannot be reached from
  user SQL.
- One `EXPLAIN` case showing a lookup join for a keyed `ON`, one showing a scan.

## Out of scope

- `MERGE` into a view or a partitioned table (neither exists here).
- `MERGE ... WHEN ... THEN INSERT OVERRIDING ...` (no identity columns until sequences land).

## Open questions

- Does a newer JSQLParser parse `DO NOTHING` and `DEFAULT VALUES`, and is upgrading worth it now?
- For a non-keyed `ON` over a large target, refuse above some size (as a cap) or always scan?
