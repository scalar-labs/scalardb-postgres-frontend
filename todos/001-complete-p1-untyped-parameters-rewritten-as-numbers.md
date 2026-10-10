---
status: complete
priority: p1
issue_id: "001"
tags: [code-review, correctness, protocol, data-integrity]
dependencies: []
---

# Untyped Bind parameters are re-typed as numbers before they reach TEXT columns

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

A Bind parameter whose declared type OID is 0 (what node-postgres sends for every value, what
psycopg 3 sends for Python str by default, and what any driver sends after our ParameterDescription
answers 0) is converted to Long, BigDecimal, Double or Boolean when its text looks numeric or
boolean. The value is later written to a TEXT column with String.valueOf, so '00123' is stored as
'123', '1e5' as '1E+5' (BigDecimal) and '-0' as '0'. PostgreSQL resolves an unknown-typed parameter
from the column and keeps the text. This is silent data corruption on an ordinary INSERT, and the
same conversion makes a pushed-down WHERE code = $1 with '007' look up '7'.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:813-838 typed(): oid 0
  plus a text matching NUMBER or true/false becomes a number or Boolean.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:966-983 plain(): the
  oid-0 case decides by the text alone.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:6218-6224 literal(): a
  JdbcParameter becomes String.valueOf(value), so the original text is gone.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:6159-6160 parseColumn(TEXT)
  stores whatever text arrives.
- The comparison path (lateConditions/pushable) pushes the converted value as a TextColumn, so
  lookups miss stored zero-padded codes.
- Reported by the correctness reviewer and confirmed by reading the code; the pattern reviewer found
  the sibling issue (todo 023).
- No unit test sends an oid-0 text parameter into a TEXT column (PostgresServerTest only asserts
  typed() in isolation).

## Proposed Solutions

### Option 1: Keep oid-0 parameters as text and coerce at the use site

**Approach:** typed() returns the String for oid 0; the planner coerces by the target column type
(INSERT/UPDATE values, keyed conditions) or by the other operand's type (comparisons), reusing
columnFromText.

**Pros:**
- Matches PostgreSQL's unknown-type resolution
- Fixes both the write and the lookup path

**Cons:**
- Touches the optimizer's literal handling for pushed parameters
- Needs the type of the other operand at bind time

**Effort:** Medium (1-2 days)

**Risk:** Medium

---

### Option 2: Never rewrite text when the target is TEXT

**Approach:** Keep typed() but carry the original text alongside the converted value (a small Param
record); literal() and the pushed-condition builder use the original text whenever the column is
TEXT.

**Pros:**
- Small diff
- No change for numeric columns

**Cons:**
- Still reports an int4 ParameterDescription-like behaviour for numeric text
- Two representations to keep in step

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/test/.../PostgresServerTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P1 #1), pattern reviewer (#4), own reading of PostgresServer.typed and QueryParser.literal
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] INSERT INTO t(code) VALUES ($1) with oid 0 and '00123' stores '00123' (node-postgres and psycopg 3 clients).
- [ ] SELECT ... WHERE code = $1 with oid 0 and '007' finds a stored '007' and not a stored '7'.
- [ ] '1e5', '-0', 'TRUE' sent as oid-0 text to a TEXT column are stored verbatim.
- [ ] Numeric columns still accept oid-0 numeric text, and pgjdbc (oid 1043) behaviour is unchanged.
- [ ] A PostgresServerTest case drives the extended protocol with oid 0 against a TEXT column.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Fix probe

**By:** Claude Code

**Actions:**
- Probed Option 1 (keep OID-0 parameters as text) by passing String parameters through QueryProcessor: `SELECT $1 + 1` and `abs($1)` fail with "Arithmetic on non-numeric values" / "Expected a numeric argument", `LIMIT $1` is ignored, and `WHERE id = $1` is not a keyed Get. Even the quoted literal `'5' + 1` errors, so the planner does not resolve unknown text from context (filed as todo 052).
- INSERT with String parameters is converted by the column type (columnFromText), so the write path is already right for text input.

**Learnings:**
- Option 1 as written needs todo 052 first; otherwise it regresses node-postgres users who rely on the sniffing for arithmetic, LIMIT and functions.
- Recommend Option 2 now: `typed()` returns a small `Untyped(text, guess)` for OID 0; `bound()` keeps returning the guess, and the three consumers that know the target is text use the original: `columnFromValue` for a TEXT column, the pushed-condition value builder for a TEXT column, and `compare()` when the other operand is a String. About 50 lines; the P1VerificationTest cases pin it.

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Reproduced end to end: pgjdbc with stringtype=unspecified (OID 0) ran INSERT INTO t (id, name) VALUES (?, ?) with '00123'; the one-shot mutate on the manager carried TextColumn name="123". typed("1e5", 0) yields a BigDecimal whose text is "100000".
- Reproduction: `scratchpad/review-main/engine/src/test/java/com/scalar/db/frontend/postgres/P1VerificationTest.java (session scratchpad; mock-based JUnit, runnable with ./gradlew :engine:test --tests '*P1VerificationTest' against an export of 6c71631)`

**Learnings:**
- Confirmed real.

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
- Fixed in 31215bc on `main`; see the commit message for how it was verified

**Remaining:**
- An untyped parameter keeps its text where a text value is needed. Still open: a quoted literal in arithmetic ('5' + 1) and typing from context in general, tracked in todo 052.
