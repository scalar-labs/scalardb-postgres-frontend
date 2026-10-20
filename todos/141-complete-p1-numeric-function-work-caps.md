---
status: complete
priority: p1
issue_id: "141"
tags: [security, denial-of-service, numeric, wrong-result]
dependencies: ["139"]
---

# Step 4's numeric functions had no bound on their work; exp lost digits near its limit

Found on 2026-10-20 by background security reviews of todo 139: BigDecimal arithmetic is quadratic
in the digits, so `log(1.<16000 zeros>1, 2)` took 60 s (PostgreSQL 2 s), `width_bucket` with
1e100000 bounds 65 s, `stddev` over 1e100000 18 s; `string_to_array(s, NULL)` made one object per
character of a 10M-character string. Testing the cap turned up a wrong value: `exp(5999.111...)`
was off from its 437th decimal, its working precision taken from the result weight clamped for the
scale (2000) instead of the real one (2605).

## Acceptance Criteria

- [x] ln, log, exp, width_bucket (numeric) and the stddev/variance family refuse an argument longer
      than 2000 digits written out (54000); the worst case under the cap takes about 1 s.
- [x] string_to_array refuses more than 1,000,000 fields (generate_series's cap), counted first.
- [x] exp's working precision from the unclamped weight; 120 calls across exp's whole range and
      ln/log of 1e-1900..1e1900 and near 1 agree with PostgreSQL 18.

## Work Log

- 2026-10-20: Evaluator.bounded, MAX_NUMERIC_DIGITS; string_to_array's count; numericExp's digits.
