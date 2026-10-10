---
status: complete
priority: p1
issue_id: "006"
tags: [code-review, correctness, caching, embedding]
dependencies: []
---

# Plan cache keyed by SQL text ignores search_path; Plan is mutable and cannot be shared per node

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Plans are cached per session by statement text and only cleared after this session's DDL. Table
resolution uses search_path at plan time, so SELECT * FROM t; SET search_path = other; SELECT * FROM
t silently reads the first namespace's table. SET LOCAL search_path reverting at COMMIT has the same
problem. The cached Plan is also mutated on every execution (rebind, attach, executeWrite, open),
and binding mutates the cached JSQLParser AST in place, so the node-level cache that
docs/integration-design.md describes ('the parse cache is already shared') cannot be built on this
object model, and that sentence is wrong today.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:52-58 planCache keyed by
  sql; 831-842 returns the cached Plan after rebind.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java:231-235 searchPathChanged
  only calls parser.setSearchPath; planCache.clear() happens only at 720 after DDL.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:5292-5317 target() resolves
  through searchNamespaces() at plan time.
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:423-446 rebind, 1100-1114
  attach (stores the Reader that closes over the live transaction), 3096 and 4244 mutate the AST
  (setTable, setAlias).
- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2225-2231 statementCache is
  per QueryParser, i.e. per connection, not shared.
- docs/integration-design.md:68 claims the parse cache is shared and proposes a per-node plan cache
  keyed by namespace + SQL, which also omits search_path.
- No unit test changes search_path between two executions of the same statement.

## Proposed Solutions

### Option 1: Clear or re-key the cache on search_path change (now)

**Approach:** planCache.clear() in searchPathChanged when the effective path differs, or key the
cache by searchPath + '\n' + sql. Fix the two sentences in integration-design.md.

**Pros:**
- One line
- Removes the wrong-table read

**Cons:**
- Does nothing for the sharing problem

**Effort:** Small (an hour)

**Risk:** Low

---

### Option 2: Split Plan into an immutable template and a per-execution state (before any node-level cache)

**Approach:** Template: sources, logical plan, output names/types, mutation builder. Execution:
parameters, root, reader, cachedRows, returned, started, unsatisfied. Stop qualifying AST columns in
place (return the qualifier from Scope.resolve).

**Pros:**
- Makes a shared cache possible
- Also fixes the retention problem in todo 008

**Cons:**
- Medium refactor of QueryParser.Plan

**Effort:** Medium (2-3 days)

**Risk:** Medium

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `docs/integration-design.md`
- `engine/src/test/.../QueryProcessorTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** architecture reviewer (P1 #1), correctness reviewer (P2 #7)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] SELECT * FROM t; SET search_path = b; SELECT * FROM t reads b.t (unit test with two namespaces).
- [ ] SET LOCAL search_path inside a transaction reverts after COMMIT and the next statement replans.
- [ ] integration-design.md no longer says the parse cache is shared.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Verification

**By:** Claude Code

**Actions:**
- Reproduced with mocks: SELECT * FROM t; SET search_path = b; SELECT * FROM t issued both ScanAll reads on namespace a. Control: a fresh session that runs SET search_path = b first reads namespace b, so resolution works and the cached plan is the cause.
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
- Fixed in 166a00d on `main`; see the commit message for how it was verified
