---
status: complete
priority: p2
issue_id: "024"
tags: [code-review, error-handling, catalog, consistency]
dependencies: []
---

# parse() swallows any planning error for a statement whose text contains 'pg_catalog.'

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

To answer psql's unsupported describe queries, parse() catches IllegalArgumentException and returns
an empty constant SELECT whenever the lower-cased statement contains the substring pg_catalog. —
with no NOTICE. SELECT nosuch FROM pg_catalog.pg_class returns 0 rows instead of 42703; SELECT *
FROM pg_catalog.pg_settings returns 0 rows while unqualified pg_settings raises 42P01; a DML whose
string literal contains pg_catalog. and fails planning comes back as SELECT 0. compat/README.md
records the downstream effect: SQLAlchemy reports NoSuchTableError instead of 'query not planned'.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1927-1937 the catch and
  substring test; 1949-1977 catalogColumnNames; documented by QueryParserTest.java:895-917.

## Proposed Solutions

### Option 1: Narrow the fallback and announce it

**Approach:** Fall back only when the statement is a Select whose FROM items are all Catalog tables
and the failure starts with 'Unsupported'; re-throw everything else; set a WARNING notice on the
constant plan ('catalog query not planned: ...') so drivers surface the reason.

**Pros:**
- Keeps psql working
- Honours error-or-NOTICE

**Cons:**
- None

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** pattern reviewer (P2 #7), agent-native reviewer (#1)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] SELECT nosuch FROM pg_catalog.pg_class raises 42703.
- [ ] psql \d still works and the unsupported describe queries carry a WARNING.
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
