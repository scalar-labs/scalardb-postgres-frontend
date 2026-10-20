---
status: complete
priority: p1
issue_id: "147"
tags: [regression-tests, wrong-result, parser]
dependencies: ["127", "129", "139"]
---

# Findings of the PostgreSQL regression run after step 4 (full4 against full3)

The 43 regression files on PostgreSQL 18 and the frontend, 2026-10-20 21:47-22:19: 357 statements
newly the same as PostgreSQL, FE_ERR 4281 -> 3975. Of the changes for the worse:

## Acceptance Criteria

- [x] `f.f1 * '-10'` failed to parse (float4, float8): normalize's `FROM t*` rewrite (todo 127)
      took the end of the text before a literal for the end of the statement, and dropped the
      `*`. A segment a literal follows now ends with a NUL while rewritten. Also `t*, u*` reads
      both (the comma no longer needs a word boundary).
- [x] A WITH query calling random() and referenced twice ran once per reference (10 rows where
      PostgreSQL's single run gives 5): refused (0A000) when the query calls a function pg_proc
      marks volatile.
- [x] width_bucket('5'::text, ARRAY[3, 4]::integer[]) answered: refused (42883) for text against
      numbers.
- [x] Checked, not frontend bugs: a DO INSTEAD rule and an index row size limit only PostgreSQL
      has (with 165, 266-275; strings 309-319), and timestamp rows the harness's resync left apart
      (timestamp 35, 36, 50: duplicate keys are found here, checked live).

## Work Log

- 2026-10-20: QueryParser.outsideLiterals, callsVolatile; Evaluator.widthBucket; tests.
