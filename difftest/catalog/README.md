# Catalog audit

What psql's meta-commands, pgjdbc's `DatabaseMetaData`, DBeaver's catalog queries and SQLAlchemy's
`Inspector` get from the frontend, next to what they get from PostgreSQL, for the same two tables
(`schema.sql`). Written for todo 057: a catalog query must answer the truth or fail, and
`results/` holds the first run (2026-10-17, main 6f73b17, PostgreSQL 18.1, psql 18, pgjdbc
42.7.14, SQLAlchemy 2.0.54 with psycopg 3.2.13, DBeaver queries from
`engine/src/test/resources/dbeaver/catalog.sql`).

The frontend answers a read it cannot plan that mentions `pg_catalog.` with no rows (`QueryParser`,
after `plan(sql)`). To see which queries rely on that, the audited build logged each one:

```java
logger.warn("CATALOG-FALLBACK {} :: {}", m, sql.replaceAll("\\s+", " "));
```

added before `return Plan.constant(catalogColumnNames(sql), ...)`. `meta.zsh` attributes those log
lines to the command that caused them; `results/fallback-hits.txt` counts them over the whole run.

```sh
# native PostgreSQL on 15499 and the frontend on 15473 (its log in fe.log here), each with database audit
psql -h 127.0.0.1 -p 15499 -U postgres -d audit -f schema.sql
psql -h 127.0.0.1 -p 15473 -U postgres -d audit -c "CREATE FRONTEND METADATA TABLES" -c "CREATE SCHEMA audit" -f schema.sql
./meta.zsh < meta.txt                           # NATIVE_PORT, FE_PORT, FE_LOG override the defaults
PGJDBC=~/.gradle/caches/modules-2/files-2.1/org.postgresql/postgresql/42.7.14/*/postgresql-42.7.14.jar
java -cp $PGJDBC Meta.java 15473                # every DatabaseMetaData method; add a name to run one
java -cp $PGJDBC TypeInfo.java 15473            # getTypeInfo, getVersionColumns and getColumns contents
sed -e "s/datname='ns'/datname='audit'/; s/relname='t'/relname='items'/g; s/tablename='t'/tablename='items'/g" \
  ../../engine/src/test/resources/dbeaver/catalog.sql > dbeaver.sql
java -cp $PGJDBC RunFile.java 15473 dbeaver.sql
python3 -m venv venv && ./venv/bin/pip install 'psycopg[binary]' sqlalchemy && ./venv/bin/python inspect_all.py 15473
```

Run each against 15499 too and compare. `rerun.zsh OUT_DIR` does all of it against both servers, each
answer next to the other; `results/phase2` is its run after Phases 1 and 2, with a `summary.md` of what
still differs and why. In `results/`, a frontend count of `(0rows)` or a `0`
where PostgreSQL has rows, with a fallback hit next to it, is an answer the frontend made up.

## Client fixtures

`engine/src/test/resources/clients/*.sql` hold the catalog queries each client sent, for
`ClientCatalogTest`. psql's come from `psql -E` against the frontend (`-- source: frontend`); the
drivers' and ORMs' from PostgreSQL 16's statement log (`log_statement=all`, a client run framed by
`SELECT 'MARK <name>-start'` and `'MARK <name>-end'`) through `pglog2fixture.py`
(`-- source: postgres16`). The oids line lists the capturing server's relation OIDs, which
`replay.py` and the test map by name to the server they run on.

```sh
python pglog2fixture.py pg16.log sqlalchemy > sqlalchemy.raw.sql
python replay.py sqlalchemy.raw.sql 15416 15473 ../../engine/src/test/resources/clients/sqlalchemy.sql
python replay.py ../../engine/src/test/resources/clients/sqlalchemy.sql   # compare again, after a fix
```

Each query records PostgreSQL 16's answer as a row count and a digest of the rows, and, once
reviewed, the frontend's expected answer (`-- expect:`), which a rewrite keeps. `replay.py` keeps at
most two queries of the same shape (equal up to string literals), so a fresh capture that looks up a
third table or type the same way loses that lookup; it counts the dropped queries on stderr.

## What the frontend implements

`builtins.py` writes the frontend's `pg_proc`, `pg_aggregate`, `pg_operator` and `pg_cast` rows (engine
resources `builtins.txt`, `aggregates.txt`, `operators.txt`, `casts.txt`) and the calls that prove them
(`engine/src/test/resources/builtins-calls.txt`, which `BuiltinsTest` runs). It calls each candidate on
PostgreSQL 16 and on the frontend, keeps what the frontend runs, and lists on stderr every call whose
result type or text differs. Run it after adding or changing a function, against a frontend started with
`-Dscalardb.frontend.builtins.ungated=true` (otherwise the frontend refuses what the lists lack yet), then
rebuild and run it once more: the second run uses the new lists to describe result types.

```
python builtins.py 15416 15473
python catalog_types.py 15416 > ../../engine/src/main/resources/com/scalar/db/frontend/postgres/catalog-types.txt
```
