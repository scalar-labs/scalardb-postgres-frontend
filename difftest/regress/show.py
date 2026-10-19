"""Prints a regress.py log's statements of the given buckets.

    python show.py OUT_DIR/FILE.jsonl DIFF,PG_ERR
"""
import json, sys
path, buckets = sys.argv[1], sys.argv[2].split(",")
for line in open(path):
    r = json.loads(line)
    if r["bucket"] in buckets:
        def s(x):
            if "error" in x: return f"ERROR {x['error']} {x['message'][:110]}"
            if "rows" in x: return f"{x['cols']} {x['rows'][:4]}{' ...' if len(x['rows'])>4 else ''} ({len(x['rows'])})"
            return x["status"]
        print(f"[{r['bucket']}{'~' if r['cascade'] else ''}] #{r['i']} {' '.join(r['sql'].split())[:160]}")
        print("   pg:", s(r["pg"])[:260]); print("   fe:", s(r["fe"])[:260])
