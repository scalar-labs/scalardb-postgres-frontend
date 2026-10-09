package com.scalar.db.frontend.postgres.server;

import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.frontend.postgres.PostgresServer;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standalone frontend: serves the PostgreSQL protocol on top of a ScalarDB transaction manager
 * created from a properties file.
 *
 * <p>Usage: {@code java -jar scalardb-postgres-frontend-<version>.jar <scalardb.properties>
 * [port]}; the port defaults to 5432.
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
    TransactionFactory factory = TransactionFactory.create(properties);
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 5432;
    // scalar.db.postgres.listen_address: one interface, such as 127.0.0.1; all of them when unset
    String listen = properties.getProperty("scalar.db.postgres.listen_address");
    PostgresServer server =
        new PostgresServer(
            factory.getTransactionManager(),
            factory.getTransactionAdmin(),
            port,
            listen == null ? null : java.net.InetAddress.getByName(listen.trim()));
    server.configure(properties);
    logger.info("Listening on port {}", server.getPort());
    server.serve();
  }
}
