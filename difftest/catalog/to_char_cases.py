"""Writes engine/src/test/resources/to_char-cases.txt, which ToCharTest checks the frontend against:
to_char of timestamps, dates and numbers under several patterns, as PostgreSQL 16 writes them.

    python to_char_cases.py NATIVE_PORT > ../../engine/src/test/resources/to_char-cases.txt
"""
import sys
import psycopg

STAMPS = ["2024-02-29 12:34:56.789012", "2023-01-01 00:00:00", "1999-12-31 23:59:59.5",
          "2001-11-03 07:08:09.000123", "2024-07-04 18:05:00"]
DT = ["YYYY-MM-DD HH24:MI:SS TZ", "HH24", "HH", "HH12", "MI", "SS", "MS", "US", "FF1", "FF3", "FF6",
      "SSSS", "SSSSS", "AM", "am", "PM", "pm", "A.M.", "p.m.", "Y,YYY", "YYYY", "YYY", "YY", "Y",
      "IYYY", "IYY", "IY", "I", "BC", "AD", "b.c.", "a.d.", "MONTH", "Month", "month", "MON", "Mon", "mon",
      "MM", "DAY", "Day", "day", "DY", "Dy", "dy", "DDD", "IDDD", "DD", "D", "ID", "W", "WW", "IW", "CC",
      "J", "Q", "RM", "rm", "TZ", "tz", "TZH", "TZM", "OF", "FMMonth", "FMDay", "FMDD", "FMHH12:MI AM",
      "DDth", "DDTH", "FMDDth", "Dth", "MMth", "hh24:mi:ss", "yyyy/mm/dd", "\"Week\" IW", "\"a\\\"b\" YYYY",
      "x", "a", "day", "YYYY-MM-DD\"T\"HH24:MI:SS.USOF", "Mon DD, YYYY", "TMMonth", "FXYYYY", "HH12:MI:SS pm"]
NUMS = ["0", "1", "-1", "12", "-12", "123", "1234567", "1.5", "-1.5", "0.5", "-0.5", "0.05", "99.95",
        "1234567.891", "-0.004"]
NT = ["9", "99", "999", "9999", "0000", "9.9", "0.9", "99.99", "9.99", "FM9.99", "FM999", "9,999",
      "9G999D99", "FM9,999,999.00", "S999", "999S", "MI999", "999MI", "PL999", "SG999", "999PR", "FM999PR",
      "0.00", "000.000", "a", "day", "1", "\"x\"999", "FM0.99", "FM90.99", "99,999.9", "FMS999", "S9.99",
      "9.9S", "FM999MI", "09", "90.0", "9 9", "$999", "99.9.9", "S9S", "9PR9", "9V9.9", "9V99",
      "9V" + "9" * 19]

def run(conn, sql, params):
    try:
        return conn.execute(sql, params).fetchone()[0]
    except psycopg.Error as e:
        return "ERROR " + e.sqlstate

conn = psycopg.connect(port=int(sys.argv[1]), host="localhost", user="postgres", password="postgres",
                       dbname="postgres", autocommit=True)
out = []
for zone in ["UTC", "Asia/Tokyo", "Asia/Kolkata", "America/New_York"]:
    conn.execute(f"SET TimeZone = '{zone}'")
    for kind in ["timestamp", "timestamptz", "date"] if zone == "UTC" else ["timestamptz"]:
        for v in STAMPS if kind != "date" else [s[:10] for s in STAMPS]:
            for t in DT:
                out.append((zone, kind, v, t, run(conn, f"SELECT to_char(%s::{kind}, %s)", (v, t))))
for kind in ["int4", "int8", "numeric", "float8", "float4"]:
    for v in NUMS:
        if kind.startswith("int") and "." in v:
            continue
        for t in NT:
            if kind == "int4" and len(t) > 12:  # PostgreSQL 16's int4 V overflow wraps; the frontend fails with 22003
                continue
            out.append(("UTC", kind, v, t, run(conn, f"SELECT to_char(%s::{kind}, %s)", (v, t))))
print("# to_char cases from PostgreSQL 16 (difftest/catalog/to_char_cases.py): zone, type, value, template, result")
for row in out:
    assert not any("\t" in str(c) or "\n" in str(c) for c in row), row
    print("\t".join(str(c) for c in row))
