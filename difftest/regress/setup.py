"""Writes setup.sql: PostgreSQL 16's test_setup.sql tables the first regression files use, adapted so
the frontend can hold them (a primary key on a unique column, text for name), with COPY's data as
INSERTs. Both servers get the same file, so the comparison stays fair.

    python setup.py REGRESS_DIR > setup.sql
"""
import sys

R = sys.argv[1]
STRINGS = "stringu1 text, stringu2 text, string4 text"
INTS = ", ".join(f"{c} int4" for c in
                 "unique1 unique2 two four ten twenty hundred thousand twothousand fivethous tenthous odd even".split())

print(f"""CREATE TABLE INT2_TBL(f1 int2 PRIMARY KEY);
INSERT INTO INT2_TBL(f1) VALUES ('0   '), ('  1234 '), ('    -1234'), ('32767'), ('-32767');
CREATE TABLE INT4_TBL(f1 int4 PRIMARY KEY);
INSERT INTO INT4_TBL(f1) VALUES ('   0  '), ('123456     '), ('    -123456'), ('2147483647'), ('-2147483647');
CREATE TABLE INT8_TBL(q1 int8, q2 int8, PRIMARY KEY (q1, q2));
INSERT INTO INT8_TBL VALUES ('  123   ','  456'), ('123   ','4567890123456789'), ('4567890123456789','123'),
  (+4567890123456789,'4567890123456789'), ('+4567890123456789','-4567890123456789');
CREATE TABLE FLOAT8_TBL(f1 float8 PRIMARY KEY);
INSERT INTO FLOAT8_TBL(f1) VALUES ('0.0'), ('-34.84'), ('-1004.30'), ('-1.2345678901234e+200'), ('-1.2345678901234e-200');
CREATE TABLE TEXT_TBL (f1 text PRIMARY KEY);
INSERT INTO TEXT_TBL VALUES ('doh!'), ('hi de ho neighbor');
-- 'abcd    ' becomes 'abcd' in varchar(4), a duplicate key here: one row fewer than in PostgreSQL's setup
CREATE TABLE VARCHAR_TBL(f1 varchar(4) PRIMARY KEY);
INSERT INTO VARCHAR_TBL (f1) VALUES ('a'), ('ab'), ('abcd');
-- 'abcd    ' is 'abcd' in char(4) too
CREATE TABLE CHAR_TBL(f1 char(4) PRIMARY KEY);
INSERT INTO CHAR_TBL (f1) VALUES ('a'), ('ab'), ('abcd');
CREATE TABLE aggtest (a int2 PRIMARY KEY, b float4);
INSERT INTO aggtest VALUES (56, 7.8), (100, 99.097), (0, 0.09561), (42, 324.78);""")


def table(name, data):
    print(f"CREATE TABLE {name} ({INTS}, {STRINGS}, PRIMARY KEY (unique1));")
    rows = [line.rstrip("\n").split("\t") for line in open(f"{R}/data/{data}")]
    for i in range(0, len(rows), 200):
        values = ",\n  ".join(
            "(" + ", ".join(r[:13]) + ", " + ", ".join("'" + s + "'" for s in r[13:]) + ")"
            for r in rows[i:i + 200])
        print(f"INSERT INTO {name} VALUES\n  {values};")


table("onek", "onek.data")
table("onek2", "onek.data")
table("tenk1", "tenk.data")
table("tenk2", "tenk.data")
