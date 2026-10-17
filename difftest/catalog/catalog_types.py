"""Writes the columns of the catalog tables the frontend declares (Catalog.java's PG_CATALOG,
INFORMATION_SCHEMA and its empty(...) and builtin(...) tables), in order, with their types as PostgreSQL 16 describes
them: a domain's base type, as RowDescription reports it. The frontend describes a catalog column with
this type when its own pg_type lists it, and an empty(...) table takes its columns from here.

    python catalog_types.py [NATIVE_PORT] > ../../engine/src/main/resources/com/scalar/db/frontend/postgres/catalog-types.txt
"""
import pathlib
import re
import sys

import psycopg

CATALOG = pathlib.Path(__file__).resolve().parents[2] / "engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java"


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "15416"
    source = CATALOG.read_text()
    declared = re.findall(r'(PG_CATALOG|INFORMATION_SCHEMA)\.put\(\s*"([a-z_]+)"', source)
    tables = [("pg_catalog" if s == "PG_CATALOG" else "information_schema", t) for s, t in declared]
    tables += [("pg_catalog", t) for t in re.findall(r'\b(?:empty|builtin)\(\s*"([a-z_]+)"', source)]
    conn = psycopg.connect(f"host=127.0.0.1 port={port} dbname=postgres user=postgres")
    rows = conn.execute(
        "SELECT n.nspname, c.relname, a.attname,"
        " CASE WHEN t.typtype = 'd' THEN t.typbasetype ELSE a.atttypid END"
        " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid"
        " JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_type t ON t.oid = a.atttypid"
        " WHERE a.attnum > 0 AND NOT a.attisdropped AND (n.nspname, c.relname) IN"
        " (SELECT * FROM unnest(%s::text[], %s::text[]))"
        " ORDER BY 1, 2, a.attnum",
        ([s for s, _ in tables], [t for _, t in tables]),
    ).fetchall()
    print("# schema.table column type-oid, from PostgreSQL 16 (difftest/catalog/catalog_types.py)")
    for schema, table, column, oid in rows:
        print(f"{schema}.{table} {column} {oid}")
    missing = set(tables) - {(s, t) for s, t, _, _ in rows}
    if missing:
        print("not in PostgreSQL 16: " + " ".join(sorted(".".join(m) for m in missing)), file=sys.stderr)


if __name__ == "__main__":
    main()
