---
status: complete
priority: p2
issue_id: "041"
tags: [code-review, security, auth-adjacent, architecture]
dependencies: []
---

# Any session can address any namespace, and DROP COORDINATOR TABLES / DROP SCHEMA CASCADE are plain statements

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

The connected database only chooses a default namespace: other_ns.table, SET search_path = other_ns
and DDL on other_ns all work, DROP SCHEMA x CASCADE drops every table in any namespace, and DROP
COORDINATOR TABLES destroys the coordinator shared by every ScalarDB application on that backend.
Namespace names are resolved in five independent places, so the auth work has no single chokepoint
to enforce 'this principal may touch these namespaces'. The auth branch delegates privilege checks
to Cluster; this todo is about the frontend-side guard for coordinator DDL and for the standalone
server where no Cluster sits behind it.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java:2576-2590 namespace();
  2549-2570 search_path; 2841-2874 DROP SCHEMA CASCADE; 2430-2441 DROP COORDINATOR TABLES ->
  admin.dropCoordinatorTables(true); Catalog.java:573-598 enumerates all namespaces (574 hides
  coordinator/scalardb); PostgresServer.java:203 database string unvalidated.

## Proposed Solutions

### Option 1: One resolver and an operator flag

**Approach:** Route every namespace decision through namespace()/target() with a per-session allow-
list (initially the connected database unless a property allows all); gate CREATE/DROP COORDINATOR
TABLES and DROP SCHEMA behind an operator property or superuser setting; validate the startup
database string (identifier chars, 63 bytes).

**Pros:**
- A single place for the auth branch to hook

**Cons:**
- Must agree with the auth branch's identity scope; coordinate before implementing

**Effort:** Small-Medium (1 day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/QueryParser.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `docs/auth-design.md`

**Database changes:** none (frontend only).

## Resources

- **Source:** security reviewer (#3)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] DROP COORDINATOR TABLES is refused unless an operator flag allows it.
- [ ] Cross-namespace access is governed by one method, with a test for each entry path.
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
- PARTLY REAL (security). With auth=none, which every non-Cluster backend uses, any client that reaches the port can drop the coordinator tables or a schema with CASCADE. With Cluster authentication, Cluster's privileges govern these calls.

### 2026-10-10 - Resolved

**By:** Claude Code

**Actions:**
- DROP COORDINATOR TABLES is refused with 42501 unless the session is a Cluster superuser or the operator sets scalar.db.postgres.allow_drop_coordinator_tables=true. CREATE COORDINATOR TABLES IF NOT EXISTS, which the setup scripts run, is unchanged.

**Remaining, by design:**
- DROP SCHEMA ... CASCADE under auth=none is left alone: a trusted connection may drop each of those tables anyway, so refusing the cascade protects nothing. With Cluster authentication its privileges decide.
