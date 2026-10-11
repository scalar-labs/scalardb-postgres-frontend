# Old passwords keep authenticating after ALTER USER and DROP USER

**Component:** ScalarDB Cluster auth, both the node (`auth`) and the Java client SDK (`client`)
**Version:** scalardb-cluster `origin/main` at 6e2cb011 (2026-09-28), node in standalone mode, `scalar.db.cluster.auth.enabled=true`, default settings otherwise
**Found by:** the ScalarDB PostgreSQL frontend's Cluster auth scenario (`difftest/cluster-auth/run.sh`), 2026-10-11

## Summary

After a user's password changes, the old password keeps authenticating through any client process that logged in with it before, for up to the token lifetime (`auth_token_expiration_time_minutes`, one day by default). The same happens after the password is removed (`alterUser(name, "")`), and after the user is dropped and re-created with the same name and a new password: the old account's password then gets into the new account.

Two pieces combine:

1. **The node never revokes a user's tokens.** `AuthService.alterUser` and `AuthService.dropUser` invalidate `userCache` (and `userRolesCache`), but leave the user's rows in `auth_tokens` and the entries in `authTokenCache`. A token row holds only the user name, so `getAuthTokenInfo` keeps resolving an old token to that name, including to a later user created with the same name.
2. **The SDK reuses a token without asking the node to check the password again.** `UserpassTokenCache` is keyed by (username, sha256(password)) and keeps each entry until the token expires. `UserpassAuthTokenManagerBase.getAuthToken` returns the cached token for a (username, old password) pair without calling `login`, so the node never sees the old password again. The SDK re-logs in only when the node rejects the token, which (1) never makes it do.

## Impact

Any client process that serves more than one end user and authenticates them with `UserpassHolder.executeWithUserpass` or the per-transaction USERPASS attributes is affected: a password change, a password removal or a user drop does not lock out someone who logged in through that process with the old password within the last token lifetime. The PostgreSQL frontend is one such process: every psql login goes through the SDK cache. An application with a single fixed credential is affected only in that a dropped user's tokens keep working.

Restarting the client process ends it (the SDK cache is in memory), which shows the node's own password check is correct: a new process gets `DB-AUTH-10005` for the old password.

## Reproduction (observed)

With a standalone node, auth enabled, and the frontend in front of it (`difftest/cluster-auth`: `node.sh`, `frontend.sh`), as the superuser:

```sql
CREATE USER carol PASSWORD 'carolpw';
```
1. `psql "user=carol password=carolpw ..." -c 'SELECT 1'` succeeds.
2. As the superuser: `ALTER USER carol PASSWORD 'carolpw2'` (the frontend calls `AuthAdmin.alterUser("carol", "carolpw2")`).
3. `psql "user=carol password=carolpw ..." -c 'SELECT 1'`.
   - **Expected:** `password authentication failed` (the node's `DB-AUTH-10005`).
   - **Actual:** `1`.
4. Restart the frontend and repeat step 3: it fails as expected, and `carolpw2` works.

The same, with `ALTER USER erin PASSWORD NULL` (`alterUser("erin", "")`) in step 2: the old password still logs in.

Drop and re-create, also observed:
1. `CREATE USER yan PASSWORD 'old1'`, then log in as yan with `old1`.
2. `DROP USER yan`. Logging in with `old1` now fails, since `getCurrentUser` finds no such user.
3. `CREATE USER yan PASSWORD 'new2'`.
4. Log in as yan with `old1`.
   - **Expected:** failure.
   - **Actual:** success, as the new yan.

## Reproduction without the frontend (sketch, not run)

```java
// admin: a DistributedTransactionAdmin from the Cluster SDK, logged in as a superuser
admin.createUser("u", "pw1");
UserpassHolder.executeWithUserpass("u", "pw1", admin::getCurrentUser); // logs in; the token is cached
admin.alterUser("u", "pw2");
UserpassHolder.executeWithUserpass("u", "pw1", admin::getCurrentUser);
// Expected: an exception carrying DB-AUTH-10005. Actual: returns user u.
```

## Code references (origin/main 6e2cb011)

- `auth/src/main/java/com/scalar/db/cluster/auth/AuthService.java`
  - `alterUser` (private overload, ends about line 593): only `userCache.invalidate(username)`.
  - `dropUser` (about line 603): deletes the user, privileges and roles, then invalidates `userCache` and `userRolesCache`. It leaves the tokens alone.
  - `login` (about line 1752): `putAuthToken(token, username, expiration)`. The row carries no user identity beyond the name, such as a creation time or a password version.
  - `getAuthTokenInfo` (about line 2243): checks only the token's expiration.
- `auth/src/main/java/com/scalar/db/cluster/auth/AuthTokensService.java`: `auth_tokens` is keyed by token only, so a user's tokens can't be found without a scan.
- `client/src/main/java/com/scalar/db/cluster/client/UserpassTokenCache.java`: `CacheKey(username, sha256(password))`, TTL = the token's remaining lifetime.
- `client/src/main/java/com/scalar/db/cluster/client/UserpassAuthTokenManagerBase.java`: `getAuthToken` returns `tokenCache.getOrLogin(...)`. It calls `login` only on a cache miss.

## Suggested fix

Server side, which also fixes the client case:
- On `alterUser` with a password change (including removal) and on `dropUser`, revoke the user's tokens. Either delete the user's `auth_tokens` rows, which needs a lookup by user name, or store a per-user credential version (or the password-change time) in the user row and the token row, and have `getAuthTokenInfo` reject a token whose version doesn't match. The version also covers drop and re-create, since a new user starts at a new version.
- Invalidate `authTokenCache` for those tokens on the node that ran the change. Other nodes keep an accepted token for up to `cache_expiration_time_millis`, which is probably acceptable if documented.

With that, the SDK needs no change: the node rejects the cached token, the SDK's reactive refresh calls `login` with the old password, and that login fails. Worth checking that the refresh path surfaces that failure as an authentication error (`DB-AUTH-10005`) and doesn't retry it.

## Workaround until fixed

Lower `scalar.db.cluster.auth.auth_token_expiration_time_minutes` on the nodes to shorten the window, or restart the client processes after a password change or a user drop.
