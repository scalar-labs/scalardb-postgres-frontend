---
title: "feat: CREATE USER, ALTER USER, DROP USER, GRANT and REVOKE in SQL, onto ScalarDB Cluster's AuthAdmin"
type: feat
status: completed
date: 2026-10-11
---

# feat: Manage Cluster users, roles and privileges in PostgreSQL SQL

Status: implemented 2026-10-11 as planned, with the three recommendations taken. The tests went into
`PostgresServerTest` (`dcl_*`, over pgjdbc against a mocked admin) rather than a separate `DclTest`. The
(verify) items were settled by `difftest/cluster-auth` (38/38, see its README). `REVOKE GRANT OPTION FOR` revokes
ScalarDB's single grant option and leaves the privileges.

## Overview

With authentication on (`scalar.db.postgres.auth=userpass|oidc_jwt`), a psql session logged in as a Cluster
superuser should be able to run `CREATE USER`, `ALTER USER`, `DROP USER`, `CREATE ROLE`, `GRANT` and `REVOKE`,
and have them land in Cluster's user store through `AuthAdmin`. Today these are parse errors, and users are
managed outside the frontend (`difftest/cluster-auth/users.sh`, `LIMITATIONS.md:16`). This is the first "Later"
item of `docs/auth-design.md:147`.

Nothing new is needed on the Cluster side. `DistributedTransactionAdmin` extends `AuthAdmin`
(`core/.../api/DistributedTransactionAdmin.java:11`), the parser already holds that admin, and every DDL already
runs under the session's identity (`Authenticator.Identity.run`). Cluster therefore decides who may create a
user or grant a privilege, as it does for every other call. The frontend only translates the statements.

## What AuthAdmin offers

From `AuthAdmin.java` in the core snapshot the frontend builds against:

- **Users:** `createUser(name, password, UserOption...)`, `alterUser` (a null password leaves it unchanged, an
  empty one deletes it), `dropUser`, `getUser`, `getUsers`. `UserOption` is `SUPERUSER` or `NO_SUPERUSER`.
- **Privileges** on a table or on a whole namespace: `grant` and `revoke` for users; `grantPrivilegeToRole` and
  `revokePrivilegeFromRole` for roles. The privileges are `READ`, `WRITE`, `DELETE`, `CREATE`, `DROP`,
  `TRUNCATE`, `ALTER` and `GRANT` (the grant option).
- **Roles:** `createRole`, `dropRole`, `getRole`, `grantRoleToUser`/`grantRoleToRole` with an admin option,
  `revokeRoleFromUser`/`revokeRoleFromRole`, `revokeAdminOptionFromUser`/`revokeAdminOptionFromRole`.

Correction to the earlier remark that ScalarDB grants only per namespace: table-level grants exist. Only
`difftest/cluster-auth/Users.java` limits itself to namespaces.

## Precedent: ScalarDB SQL

ScalarDB SQL already maps PostgreSQL-style privilege names onto `AuthAdmin`, in
`scalardb-sql/direct-mode/.../DclStatementExecutor.java`. Its grammar is in `SqlParser.g4:501-633`. The frontend
follows its rules, so the same `GRANT` means the same thing in both products:

| SQL | grant | revoke |
|---|---|---|
| `SELECT` | READ | READ |
| `INSERT` | WRITE | WRITE |
| `UPDATE` | READ, WRITE | WRITE |
| `DELETE` | READ, DELETE | DELETE |
| `TRUNCATE` | TRUNCATE | TRUNCATE |
| `CREATE`, `DROP`, `ALTER` | the same | the same |
| `ALL [PRIVILEGES]` | every one except GRANT | the same |
| `WITH GRANT OPTION` / `GRANT OPTION FOR` | adds GRANT | revokes GRANT only |

On top of the mapping, ScalarDB SQL enforces these checks, which the frontend copies with the same messages:

- `INSERT` and `UPDATE` must be granted together and revoked together, since both are `WRITE`.
- `UPDATE` and `DELETE` need `SELECT`, either already held or in the same statement, because both read rows
  first.
- `SELECT` cannot be revoked while `DELETE` stays.

The "already held" part reads the grantee's current privileges with `getPrivileges` or `getRolePrivileges`.

`CREATE`, `DROP` and `ALTER` on a table are not PostgreSQL privileges, where they come with ownership. ScalarDB
SQL accepts them, and so does the frontend, as an extension. Without them, a non-superuser could only be allowed
to drop or alter a table through `ALL`.

## Statements

Only a subset of PostgreSQL's syntax is accepted. Anything else in these statements gets `0A000`, naming the
unsupported clause.

**CREATE USER / CREATE ROLE** `name [[WITH] option ...]`

- Options: `[ENCRYPTED] PASSWORD 'p'`, `PASSWORD NULL`, `SUPERUSER`, `NOSUPERUSER`, `LOGIN`, `NOLOGIN`.
- As in PostgreSQL, `CREATE USER` is `CREATE ROLE ... LOGIN`.
- A role that can log in becomes a ScalarDB **user**: `createUser(name, password, SUPERUSER | NO_SUPERUSER)`.
- `NOLOGIN`, which is `CREATE ROLE`'s default, becomes a ScalarDB **role**: `createRole(name)`. `PASSWORD` or
  `SUPERUSER` on such a role gets `0A000`, since a ScalarDB role can neither log in nor be a superuser.
- `CREATEDB`, `CREATEROLE`, `INHERIT`, `REPLICATION`, `BYPASSRLS`, `CONNECTION LIMIT`, `VALID UNTIL`, `IN ROLE`,
  `ROLE`, `ADMIN` and `SYSID` get `0A000`.

**ALTER USER / ALTER ROLE** `name [[WITH] option ...]`

- `PASSWORD 'p'` becomes `alterUser(name, "p")`. `PASSWORD NULL` becomes `alterUser(name, "")`, which deletes
  the password.
- `SUPERUSER` and `NOSUPERUSER` become the user options.
- `CURRENT_USER` and `SESSION_USER` resolve to the session's user.
- `RENAME TO`, `SET` and `RESET` get `0A000`.

**DROP USER / DROP ROLE** `[IF EXISTS] name [, ...]`

- `DROP USER` becomes `dropUser`.
- `DROP ROLE` drops the user if `getUser(name)` finds one, otherwise the role.
- With `IF EXISTS`, a missing name gets PostgreSQL's notice, `role "x" does not exist, skipping`.
- Unlike PostgreSQL, a list of names is not atomic. The names are dropped in order, and the statement stops at the
  first failure.

**GRANT / REVOKE on objects**

```
GRANT privs ON [TABLE] t [, ...]                 TO grantee [, ...] [WITH GRANT OPTION]
GRANT privs ON ALL TABLES IN SCHEMA s [, ...]    TO grantee [, ...] [WITH GRANT OPTION]
GRANT {CREATE | USAGE} ON SCHEMA s [, ...]       TO grantee [, ...]
REVOKE [GRANT OPTION FOR] privs ON ...           FROM grantee [, ...] [RESTRICT]
```

- A table name resolves through `search_path`, the same way as in DML.
- `ON ALL TABLES IN SCHEMA s` is a namespace grant. Unlike PostgreSQL, it also covers tables created later. That
  is ScalarDB's meaning, and PostgreSQL needs `ALTER DEFAULT PRIVILEGES` for the same effect.
  `LIMITATIONS.md` must say so.
- `ON SCHEMA s` maps `CREATE` to the namespace's `CREATE`. `USAGE` has no ScalarDB counterpart, since nothing
  needs it, so it is accepted with a notice and does nothing. Tools emit `GRANT USAGE ON SCHEMA` routinely.
- A grantee is a user if `getUser` finds one, and otherwise a role, which uses the `...ToRole` and `...FromRole`
  methods.
- `PUBLIC`, column lists, `REFERENCES`, `TRIGGER`, `MAINTAIN`, `CASCADE` and `GRANTED BY` get `0A000`.

**GRANT / REVOKE of roles**

```
GRANT role [, ...] TO grantee [, ...] [WITH ADMIN OPTION]
REVOKE [ADMIN OPTION FOR] role [, ...] FROM grantee [, ...]
```

These map to `grantRoleToUser` or `grantRoleToRole`, and to `revokeRoleFrom...` or
`revokeAdminOptionFrom...`. A user or role is resolved as above. `GRANT x TO y` with no `ON` is role membership,
as in PostgreSQL.

**Command tags:** as in PostgreSQL, `CREATE ROLE`, `ALTER ROLE`, `DROP ROLE`, `GRANT` and `REVOKE`. PostgreSQL
tags `CREATE USER` as `CREATE ROLE` too.

## Technical approach

**Parsing.** JSQLParser has a `Grant` statement but none for `CREATE USER`, `CREATE ROLE` or `ALTER ROLE`, and its
`Grant` lacks `ON ALL TABLES IN SCHEMA` and the role forms. The frontend intercepts these statements before
JSQLParser, as it already does for `VACUUM` and `COORDINATOR TABLES` (`QueryParser.java:2579-2666`). A leading
keyword pattern (`CREATE|ALTER|DROP (USER|ROLE)`, `GRANT`, `REVOKE`) routes the statement to a new
`engine/.../Dcl.java`. That class holds:

- a small tokenizer for identifiers, quoted identifiers, string literals, commas and keywords;
- the grammar above;
- the mapping and the ScalarDB SQL checks.

Identifiers go through `QueryParser.unquote`, so unquoted names fold to lower case as in PostgreSQL. That
matches logins: `CREATE USER Alice` creates `alice`, and psql sends `alice`. The result is
`Plan.ddl(() -> ..., tag)`. It runs like any DDL: under the session identity, inside or outside `BEGIN`, not
transactional, and clearing the plan cache afterwards (`QueryProcessor.java:906-916`).

**Without user management.** The frontend has no `AuthAdmin` behind it when `scalar.db.postgres.auth=none` or on
embedded core, and the default methods throw `UnsupportedOperationException`. `isInternal` would turn that into
"internal error" (`PostgresServer.java:1144`). Instead, the parser gets a flag next to `coordinatorDropAllowed`,
set when an `Authenticator` is configured. Without it these statements get `0A000`: "user management needs ScalarDB
Cluster with scalar.db.postgres.auth set". **(verify)** what a Cluster client reports when the node has
authentication off.

**Passwords.**

- No error message, log line or `EXPLAIN` output may contain the statement text or the literal. `Dcl`'s parse
  errors name the token kind and position, never the token.
- The statement is held only as long as any SQL is: in the extended protocol's prepared-statement map until
  closed, and in the plan cache, which DDL clears.
- psql's `\password`, and pgjdbc's `PGConnection.alterUserPassword`, send a SCRAM verifier hashed on the client
  (`SCRAM-SHA-256$...`), or for an `md5` setting, `md5` followed by 32 hex digits. Cluster needs the cleartext,
  so these get `0A000`: "pre-hashed passwords are not supported: use ALTER USER ... PASSWORD '...'". The
  session is already over TLS when authentication is on. `\password` is listed as a limitation.

**Errors.** `sqlState` maps every `DB-AUTH-` code to `42501` (`PostgresServer.java:1203`). That is already wrong
for "not found" and internal errors, and DCL makes those common. The fix splits the mapping by Cluster's
`AuthError` (`scalardb-cluster/auth/.../AuthError.java`):

| DB-AUTH | meaning | SQLSTATE |
|---|---|---|
| 10000, 10021 | user or role already exists | 42710 duplicate_object |
| 10001, 10022 | user or role not found | 42704 undefined_object |
| 10003 | namespace not found | 3F000 invalid_schema_name |
| 10004 | table not found | 42P01 undefined_table |
| 10023 | role cycle | 0LP01 invalid_grant_operation |
| 10013, 10014 | changing your own SUPERUSER, dropping yourself | 55006 object_in_use |
| 10005, 10006 | unchanged | 28P01, 28000 |
| other 1xxxx | access denied | 42501 |
| 3xxxx | internal | XX000 |

**(verify)** that the SDK carries these codes in the message for admin calls, as it did for CRUD in the
2026-10-09 run, and which exception type wraps an `IllegalArgumentException` such as "user already exists".

## Phases

1. **Users** (`Dcl.java`, the routing, the error-map split): `CREATE`, `ALTER` and `DROP` for users and roles,
   the pre-hashed password refusal, and `0A000` without auth.
2. **Privileges:** the object `GRANT`/`REVOKE` forms with the ScalarDB SQL mapping and checks, and grantees
   resolved as users or roles.
3. **Role membership:** the role `GRANT`/`REVOKE` forms.
4. **Integration and docs.**
   - Extend `difftest/cluster-auth/run.sh` against the local node. The superuser creates `carol` in psql and
     grants her `SELECT` on a table. Carol logs in and reads, and her `INSERT` gets `42501`. Her own
     `CREATE USER` gets `42501`. The superuser revokes, then a read gets `42501`. A role granted to carol carries
     a privilege. Finally the superuser drops carol and her login gets `28P01`.
   - `users.sh` stays as the admin-API fallback, and the READMEs show SQL first.
   - Update `LIMITATIONS.md` (lines 16 and 86), `getting-started/README.md:271`, `docs/auth-design.md:147` and
     the cluster-auth README.

Roughly a day and a half, most of it in phases 1 and 2.

## Tests

- **Unit (`DclTest`, with a Mockito `DistributedTransactionAdmin`):**
  - each statement form gives the expected `AuthAdmin` call;
  - every row of the mapping table, and each ScalarDB SQL check, both refused and passing;
  - the `0A000` refusals and `IF EXISTS`;
  - user versus role resolution for grantees and `DROP ROLE`;
  - quoted and folded names;
  - the SCRAM and md5 refusal;
  - a parse error on a statement holding a password does not contain the password.
- **`PostgresServerTest`:** the command tags over pgjdbc, the `DB-AUTH` to SQLSTATE table, and `0A000` with
  auth off.
- **Regression:** the six difftest sets are unchanged, since they run with auth off and none of their cases uses
  DCL. Cluster-auth stays 17/17, plus the new checks.

## Out of scope

These are follow-ups, listed in `docs/auth-design.md`:

- **The role catalog:** `\du`, `pg_roles` and `pg_user` from `getUsers` and `getRoles`, `has_table_privilege`,
  `information_schema.table_privileges` and `\dp`. Until then, `users.sh list` or a login is how to check.
- **OIDC-only users:** `createUser` with `AuthenticationMethod`s. Users created here get Cluster's default
  methods.
- `ALTER DEFAULT PRIVILEGES`, `REASSIGN OWNED` and `DROP OWNED`.

## Open questions

1. **Privilege rules.** Recommended: copy ScalarDB SQL's rules, including refusing `INSERT` without `UPDATE`.
   The alternative is to map loosely and emit a notice ("INSERT also grants UPDATE in ScalarDB"). That accepts
   more PostgreSQL scripts, but means a different meaning than in ScalarDB SQL.
2. **CREATE ROLE without LOGIN.** Recommended: make it a ScalarDB role, as above. The alternative is to always
   create a user, which is simpler but loses role grouping.
3. **Catalog.** Should `\du` be in this plan, about half a day more, or follow it? Recommended: follow it.

## Sources

- `~/Dropbox/Docs/git/scalardb/core/src/main/java/com/scalar/db/api/AuthAdmin.java`
- `scalardb-sql`: `direct-mode/src/main/antlr/SqlParser.g4:501-633`,
  `direct-mode/.../DclStatementExecutor.java:190-360`, `direct-mode/.../parser/SqlParserVisitor.java:955`
- `scalardb-cluster` `origin/main`: `auth/src/main/java/com/scalar/db/cluster/auth/AuthError.java`
- `docs/auth-design.md`, `docs/plans/2026-10-09-001-feat-cluster-authentication-plan.md`,
  `difftest/cluster-auth/README.md`
