---
status: complete
priority: p2
issue_id: "139"
tags: [functions, step-4, differential]
dependencies: []
---

# Common functions the frontend refused (step 4, groups 1 and 2)

Agreed with the user on 2026-10-20: implement the functions whose semantics are fully specified,
port PostgreSQL's numeric scale rules for ln/log/exp/trunc, and keep refusing regexp_replace and
to_number.

## Acceptance Criteria

- [x] split_part, string_to_array, repeat, chr, overlay
- [x] ln, log, log10, exp, trunc for double precision and numeric (estimate_ln_dweight, log_var's
      and numeric_exp's scales), width_bucket (float8, numeric, thresholds array)
- [x] stddev, stddev_samp, stddev_pop, variance, var_samp, var_pop (Youngs-Cramer for floats,
      numeric_stddev_internal's scales otherwise)
- [x] jsonb_set, jsonb_insert (jsonfuncs.c's setPath), jsonb_strip_nulls (PostgreSQL 16's one
      argument)
- [x] make_timestamp, make_timestamptz (6 arguments; the zone form is refused), date_bin,
      date_trunc's decade, century, millennium, milliseconds, microseconds and every unit spelling
      DecodeUnits takes; below a day a timestamptz keeps its offset, as timestamptz_trunc does
- [x] Catalog rows from PostgreSQL 18 merged for these only (PostgreSQL 17+ functions such as
      random(int, int) and the int-to-bytea casts left out); defaults taken by Builtins.takes
- [x] Checked against PostgreSQL 18: ~650 calls, 440 of them random numerics for ln/log/exp

## Work Log

- 2026-10-20: Implemented; LIMITATIONS.md lists what stays refused.
