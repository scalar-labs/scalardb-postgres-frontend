# Re-run after Phases 1 and 2, 2026-10-17

Branch `fix-catalog-truth` (todo 057, `docs/plans/2026-10-17-001-fix-catalog-truth-or-error-plan.md`),
against PostgreSQL 16.15 on 15416, with `../../rerun.zsh`. The first run, in `..`, compared with
PostgreSQL 18.1, so its native counts differ slightly.

The catch-all that answered an unplannable catalog query with no rows is gone, so `../fallback-hits.txt`
has no counterpart: a query the frontend cannot answer now fails. Every answer below that differs from
PostgreSQL 16's is either true for the frontend or one of the gaps listed under "Still open".

## Answers that differ, and why they are true

| Where | PostgreSQL 16 | Frontend | Why |
|---|---|---|---|
| psql `\dS`, `\dtS`, `\diS`, `\dvS`, `\dpS`, `\dt *.*`; JDBC `getTables(all)`, `getTables(SYSTEM TABLE)`, `getColumns(all)`; `pg_class`, `pg_attribute`, `pg_statio_all_tables`, `information_schema.tables`/`columns` | the system catalogs and views | the user tables only | the catalog does not list itself yet (Phase 3) |
| `pg_views`, `\dvS` | 141 | 0 | no views; the system views come with Phase 3 |
| `\dfS`, `\daS`, `\doS`, `\dC`, JDBC `getFunctions`, `getProcedureColumns`; `pg_proc`, `pg_aggregate`, `pg_operator`, `pg_cast` | 3297 functions, 799 operators, 229 casts | 242, 378, 98 | only what the frontend implements (Phase 2) |
| `\dTS`, JDBC `getTypeInfo`, `pg_type` | 617 types | 65 | only the frontend's types; `typeinfo.txt` shows it adds none of its own |
| `\dA`, `\dAc`, `\dAf`; `pg_am`, `pg_opclass` | 7, 177, 146 | 2, 17, 12 | heap and btree, with the default classes of the frontend's types |
| `\dAo`, `\dAp` | 945, 696 | 0 | the operator families' members are not exposed (`EMPTY_BECAUSE`) |
| `\dF*`, `\dcS`, `\dL`, `\dx`, `\dx+`, `\drgS`, `pg_extension`, `pg_available_extensions` | text search, conversions, plpgsql, role grants | 0 | the frontend has none of them |
| `\l`, `getCatalogs` | 4 or 5 databases | 2 | the ScalarDB namespaces |
| `\du`, `\dg`, `pg_roles`, `getTablePrivileges` | PostgreSQL's roles and grants | the `scalardb` role | the frontend's one role without Cluster authentication |
| `\dconfig`, `\dconfig+ work_mem` | 14, 1 | 1, 0 | the frontend's settings; `work_mem` is not one |
| `pg_stats`, `pg_depend` | 409, 1719 | 0 | no statistics are kept; dependencies come with Phase 3 |
| `pg_timezone_names` | 487 | 603 | the zones `SET TimeZone` accepts, from Java's zone data |
| SQLAlchemy `get_table_comment(items)` | None | the ScalarDB keys | the frontend's comment, as `\dt+` shows |
| psql 14 `\dp`, `\dpS`, `\z` | error: operator is not unique | 1 | PostgreSQL 16 rejects psql 14's query; the frontend answers it |

## Errors instead of made-up rows

`pg_stat_activity`, `pg_stat_user_tables`, `pg_stat_all_tables`, `pg_locks` (todo 058) and the
`information_schema` views the frontend lacks (`views`, `routines`, `parameters`, `check_constraints`,
`domains`, `table_privileges`, ...) fail with `42P01`. Before, they answered no rows.

## Fixed by this re-run

- psql 18's `\dx` failed on `pg_available_extensions()`, a table function the frontend did not have.
  It now answers no rows, which is true.
- `'{a,,b}'::text[]` and other array text with an empty unquoted element answered an empty string; it
  now fails with `22P02` as in PostgreSQL. DBeaver's captured `getSQLKeywords()` query
  (`engine/src/test/resources/dbeaver/catalog.sql`) had lost the word `null` from pgjdbc's list, so
  PostgreSQL rejected it; the word is back.

## Still open

- `information_schema`: its namespace, domains and the missing views (Phase 3).
- `pg_collation`, which lists only the ICU collation (none in this run) (Phase 3); DBeaver's collations
  cache and `\dOS` get no rows.
- `pg_depend`: DBeaver's Dependencies tab shows nothing (Phase 3).
- `json` extraction spacing (todo 060); `anycompatible`'s common type (todo 061).
