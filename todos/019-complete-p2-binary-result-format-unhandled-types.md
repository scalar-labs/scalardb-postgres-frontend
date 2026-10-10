---
status: complete
priority: p2
issue_id: "019"
tags: [code-review, encoding, protocol]
dependencies: []
---

# Binary result format sends text bytes for interval, oid/regclass and other OIDs the encoder does not know

Found by `/ce:review` of the whole codebase at `main@6c71631` on 2026-10-09. Line numbers refer to that commit; the `auth` branch has since changed PostgresServer.java and QueryProcessor.java, so re-locate by symbol there.

## Problem Statement

A client that asks for binary results for every type it knows (pgx, psycopg 3 binary, asyncpg)
selecting now() - ts (oid 1186) or 't'::regclass (oid 26) receives text bytes in a column flagged
binary and fails to decode. The two hand-listed array OID case blocks also omit name[], json[],
jsonb[] and interval[] that Catalog.ARRAY_OIDS knows, so those fall to the text default too.

## Findings

- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:867-950 binary(oid,
  value) has no case for 1186, 26, 22; default at 947-948 emits text.
- engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java:904-918 and 1039-1053
  array OID lists versus Catalog.java:265-306 ARRAY_OIDS.

## Proposed Solutions

### Option 1: Encode what is known, declare text for the rest

**Approach:** Add interval (16 bytes: micros, days, months) and oid (4 bytes); use
Catalog.elementOid(oid) != 0 instead of the case lists; for any OID without a binary encoder, report
format code 0 for that column in RowDescription and send text.

**Pros:**
- Never sends mislabelled bytes

**Cons:**
- Per-column format override needs the RowDescription to know the encoder set

**Effort:** Small (half a day)

**Risk:** Low

## Recommended Action

**To be filled during triage.**

## Technical Details

**Affected files:**
- `engine/src/main/java/com/scalar/db/frontend/postgres/PostgresServer.java`
- `engine/src/main/java/com/scalar/db/frontend/postgres/Catalog.java`

**Database changes:** none (frontend only).

## Resources

- **Source:** correctness reviewer (P2 #18), pattern reviewer (#6), simplicity reviewer (#12)
- **Reviewed commit:** `main@6c71631` on `main`
- **Documentation:** `LIMITATIONS.md`, `docs/integration-design.md`, `docs/auth-design.md`, `difftest/README.md`

## Acceptance Criteria

- [ ] pgx with binary results decodes an interval and a regclass column.
- [ ] A name[] binary parameter round-trips.
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
- Fixed in 4d622ab on `main`; see the commit message for how it was verified
