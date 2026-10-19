# PostgreSQL's regression suite, statement by statement

`regress.py` runs files of PostgreSQL 16's own regression suite (`src/test/regress/sql`) on PostgreSQL 16 and
on the frontend. It uses one session per server per file, and sorts every statement into a bucket:

| Bucket | Meaning |
|---|---|
| `SAME` | same rows (or command status), or both fail with the same SQLSTATE |
| `FE_ERR` | the frontend fails where PostgreSQL succeeds: a gap, which is honest |
| `CODE` | both fail, with different SQLSTATEs |
| `DIFF` | both succeed with different results: **a wrong result** |
| `PG_ERR` | the frontend succeeds where PostgreSQL fails: **it accepts what it should refuse** |
| `ORDER` | the same rows in another order, for a query whose `ORDER BY` may not decide the order |

A `~` after a bucket marks a cascade: the statement names a table that PostgreSQL created and the frontend
could not.

## Adaptations

- **Keys.** A ScalarDB table needs a primary key, so a table a file creates without one gets one, on both
  servers: on its leading integer and text columns, or else on its first column. `TEMP` is dropped.
- **Skipped statements.** `COPY` needs psql, EXPLAIN compares plans rather than results, and psql's
  backslash commands are not SQL.
- **Clean state.** Each file starts from the setup's tables: whatever an earlier file left is dropped.
- **Setup.** `setup.py` writes the tables of the suite's `test_setup.sql` that the query files use, the
  same way on both servers: a primary key on a unique column, `text` for `name`, and the data as
  INSERTs instead of `COPY`.

## Running

```sh
curl -sL https://github.com/postgres/postgres/archive/refs/tags/REL_16_4.tar.gz | tar -xz
R=postgres-REL_16_4/src/test/regress
# PostgreSQL 16 (the C collation, as the frontend compares) and the frontend, each with database regress
psql -h 127.0.0.1 -p 15416 -U postgres -c "CREATE DATABASE regress TEMPLATE template0 LC_COLLATE 'C' LC_CTYPE 'C'"
psql -h 127.0.0.1 -p 15473 -U postgres -d postgres -c "CREATE SCHEMA regress"   # needs the metadata tables
python setup.py $R > setup.sql
psql -h 127.0.0.1 -p 15416 -U postgres -d regress -f setup.sql
psql -h 127.0.0.1 -p 15473 -U postgres -d regress -f setup.sql
python regress.py out $R/sql/int4.sql $R/sql/join.sql ...   # NATIVE_PORT, FE_PORT, DB override 15416, 15473, regress
python show.py out/join.jsonl DIFF,PG_ERR
```

It needs psycopg 3. Each file's log is `out/<file>.jsonl`, with one line per statement holding the SQL and
both answers, and the counts are in `out/summary.json`. The results of the runs are in `results/`.
