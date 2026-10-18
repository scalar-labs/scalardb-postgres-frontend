"""Writes the functions, operators and casts the frontend implements, as PostgreSQL 16 catalogs them.

A candidate is a pg_proc row whose name the frontend's source mentions, or a pg_operator or pg_cast row
between types the frontend has. Each is called with sample values of its argument types, on PostgreSQL 16
and on the frontend, and kept when the frontend runs the call: the lists claim nothing the frontend does
not do. The frontend takes a call's result type from these rows. Calls whose described type or text
differs from PostgreSQL's are listed on stderr, as bugs to look at.

    python builtins.py NATIVE_PORT FRONTEND_PORT

with the frontend started with -Dscalardb.frontend.builtins.ungated=true, so that it runs the functions
it implements whether this list has them or not.

writes, under engine/src:
    main/resources/.../builtins.txt   pg_proc rows, with pg_get_function_arguments and friends
    main/resources/.../aggregates.txt pg_aggregate rows
    main/resources/.../operators.txt  pg_operator rows
    main/resources/.../casts.txt      pg_cast rows
    main/resources/.../information_schema-*.txt  information_schema's routines, parameters, domains
                                      and character_sets rows
    test/resources/builtins-calls.txt the call that proved each row, and its result type
"""
import itertools
import pathlib
import re
import sys

import psycopg

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "engine/src/main/java/com/scalar/db/frontend/postgres"
RESOURCES = ROOT / "engine/src/main/resources/com/scalar/db/frontend/postgres"
TEST_RESOURCES = ROOT / "engine/src/test/resources"

# sample values per argument type OID, the likeliest first
TEXTS = ["'a'", "'day'", "'pg_class'", "'search_path'", "'1'", "'{\"a\":1}'", "'UTC'", "'a,b'", "'CONNECT'"]
SAMPLES = {
    16: ["true"],
    17: ["'\\x0102'::bytea"],
    18: ["'a'::\"char\""],
    19: ["'pg_class'::name", "'search_path'::name", "'pg_class'", "'audit'", "'UTF8'"],
    20: ["2::int8", "1::int8"],
    21: ["2::int2"],
    22: ["'1 2'::int2vector"],
    23: ["2::int4", "1::int4", "0::int4"],
    25: [t + "::text" for t in TEXTS],
    26: ["1259::oid", "2::oid", "23::oid", "1663::oid", "1259"],
    194: ["NULL::pg_node_tree"],
    114: ["'{\"a\":[1,2]}'::json", "'[1,2]'::json"],
    700: ["1.5::float4"],
    701: ["1.5::float8", "2::float8"],
    1005: ["ARRAY[1,2]::int2[]"],
    1007: ["ARRAY[1,2]::int4[]"],
    1009: ["ARRAY['a','b']::text[]"],
    1016: ["ARRAY[1,2]::int8[]"],
    1028: ["ARRAY[1259]::oid[]"],
    1042: ["'a'::bpchar"],
    1043: ["'a'::varchar"],
    1082: ["'2024-02-29'::date"],
    1083: ["'12:34:56'::time"],
    1114: ["'2024-02-29 12:34:56'::timestamp"],
    1184: ["'2024-02-29 12:34:56+00'::timestamptz"],
    1186: ["'1 day 02:03:04'::interval"],
    1266: ["'12:34:56+09'::timetz"],
    1700: ["1.5::numeric", "2::numeric"],
    2205: ["'pg_class'::regclass"],
    2206: ["'int4'::regtype"],
    2950: ["'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid"],
    3802: ["'{\"a\":[1,2]}'::jsonb", "'[1,2]'::jsonb"],
    # pseudo-types: a value of a type that resolves them
    2276: ["2::int4", "'a'::text"],  # "any"
    2277: ["ARRAY[1,2]::int4[]", "ARRAY['a','b']::text[]"],  # anyarray
    2283: ["2::int4", "'a'::text"],  # anyelement
    2776: ["2::int4", "'a'::text"],  # anynonarray
    5077: ["2::int4", "1.5::numeric"],  # anycompatible
    5078: ["ARRAY[1,2]::int4[]"],  # anycompatiblearray
}
MAX_COMBINATIONS = 48
# values that depend on the server, its clock or its data: only that the call runs is compared
ENVIRONMENT = re.compile(
    r"version|pg_backend_pid|now|clock_timestamp|statement_timestamp|transaction_timestamp|random|"
    r"gen_random_uuid|pg_stat_|_size|pg_get_userbyid|description|privilege|pg_tablespace_location|age"
)


def names_in_source():
    text = "".join(p.read_text() for p in SOURCE.glob("*.java"))
    return {w.lower() for w in re.findall(r'"([A-Za-z_][A-Za-z0-9_]*)"', text)}


def text_rows(conn, sql, params=None):
    """The cursor and its rows as the server writes them in text (bytes, None for NULL)."""
    cur = conn.cursor(binary=False)
    cur.execute(sql, params)
    res = cur.pgresult
    return cur, [tuple(res.get_value(r, c) for c in range(res.nfields)) for r in range(res.ntuples)]


def run(conn, sql):
    """(result type OID, rows as the server's text) or (None, the error)."""
    try:
        cur, rows = text_rows(conn, sql)
        return cur.description[0].type_code, sorted(rows, key=repr)
    except psycopg.Error as e:
        return None, f"{e.sqlstate} {str(e).splitlines()[0] if str(e) else ''}"


def prove(native, frontend, calls, mismatches, same_type=False):
    """(sql, PostgreSQL's result type OID) for the first call both servers run alike, else for the
    first both run, with its differences added to mismatches; None if the frontend runs none. With
    same_type, a call the frontend describes with another type does not count as run."""
    first = None
    for sql in calls:
        expected, rows = run(native, sql)
        if expected is None:
            continue
        got, frontend_rows = run(frontend, sql)
        if got is None:
            if "Unsupported function" in frontend_rows:
                break  # not dispatched at all: other samples will not help
            continue
        differences = []
        if got != expected:
            differences.append(f"type postgres16={expected} frontend={got}")
        if rows != frontend_rows and not ENVIRONMENT.search(sql):
            differences.append(f"postgres16={rows[:3]}\n    frontend  ={frontend_rows[:3]}")
        if not differences:
            return sql, expected
        if first is None and not (same_type and got != expected):
            first = sql, expected, differences
    if first is None:
        return None
    mismatches.append(first[0] + "".join("\n    " + d for d in first[2]))
    return first[:2]


def arguments(types):
    lists = [SAMPLES.get(t) for t in types]
    if any(s is None for s in lists):
        return []
    return list(itertools.islice(itertools.product(*lists), MAX_COMBINATIONS))


def information_schema(native, kept):
    """information_schema's rows about the built-in objects: the kept functions' routines and
    parameters (specific_name ends in the oid), the domains and the character set."""
    kept_only = " WHERE substring(specific_name from '_([0-9]+)$')::oid = ANY(%s)"
    for view in ["routines", "parameters", "domains", "character_sets"]:
        where, params = (kept_only, (kept,)) if view in ("routines", "parameters") else ("", None)
        cur, rows = text_rows(native, f"SELECT * FROM information_schema.{view}{where} ORDER BY 1, 2, 3, 4",
                              params)
        write(f"information_schema-{view}.txt", cur, rows)


def functions(native, frontend, calls, mismatches):
    names = names_in_source()
    candidates = native.execute(
        "SELECT p.oid, p.proname, p.prokind, p.proretset, p.provariadic::int,"
        " p.proargtypes::oid[]::int[], p.pronargs"
        " FROM pg_proc p WHERE p.pronamespace = 11 AND p.prokind IN ('f', 'a', 'w')"
        " AND p.proname = ANY(%s) AND p.pronargs <= 4 ORDER BY p.proname, p.oid",
        (sorted(names),),
    ).fetchall()
    kept = []
    for oid, name, kind, retset, variadic, argtypes, nargs in candidates:
        if variadic and variadic != 2276:
            argtypes = argtypes[:-1] + [variadic]  # f(a, VARIADIC text[]) is called as f(a, 'x')
        if kind == "a" and nargs == 0:
            tries = [f"SELECT {name}(*)"]  # count(*)
        else:
            tries = [f"SELECT {name}({', '.join(a)})" for a in arguments(argtypes)]
            if variadic:  # and with two variadic arguments, as json_build_object needs
                tries += [f"SELECT {name}({', '.join(a + a[-1:])})" for a in arguments(argtypes)]
        if kind == "w":
            tries = [t + " OVER ()" for t in tries]
        proof = prove(native, frontend, tries, mismatches)
        if proof:
            kept.append(oid)
            calls.append(f"{oid}\t{proof[1]}\t{proof[0]}")
    # and what psql's \\df and pg_get_functiondef show, which the frontend answers from here
    extra = [
        "pg_get_function_arguments(oid)", "pg_get_function_identity_arguments(oid)",
        "pg_get_function_result(oid)",
        "CASE WHEN prokind <> 'a' THEN pg_get_functiondef(oid) END AS pg_get_functiondef",
        "obj_description(oid, 'pg_proc') AS description",
    ]
    write_table(native, "builtins.txt", "pg_proc", "oid", kept, extra)
    write_table(native, "aggregates.txt", "pg_aggregate", "aggfnoid", kept)
    information_schema(native, kept)


def operators(native, frontend, calls, mismatches):
    candidates = native.execute(
        "SELECT oid, oprname, oprleft::int, oprright::int FROM pg_operator ORDER BY oid"
    ).fetchall()
    kept = []
    for oid, name, left, right in candidates:
        operands = arguments([left, right] if left else [right])
        tries = [f"SELECT {' '.join(o[:-1])} OPERATOR(pg_catalog.{name}) {o[-1]}" for o in operands]
        proof = prove(native, frontend, tries, mismatches)
        if proof:
            kept.append(oid)
            calls.append(f"o{oid}\t{proof[1]}\t{proof[0]}")
    write_table(native, "operators.txt", "pg_operator", "oid", kept,
                ["obj_description(oid, 'pg_operator') AS description"])


def casts(native, frontend, calls, mismatches):
    candidates = native.execute(
        "SELECT oid, castsource::int, casttarget::regtype::text FROM pg_cast ORDER BY oid"
    ).fetchall()
    kept = []
    for oid, source, target in candidates:
        tries = [f"SELECT {s}::{target}" for s in SAMPLES.get(source, [])]
        proof = prove(native, frontend, tries, mismatches, same_type=True)
        if proof:
            kept.append(oid)
            calls.append(f"c{oid}\t{proof[1]}\t{proof[0]}")
    write_table(native, "casts.txt", "pg_cast", "oid", kept)


def write_table(native, name, table, key, oids, extra=()):
    """A catalog table's rows, every column, then the extra ones; a regproc as its OID and name
    ("0" for none)."""
    columns = native.execute(
        "SELECT attname, atttypid = 'regproc'::regtype FROM pg_attribute"
        " WHERE attrelid = %s::regclass AND attnum > 0 ORDER BY attnum", (table,)).fetchall()
    select = ", ".join(
        f"CASE WHEN {c}::oid = 0 THEN '0' ELSE {c}::oid || ' ' || {c} END AS {c}" if regproc else c
        for c, regproc in columns)
    select = ", ".join([select, *extra])
    cur, rows = text_rows(
        native, f"SELECT {select} FROM {table} WHERE {key}::oid = ANY(%s) ORDER BY {key}::oid", (oids,))
    write(name, cur, rows)


def write(name, cur, rows):
    lines = ["\t".join(d.name for d in cur.description)]
    for row in rows:
        lines.append("\t".join("\\N" if v is None else v.decode().replace("\\", "\\\\").replace("\n", "\\n")
                               .replace("\t", "\\t") for v in row))
    (RESOURCES / name).write_text(
        "# from PostgreSQL 16 by difftest/catalog/builtins.py; tab separated, \\N is NULL\n"
        + "\n".join(lines) + "\n")
    print(f"{name}: {len(rows)} rows", file=sys.stderr)


def connect(port, dbname):
    conn = psycopg.connect(f"host=127.0.0.1 port={port} dbname={dbname} user=postgres password=postgres",
                           autocommit=True)
    conn.execute("SET statement_timeout = '3s'")
    conn.execute("SET TimeZone = 'UTC'")
    return conn


def main():
    native = connect(sys.argv[1], "audit")
    frontend = connect(sys.argv[2], "audit")
    calls, mismatches = [], []
    functions(native, frontend, calls, mismatches)
    operators(native, frontend, calls, mismatches)
    casts(native, frontend, calls, mismatches)
    (TEST_RESOURCES / "builtins-calls.txt").write_text(
        "# what proved each row of builtins.txt, operators.txt (o) and casts.txt (c): id, result type,"
        " call\n" + "\n".join(calls) + "\n")
    print(f"{len(mismatches)} differences from PostgreSQL 16:", file=sys.stderr)
    for m in mismatches:
        print(m, file=sys.stderr)


if __name__ == "__main__":
    main()
