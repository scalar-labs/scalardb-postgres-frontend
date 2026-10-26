package com.scalar.db.frontend.postgres.server;

import com.scalar.db.api.AuthAdmin;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.cluster.client.OidcJwtAccessTokenHolder;
import com.scalar.db.cluster.client.UserpassHolder;
import com.scalar.db.frontend.postgres.Authenticator;
import com.scalar.db.util.ThrowableSupplier;
import javax.annotation.Nullable;

/**
 * Logs PostgreSQL clients in as ScalarDB Cluster users through the Cluster client SDK, for the
 * standalone server. The SDK takes the credentials of the current thread for the duration of one
 * call, logs in with them and caches the token per user, so an identity's {@link Identity#run} is
 * one such call and the frontend itself is configured with no credentials. The SDK takes one auth
 * type per client, so a server serves one kind of secret, the one {@code scalar.db.postgres.auth}
 * names and the engine then forces on every connection. A node hosting the engine implements {@link
 * Authenticator} over its own auth service instead, and can take both kinds, per connection.
 */
final class ClusterAuthenticator implements Authenticator {
  private final DistributedTransactionAdmin admin;

  ClusterAuthenticator(DistributedTransactionAdmin admin) {
    this.admin = admin;
  }

  @Override
  public Identity authenticate(String user, String secret, Secret kind)
      throws AuthenticationException {
    boolean oidc = kind == Secret.OIDC_TOKEN; // the SDK's holder of that kind
    AuthAdmin.User me;
    try {
      // one round trip, which makes the SDK log in: the answer is who Cluster says we are
      me =
          oidc
              ? OidcJwtAccessTokenHolder.executeWithToken(secret, admin::getCurrentUser)
              : UserpassHolder.executeWithUserpass(user, secret, admin::getCurrentUser);
    } catch (Exception e) {
      String message = String.valueOf(e.getMessage());
      if (message.contains("DB-AUTH-10005") || message.contains("DB-AUTH-10006")) {
        throw new AuthenticationException(message);
      }
      throw new IllegalStateException("Cluster did not answer the login: " + message, e);
    }
    return new ClusterIdentity(me.getName(), me.isSuperuser(), user, secret, oidc);
  }

  private static final class ClusterIdentity implements Identity {
    private final String name;
    private final boolean superuser;
    private final String user;
    private final boolean oidc;
    @Nullable private volatile String secret; // gone once the session closes

    ClusterIdentity(String name, boolean superuser, String user, String secret, boolean oidc) {
      this.name = name;
      this.superuser = superuser;
      this.user = user;
      this.secret = secret;
      this.oidc = oidc;
    }

    @Override
    public String user() {
      return name;
    }

    @Override
    public boolean superuser() {
      return superuser;
    }

    @Override
    public <R, T extends Throwable> R run(ThrowableSupplier<R, T> call) throws T {
      String current = secret;
      if (current == null) {
        throw new IllegalStateException("The session has ended");
      }
      return oidc
          ? OidcJwtAccessTokenHolder.executeWithToken(current, call)
          : UserpassHolder.executeWithUserpass(user, current, call);
    }

    @Override
    public void close() {
      secret = null;
    }
  }
}
