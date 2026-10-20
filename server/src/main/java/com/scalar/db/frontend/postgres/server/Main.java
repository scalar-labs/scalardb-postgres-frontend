package com.scalar.db.frontend.postgres.server;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.config.ConfigUtils;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.frontend.postgres.PostgresServer;
import com.scalar.db.frontend.postgres.Timing;
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
    resolvePlaceholders(properties);
    // ScalarDB fetches 10 rows per round trip by default: under network latency, a scan of a few
    // hundred rows then costs tens of round trips (bench/README.md); 1000 rows is still small
    properties.putIfAbsent(DatabaseConfig.SCAN_FETCH_SIZE, "1000");
    String auth;
    try {
      auth = checkedAuth(properties);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(1);
      return;
    }
    try {
      checkBackendCollation(properties);
    } catch (IllegalStateException e) {
      System.err.println(e.getMessage());
      System.exit(1);
      return;
    }
    TransactionFactory factory = TransactionFactory.create(properties);
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 5432;
    // scalar.db.postgres.listen_address: one interface, such as 127.0.0.1; all of them when unset
    String listen = properties.getProperty("scalar.db.postgres.listen_address");
    java.net.InetAddress address =
        listen == null ? null : java.net.InetAddress.getByName(listen.trim());
    DistributedTransactionManager manager = factory.getTransactionManager();
    DistributedTransactionAdmin admin = factory.getTransactionAdmin();
    // scalar.db.postgres.timing_log_interval: seconds between logs of the time spent in ScalarDB
    // versus in the frontend, for benchmarks; 0 (the default) is off
    long timing =
        Long.parseLong(
            properties.getProperty("scalar.db.postgres.timing_log_interval", "0").trim());
    if (timing > 0) {
      manager = Timing.wrap(DistributedTransactionManager.class, manager);
      admin = Timing.wrap(DistributedTransactionAdmin.class, admin);
      Timing.start(timing);
    }
    PostgresServer server = new PostgresServer(manager, admin, port, address);
    if (!auth.equals("none")) {
      server.setAuthenticator(
          new ClusterAuthenticator(factory.getTransactionAdmin(), auth.equals("oidc_jwt")));
    }
    server.configure(properties);
    if (auth.equals("none") && (address == null || !address.isLoopbackAddress())) {
      logger.warn(
          "No authentication, and listening on {}: anyone who can reach port {} has full access as"
              + " the configured ScalarDB user. Set scalar.db.postgres.listen_address=127.0.0.1 or"
              + " turn on scalar.db.postgres.auth",
          address == null ? "every interface" : address.getHostAddress(),
          server.getPort());
    }
    logger.info(
        "Listening on port {}{}",
        server.getPort(),
        auth.equals("none") ? ", trusting every connection" : " with " + auth + " authentication");
    server.serve();
  }

  /**
   * Refuses to start on a backing database whose text collation is not bytewise, as ScalarDB's
   * default BINARY collation is: what is pushed down would compare text one way and what the
   * frontend evaluates another, so one query could answer differently depending on its plan.
   * Checked for the JDBC storage on PostgreSQL and MySQL; {@code
   * scalar.db.postgres.backend_collation_check=false} skips it.
   */
  static void checkBackendCollation(Properties properties) {
    String url = properties.getProperty("scalar.db.contact_points", "").trim();
    if ("false"
            .equalsIgnoreCase(
                properties.getProperty("scalar.db.postgres.backend_collation_check", "true").trim())
        || !"jdbc".equalsIgnoreCase(properties.getProperty("scalar.db.storage", "").trim())
        || !properties
            .getProperty("scalar.db.collation", "BINARY")
            .trim()
            .equalsIgnoreCase("BINARY")
        || !(url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:mysql:"))) {
      logger.info("Backend collation not checked (only JDBC storage on PostgreSQL or MySQL)");
      return;
    }
    boolean postgres = url.startsWith("jdbc:postgresql:");
    try {
      // the shaded jar does not register drivers by ServiceLoader; ScalarDB names them, so do we
      Class.forName(postgres ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver");
    } catch (ClassNotFoundException e) {
      logger.warn("Backend collation not checked: no JDBC driver {}", e.getMessage());
      return;
    }
    String collation;
    try (java.sql.Connection c =
            java.sql.DriverManager.getConnection(
                url,
                properties.getProperty("scalar.db.username"),
                properties.getProperty("scalar.db.password"));
        java.sql.Statement st = c.createStatement();
        java.sql.ResultSet rs =
            st.executeQuery(
                postgres
                    ? "SELECT datcollate FROM pg_database WHERE datname = current_database()"
                    : "SELECT @@collation_database")) {
      if (!rs.next()) {
        return;
      }
      collation = rs.getString(1);
    } catch (java.sql.SQLException e) {
      logger.warn("Backend collation not checked: {}", e.getMessage());
      return; // ScalarDB reports a connection problem itself
    }
    String problem = collationProblem(postgres, collation);
    if (problem != null) {
      throw new IllegalStateException(problem);
    }
  }

  /** Why a backing database's collation does not match BINARY; null when it does. */
  @javax.annotation.Nullable static String collationProblem(boolean postgres, @javax.annotation.Nullable String collation) {
    String c = collation == null ? "" : collation.trim();
    boolean bytewise =
        postgres
            ? c.equals("C")
                || c.equals("POSIX")
                || c.equalsIgnoreCase("C.UTF-8")
                || c.equalsIgnoreCase("C.utf8")
            // utf8mb4_bin pads spaces ('a' = 'a '), utf8mb4_0900_bin does not
            : c.equalsIgnoreCase("utf8mb4_0900_bin") || c.equalsIgnoreCase("binary");
    if (bytewise) {
      return null;
    }
    return "The backing database's collation is "
        + (c.isEmpty() ? "unknown" : c)
        + ", but ScalarDB compares text bytewise (scalar.db.collation=BINARY): text compared by the"
        + " database and by the frontend would disagree, and a query could answer differently"
        + " depending on its plan. Create the database with "
        + (postgres ? "LC_COLLATE 'C' (TEMPLATE template0)" : "COLLATE utf8mb4_0900_bin")
        + ", or set scalar.db.postgres.backend_collation_check=false to accept the difference"
        + " (LIMITATIONS.md)";
  }

  /**
   * Replaces ScalarDB's {@code ${env:NAME}} and {@code ${sys:name}} placeholders in every value,
   * the frontend's own {@code scalar.db.postgres.*} included, so that a container can take its
   * passwords and addresses from the environment; a value that resolves to nothing is removed.
   */
  static void resolvePlaceholders(Properties properties) {
    for (String name : properties.stringPropertyNames()) {
      String value = ConfigUtils.getString(properties, name, null);
      if (value == null) {
        properties.remove(name);
      } else {
        properties.setProperty(name, value);
      }
    }
  }

  /**
   * {@code scalar.db.postgres.auth}, checked against what it needs: Cluster, TLS, and no configured
   * user, so that a call outside a session fails rather than running as that user. Sets the Cluster
   * SDK's auth settings to match, refusing ones configured otherwise.
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
    // The SDK logs in only when told to, and reads only the holder of its own auth type (userpass
    // by default): with another, every login fails with DB-CLUSTER-10066
    requireOrSet(properties, "scalar.db.cluster.auth.enabled", "true", auth);
    requireOrSet(properties, "scalar.db.cluster.client.auth.type", auth, auth);
    return auth;
  }

  private static void requireOrSet(Properties properties, String name, String value, String auth) {
    String set = properties.getProperty(name);
    if (set != null && !set.trim().equalsIgnoreCase(value)) {
      throw new IllegalArgumentException(
          "scalar.db.postgres.auth=" + auth + " needs " + name + "=" + value + ", not " + set);
    }
    properties.setProperty(name, value);
  }
}
