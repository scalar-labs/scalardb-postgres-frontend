---
status: pending
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

**To be filled during triage.**

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

- [ ] Evaluator and Operators do not import QueryParser.
- [ ] Only PostgresServer and QueryProcessor are public.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code (`/ce:review`, eight review agents plus a direct read of PostgresServer.java and QueryProcessor.java)

**Actions:**
- Static review only; nothing was built or run
- Findings deduplicated across the security, performance, correctness, architecture, pattern, simplicity, agent-native and learnings reviews

**Learnings:**
- See Findings above

