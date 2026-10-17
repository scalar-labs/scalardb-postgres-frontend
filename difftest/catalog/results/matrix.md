# Client matrix, 2026-10-17 (Phase 0 of docs/plans/2026-10-17-001-fix-catalog-truth-or-error-plan.md)

Run on main 6f73b17 with both catalog fallbacks logging (`CATALOG-FALLBACK`), against PostgreSQL 16.15
(Docker `postgres:16`) for reference. Hits are in `fallback-hits.txt`.

| Client | Fallback hits | Wrong answers seen |
|---|---|---|
| psql 18.1 and 14.18, every meta-command | ~40 tables not emulated; `generate_series` over `array_upper(...)`; `pg_catalog.unnest(tc.reloptions)` (`\d+`) | see `meta.txt`; `\sf`: `'now'::regproc::oid` answers the text `now` in an oid column |
| pgjdbc 42.7.14 `DatabaseMetaData` | `TypeInfoCache` lookups (`generate_series(1, array_upper(current_schemas(false), 1))`) | `getTypeInfo`: `_interval` is OTHER, not ARRAY |
| psycopg 3 / SQLAlchemy 2.0 (compat/python) | `pg_sequence` in the columns query | `get_columns`: NoSuchTableError; `get_pk_constraint`, `get_indexes`: 42703 |
| node-postgres / Sequelize 6 (compat/node) | none | none from the catalog |
| pgx v5 (compat/go) | none | none |
| pg / ActiveRecord 7.2 (compat/ruby) | none | primary key `nil`: `generate_subscripts(indkey, 1)` starts at 1, PostgreSQL's int2vector at 0, so `indkey[idx]` misses (a regression since 2026-10-04) |
| asyncpg 0.32 | none | prepared statements with parameters: RecursionError; `oid::regtype::text` answers `23`, not `integer` (every `reg*` output type prints the oid); jsonb arrives with its binary version byte; uuid as text |
| Django 4.2 `inspectdb` (Python 3.9) | `unnest(c.conkey) WITH ORDINALITY` (constraints) | primary key missing (`id = IntegerField()`) |
| Prisma 6.19 | not reached | schema engine stack overflow after its connect probe (likely the `reg*` output too) |

Not run yet: the low-privilege user under `difftest/cluster-auth` (needs a Cluster node; after Phase 1).
