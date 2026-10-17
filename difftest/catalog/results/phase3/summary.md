# Re-run after Phase 3, 2026-10-17

Branch `fix-catalog-self`, against PostgreSQL 16.15 on 15416, with `../../rerun.zsh`. What changed
since `../phase2`, whose summary still explains the answers not listed here.

## The catalog lists itself

| Where | PostgreSQL 16 | Phase 2 | Phase 3 | Why it still differs |
|---|---|---|---|---|
| `\dS`, `\dpS`, JDBC `getTables(all)`, `pg_class` | 141, 141, 420, 420 | 1, 1, 5, 5 | 66, 66, 84, 84 | the catalog relations the frontend declares, with PostgreSQL 16's oids, and no indexes, TOAST tables or sequences of its own |
| `\dtS`, `getTables(SYSTEM TABLE)`, `pg_tables` | 65, 68, 70 | 1, 0, 2 | 51, 50, 52 | as above |
| `\dvS`, `pg_views`, `information_schema.views` | 76, 141, 141 | 0 | 15, 29, 29 | as above; `definition` is NULL, as the frontend's views are code, not SQL |
| `getColumns(all)`, `pg_attribute`, `information_schema.columns` | 2064, 3144, 2064 | 9, 13, 9 | 806, 860, 806 | the columns the frontend declares, numbered in its order, and `tableoid` on the catalog tables |
| `\dnS`, `getSchemas`, `information_schema.schemata` | 5, 4, 5 | 3, 3, 2 | 4, 4, 4 | no `pg_toast`; `information_schema` is there now, as SQLAlchemy's `get_schema_names` shows |
| `\dTS`, `getTypeInfo`, `getUDTs`, `pg_type` | 107, 409, 76, 617 | 38, 68, 0, 65 | 43, 78, 5, 75 | `information_schema`'s five domains are added; the catalog relations have no row types here |
| `getTablePrivileges` | 1668 | 14 | 93 | the catalog is readable by all and writable by none (`=r/scalardb`) |
| `\dOS`, `pg_collation`, DBeaver's collations cache | 878 | 0 | 4 | `default`, `C`, `POSIX` and `ucs_basic`, the collations COLLATE accepts (under ICU: `default` and the ICU one) |
| `pg_depend`, DBeaver's Dependencies tab | 1719, 4 | 0 | 8, 2 | a table on its namespace, a key on its columns, its index on the key, a secondary index on its column; no row types or TOAST tables to depend on the table |

## information_schema

`routines`, `parameters`, `domains` and `character_sets` are PostgreSQL 16's rows for the objects the
frontend has (242 routines and their 317 parameters, the five domains, UTF8). `triggers`, `sequences`
and `referential_constraints` are empty, as none exist here. `constraint_column_usage` has the primary
keys' columns. `check_constraints` (PostgreSQL lists NOT NULL columns there), `table_privileges`,
`role_table_grants`, `element_types` and the other views the frontend does not declare still fail with
`42P01`.

## Still open

`json` extraction spacing (todo 060), `anycompatible`'s common type (todo 061), `pg_stat_activity`
(todo 058), and the `information_schema` views above.
