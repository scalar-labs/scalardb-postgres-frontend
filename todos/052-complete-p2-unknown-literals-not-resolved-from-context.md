---
status: complete
priority: p2
issue_id: "052"
tags: [code-review, correctness, pushdown, type-system]
dependencies: []
---

# Unknown-typed literals and text parameters are not resolved from context

Found while probing the fix for todo 001, against `main@6c71631`, on 2026-10-09.

## Problem Statement

PostgreSQL gives an unquoted-type (unknown) literal or an unknown-typed parameter the type its
context demands: `'5' + 1` is 6, `WHERE id = '1'` on an integer key is a keyed read, `LIMIT '1'`
limits, `abs('-3')` is 3. The frontend keeps the text as a String and then refuses or ignores it:
arithmetic raises "Arithmetic on non-numeric values", functions raise "Expected a numeric
argument", `LIMIT $1` with a text parameter is silently ignored (all rows returned), and
`WHERE id = '1'` is not turned into a Get. Today the OID-0 parameter sniffing in
`PostgresServer.typed` hides this for node-postgres and psycopg 3, which is why fixing todo 001 by
removing the sniffing would regress them. Writes are fine: `columnFromText` converts by column type.

## Findings

- Probe test (session scratchpad, `UntypedAsTextProbeTest`): `SELECT '5' + 1` -> error; `SELECT $1 + 1` with "5" -> error; `SELECT abs($1)` with "-3" -> error; `SELECT id FROM t LIMIT $1` with "1" -> 2 rows; `WHERE id = $1` / `= '1'` -> no keyed Get; INSERT with String parameters -> correct columns.
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java` arithmetic and function argument checks reject String operands outright; `QueryParser` key extraction (`keyed`, `pushable`) and LIMIT binding expect numbers.

## Proposed Solutions

### Option 1: Coerce unknown text by context at bind time

**Approach:** When a String literal or an unknown-typed parameter meets a typed operand (column, number, LIMIT/OFFSET, a numeric function parameter), convert it with the single text-to-value converter of todo 021 using that type; leave it text only when nothing types it.

**Pros:**
- PostgreSQL semantics; lets todo 001 drop the sniffing entirely

**Cons:**
- Touches arithmetic, comparison, LIMIT and function binding

**Effort:** Medium (1-2 days)

**Risk:** Medium

---

### Option 2: Document

**Approach:** List under "Results that can differ" that unknown literals are text.

**Pros:**
- No code

**Cons:**
- `LIMIT $1` silently ignoring a text parameter stays a silent difference

**Effort:** Small

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** own probe while verifying todo 001; related: todo 021 (one converter), todo 023
- **Reviewed commit:** `main@6c71631` on `main`

## Acceptance Criteria

- [ ] `SELECT '5' + 1` returns 6; `SELECT $1 + 1` with a text parameter returns 6.
- [ ] `LIMIT $1` with a text parameter limits.
- [ ] `WHERE id = '1'` on an integer key is a keyed Get in EXPLAIN.
- [ ] `./gradlew check` passes; the difftest bind set is rerun

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by the probe for todo 001's fix; reproduced with mocks in the session scratchpad

**Learnings:**
- The OID-0 sniffing in `typed()` is load-bearing until this lands

### 2026-10-10 - Resolved

**By:** Claude Code

**Actions:**
- Probed against native PostgreSQL first: comparisons, keyed reads, LIMIT/OFFSET with quoted literals and every untyped-parameter case already matched (31215bc and the P2 fixes). What remained were errors, no wrong results: a quoted literal in arithmetic and in numeric functions.
- Fixed in the commit after bfeee75 on `main`: a quoted literal next to a number takes that number's type ('5' + 1 is 6, '1.5' * 2 is 22P02, '2147483647' + 1 is 22003); in abs, round, floor, ceil, power, sqrt and sign it is double precision, in round(x, n) numeric, in mod the other argument's type. Text to integer is strict, as in PostgreSQL (CAST('1.5' AS INT) was 2, now 22P02). Arithmetic on a text column and a non-numeric function argument are 42883.

**Remaining:**
- round('2.5') is 3 where PostgreSQL's float8 round gives 2: the known half-up rounding of todo 027.
- The OID-0 parameter guessing (todo 001) stays; typing parameters from context would let it go.
