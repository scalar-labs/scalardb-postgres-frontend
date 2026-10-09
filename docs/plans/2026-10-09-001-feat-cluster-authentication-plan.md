---
title: "feat: Authenticate PostgreSQL clients through ScalarDB Cluster"
type: feat
status: active
date: 2026-10-09
---

# feat: Authenticate PostgreSQL clients through ScalarDB Cluster

## Overview

Let psql, pgjdbc and every other PostgreSQL client log in to the frontend as a ScalarDB Cluster user, over
TLS, and have Cluster's privileges apply to everything the session runs. The work lands on a branch named
`auth`: the frontend side is complete and tested against a stub and, if a node starts locally, against a real
standalone Cluster node; the Cluster side keeps working as is, and gains a second implementation of one small
interface when the frontend is later embedded in a node (see `docs/integration-design.md`).

The design is `docs/auth-design.md` (draft of 2026-10-07). This plan carries it into files, with two
decisions taken after reading the Cluster code, since a Cluster node is to host the frontend before release:
the session's identity is thread-scoped on every path, which is the only mechanism a node offers, with the
engine carrying it across its own lookup pool, instead of the design's operation attributes, which only the
client SDK understands; and the engine does not depend on the Cluster client SDK, so an `Authenticator`
interface lives in the engine, the SDK-backed implementation in the server module as a `compileOnly` bridge
for standalone runs, and the node's implementation arrives with the embedding.

## Problem Statement

`PostgresServer.startup` answers every StartupMessage with `AuthenticationOk` (`PostgresServer.java:204`),
answers `SSLRequest` with `N` (`:184`), and runs every statement with the single identity from
`scalardb.properties`. Anyone who can reach the port can read and write every namespace, and `current_user`
echoes whatever the client sent. Cluster already has users, roles, privileges and two login methods
(userpass and OIDC); the frontend bypasses all of it.

## Proposed Solution

1. **TLS.** `SSLRequest` is answered with `S` and the socket upgraded when a certificate and key are
   configured; plaintext password connections are refused unless explicitly allowed.
2. **Cleartext password over TLS.** After the StartupMessage the frontend sends
   `AuthenticationCleartextPassword`, reads the `PasswordMessage`, and verifies the pair with Cluster. A
   wrong password gets `28P01` after a one-second delay and the connection closes. OIDC is the same handshake
   with the token as the password.
3. **The identity on every call.** Each message runs inside the identity's scope, `Identity.run(...)`, on
   the connection thread, and the lookup join re-enters that scope on its pool threads, so transactions,
   one-shot operations, planning's metadata reads and DDL are all the user's. With authentication on, the
   frontend is configured without service credentials, so a call outside the scope fails instead of running
   as someone else. Cluster enforces privileges; the frontend maps `DB-AUTH` failures to `28P01`, `28000`
   and `42501`.
4. **One interface, two homes.** `Authenticator` and `Authenticator.Identity` in the engine; the Cluster
   client SDK implementation (`UserpassHolder`, `OidcJwtAccessTokenHolder`) in `server/`, compiled against
   the SDK but not bundled. A node embedding the engine implements the same interface over
   `AuthService.login` and `AuthExecutionUtils.setAuthTokenAndExecute`, which is the "Cluster updated later"
   part and is out of this plan's scope.

## Technical Approach

### Architecture

```
client ──TLS──▶ PostgresServer.Connection (thread per connection)
                 │ startup: SSLRequest→S, R(3), p, authenticator.authenticate(user, secret) → Identity
                 │ serve:   identity.run(() -> dispatch(message))        ← one scope per message, every path
                 ▼
               QueryProcessor / QueryParser.Plan                        all on the connection thread
                 ├─ manager.begin(), plan.open(manager), admin.*        ← inside the scope, unchanged code
                 └─ Operators.LookupJoin ──pool──▶ identity.run(() -> manager.get(...))  ← re-entered
                 ▼
               standalone: Cluster client SDK, UserpassHolder resolved per call ──gRPC──▶ node
               embedded:   the node's AuthDistributedTransactionManager / Admin via AuthExecutionUtils
```

| Piece | Module | File | Role |
|---|---|---|---|
| `Authenticator`, `Authenticator.Identity`, `Authenticator.AuthenticationException` | engine | `engine/.../Authenticator.java` (new) | the SPI: verify credentials, hand back the identity |
| TLS, handshake, FATAL errors, redaction, DB-AUTH SQLSTATEs | engine | `PostgresServer.java` | wire protocol |
| identity scope on every message, pool hand-off, refuse `SET ROLE` | engine | `PostgresServer.java`, `QueryProcessor.java`, `Operators.java` | statement execution |
| `ClusterAuthenticator` (userpass, oidc_jwt) | server | `server/.../ClusterAuthenticator.java` (new) | SDK-backed implementation |
| property parsing, wiring, refusal to start misconfigured | server | `Main.java` | configuration |
| SDK dependency, `compileOnly` | server | `server/build.gradle.kts`, `gradle/libs.versions.toml` | `scalardb-cluster-java-client-sdk` 4.0.0-SNAPSHOT, on the classpath at run time |
| tests | engine, server | `PostgresServerTest.java`, `QueryProcessorTest.java`, `src/test/resources/tls/` | stub authenticator over pgjdbc |
| integration | difftest | `difftest/cluster-auth/` (new) | a standalone node on the local PostgreSQL |

### The SPI

```java
// engine/src/main/java/com/scalar/db/frontend/postgres/Authenticator.java
public interface Authenticator {
  /** Verifies the credentials the client sent; the secret is a password or an OIDC token. */
  Identity authenticate(String user, String secret) throws AuthenticationException;

  /** What a session runs as. Credentials live only here and go away with close(). */
  interface Identity extends AutoCloseable {
    String user();                                  // canonical name, for current_user
    boolean superuser();                            // the is_superuser ParameterStatus
    <R, T extends Throwable> R run(ThrowableSupplier<R, T> call) throws T;  // every ScalarDB call runs in here
    @Override void close();
  }

  final class AuthenticationException extends Exception { ... }   // startup only: 28P01
}
```

`ThrowableSupplier` is ScalarDB's `com.scalar.db.util.ThrowableSupplier`, already on the engine's classpath
and the same type `AuthExecutionUtils` takes, so the node-side implementation is a direct fit.

### The server-side implementation

```java
// server/src/main/java/com/scalar/db/frontend/postgres/server/ClusterAuthenticator.java
final class ClusterAuthenticator implements Authenticator {
  public Identity authenticate(String user, String secret) throws AuthenticationException {
    AuthAdmin.User me;
    try {
      me = run(user, secret, () -> admin.getCurrentUser());   // one round trip, forces the login
    } catch (Exception e) {
      if (message(e).contains("DB-AUTH-10005") || contains("DB-AUTH-10006")) throw new AuthenticationException();
      throw e;
    }
    return new Identity(me.getName(), me.isSuperuser(), call -> run(user, secret, call));
  }
  // run(): UserpassHolder.executeWithUserpass(user, secret, call) or OidcJwtAccessTokenHolder.executeWithToken(secret, call)
}
```

The SDK resolves the holder on every call, ahead of the configured user
(`UserpassAuthTokenManagerBase.java:37-46`, `UserpassAuthTokenManager.java:30`), caches the token per
credential pair and re-logs in before it expires, so one scope per message is enough. The engine needs
nothing from the SDK; only the holders do, and they live here.

### Carrying the identity across the lookup pool

`Operators.LookupJoin` issues one-shot reads from a cached thread pool (`Operators.java:1323`), where the
connection thread's scope does not exist. The join takes the session's `Identity` from the plan's execution
context when it builds its tasks and runs each as `identity.run(() -> manager.get(...))`, so the pool thread
enters the same scope. The engine never touches a `ThreadLocal` itself; only the SDK and the node do, behind
`run`. A test asserts that reads issued on pool threads carry the user. Any executor added to the engine later
must make the same hand-off; without it a call fails closed (below) rather than running as someone else.

### TLS

`PostgresServer.configure` reads `scalar.db.postgres.ssl.cert` and `scalar.db.postgres.ssl.key` (PEM: a
certificate chain and an unencrypted PKCS#8 key, RSA or EC), builds an in-memory PKCS12 `KeyStore` and an
`SSLContext`. `startup` answers `SSLRequest` with `S`, wraps the socket with
`sslContext.getSocketFactory().createSocket(socket, host, port, true)` in server mode, and reopens `in`/`out`
on it. A PKCS#1 (`BEGIN RSA PRIVATE KEY`) or encrypted key fails at start with the `openssl pkcs8 -topk8
-nocrypt` command in the message. `scalar.db.postgres.ssl.require` (default true when auth is on) makes a
plaintext StartupMessage fail with FATAL `28000` before any password is asked. PostgreSQL 17's direct TLS
(`sslnegotiation=direct`) is out of scope.

### Startup handshake

In `Connection.startup`, after the parameters are read and when an authenticator is set: send `R` with code
3; read one message, which must be `p` with the NUL-terminated password as its body (anything else: FATAL
`08P01` protocol violation); call
`authenticator.authenticate(user, secret)`; on `AuthenticationException` sleep one second, send FATAL `28P01`
`password authentication failed for user "<user>"`, close; on success continue as today with `is_superuser`
and `session_authorization` from the identity, and `processor.setIdentity(identity)`. In `oidc_jwt` mode the
StartupMessage's `user` is informational: the token decides, and the identity's name comes from
`getCurrentUser`. Startup errors need severity FATAL, which `sendError` does not produce today: add a severity
parameter.

### Statement execution

When an identity exists, `serve` wraps its whole per-message dispatch in `identity.run(...)`: the simple-query
path and every extended-protocol message (`Parse`, `Bind`, `Describe`, `Execute`, `Sync`), because `Parse` and
`Describe` plan and therefore read metadata; the disconnect `ROLLBACK` in `run()`'s `finally` is wrapped the
same way. One wrap in one place, so no entry point can run as the service identity. Inside it, planning's
`admin.getTableMetadata` calls, the catalog's `getNamespaceTableNames`, and `plan.getDdl().run()`
(`QueryProcessor.java:718`) are the user's, and so are `begin()` at `:735` and `:900` and the one-shot
`plan.open(manager)` at `:582` and `:757`, none of which change. On the node, metadata reads need only a
logged-in caller (`AuthDistributedTransactionAdmin.java:214`, `checkIfLoggedIn`), so the catalog keeps listing
every table; filtering by privilege stays a later step as the design says. With no authenticator there is no
scope and nothing changes, so the mocked tests are untouched.

**Fail closed.** With authentication on, `Main` refuses a properties file that sets `scalar.db.username` or
`scalar.db.password`: nothing in the frontend needs a service identity once every call runs as the session's
user, and without one the SDK throws on a call made outside a scope (`UserpassAuthTokenManager.java:30-37`)
instead of falling back to the configured user. The node behaves the same through `checkIfLoggedIn`.

`SET ROLE`, `SET SESSION AUTHORIZATION` and `RESET SESSION AUTHORIZATION` are refused with `0A000` when an
identity exists: today they are recorded as plain settings, which would let `session_authorization` lie.

### Errors

In `PostgresServer.sqlState`, before the generic rules: `DB-AUTH-10005` → `28P01`; `DB-AUTH-10006` → `28000`
and `serve` closes the connection after sending it (the token died mid-session; pools reconnect); any other
`DB-AUTH-100` → `42501`. The SDK's exception types for UNAUTHENTICATED and PERMISSION_DENIED are not known
until phase 5; keying on the code in the message is what the design asks for and survives either way.

**Secrets in messages.** Credentials never enter an operation or a transaction, only the holder's
thread-local, so ScalarDB's own messages, which print whole operations, cannot echo them. The wrong-password
test still asserts that neither the error sent to the client nor the log line contains the password.

### Configuration

| Property | Values | Effect |
|---|---|---|
| `scalar.db.postgres.auth` | `none` (default), `userpass`, `oidc_jwt` | which authenticator `Main` installs; anything but `none` requires `scalar.db.transaction_manager=cluster`, else the server refuses to start |
| `scalar.db.postgres.ssl.cert`, `scalar.db.postgres.ssl.key` | PEM paths | enables TLS |
| `scalar.db.postgres.ssl.require` | boolean, default `true` when auth is not `none` | refuse plaintext connections |

`Main` validates the combination and fails fast with one message naming the missing or offending piece: an
`auth` other than `none` needs `scalar.db.transaction_manager=cluster`, a certificate unless
`ssl.require=false`, and no `scalar.db.username` or `scalar.db.password`.

### Implementation Phases

#### Phase 1: SPI, handshake, errors (engine)

- `Authenticator.java`; `PostgresServer.setAuthenticator`; the password handshake; FATAL severity;
  the one-second delay; `is_superuser` and `session_authorization` from the identity; `current_user` from
  `processor.setUser(identity.user())`.
- `sqlState` DB-AUTH mapping; `28000` closes the session.
- Tests in `PostgresServerTest` with a stub authenticator: wrong password → `PSQLException` 28P01 and the
  connection is closed; right password → queries run, `current_user` is the canonical name, `is_superuser`
  matches; the wrong-password error and log line contain no password; the three SQLSTATEs.
- Success: all of the above green; existing tests unchanged (no authenticator means today's path).
- Effort: half a day.

#### Phase 2: TLS (engine)

- PEM loading into an `SSLContext`; `SSLRequest` → `S` and the socket upgrade; `ssl.require`.
- `engine/src/test/resources/tls/server.crt` and `server.key`, a self-signed pair generated once with
  `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -subj /CN=localhost`
  and the key converted to PKCS#8.
- Tests: pgjdbc with `ssl=true&sslmode=require&sslfactory=org.postgresql.ssl.NonValidatingFactory` connects
  and queries; with `ssl.require` a `sslmode=disable` connection gets 28000; a PKCS#1 key fails at start with
  the conversion hint; psql by hand with `sslmode=require`.
- Success: tests green; the handshake logs nothing about keys.
- Effort: half a day.

#### Phase 3: The identity on every call (engine)

- The scope: one `identity.run(...)` around the message dispatch in `serve` and around the disconnect
  rollback; the lookup join's hand-off through the plan context; `Main`'s refusal of service credentials in
  auth mode; refuse `SET ROLE` / `SET SESSION AUTHORIZATION`.
- Tests in `PostgresServerTest` and `QueryProcessorTest` with a stub identity that records the threads it was
  entered on: BEGIN, a read-then-write statement, a one-shot read and write, `Parse` and `Describe` in the
  extended protocol, and DDL all run inside the scope on the connection thread; a lookup join in autocommit
  (two tables, the inner by key) enters it on the pool threads; with the stub set to throw outside its scope,
  nothing in the test suite trips it; `SET ROLE` is 0A000 with an identity and accepted without one.
- Success: tests green; `./gradlew check` clean; `bench/check_ops.py` unchanged.
- Effort: half a day.

#### Phase 4: Server wiring, SDK, docs

- `libs.versions.toml` and `server/build.gradle.kts`: `compileOnly` on the SDK. The fat jar stays as it is;
  cluster mode runs with the SDK on the classpath, as it must today:
  `java -cp scalardb-postgres-frontend.jar:scalardb-cluster-java-client-sdk.jar com.scalar.db.frontend.postgres.server.Main scalardb.properties 5432`.
  `ClusterAuthenticator` for userpass and oidc_jwt. `Main`: properties, validation, wiring.
- Docs: README status line; `LIMITATIONS.md` loses "no authentication or TLS" and gains what remains
  (catalog not filtered by privilege, OIDC sessions outlive their token, no SCRAM, no direct TLS); the
  getting-started guide gets a "Connect as a user" section with the psql and pgjdbc examples from the design
  and the cluster-mode run command; `docs/auth-design.md` status becomes "implemented on branch auth" with the
  two decisions noted.
- Success: `auth=userpass` with no certificate, or with a service user configured, refuses to start with one
  clear message; with `auth=none` the difftest sets run on their baselines.
- Effort: half a day.

#### Phase 5: Integration against a standalone node

- Publish the SDK from the Cluster checkout first (`./gradlew :client:publishToMavenLocal`) so the client the
  frontend links against and the node are the same commit, then build the node (`./gradlew :node:installDist`,
  no `-PenableLicensing`) and run it on
  the existing PostgreSQL at 15440 with `scalar.db.cluster.node.standalone_mode.enabled=true`,
  `scalar.db.cluster.auth.enabled=true`, `scalar.db.cluster.node.port=60053`, Prometheus port `-1`, as
  `StandaloneClusterNodeEnv.java:84-109` does. The frontend's properties: `scalar.db.transaction_manager=cluster`,
  `scalar.db.contact_points=indirect:localhost`, `scalar.db.contact_port=60053`, no service credentials,
  `scalar.db.postgres.auth=userpass`, the test certificate.
- A script `difftest/cluster-auth/run.sh`: as the default superuser create `alice` and `bob`, a namespace and
  a table, grant `alice` READ and WRITE and `bob` nothing; then through psql: alice logs in and reads and
  writes; bob logs in and gets 42501 on SELECT and on INSERT; a wrong password is 28P01 with a one-second
  delay; bob's `CREATE TABLE` is 42501; two sessions at once see their own privileges; a lookup join in
  autocommit as alice; `current_user` is alice; `\conninfo` shows TLS.
- The design's **(verify)** items are closed here: `getCurrentUser` under the holder as the login check,
  the holder honored on `begin` and on every one-shot call, `getScanner` and `mutate` lists included, the SDK's
  exception types for DB-AUTH codes.
- If the node will not start without a license, `difftest/cluster-auth/README.md` records the steps and
  what was and was not verified, and the branch stays "workable" on the stub tests.
- Outcome 2026-10-09: the SDK built from an export of Cluster main; the node did not, for want of
  `scalardb-graphql` and `scalardb-sql-direct-mode` snapshots that exist nowhere reachable (details in the
  kit's README). The kit, the properties, `Setup.java` and `run.sh` are ready for a machine that has a node.
- Effort: half a day to a day, most of it the node setup.

## Alternative Approaches Considered

- **Operation attributes** (the design's mechanism, and this plan's first draft). The SDK logs in per
  transaction or operation from `auth-*` attributes and strips them before the node. Safe across thread hops
  by construction, but only the SDK understands the keys: a node hosting the engine would ignore them and keep
  the password in the operation's attributes, so the embedding would need the thread-scoped path anyway,
  leaving two mechanisms. Rejected in favor of the one path the node already has, with the pool hand-off and
  the fail-closed configuration covering what attributes gave for free.
- **A `TransactionFactory` per session**, logged in as the user. No holder, but a gRPC channel and token
  manager per PostgreSQL session, and connection pools open dozens. Rejected; the SDK's per-call holder
  exists for exactly this, and the node has nothing equivalent.
- **Autocommit statements inside an explicit transaction** (the design's option b). Loses the one-operation
  fast path the benchmarks measure. Rejected, as the design recommends.
- **SCRAM-SHA-256.** Never shows the server the password, so Cluster could not check it. Cleartext over TLS
  is what PostgreSQL itself does for LDAP, PAM and RADIUS. Rejected for now; a verifier store on the node
  would be a Cluster feature.
- **The engine depending on the SDK.** Would tie the OSS engine to a proprietary jar and get in the way of
  the node embedding, which has the real `AuthService` at hand. Rejected: the SPI costs one interface.

## System-Wide Impact

### Interaction Graph

Connection thread: StartupMessage → `authenticate` → `UserpassHolder` → `admin.getCurrentUser()` → SDK
`UserpassAuthTokenManager` → node `AuthService.login` → token cached in the SDK's `UserpassTokenCache`
(shared by sessions with the same credentials, refreshed ahead of expiry). Each message: `identity.run` →
plan (metadata through the admin as the user) → `begin()` and the operations, the SDK resolving the holder
on each call and attaching the token → node `AuthDistributedTransactionManager` reads it from its
thread-local and checks the privilege per operation. Lookup join: the task re-enters `identity.run` on the
pool thread, then the same chain. Embedded: `run` is `AuthExecutionUtils.setAuthTokenAndExecute`, and the SDK
and gRPC drop out of the chain.

### Error & Failure Propagation

Node `AuthError` → gRPC `UNAUTHENTICATED` / `PERMISSION_DENIED` (`ProtoError.java:47-52`) → SDK exception
with the `DB-AUTH-` code in its message (type to confirm in phase 5) → `sqlState` → `28P01` at login,
`28000` mid-session with the connection closed, `42501` for privileges. Statement failures inside a
transaction abort it as any error does today (`25P02` afterwards). `TransactionException` from `begin` with
`DB-AUTH-10006` reaches the client once and the session ends; a pool reconnects and logs in afresh.

### State Lifecycle Risks

The identity and its secret live in the `Connection` for the session and are dropped in `run()`'s `finally`
with `identity.close()`; a Java `String` cannot be zeroed, which the design accepts. Credentials never enter
an operation or a transaction. A failed login creates no state. The SDK's token cache outlives sessions by design. DDL is not transactional today and stays so.

### API Surface Parity

Simple and extended protocol both pass through `serve`, so one wrap covers both. `CancelRequest` is ignored
today and must stay ignored until the key is validated (design's security note). `current_user`,
`session_user`, `current_role` (`Evaluator.java:1092`) read the processor's user, which becomes the canonical
name. `pg_roles` and `\du` are unchanged and may list nothing; filtering the catalog is later.

### Integration Test Scenarios

1. Two sessions, alice and bob, interleaved on one frontend: each statement runs under its own identity,
   including a lookup join in autocommit whose inner reads run on the pool.
2. bob's `SELECT` on a table he cannot read: `42501`, and the session is still usable afterwards.
3. alice's transaction begun, then bob logs in and out: alice's `COMMIT` still carries alice's token.
4. A wrong password: `28P01`, one-second delay, connection closed, nothing logged but the user name.
5. `auth=none`: difftest, the concurrency harness and the benchmarks run exactly as before.

## Acceptance Criteria

### Functional

- [ ] With `auth=userpass`, psql and pgjdbc log in with a Cluster user and password over TLS; a wrong password
      is `28P01` after about one second and the connection closes.
- [ ] With `auth=oidc_jwt`, a token given as the password logs in; an expired token mid-session is `28000` and
      the session closes.
- [ ] Every ScalarDB call of a session runs as its user: transactions, autocommit reads and writes including
      the lookup join's pool reads, DDL and metadata reads.
- [ ] A missing privilege is `42501`; the session continues.
- [ ] `current_user`, `session_authorization` and `is_superuser` report the authenticated identity;
      `SET ROLE` and `SET SESSION AUTHORIZATION` are refused.
- [ ] `SSLRequest` is honored when a certificate is configured; plaintext is refused with `28000` unless
      `ssl.require=false`.
- [ ] With authentication on, the frontend refuses to start with service credentials configured, and a call
      outside the identity scope fails rather than running as another user.
- [ ] `auth=none` behaves exactly as today.

### Non-functional

- [ ] No credential appears in any log line, error message or EXPLAIN output; credentials never enter an
      operation.
- [ ] The engine module has no dependency on the Cluster SDK; the server module compiles against it and does
      not bundle it.
- [ ] The autocommit fast path keeps issuing one ScalarDB operation per point read (`bench/check_ops.py`).

### Quality gates

- [ ] `./gradlew check` clean (tests, Spotless, SpotBugs with FindSecBugs, Error Prone, NullAway).
- [ ] Difftest sets on their baselines with `auth=none`.
- [ ] `difftest/cluster-auth/run.sh` passes against a local node, or its README states what could not run.
- [ ] README, `LIMITATIONS.md`, getting-started and `docs/auth-design.md` updated.

## Dependencies & Risks

- **SDK on the classpath.** `compileOnly` means `java -jar` alone cannot run cluster mode; the docs give the
  `-cp` command, which cluster mode needs today anyway. The SDK is in the local Maven repository only, like
  core 4.0.0-SNAPSHOT.
- **Unverified Cluster behavior** (the design's "verify" marks): `getCurrentUser` forcing a login under the
  holder; the holder honored on `begin` and on every one-shot call, `getScanner`, `mutate` lists and `batch`
  included; the SDK's exception types. Phase 5
  settles them; until then the stub tests pin the frontend's side of the contract.
- **A node without a license.** `enableLicensing` is an optional Gradle property and licensing is referenced
  from one node class; if the node refuses to start anyway, phase 5 becomes a documented manual procedure.
- **A future executor in the engine** without the pool hand-off fails closed rather than leaking, but it
  fails; the hand-off is documented at the lookup join, and the stub-that-throws test catches a missed path.
- **`sslmode=prefer`** (psql's default) retries without TLS when `S` is refused; with `ssl.require` that retry
  gets a clear `28000`, not a silent plaintext login.
- **libpq `require_auth=scram-sha-256`** refuses a cleartext request; documented, as in the design.
- **Passwords as `String`.** Accepted by the design; noted in `LIMITATIONS.md`.

## Documentation Plan

- `README.md`: the status line drops "no authentication or TLS" and names the properties.
- `LIMITATIONS.md`: replace the auth bullet with what remains: cleartext over TLS only, catalog not filtered
  by privilege, OIDC sessions outlive their token, no `CancelRequest`, no `CREATE USER`/`GRANT` in SQL yet.
- `getting-started/README.md`: "Connect as a user" with the psql and pgjdbc snippets from the design, and the
  cluster-mode run command with the SDK on the classpath.
- `docs/auth-design.md`: status, the two decisions, and the verified items from phase 5.
- `difftest/cluster-auth/README.md`: how to run the node and the script.

## Sources & References

### Internal

- Design: `docs/auth-design.md`; node embedding: `docs/integration-design.md` (sections "Authentication",
  "Authorization", open question 4).
- Frontend touch points: `PostgresServer.java:179-245` (startup), `:528` (`sendError`), `:557` (`sqlState`);
  `QueryProcessor.java:582`, `:757` (`plan.open(manager)`), `:718` (DDL), `:735` and `:900` (begin);
  `Operators.java:1323` (lookup pool);
  `Evaluator.java:1092` (`current_user`); `Main.java`.
- Core API: `AuthAdmin.java:190` (`getCurrentUser`), `:493-506` (`User`); `ThrowableSupplier`.
- Cluster: `client/.../UserpassHolder.java`, `OidcJwtAccessTokenHolder.java`,
  `UserpassAuthTokenManager.java:30-37` (holder before the configured user, else it throws),
  `UserpassAuthTokenManagerBase.java:37-46` (resolved per call), `ClusterClientConfig.java:157` (configured
  user), `auth/.../AuthDistributedTransactionManager.java:44-62` and `:226-241` (thread-local token for begin
  and one-shot operations),
  `auth/.../AuthError.java:16-17` (10005, 10006), `AuthDistributedTransactionAdmin.java:214` (metadata needs
  login only), `common/.../ProtoError.java:47-52`, `integration-test/.../StandaloneClusterNodeEnv.java:84-182`
  (node properties), `node/build.gradle:90` (`ClusterNodeServer` main).

### External

- PostgreSQL frontend/backend protocol, message formats: https://www.postgresql.org/docs/current/protocol-message-formats.html
  (`AuthenticationCleartextPassword`, `PasswordMessage`, `SSLRequest`, `ErrorResponse` fields).
- libpq connection parameters (`sslmode`, `require_auth`): https://www.postgresql.org/docs/current/libpq-connect.html
- pgjdbc SSL: https://jdbc.postgresql.org/documentation/ssl/
