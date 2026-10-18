---
status: complete
priority: p3
issue_id: "048"
tags: [code-review, dead-code, cleanup]
dependencies: []
---

# Simplifications: dead members, unreachable branches, 19-argument Plan constructors, duplicated helpers, orphaned Javadoc

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

Roughly 280-320 removable lines, about half at no behavioural risk, found by the simplicity and
pattern reviewers.

## Findings

- Dead: Plan.getRoot
  (engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:1088-1091), Json.ofNullable
  (Json.java:101-104); unreachable sqlState branches (PostgresServer.java:659-661, 689-691, see todo
  026).
- Three 19-argument new Plan(...) calls with all-default values
  (engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:448-469, 472-500, 859-879);
  Plan.of has one caller that discards what it passed.
- arrayText reimplements Evaluator.text(List)
  (engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:1109-1136 vs
  Evaluator.java:1178-1204); hand-rolled hex where HexFormat exists (Evaluator.java:1253-1259,
  QueryParser.java:6447-6454, 1848-1855); one-line delegates (PostgresServer.java:986-988,
  1214-1220); line 442 re-implements format(); oids()/oid() singleton-list recursion trick (845-862,
  1223-1303).
- Orphaned or doubled Javadoc: PostgresServer.java:334-339, 794; QueryProcessor.java:49-50, 338,
  775; Operators.java:453, 705-708, 1539; QueryParser.java:2532; a bare block around statement()
  (QueryProcessor.java:706-759).
- Evaluator.Context has three constructors for two call sites (Evaluator.java:117-130);
  QueryParserTest map/row duplicates (130-136, 1983-1989); children() two-add lists
  (Operators.java:1109-1114, 1519-1524, 1599-1604, 1651-1656); Source.lookupParams mirrors
  lookup().lookupParams (QueryParser.java:179, 3983-3987).
- Keep as is (justified): the two TestScanners classes, the two LRU LinkedHashMap subclasses,
  isSpace, the hand scanners, Source vs LogicalPlan.Source, the two typeCategory granularities,
  pgTypeName vs Catalog.typeName, Project.keepInput, the ponytail markers.

## Proposed Solutions

### Option 1: Delete and inline

**Approach:** Work through the list above; HexFormat.parseHex rejects odd-length input where the
loop dropped a nibble (already wrapped as Cannot cast).

**Pros:**
- Smaller surface

**Cons:**
- Needs a test run per batch

**Effort:** Small-Medium (1 day)

**Risk:** Low

## Recommended Action

Remove what is dead or duplicated, re-located against main@fd45c06. Of the doubled and orphaned
Javadoc, only PostgresServer's `run`, QueryParser's `declaredText` and SELECT-planning header, and
QueryProcessor's TimeZone pattern were still there; the rest had gone with earlier work. The sqlState
branches went with todo 026.

Left as is: the bare block in `QueryProcessor.statement` (removing it re-indents 60 lines for nothing),
`Source.lookupParams` (the physical plan's copy of the logical one, as the other Source fields are),
and the items the Findings already mark "keep as is".

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Json.java`
- `engine/src/test/.../QueryParserTest.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** simplicity reviewer (#3-#11), pattern reviewer (#8, #11)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [x] No orphaned block (checked by a scan for a Javadoc followed by another, a blank line or a brace; the javadoc task was not run).
- [x] No unused public members among those the review named (no repository-wide scan).
- [x] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

### 2026-10-10 - Triage

**By:** Claude Code (code read against main@85d3c40; probes against native PostgreSQL 18 where marked)

**Verdict:**
- MOSTLY REAL (code quality). The sqlState branches are gone (todo 026); the dead code, 19-argument Plan constructor and doubled Javadoc remain.

### 2026-10-18 - Done

**By:** Claude Code

**Actions:**
- Removed `Plan.getRoot`, `Plan.of`, `Json.ofNullable` (no callers here or in the cluster-embedding
  worktree) and PostgresServer's `hex`, `timestamp` and `parameterCount` delegates.
- `Plan` gains a three-argument constructor for plans with only output columns; `setOf`, `constant`
  and `deferred` use it instead of 19 arguments.
- PostgresServer's `arrayText` collects the elements and prints them with `Evaluator.text(List)`; the
  Bind loop uses `format(formats, i)`; `oid` no longer recurses through a one-row list, a `valueOid`
  takes the value.
- `Evaluator.Context` keeps two constructors. Operators' two-child `children()` return `Arrays.asList`.
- QueryParserTest's `row` helper merged into `map`.
- `./gradlew check`: 374 tests, 0 failing; `PostgresServerTest` covers the binary array with a NULL.
