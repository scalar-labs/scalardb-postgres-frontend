"""Turns a PostgreSQL statement log (log_statement=all, log_line_prefix='%m [%p] ') into a fixture
file of distinct catalog queries with bound parameters inlined, one client per marker pair.

A client run is framed by `SELECT 'MARK <name>-start'` and `SELECT 'MARK <name>-end'`.

    python pglog2fixture.py LOG NAME > NAME.sql
"""
import re
import sys

ENTRY = re.compile(r"^\S+ \S+ \S+ \[\d+\] (LOG|DETAIL|ERROR|STATEMENT|FATAL|WARNING|HINT):  (.*)$")
PARAM = re.compile(r"\$(\d+) = ('(?:[^']|'')*'|NULL)")


def entries(path):
    current = None
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        m = ENTRY.match(line)
        if m:
            if current:
                yield current
            current = [m.group(1), m.group(2)]
        elif current is not None and (line.startswith("\t") or not line.startswith("20")):
            current[1] += "\n" + line.lstrip("\t")
    if current:
        yield current


def statements(path, name):
    inside = False
    pending = None
    for kind, text in entries(path):
        if kind == "LOG" and f"MARK {name}-start" in text:
            inside = True
            continue
        if kind == "LOG" and f"MARK {name}-end" in text:
            break
        if not inside:
            continue
        if kind == "LOG" and (text.startswith("statement: ") or text.startswith("execute ")):
            if pending:
                yield pending
            pending = text.split(": ", 1)[1]
        elif kind == "DETAIL" and text.startswith("parameters: ") and pending:
            values = dict(PARAM.findall(text))
            pending = re.sub(r"\$(\d+)\b", lambda m: values.get(m.group(1), m.group(0)), pending)
    if pending:
        yield pending


def main():
    path, name = sys.argv[1], sys.argv[2]
    seen = set()
    print(f"-- {name}: catalog queries captured from PostgreSQL 16's statement log, parameters inlined.")
    print("-- source: postgres16")
    for sql in statements(path, name):
        key = sql.strip()
        if key in seen or not re.search(r"\bpg_[a-z_]+|information_schema|current_schema", key, re.I):
            continue
        seen.add(key)
        print(f"-- [{name}]\n{key}\n-----")


if __name__ == "__main__":
    main()
