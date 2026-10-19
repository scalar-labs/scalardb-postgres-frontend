"""Runs PostgreSQL regression files statement by statement on PostgreSQL 16 and the frontend, one session
per server per file, and sorts each statement into a bucket:

  SAME    same rows (or status), or both fail with the same SQLSTATE
  FE_ERR  the frontend fails where PostgreSQL succeeds (a gap)
  CODE    both fail, with different SQLSTATEs
  DIFF    both succeed with different results (a wrong result)
  PG_ERR  the frontend succeeds where PostgreSQL fails (accepts what it should refuse)
  ORDER   the same rows in another order, for a query whose ORDER BY may not be top-level

A table the file creates without a primary key gets one on its first column, on both servers, since a
ScalarDB table needs one; TEMP is dropped. psql backslash commands are skipped. A failure that names a
table the frontend could not create (but PostgreSQL could) is marked as a cascade.

    python regress.py OUT_DIR FILE.sql...       # env NATIVE_PORT (15416), FE_PORT (15473), DB (regress)
"""
import json
import os
import re
import sys

import psycopg

NATIVE = int(os.environ.get("NATIVE_PORT", "15416"))
FE = int(os.environ.get("FE_PORT", "15473"))
DB = os.environ.get("DB", "regress")


def statements(text):
    """(statement, skipped psql command or None) in order; quotes, dollar quotes and comments respected."""
    out, cur, i, n = [], [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "-" and text.startswith("--", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            cur.append(text[i:j])
            i = j
        elif c == "/" and text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            cur.append(text[i:j])
            i = j
        elif c in "'\"":
            escape = c == "'" and i > 0 and text[i - 1] in "eE" and (i < 2 or not (text[i - 2].isalnum() or text[i - 2] == "_"))
            j = i + 1
            while j < n:
                if escape and text[j] == "\\":
                    j += 2
                    continue
                if text[j] == c:
                    if j + 1 < n and text[j + 1] == c:
                        j += 2
                        continue
                    break
                j += 1
            cur.append(text[i:j + 1])
            i = j + 1
        elif c == "$" and re.match(r"\$([A-Za-z_]\w*)?\$", text[i:]) and not (i > 0 and (text[i - 1].isalnum() or text[i - 1] == "_")):
            tag = re.match(r"\$([A-Za-z_]\w*)?\$", text[i:]).group(0)
            j = text.find(tag, i + len(tag))
            j = n if j < 0 else j + len(tag)
            cur.append(text[i:j])
            i = j
        elif c == "\\":
            j = text.find("\n", i)
            j = n if j < 0 else j
            stmt = "".join(cur).strip()
            out.append((stmt, text[i:j]) if stmt and not only_comments(stmt) else ("", text[i:j]))
            cur, i = [], j
        elif c == ";":
            stmt = "".join(cur).strip()
            if stmt and not only_comments(stmt):
                out.append((stmt, None))
            cur, i = [], i + 1
        else:
            cur.append(c)
            i += 1
    return out


def only_comments(s):
    return not re.sub(r"--[^\n]*|/\*.*?\*/", "", s, flags=re.S).strip()


SETUP = {"aggtest", "char_tbl", "float8_tbl", "int2_tbl", "int4_tbl", "int8_tbl", "onek", "onek2", "tenk1", "tenk2",
         "text_tbl", "varchar_tbl"}
# setup.sql's statements by the table they create or fill, to restore a setup table a file dropped
SETUP_SQL = {}
if os.path.exists(os.environ.get("SETUP", "setup.sql")):
    for stmt, _ in statements(open(os.environ.get("SETUP", "setup.sql")).read()):
        m = re.search(r"(?i)\b(?:TABLE|INTO)\s+(\w+)", stmt)
        if m:
            SETUP_SQL.setdefault(m.group(1).lower(), []).append(stmt)
CREATE = re.compile(r"(?is)^\s*CREATE\s+(?:(?:GLOBAL|LOCAL)\s+)?(TEMP|TEMPORARY|UNLOGGED)?\s*TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.\"]+)\s*\((.*)\)\s*$")


def adapt(sql):
    """The statement both servers run, and the table it creates (or None)."""
    sql = re.sub(r"(?s)^(\s*(--[^\n]*(\n|$)|/\*.*?\*/))*\s*", "", sql)  # leading comments
    m = CREATE.match(sql)
    if not m:
        return sql, None
    name, body = m.group(2), m.group(3)
    if re.search(r"(?i)\bPRIMARY\s+KEY\b", body) or re.search(r"(?i)\)\s*(INHERITS|PARTITION|AS)\b", sql):
        return re.sub(r"(?i)\b(TEMP|TEMPORARY|UNLOGGED)\s+TABLE", "TABLE", sql, count=1), name
    # the leading integer and text columns, or the first column: a key the file's rows likely keep unique
    parts, depth, start = [], 0, 0
    for j, ch in enumerate(body):
        depth += ch == "("
        depth -= ch == ")"
        if ch == "," and depth == 0:
            parts.append(body[start:j])
            start = j + 1
    parts.append(body[start:])
    keyable = r"(?i)((small|big)?int[248]?|integer|text|varchar|character varying)$"
    key = []
    for part in parts:
        m2 = re.match(r'\s*("[^"]+"|\w+)\s+(.*)', part, re.S)
        if not m2 or m2.group(1).upper() in ("LIKE", "CONSTRAINT", "CHECK", "UNIQUE", "FOREIGN", "EXCLUDE", "PRIMARY"):
            break
        typ = re.split(r"(?i)\(|\b(NOT|NULL|DEFAULT|CHECK|REFERENCES|COLLATE|UNIQUE|GENERATED)\b", m2.group(2))[0].strip()
        if re.match(keyable, typ):
            key.append(m2.group(1))
        else:
            if not key:
                key.append(m2.group(1))
            break
    if not key:
        return sql, name
    sql = re.sub(r"(?i)\b(TEMP|TEMPORARY|UNLOGGED)\s+TABLE", "TABLE", sql, count=1)
    k = sql.rstrip().rfind(")")
    return sql[:k] + f", PRIMARY KEY ({', '.join(key)})" + sql[k:], name


def connect(port):
    conn = psycopg.connect(host="127.0.0.1", port=port, dbname=DB, user="postgres", password="postgres",
                           autocommit=True, options="-c statement_timeout=60000")
    conn.add_notice_handler(lambda d: None)
    return conn


def run(conn, sql):
    try:
        cur = conn.cursor(binary=False)
        cur.execute(sql)
        res = cur.pgresult
        if cur.description is None:
            return {"status": cur.statusmessage}
        cols = [d.name for d in cur.description]
        rows = ["|".join("<NULL>" if v is None else v.decode("utf-8", "replace")
                         for v in (res.get_value(r, c) for c in range(res.nfields))) for r in range(res.ntuples)]
        return {"cols": cols, "rows": rows}
    except psycopg.Error as e:
        if conn.closed or conn.broken:
            raise
        return {"error": e.sqlstate or "?????", "message": str(e).splitlines()[0] if str(e) else ""}


def bucket(sql, pg, fe):
    if "error" in pg or "error" in fe:
        if "error" in pg and "error" in fe:
            return "SAME" if pg["error"] == fe["error"] else "CODE"
        return "FE_ERR" if "error" in fe else "PG_ERR"
    if pg == fe:
        return "SAME"
    if "rows" in pg and "rows" in fe and pg["cols"] == fe["cols"] and sorted(pg["rows"]) == sorted(fe["rows"]):
        return "ORDER" if re.search(r"(?i)\border\s+by\b", sql) else "SAME"
    return "DIFF"


def main():
    out_dir, files = sys.argv[1], sys.argv[2:]
    os.makedirs(out_dir, exist_ok=True)
    totals = {}
    for path in files:
        name = os.path.basename(path)[:-4]
        pg, fe = connect(NATIVE), connect(FE)
        for conn in (pg, fe):  # whatever an earlier file left behind, and the setup's tables it dropped
            present = {t for (t,) in conn.execute("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")}
            for t in present - SETUP:
                run(conn, f'DROP TABLE IF EXISTS "{t}" CASCADE')
            for t in SETUP - present:
                for sql in SETUP_SQL.get(t, []):
                    run(conn, sql)
        created, failed = [], set()
        counts = {}
        with open(f"{out_dir}/{name}.jsonl", "w") as log:
            for i, (raw, meta) in enumerate(statements(open(path).read())):
                if not raw:
                    counts["META"] = counts.get("META", 0) + 1
                    continue
                if re.match(r"(?is)^(\s*(--[^\n]*\n|/\*.*?\*/))*\s*(COPY|EXPLAIN)\b", raw):
                    counts["SKIP"] = counts.get("SKIP", 0) + 1  # COPY needs psql; EXPLAIN compares plans
                    continue
                sql, table = adapt(raw)
                try:
                    a = run(pg, sql)
                    b = run(fe, sql)
                except psycopg.Error as e:
                    print(f"{name}: connection lost at statement {i}: {e}", file=sys.stderr)
                    pg, fe = connect(NATIVE), connect(FE)
                    counts["LOST"] = counts.get("LOST", 0) + 1
                    continue
                kind = bucket(sql, a, b)
                if table:
                    if "error" not in a:
                        created.append(table)
                    if "error" not in a and "error" in b:
                        failed.add(table.strip('"').lower().split(".")[-1])
                cascade = kind in ("FE_ERR", "DIFF", "CODE") and any(
                    re.search(rf"(?i)\b{re.escape(t)}\b", sql) for t in failed) and not table
                key = kind + ("~" if cascade else "")
                counts[key] = counts.get(key, 0) + 1
                log.write(json.dumps({"i": i, "bucket": kind, "cascade": cascade, "sql": sql, "adapted": sql != raw,
                                      "meta": meta, "pg": a, "fe": b}) + "\n")
        for conn in (pg, fe):
            run(conn, "ROLLBACK")  # a file may end inside a failed transaction
            for t in reversed(created):
                run(conn, f"DROP TABLE IF EXISTS {t} CASCADE")
        pg.close()
        fe.close()
        totals[name] = counts
        print(name, json.dumps(dict(sorted(counts.items()))))
    json.dump(totals, open(f"{out_dir}/summary.json", "w"), indent=1)


if __name__ == "__main__":
    main()
