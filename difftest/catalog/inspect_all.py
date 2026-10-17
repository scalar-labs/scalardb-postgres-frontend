"""Calls every SQLAlchemy Inspector method; prints name | result summary or ERR."""
import sys
from sqlalchemy import create_engine, inspect

port = sys.argv[1]
eng = create_engine(f"postgresql+psycopg://postgres@127.0.0.1:{port}/audit")
insp = inspect(eng)
calls = [
    ("get_schema_names", lambda: insp.get_schema_names()),
    ("get_table_names", lambda: insp.get_table_names()),
    ("get_table_names(sales)", lambda: insp.get_table_names(schema="sales")),
    ("has_table(items)", lambda: insp.has_table("items")),
    ("get_view_names", lambda: insp.get_view_names()),
    ("get_materialized_view_names", lambda: insp.get_materialized_view_names()),
    ("get_sequence_names", lambda: insp.get_sequence_names()),
    ("get_enums", lambda: insp.get_enums()),
    ("get_domains", lambda: insp.get_domains()),
    ("get_columns(items)", lambda: [(c["name"], str(c["type"]), c["nullable"]) for c in insp.get_columns("items")]),
    ("get_pk_constraint(items)", lambda: insp.get_pk_constraint("items")),
    ("get_pk_constraint(orders)", lambda: insp.get_pk_constraint("orders", schema="sales")),
    ("get_foreign_keys(orders)", lambda: insp.get_foreign_keys("orders", schema="sales")),
    ("get_indexes(items)", lambda: [(i["name"], i["column_names"], i["unique"]) for i in insp.get_indexes("items")]),
    ("get_unique_constraints(items)", lambda: insp.get_unique_constraints("items")),
    ("get_check_constraints(items)", lambda: insp.get_check_constraints("items")),
    ("get_table_comment(items)", lambda: insp.get_table_comment("items")),
    ("get_table_options(items)", lambda: insp.get_table_options("items")),
    ("get_multi_columns", lambda: {k: len(v) for k, v in insp.get_multi_columns().items()}),
    ("get_multi_pk_constraint", lambda: {k: v["constrained_columns"] for k, v in insp.get_multi_pk_constraint().items()}),
    ("get_multi_indexes", lambda: {k: len(v) for k, v in insp.get_multi_indexes().items()}),
]
for name, f in calls:
    try:
        r = repr(f())
    except Exception as e:
        r = "ERR " + type(e).__name__ + ": " + str(e).split("\n")[0][:90]
    print(f"{name} | {r[:150]}")
