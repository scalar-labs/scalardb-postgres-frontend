package com.scalar.db.frontend.postgres.server;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.frontend.postgres.PostgresServer;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standalone frontend: serves the PostgreSQL protocol on top of a ScalarDB transaction manager
 * created from a properties file.
 *
 * <p>Usage: {@code java -jar scalardb-postgres-frontend-<version>.jar <scalardb.properties>
 * [port]}; the port defaults to 5432. With {@code scalar.db.postgres.auth} set to {@code userpass}
 * or {@code oidc_jwt}, clients log in as ScalarDB Cluster users: the transaction manager must be
 * {@code cluster}, the Cluster client SDK and its dependencies must be on the classpath ({@code
 * java -cp frontend.jar:'sdk-classpath/lib/*' ...Main}, see difftest/cluster-auth), TLS must be
 * configured with {@code scalar.db.postgres.ssl.cert} and {@code .key} unless {@code
 * scalar.db.postgres.ssl.require=false} allows plaintext for local testing, and no {@code
 * scalar.db.username} may be configured, so that nothing can run as anyone but the session's user.
 */
public final class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  private Main() {}

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification = "The operator names the properties file on the command line")
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println(
          "Usage: java -jar scalardb-postgres-frontend.jar <scalardb.properties> [port]");
      System.exit(1);
    }
    Properties properties = new Properties();
    try (InputStream in = new FileInputStream(args[0])) {
      properties.load(in);
    }
    // ScalarDB caches table metadata for 60 s by default; a short cache lets sessions wait out DDL
    // on an existing table (see PostgresServer#configure) instead of missing it for a minute
    properties.putIfAbsent(DatabaseConfig.METADATA_CACHE_EXPIRATION_TIME_SECS, "1");
    String auth;
    try {
      auth = checkedAuth(properties);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(1);
      return;
    }
    TransactionFactory factory = TransactionFactory.create(properties);
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 5432;
    PostgresServer server =
        new PostgresServer(factory.getTransactionManager(), factory.getTransactionAdmin(), port);
    if (!auth.equals("none")) {
      server.setAuthenticator(
          new ClusterAuthenticator(factory.getTransactionAdmin(), auth.equals("oidc_jwt")));
    }
    server.configure(properties);
    logger.info(
        "Listening on port {}{}",
        server.getPort(),
        auth.equals("none") ? ", trusting every connection" : " with " + auth + " authentication");
    server.serve();
  }

  /**
   * {@code scalar.db.postgres.auth}, checked against what it needs: Cluster, TLS, and no configured
   * user, so that a call outside a session fails rather than running as that user.
   */
  static String checkedAuth(Properties properties) {
    String auth =
        properties.getProperty("scalar.db.postgres.auth", "none").trim().toLowerCase(Locale.ROOT);
    if (auth.equals("none")) {
      return auth;
    }
    if (!auth.equals("userpass") && !auth.equals("oidc_jwt")) {
      throw new IllegalArgumentException(
          "scalar.db.postgres.auth must be none, userpass or oidc_jwt, not \"" + auth + "\"");
    }
    if (!"cluster".equalsIgnoreCase(properties.getProperty("scalar.db.transaction_manager", ""))) {
      throw new IllegalArgumentException(
          "scalar.db.postgres.auth="
              + auth
              + " needs scalar.db.transaction_manager=cluster: only ScalarDB Cluster has users");
    }
    if (properties.containsKey("scalar.db.username")
        || properties.containsKey("scalar.db.password")) {
      throw new IllegalArgumentException(
          "With scalar.db.postgres.auth, leave scalar.db.username and scalar.db.password unset:"
              + " every call runs as the session's user, and a configured user is what a call"
              + " outside a session would silently run as");
    }
    if (properties.getProperty("scalar.db.postgres.ssl.cert") == null
        && !"false"
            .equalsIgnoreCase(properties.getProperty("scalar.db.postgres.ssl.require", ""))) {
      throw new IllegalArgumentException(
          "scalar.db.postgres.auth sends passwords: set scalar.db.postgres.ssl.cert and"
              + " scalar.db.postgres.ssl.key, or scalar.db.postgres.ssl.require=false for local"
              + " testing only");
    }
    return auth;
  }
}
