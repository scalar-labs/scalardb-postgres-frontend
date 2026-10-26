package com.scalar.db.frontend.postgres;

import com.scalar.db.util.ThrowableSupplier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.annotation.Nullable;

/**
 * Verifies a client's credentials at connection startup and hands back the identity its session
 * then runs as. The frontend has no user store of its own: an implementation checks the user and
 * secret (a password, or an OIDC token sent as the password) with ScalarDB Cluster, and {@link
 * Identity#run} makes every ScalarDB call inside it the user's, so Cluster enforces the user's
 * privileges. The standalone server implements it over the Cluster client SDK; a Cluster node
 * hosting the engine implements it over its own auth service.
 */
public interface Authenticator {
  /**
   * Verifies the credentials the client sent.
   *
   * @param user the user name from the startup message
   * @param secret the password message's content: a password, or an OIDC access token
   * @param kind what {@code secret} is, as the server decided: the kind {@code
   *     scalar.db.postgres.auth} forces, else the kind the client stated with the {@code
   *     scalardb.auth} startup option, else the secret's shape ({@link Secret#of})
   * @return the identity the session runs as
   * @throws AuthenticationException if the credentials are not accepted; the client gets SQLSTATE
   *     28P01 and the connection closes
   */
  Identity authenticate(String user, String secret, Secret kind) throws AuthenticationException;

  /** What a client sent in the password message. */
  enum Secret {
    /** The ScalarDB Cluster user's password. */
    PASSWORD,
    /** An OIDC access token, from which Cluster takes the user. */
    OIDC_TOKEN;

    /**
     * The kind of {@code secret} by its shape. An OIDC access token is a JWT: three base64url
     * segments joined by dots, the first of which decodes to a JSON object with an {@code alg}
     * member. No password a person sets looks like that, so such a secret is a token and anything
     * else a password. The shape decides alone: a secret is checked as one kind and never retried
     * as the other, so a wrong secret still gets one check.
     *
     * @param secret what the client sent
     * @return its kind
     */
    public static Secret of(String secret) {
      int first = secret.indexOf('.');
      int second = first < 0 ? -1 : secret.indexOf('.', first + 1);
      if (first <= 0
          || second <= first + 1
          || second == secret.length() - 1
          || secret.indexOf('.', second + 1) >= 0) {
        return PASSWORD;
      }
      for (int i = 0; i < secret.length(); i++) {
        char c = secret.charAt(i);
        boolean base64url =
            (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '_'
                || c == '.';
        if (!base64url) {
          return PASSWORD;
        }
      }
      String header;
      try {
        header =
            new String(
                    Base64.getUrlDecoder().decode(secret.substring(0, first)),
                    StandardCharsets.UTF_8)
                .trim();
      } catch (IllegalArgumentException e) {
        return PASSWORD;
      }
      return header.startsWith("{") && header.endsWith("}") && header.contains("\"alg\"")
          ? OIDC_TOKEN
          : PASSWORD;
    }
  }

  /**
   * What the frontend's own metadata table ({@code scalardb_postgres_frontend.column_types}) is
   * read and written as, when the host runs those calls under an authority of its own: a Cluster
   * node's privileged mode, which passes the calls without any user's grant. No session user then
   * needs privileges on the table, none can rewrite another table's declared types, and no service
   * user or password is configured. Its {@link Identity#run} is entered inside a session's, on the
   * session's thread, and the engine enters it only around its own operations on that table. Null,
   * the default, sends the credentials of the service user that {@code
   * scalar.db.postgres.metadata_user} and {@code metadata_password} name on each operation instead
   * (the Cluster client SDK reads them), as the standalone server does.
   *
   * @return the frontend's own identity, or null
   */
  @Nullable default Identity frontend() {
    return null;
  }

  /** What a session runs as. Credentials live only here and go away with {@link #close}. */
  interface Identity extends AutoCloseable {
    /** The canonical user name, for {@code current_user}. */
    String user();

    /** Whether the user is a superuser, for the {@code is_superuser} session parameter. */
    boolean superuser();

    /**
     * Runs {@code call} as this user on the current thread. Every ScalarDB call a session makes
     * happens inside one: the server enters it once per protocol message on the connection thread,
     * and an operator that reads from another thread enters it there. A session's calls are never
     * nested on one thread; only the {@link #frontend} identity's are, inside a session's.
     *
     * @param call what to run
     * @param <R> the result type
     * @param <T> what the call throws
     * @return the call's result
     * @throws T if the call throws
     */
    <R, T extends Throwable> R run(ThrowableSupplier<R, T> call) throws T;

    @Override
    void close();
  }

  /** The credentials were not accepted. */
  final class AuthenticationException extends Exception {
    private static final long serialVersionUID = 1L;

    public AuthenticationException(String message) {
      super(message);
    }
  }
}
