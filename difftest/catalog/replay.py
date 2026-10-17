"""Replays a fixture file (queries separated by a line "-----") against native PostgreSQL and the
frontend and prints, per query: index | label | native | frontend, each an answer as below.

An answer is `N DIGEST` (row count and a digest of the rows) or `ERROR <sqlstate> message`. The digest
is the first 12 hex digits of the SHA-256 of the rows' text values (tab separated, NULL as \\N), sorted
and joined by newlines; ClientCatalogTest computes the same one.

Queries name relation OIDs ('16385') of the server they were captured on, given by the file's
`-- source: postgres16` (pglog2fixture.py output) or `-- source: frontend` (psql -E captures, the
default). The quoted OIDs listed in the oids line are mapped by relation name to the other server's
before running there; any other quoted number of five or more digits is reported, since it is either a
system OID or a relation the mapping does not know.

    python replay.py FILE [NATIVE_PORT] [FE_PORT]       # defaults 15416 15473, database audit
    python replay.py FILE NATIVE_PORT FE_PORT OUT       # also write FILE as a fixture to OUT

A fixture keeps the input's header comments, `-- source:`, `-- oids:` (the capturing server's relation
OIDs), and per query its label, PostgreSQL's answer (`-- postgres16: N DIGEST`) and, once reviewed, the
frontend's expected answer, which is kept across rewrites: `-- expect: N`, `-- expect: rows` for at least
one, `-- expect: same` for PostgreSQL's rows exactly, or `-- expect: ERROR 42P01`.
Near-duplicate queries (the same text up to string literals) are kept at most twice; when capturing
anew, a third lookup of the same shape (another table, another type) is dropped, and counted on stderr.
"""
import hashlib
import re
import sys

import psycopg

META = ("-- [", "-- oids:", "-- source:", "-- postgres16:", "-- expect:")


def header(text):
    lines = []
    for line in text.split("\n"):
        if not line.startswith("-- ") or line.startswith("-- ["):
            break
        if not line.startswith(META):
            lines.append(line)
    return lines


def queries(text):
    for chunk in text.split("\n-----\n"):
        lines = chunk.strip("\n").split("\n")
        label = next((l[4:].strip().rstrip("]") for l in lines if l.startswith("-- [")), "")
        expect = next((l for l in lines if l.startswith("-- expect: ")), None)
        sql = "\n".join(l for l in lines if not l.startswith("-- ")).strip()
        if sql:
            yield label, expect, sql


def oids(conn):
    rows = conn.execute(
        "SELECT c.oid::text, n.nspname || '.' || c.relname FROM pg_catalog.pg_class c"
        " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
        " WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')"
    ).fetchall()
    return {name.replace("audit.", "public."): oid for oid, name in rows}


def digest(result):
    rows = []
    for r in range(result.ntuples):
        values = (result.get_value(r, c) for c in range(result.nfields))
        rows.append("\t".join("\\N" if v is None else v.decode() for v in values))
    return hashlib.sha256("\n".join(sorted(rows)).encode()).hexdigest()[:12]


def run(conn, sql):
    # libpq directly, for the rows' text as the server sent it
    result = conn.pgconn.exec_(sql.encode())
    status = result.status
    if status == psycopg.pq.ExecStatus.TUPLES_OK:
        return "%d %s" % (result.ntuples, digest(result))
    if status == psycopg.pq.ExecStatus.COMMAND_OK:
        return "0"
    state = (result.error_field(psycopg.pq.DiagnosticField.SQLSTATE) or b"?????").decode()
    message = (result.error_message or b"").decode(errors="replace").split("\n")[0][:90]
    return "ERROR %s %s" % (state, message)


def main():
    path = sys.argv[1]
    native_port = sys.argv[2] if len(sys.argv) > 2 else "15416"
    fe_port = sys.argv[3] if len(sys.argv) > 3 else "15473"
    native = psycopg.connect(f"host=127.0.0.1 port={native_port} dbname=audit user=postgres", autocommit=True)
    fe = psycopg.connect(f"host=127.0.0.1 port={fe_port} dbname=audit user=postgres", autocommit=True)
    text = open(path).read()
    source = "postgres16" if re.search(r"^-- source: postgres16$", text, re.M) else "frontend"
    fe_oids, native_oids = oids(fe), oids(native)
    captured, other = (native_oids, fe_oids) if source == "postgres16" else (fe_oids, native_oids)
    mapping = {captured[n]: other[n] for n in captured if n in other}
    unmapped = sorted(n for n in captured if n not in other)
    if unmapped:
        print("relations missing on the other server: %s" % " ".join(unmapped), file=sys.stderr)
    out = open(sys.argv[4], "w") if len(sys.argv) > 4 else None
    if out:
        out.write("".join(line + "\n" for line in header(text)))
        out.write("-- source: %s\n" % source)
        out.write("-- oids: %s\n" % " ".join("%s=%s" % (n, o) for n, o in sorted(captured.items())))
    templates, dropped = {}, 0
    for i, (label, expect, sql) in enumerate(queries(text)):
        template = re.sub(r"'(?:[^']|'')*'", "?", sql)
        templates[template] = templates.get(template, 0) + 1
        if templates[template] > 2 and "_interval" not in sql:
            dropped += 1
            continue
        for oid in re.findall(r"'(\d{5,})'", sql):
            if oid not in mapping:
                print("%d: '%s' is not a relation OID in the oids line" % (i, oid), file=sys.stderr)
        mapped = re.sub(r"'(\d{5,})'", lambda m: "'%s'" % mapping.get(m.group(1), m.group(1)), sql)
        n, f = (run(native, sql), run(fe, mapped)) if source == "postgres16" else (run(native, mapped), run(fe, sql))
        print("%d | %s | %s | %s" % (i, label, n, f))
        if out:
            out.write("-- [%s]\n-- postgres16: %s\n%s%s\n-----\n" % (label, n, expect + "\n" if expect else "", sql))
    if dropped:
        print("dropped %d near-duplicate queries" % dropped, file=sys.stderr)


if __name__ == "__main__":
    main()
