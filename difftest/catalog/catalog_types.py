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
    # empty("pg_trigger", ...), declare("information_schema.views"): bare names are pg_catalog's
    for name in re.findall(r'\b(?:empty|builtin|declare)\(\s*"([a-z_.]+)"', source):
        tables.append(tuple(name.split(".")) if "." in name else ("pg_catalog", name))
    is_declared = " (n.nspname, c.relname) IN (SELECT * FROM unnest(%s::text[], %s::text[]))"
    names = ([s for s, _ in tables], [t for _, t in tables])
    conn = psycopg.connect(f"host=127.0.0.1 port={port} dbname=postgres user=postgres")
    rows = conn.execute(
        "SELECT n.nspname, c.relname, a.attname,"
        " CASE WHEN t.typtype = 'd' THEN t.typbasetype ELSE a.atttypid END,"
        " a.atttypid, a.atttypmod, a.attnotnull, a.attcollation, a.attlen, a.attndims, a.attstorage"
        " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid"
        " JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_type t ON t.oid = a.atttypid"
        " WHERE a.attnum > 0 AND NOT a.attisdropped AND" + is_declared + " ORDER BY 1, 2, a.attnum",
        names,
    ).fetchall()
    relations = conn.execute(
        "SELECT n.nspname, c.relname, c.oid, c.relkind FROM pg_class c"
        " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE" + is_declared + " ORDER BY 1, 2",
        names,
    ).fetchall()
    # information_schema's namespace and its domains, which its columns are typed with
    domains = conn.execute(
        "SELECT t.oid, t.typname, t.typbasetype, t.typtypmod, t.typarray, t.typnamespace,"
        " t.typcollation, coalesce(t.typdefault, '') FROM pg_type t WHERE t.typtype = 'd'"
        " AND t.typnamespace = 'information_schema'::regnamespace ORDER BY t.oid"
    ).fetchall()
    checks = conn.execute(
        "SELECT c.oid, c.conname, c.contypid, pg_get_constraintdef(c.oid),"
        " pg_get_constraintdef(c.oid, true) FROM pg_constraint c"
        " JOIN pg_type t ON t.oid = c.contypid"
        " WHERE t.typnamespace = 'information_schema'::regnamespace ORDER BY c.oid"
    ).fetchall()
    print("# from PostgreSQL 16 (difftest/catalog/catalog_types.py):")
    print("#   schema.table column type-oid (a domain's base type) atttypid atttypmod attnotnull"
          " attcollation attlen attndims attstorage")
    print("#   @ schema.table oid relkind")
    print("#   % domain oid name base-oid typmod array-oid namespace-oid collation-oid default...")
    print("#   % check oid name domain-oid definition<TAB>pretty definition")
    for schema, table, column, oid, typid, typmod, notnull, collation, length, ndims, storage in rows:
        print(f"{schema}.{table} {column} {oid} {typid} {typmod} {'t' if notnull else 'f'} {collation}"
              f" {length} {ndims} {storage}")
    for schema, table, oid, kind in relations:
        print(f"@ {schema}.{table} {oid} {kind}")
    for oid, name, base, typmod, array, namespace, collation, default in domains:
        print(f"% domain {oid} {name} {base} {typmod} {array} {namespace} {collation} {default}")
    for oid, name, domain, definition, pretty in checks:
        print(f"% check {oid} {name} {domain} {definition}\t{pretty}")
    missing = set(tables) - {(s, t) for s, t, *_ in rows}
    if missing:
        print("not in PostgreSQL 16: " + " ".join(sorted(".".join(m) for m in missing)), file=sys.stderr)


if __name__ == "__main__":
    main()
