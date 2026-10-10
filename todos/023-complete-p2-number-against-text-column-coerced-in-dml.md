---
status: complete
priority: p2
issue_id: "023"
tags: [code-review, error-handling, dml, consistency]
dependencies: ["001"]
---

# A number against a TEXT column is refused in SELECT but silently coerced in keyed UPDATE/DELETE and INSERT values

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

checkTextAgainstNumber runs only from analyze(), so SELECT ... WHERE name = 1 is refused as
PostgreSQL does, but UPDATE t SET price = 1 WHERE id = 1 AND name = 1 returns UPDATE 0 (condition
name = '1'), UPDATE t SET name = 1 and INSERT INTO t (id, seq, name) VALUES (1, 1, 1) store "1", and
WHERE name = $1 with setInt matches nothing. PostgreSQL raises 42883/42804 in each case.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:4473-4536
  checkTextAgainstNumber, called only at 4462.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2491-2519 keyed
  UPDATE/DELETE via keyed() 4903-4915 and conditions() 5354-5368; values via value() 5371-5383 and
  insert() 4705-4733; all end in columnFromValue 1835-1862 (String.valueOf) and parseColumn TEXT.
- The check looks only at LongValue/DoubleValue, not at a Long parameter.

## Proposed Solutions

### Option 1: Check in columnFromValue

**Approach:** When the column is TEXT and the value is a Number or Boolean (not a String), throw the
same 'column "x" is of type text but expression is of type integer' / 'operator does not exist'
error; keep the visitor only for early failure.

**Pros:**
- One chokepoint for every path, including parameters

**Cons:**
- Interacts with todo 001 (oid-0 parameters must stay text first)

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** pattern reviewer (P2 #4)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] The four examples raise 42804/42883.
- [ ] INSERT of a quoted '1' into TEXT still works.
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
