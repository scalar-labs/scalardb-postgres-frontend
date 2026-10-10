---
status: complete
priority: p2
issue_id: "027"
tags: [code-review, numeric, correctness]
dependencies: []
---

# Numeric edge semantics: domain errors, MIN negation, rounding, cast ranges, whitespace

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Several arithmetic and cast paths silently differ from PostgreSQL or report the wrong code: sqrt(-1)
and power(0,-1) return NaN/Infinity (PostgreSQL 2201F); -x and abs(x) wrap for Long.MIN_VALUE and
negating an Integer returns a Long so the int4 overflow check is lost (22003 expected); float8 to
int and round() use half-up where PostgreSQL rounds half to even (2.5::float8::int is 2);
'3000000000'::int reports 22P02 instead of 22003; ' 42 '::int and INSERT of ' 42' into INT fail
where PostgreSQL trims; ::numeric(p,s) applies the scale but never checks precision
(12345::numeric(3,0) succeeds); ::smallint does no 16-bit check (40000::smallint succeeds). None is
listed in LIMITATIONS.md.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java:2168-2172 sqrt/power;
  1136-1153 and 2124-2129 negation/abs; 1913-1915 and 2130-2143 rounding; 1840-1847 int casts;
  1848-1856 numeric(p,s); 1858 double parse.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:6147-6150 new BigDecimal(s)
  without trim.
- difftest summary (2026-10-08-fuzz) also lists as OPEN: UPDATE/DELETE WHERE int_col = $1 with a
  value beyond int range returns 'Unsupported condition' where PostgreSQL affects no rows.

## Proposed Solutions

### Option 1: Fix each in place, or document

**Approach:** Domain checks raising 2201F; Math.negateExact/absExact keeping the Integer type;
RoundingMode.HALF_EVEN for float8->int; 22003 on int cast overflow; trim before BigDecimal;
precision and smallint range checks in cast(); the out-of-range parameter condition becomes a no-row
match. Anything deliberately left goes under 'Results that can differ'.

**Pros:**
- Each is a few lines

**Cons:**
- Rounding change could alter existing baselines; rerun difftest

**Effort:** Small-Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `LIMITATIONS.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #15, P3 #20), architecture reviewer (#5), learnings researcher (OPEN item)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Each example above matches PostgreSQL or is documented.
- [ ] difftest boundary and fuzz baselines updated.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

### 2026-10-10 - Resolved

**By:** Claude Code

**Actions:**
- Fixed in 46a2cf1 on `main`; see the commit message for how it was verified

**Remaining:**
- Not changed: float8 rounds .5 half up, since NUMERIC columns are doubles here.
