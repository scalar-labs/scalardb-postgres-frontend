---
status: complete
priority: p3
issue_id: "049"
tags: [code-review, architecture, naming]
dependencies: ["006"]
---

# Structure seams: move Plan out of QueryParser, narrow the public API, remove the null-context mode switch, fix name collisions

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

QueryParser's 6455 lines hold the planner and the runtime Plan class, and Operators/Evaluator depend
on QueryParser.Plan/Reader/Cursor and a dozen static helpers, so the three files are one compilation
knot. The public API is wider than embedding needs (QueryParser, Plan, Operator public; Execution's
constructor package-private). pushable()/valueColumn use ctx == null as a mode; several boolean
flags select unrelated behaviours; binary(int,Object) encodes while binary(int,byte[]) decodes; four
text(...) methods mean different things; QueryProcessor mixes fully-qualified and imported names.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:321-1511 Plan and friends;
  5386-5399, 5473-5490, 5460-5464 null-mode; Operators.java:303-321 keepInput, 132 stream;
  PostgresServer.java:867 vs 991; Evaluator.java:1165, PostgresServer.java:1207,
  QueryParser.java:1644, Json.java:397 text(); QueryProcessor.java FQNs.

## Proposed Solutions

### Option 1: Two mechanical moves, then naming

**Approach:** Move Plan, Source, Target, Effect, Mutator, Returning, WriteByScan, Ddl, Cursor,
Reader, ReadFailure and the cursor helpers to Plan.java (~1,500 lines); move the literal/identifier
helpers Evaluator borrows to Sql.java; make QueryParser/Plan/Operator package-private and
PostgresServer final; always pass a Context; enum for Project/Scan modes; encodeBinary/decodeBinary;
conjunctionText; normalise imports. Do not split bind/optimize/physical per statement or replace
JSQLParser nodes with an own IR.

**Pros:**
- Removes the circular coupling
- Smaller API for the Cluster node

**Cons:**
- Churn in a file the auth branch also touches; sequence after it merges

**Effort:** Medium (1-2 days)

**Risk:** Low

## Recommended Action

Option 1's mechanical part: the move, the narrower API and the names. Not the rest, see the work log.

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Operators.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `docs/integration-design.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** architecture reviewer (#6, #9), pattern reviewer (#10, #12, #13)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] Evaluator and Operators do not import QueryParser. Not met: they still call 35 and 21 QueryParser helpers; moving those to a Sql.java was left out.
- [x] Only PostgresServer and QueryProcessor are public, plus Authenticator, which the Cluster node implements.
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
- STILL REAL (code quality). QueryParser is 6,740 lines with Plan inside; name collisions remain.

### 2026-10-19 - Done, in part

**By:** Claude Code

**Actions:**
- Plan and its helper types (Source, Effect, Mutator, Returning, WriteByScan, Ddl, Cursor, Reader,
  ReadFailure, UniqueViolationException) moved to Plan.java, as a top-level `Plan` with them nested.
  QueryParser is 6,328 lines, Plan.java 1,551. `QueryParser.Plan` and `QueryParser.Cursor` and the like
  are now `Plan` and `Plan.Cursor`. Plan reaches 19 QueryParser helpers by static import, which were
  made package-private: the coupling is now visible, not removed.
- QueryParser, Plan and Operator, and Plan's nested types, are package-private; PostgresServer is
  final. Outside the package only PostgresServer and Authenticator are used (server module and the
  Cluster node).
- `PostgresServer.binary` split into `encodeBinary(int, Object)` and `decodeBinary(int, byte[])`: a
  `byte[]` argument used to pick the decoder silently. The rename went encoder first, so the compiler
  listed every encode call. `QueryParser.text(List<Expression>)` is `conjunctionText`,
  `PostgresServer.text` is `wireText`.
- QueryProcessor imports what it named by full name (Pattern, Matcher, ZoneId and 11 more).
- `./gradlew check`: 374 tests, 0 failing; a CREATE, INSERT, UNION, EXPLAIN and DROP through the rebuilt
  frontend on 15473 work.

**Left out:**
- The `ctx == null` mode of `pushable`/`valueColumn`: it is the plan-time check behind the named
  two-argument `pushable(e, t)`, and passing a Context there would evaluate constant expressions at plan
  time, a behaviour change.
- Enums for Scan's `stream` and Project's `keepInput`: one meaning each, documented; todo 048 kept
  `keepInput` too.
- Moving the literal and identifier helpers Evaluator and Operators borrow to a Sql.java, so the first
  acceptance criterion is not met. It is the bigger half of untangling the three files and can be its
  own todo if wanted.
- Execution's constructor visibility.
