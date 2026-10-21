package com.scalar.db.frontend.postgres;

import com.scalar.db.util.ThrowableSupplier;
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
   * @param secret the password message's content: a password, or a token
   * @return the identity the session runs as
   * @throws AuthenticationException if the credentials are not accepted; the client gets SQLSTATE
   *     28P01 and the connection closes
   */
  Identity authenticate(String user, String secret) throws AuthenticationException;

  /**
   * What the frontend's own metadata table ({@code scalardb_postgres_frontend.column_types}) is
   * read and written as, when the implementation runs those calls itself: as the service user that
   * {@code scalar.db.postgres.metadata_user} and {@code metadata_password} name, so that no session
   * user needs privileges on the table and none can rewrite another table's declared types. Its
   * {@link Identity#run} is entered inside a session's. Null, the default, sends the service user's
   * credentials on each operation instead (the Cluster client SDK reads them).
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
