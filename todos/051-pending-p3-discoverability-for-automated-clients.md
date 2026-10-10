---
status: pending
priority: p3
issue_id: "051"
tags: [code-review, discoverability, documentation]
dependencies: []
---

# Discoverability for automated clients: current_setting(), SHOW ALL descriptions, metadata settle NOTICE, EXPLAIN conventions

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

An agent or script driving the frontend over SQL cannot read
current_setting('scalardb.max_rows_per_write') (0A000 although SHOW works), sees NULL descriptions
in SHOW ALL, cannot tell from a DB-CORE-10017 after DDL that it should retry in N seconds when the
metadata cache expiry exceeds 5 s, and has nowhere to read that only ScalarDB-prefixed EXPLAIN lines
run in the engine or why a Filter conjunct stayed in memory.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:3924-3925
  current_setting/set_config typed but not evaluated (Evaluator.java:2220 default);
  QueryProcessor.java:314 description NULL; PostgresServer.java:85-96 configure only logs when
  cacheSecs > 5; QueryProcessor.java:854-864 settleMetadata; QueryParser.java:1604-1663 explain;
  5414-5463 the reasons a conjunct is not pushed.

## Proposed Solutions

### Option 1: Small additions

**Approach:** Pass QueryProcessor::currentSetting into the Evaluator context and evaluate
current_setting(name[, missing_ok]); describe scalardb.max_rows_per_write in SHOW ALL; expose the
cache expiry via SHOW and send a NOTICE 'takes effect in N s' when settleMetadata does not sleep;
one README sentence on the EXPLAIN prefix convention; append the kept-in-memory reason to the Filter
line.

**Pros:**
- Each is a few lines

**Cons:**
- None

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryProcessor.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Evaluator.java`
- `README.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** agent-native reviewer (#3, #4, #5)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] SELECT current_setting('scalardb.max_rows_per_write') works.
- [ ] EXPLAIN's Filter line names why a conjunct was not pushed.
- [ ] `./gradlew check` passes; the relevant difftest set is rerun where behaviour changes

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
- STILL REAL. Probed: current_setting('TimeZone') and set_config(...) are unsupported functions; SHOW ALL has no descriptions; EXPLAIN's ScalarDB lines are undocumented.

### 2026-10-10 - Partly fixed (group 3)

**By:** Claude Code

**Actions:**
- current_setting(name [, missing_ok]) and set_config(name, value, is_local) read and write the settings SHOW and SET do, with SET's checks; an unknown name is 42704 unless missing_ok.

**Remaining:**
- SHOW ALL has no descriptions, the metadata cache delay after DDL is not announced to the client, and EXPLAIN's ScalarDB lines are not documented.
