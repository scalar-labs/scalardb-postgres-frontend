package com.scalar.db.frontend.postgres;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.common.CollationComparator;
import com.scalar.db.config.Collation;
import com.scalar.db.config.DatabaseConfig;
import com.scalar.db.exception.transaction.CommitConflictException;
import com.scalar.db.exception.transaction.CrudConflictException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import com.scalar.db.io.DataType;
import com.scalar.db.util.ThrowableRunnable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PostgreSQL wire-protocol (version 3) server in front of ScalarDB. It speaks the simple and the
 * extended query protocol with text-format parameters and results. Connections are trusted unless
 * an {@link Authenticator} is set, which then verifies a password at startup and makes every
 * ScalarDB call of the session the authenticated user's. TLS is negotiated by SSLRequest once a
 * certificate is configured, see {@link #configure}. The database name of a connection is used as
 * the default ScalarDB namespace, and each connection is a session with its own transaction state.
 */
public class PostgresServer implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(PostgresServer.class);
  private static final java.security.SecureRandom KEYS = new java.security.SecureRandom();
  private static final int SSL_REQUEST = 80877103;
  private static final int GSS_REQUEST = 80877104;
  private static final int CANCEL_REQUEST = 80877102;

  // The settings PostgreSQL reports in ParameterStatus (GUC_REPORT); is_superuser and
  // session_authorization are reported once at startup, from the login
  private static final List<String> REPORTED =
      java.util.Arrays.asList(
          "server_version",
          "server_encoding",
          "client_encoding",
          "DateStyle",
          "integer_datetimes",
          "standard_conforming_strings",
          "TimeZone",
          "application_name");

  // Each session's cancel flag, by the process ID and secret key sent to it in BackendKeyData
  private static final Map<Long, java.util.concurrent.atomic.AtomicBoolean> CANCELS =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static long cancelKey(int pid, int secret) {
    return ((long) pid << 32) | (secret & 0xFFFFFFFFL);
  }

  private static final int TEXT_OID = 25;

  /** PostgreSQL's own limit on a startup packet. */
  private static final int MAX_STARTUP_LENGTH = 10_000;

  /** The largest message accepted: a client cannot make the server allocate what it names. */
  static final int MAX_MESSAGE_LENGTH = 64 << 20;

  /** The most parameters a statement can have: the protocol counts them in 16 bits. */
  private static final int MAX_PARAMETERS = 65_535;

  /** PostgreSQL's message for a length or count that runs past the end of the message. */
  private static final String SHORT_MESSAGE = "insufficient data left in message";

  private final DistributedTransactionManager manager;
  private final DistributedTransactionAdmin admin;
  private final ServerSocket serverSocket;
  // Open sessions, so that close() ends them too (todo 030)
  private final java.util.Set<Socket> sessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
  @Nullable private volatile Authenticator authenticator;
  @Nullable private volatile SSLContext sslContext;
  @Nullable private Boolean sslRequire; // null: required exactly when an authenticator is set

  private static final int MAX_PASSWORD_LENGTH = 65_536; // room for an OIDC token

  /** Milliseconds a refused login waits before answering, to slow down password guessing. */
  long loginFailureDelayMillis = 1000;

  /** Sessions served at once; one more is refused as PostgreSQL does (53300). */
  volatile int maxConnections = 100;

  /**
   * Milliseconds a client has for its startup handshake, as PostgreSQL's authentication_timeout.
   */
  volatile int startupTimeoutMillis = 60_000;

  /** Named prepared statements or portals one session may keep. */
  static final int MAX_PREPARED = 10_000;

  /** Bytes of SQL and bound values one session's prepared statements and portals may hold. */
  static final long MAX_PREPARED_BYTES = 64L << 20;

  // Closes a connection that has not finished its startup in time, however it trickles its bytes
  private static final java.util.concurrent.ScheduledExecutorService STARTUP_GUARD =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread t = new Thread(runnable, "postgres-frontend-startup-guard");
            t.setDaemon(true);
            return t;
          });

  public PostgresServer(
      DistributedTransactionManager manager, DistributedTransactionAdmin admin, int port)
      throws IOException {
    this(manager, admin, port, null);
  }

  /**
   * A server listening on {@code port} of {@code address}, or of every interface when it is null.
   *
   * @param manager the transaction manager sessions run on
   * @param admin the admin for metadata and DDL
   * @param port the port; 0 for any free one
   * @param address the address to listen on; null for all
   * @throws IOException if the port cannot be bound
   */
  @SuppressFBWarnings(
      value = "UNENCRYPTED_SERVER_SOCKET",
      justification = "TLS is negotiated per connection by SSLRequest, as the protocol requires")
  public PostgresServer(
      DistributedTransactionManager manager,
      DistributedTransactionAdmin admin,
      int port,
      @Nullable java.net.InetAddress address)
      throws IOException {
    this.manager = manager;
    this.admin = admin;
    this.typeStore = new TypeStore(manager, admin, new java.util.Properties());
    this.serverSocket = new ServerSocket(port, 50, address);
  }

  /** Session defaults every connection starts with, such as the transaction isolation level. */
  final Map<String, String> defaults = new HashMap<>();

  /** Longest metadata cache expiry worth waiting out after DDL, see {@link #configure}. */
  static final long MAX_SETTLE_SECS = 5;

  /** Milliseconds a session waits after DDL on an existing table, see {@link #configure}. */
  long ddlSettleMillis;

  private volatile boolean allowCoordinatorDrop; // scalar.db.postgres.allow_drop_coordinator_tables
  private volatile int maxRowsPerWriteCeiling; // scalar.db.postgres.max_rows_per_write_ceiling
  // numeric(p,s) up to this many digits is stored as a scaled bigint
  // (ColumnType.maxScaledPrecision)
  private volatile int maxScaledPrecision = ColumnType.MAX_SCALED_PRECISION;
  // the declared types of columns, read and written as the service user with authentication
  private volatile TypeStore typeStore;

  /**
   * Applies the settings that depend on the ScalarDB configuration the manager was created from:
   * how long a session waits after DDL on an existing table, so that the engine's table metadata
   * cache has expired, and the isolation level {@code SHOW transaction_isolation} reports. Call it
   * before {@link #start} or {@link #serve}.
   *
   * @param properties the ScalarDB properties of the transaction manager
   */
  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification = "The operator names the certificate and key files in the properties")
  public void configure(Properties properties) {
    String max = properties.getProperty("scalar.db.postgres.max_connections");
    if (max != null) {
      maxConnections = Integer.parseInt(max.trim());
    }
    allowCoordinatorDrop =
        Boolean.parseBoolean(
            properties.getProperty("scalar.db.postgres.allow_drop_coordinator_tables", "false"));
    String ceiling = properties.getProperty("scalar.db.postgres.max_rows_per_write_ceiling");
    if (ceiling != null) {
      maxRowsPerWriteCeiling = Integer.parseInt(ceiling.trim());
    }
    String lookups = properties.getProperty("scalar.db.postgres.max_lookup_threads");
    if (lookups != null) {
      Operators.LookupJoin.setMaxThreads(Integer.parseInt(lookups.trim()));
    }
    String held = properties.getProperty("scalar.db.postgres.max_rows_in_memory");
    if (held != null) {
      QueryParser.setMaxRowsInMemory(Integer.parseInt(held.trim()));
    }
    // TLS: a certificate chain and an unencrypted PKCS#8 key, both PEM; SSLRequest is then honored
    String cert = properties.getProperty("scalar.db.postgres.ssl.cert");
    String key = properties.getProperty("scalar.db.postgres.ssl.key");
    if (cert != null || key != null) {
      if (cert == null || key == null) {
        throw new IllegalArgumentException(
            "scalar.db.postgres.ssl.cert and scalar.db.postgres.ssl.key go together");
      }
      sslContext = sslContext(Paths.get(cert), Paths.get(key));
    }
    String require = properties.getProperty("scalar.db.postgres.ssl.require");
    if (require != null) {
      sslRequire = Boolean.parseBoolean(require);
    }
    maxScaledPrecision = ColumnType.maxScaledPrecision(properties);
    DatabaseConfig config = new DatabaseConfig(properties);
    Evaluator.setCollation(
        CollationComparator.from(config),
        config.getCollation() == Collation.ICU
            ? config.getCollationIcuLocale().orElse("und")
            : null);
    typeStore = new TypeStore(manager, admin, properties);
    // The engine caches table metadata and its admin does not refresh the cache, so DDL on an
    // existing table is not seen until the cache expires: sessions wait it out when it is short
    long cacheSecs = DatabaseConfig.getMetadataCacheExpirationTimeSecs(properties);
    if (cacheSecs >= 0 && cacheSecs <= MAX_SETTLE_SECS) {
      ddlSettleMillis = cacheSecs * 1000 + 50;
    } else {
      logger.warn(
          "Table metadata is cached for {} s: DDL on an existing table takes effect for the engine"
              + " only after that",
          cacheSecs);
    }
    // SHOW TRANSACTION ISOLATION LEVEL: Consensus Commit's SNAPSHOT reads like REPEATABLE READ
    String isolation = properties.getProperty("scalar.db.consensus_commit.isolation_level", "");
    String level = isolation.equalsIgnoreCase("SERIALIZABLE") ? "serializable" : "read committed";
    defaults.put("transaction_isolation", level);
    defaults.put("default_transaction_isolation", level);
  }

  /**
   * Requires every connection to log in with a password that {@code authenticator} verifies, and
   * runs each session's ScalarDB calls as the identity it returns. Without one, connections are
   * trusted as the user name they give.
   *
   * @param authenticator the authenticator
   */
  public void setAuthenticator(Authenticator authenticator) {
    this.authenticator = authenticator;
  }

  /** Whether a connection must be TLS: as configured, else exactly when it must log in. */
  private boolean sslRequired() {
    Boolean require = sslRequire;
    return require != null ? require : authenticator != null;
  }

  /**
   * An SSL context from a PEM certificate chain and an unencrypted PKCS#8 key (RSA or EC), the
   * files a PostgreSQL server is configured with.
   */
  static SSLContext sslContext(Path certificate, Path key) {
    try {
      Collection<? extends Certificate> chain;
      try (java.io.InputStream in = Files.newInputStream(certificate)) {
        chain = CertificateFactory.getInstance("X.509").generateCertificates(in);
      }
      String pem = Files.readString(key);
      String begin = "-----BEGIN PRIVATE KEY-----";
      String end = "-----END PRIVATE KEY-----";
      if (!pem.contains(begin) || !pem.contains(end)) {
        throw new IllegalArgumentException(
            "The TLS key must be an unencrypted PKCS#8 PEM (BEGIN PRIVATE KEY); convert it with:"
                + " openssl pkcs8 -topk8 -nocrypt -in "
                + key
                + " -out "
                + key
                + ".pk8");
      }
      byte[] der =
          Base64.getMimeDecoder()
              .decode(pem.substring(pem.indexOf(begin) + begin.length(), pem.indexOf(end)));
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
      PrivateKey privateKey;
      try {
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
      } catch (InvalidKeySpecException notRsa) {
        privateKey = KeyFactory.getInstance("EC").generatePrivate(spec);
      }
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, null);
      char[] none = new char[0]; // the store lives in memory only
      keyStore.setKeyEntry("server", privateKey, none, chain.toArray(new Certificate[0]));
      KeyManagerFactory keys =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(keyStore, none);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys.getKeyManagers(), null, null);
      return context;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalArgumentException("TLS configuration: " + e.getMessage(), e);
    }
  }

  public int getPort() {
    return serverSocket.getLocalPort();
  }

  /** Accepts connections on a daemon thread. */
  public void start() {
    Thread thread = new Thread(this::serve, "postgres-frontend-acceptor");
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public void close() throws IOException {
    serverSocket.close();
    for (Socket s : sessions) {
      try {
        s.close(); // ends the session; its open transaction is rolled back on the way out
      } catch (IOException e) {
        logger.debug("Close failed: {}", e.toString());
      }
    }
  }

  /** Accepts connections on the calling thread until {@link #close} is called. */
  public void serve() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        if (sessions.size() >= maxConnections) {
          refuse(socket);
          continue;
        }
        sessions.add(socket);
        Thread thread = new Thread(new Connection(socket), "postgres-frontend-" + socket.getPort());
        thread.setDaemon(true);
        thread.start();
      } catch (IOException | RuntimeException e) {
        // the acceptor keeps going: one failed accept must not stop the server
        if (!serverSocket.isClosed()) {
          logger.warn("Accept failed", e);
        }
      }
    }
  }

  /** Refuses a connection over the cap with PostgreSQL's FATAL 53300 and closes it. */
  private static void refuse(Socket socket) {
    try (Socket s = socket) {
      s.setSoTimeout(1000);
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
      Msg m =
          new Msg()
              .i8('S')
              .str("FATAL")
              .i8('V')
              .str("FATAL")
              .i8('C')
              .str("53300")
              .i8('M')
              .str("sorry, too many clients already")
              .i8(0);
      byte[] body = m.bytes.toByteArray();
      out.writeByte('E');
      out.writeInt(body.length + 4);
      out.write(body);
      out.flush();
    } catch (IOException e) {
      logger.debug("Refusing a connection failed: {}", e.toString());
    }
  }

  /** One client connection: the startup handshake, then the message loop. */
  private final class Connection implements Runnable {
    private Socket socket; // replaced by the TLS socket after SSLRequest
    private final Socket accepted; // as registered in sessions, whatever wraps it later
    private DataInputStream in;
    private DataOutputStream out;
    private boolean tls;
    private final Map<String, String> statements = new HashMap<>(); // name -> SQL with $n
    private final Map<String, int[]> parameterTypes = new HashMap<>(); // name -> declared OIDs
    // name -> the OIDs Describe reported, by which a binary parameter is read when none was
    // declared
    private final Map<String, int[]> describedTypes = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();
    // Bytes held per prepared statement and portal, and their total, against MAX_PREPARED_BYTES
    private final Map<String, Long> statementBytes = new HashMap<>();
    private final Map<String, Long> portalBytes = new HashMap<>();
    private long preparedBytes;

    /**
     * Accounts for {@code bytes} under {@code name}, replacing what it held, or refuses (53000).
     */
    private void reserve(Map<String, Long> sizes, String name, long bytes) {
      long next = preparedBytes - sizes.getOrDefault(name, 0L) + bytes;
      if (next > MAX_PREPARED_BYTES) {
        throw new PgException.Argument(
            "53000",
            "prepared statements and portals would hold more than "
                + (MAX_PREPARED_BYTES >> 20)
                + " MiB: close some");
      }
      sizes.put(name, bytes);
      preparedBytes = next;
    }

    private void release(Map<String, Long> sizes, String name) {
      Long bytes = sizes.remove(name);
      if (bytes != null) {
        preparedBytes -= bytes;
      }
    }

    @Nullable private Authenticator.Identity identity; // the authenticated user, if any
    @Nullable private Long cancelKey; // this session's entry in CANCELS
    // The ParameterStatus values the client was last told, by name
    private final Map<String, String> reported = new java.util.LinkedHashMap<>();

    Connection(Socket socket) throws IOException {
      // Replies are flushed whole at ReadyForQuery; Nagle would hold the next one back for the
      // client's delayed ACK (tens of milliseconds) on a real network
      socket.setTcpNoDelay(true);
      this.socket = socket;
      this.accepted = socket;
      this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
      this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    @Override
    public void run() {
      QueryProcessor processor = null;
      try {
        // The whole startup, TLS and password included, must finish in time: a read timeout alone
        // restarts with every byte, so a client trickling one a minute would hold its slot forever
        java.util.concurrent.ScheduledFuture<?> guard =
            STARTUP_GUARD.schedule(
                () -> {
                  try {
                    accepted.close();
                  } catch (IOException e) {
                    logger.debug("Close failed: {}", e.toString());
                  }
                },
                startupTimeoutMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
          processor = startup();
        } finally {
          guard.cancel(false);
        }
        if (processor != null) {
          serve(processor);
        }
      } catch (IOException e) {
        logger.debug("Connection ended: {}", e.toString());
      } catch (RuntimeException | Error e) {
        logger.warn("Connection failed", e);
      } finally {
        closeSuspended();
        if (processor != null && processor.inTransaction()) {
          QueryProcessor open = processor;
          try {
            scoped(() -> open.execute("ROLLBACK"));
          } catch (Exception e) {
            logger.warn("Rollback on disconnect failed", e);
          }
        }
        sessions.remove(accepted);
        if (cancelKey != null) {
          CANCELS.remove(cancelKey);
        }
        if (identity != null) {
          identity.close();
        }
        try {
          socket.close();
        } catch (IOException e) {
          logger.debug("Close failed: {}", e.toString());
        }
      }
    }

    @Nullable private QueryProcessor startup() throws IOException {
      while (true) {
        int length = in.readInt();
        int code = in.readInt();
        if (code == SSL_REQUEST) {
          SSLContext context = sslContext;
          if (context == null) {
            out.writeByte('N');
            out.flush();
            continue;
          }
          out.writeByte('S');
          out.flush();
          SSLSocket ssl =
              (SSLSocket)
                  context
                      .getSocketFactory()
                      .createSocket(
                          socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
          ssl.setUseClientMode(false);
          ssl.startHandshake();
          socket = ssl;
          in = new DataInputStream(new BufferedInputStream(ssl.getInputStream()));
          out = new DataOutputStream(new BufferedOutputStream(ssl.getOutputStream()));
          tls = true;
          continue;
        }
        if (code == GSS_REQUEST) {
          out.writeByte('N'); // GSS encryption is not supported
          out.flush();
          continue;
        }
        if (length < 8 || length > MAX_STARTUP_LENGTH) {
          fatal("08P01", "invalid length of startup packet"); // protocol_violation, as PostgreSQL
          return null;
        }
        if (code == CANCEL_REQUEST) {
          // The key pair from BackendKeyData: the named session's running statement stops
          // with 57014 at its next row. As in PostgreSQL, the requester gets no answer
          if (length == 16) {
            java.util.concurrent.atomic.AtomicBoolean flag =
                CANCELS.get(cancelKey(in.readInt(), in.readInt()));
            if (flag != null) {
              flag.set(true);
            }
          }
          return null;
        }
        byte[] body = new byte[length - 8];
        in.readFully(body);
        if (body.length == 0 || body[body.length - 1] != 0) {
          // as PostgreSQL: names and values are NUL-terminated and the list ends with a NUL
          fatal("08P01", "invalid startup packet layout: expected terminator as last byte");
          return null;
        }
        ByteBuffer msg = ByteBuffer.wrap(body);
        Map<String, String> params = new HashMap<>();
        while (msg.hasRemaining()) {
          String key = readString(msg);
          if (key.isEmpty()) {
            break;
          }
          params.put(key, readString(msg));
        }
        String user = params.getOrDefault("user", "");
        String database = params.getOrDefault("database", user);
        if (sslRequired() && !tls) {
          // as PostgreSQL answers a plaintext connection where pg_hba.conf allows only hostssl
          fatal("28000", "no encryption: this server accepts only SSL connections");
          return null;
        }
        Authenticator authenticator = PostgresServer.this.authenticator;
        boolean superuser = false;
        if (authenticator != null) {
          // Cleartext is the point: the secret is checked by Cluster, which SCRAM would never let
          // the server see. TLS keeps it off the wire (docs/auth-design.md).
          send('R', new Msg().i32(3)); // AuthenticationCleartextPassword
          out.flush();
          int type = in.read();
          int responseLength = in.readInt() - 4;
          if (responseLength < 0 || responseLength > MAX_PASSWORD_LENGTH) {
            fatal("08P01", "invalid length of password message");
            return null;
          }
          byte[] response = new byte[responseLength];
          in.readFully(response);
          if (type != 'p') {
            fatal("08P01", "expected a password response, got message type " + (char) type);
            return null;
          }
          try {
            identity = authenticator.authenticate(user, readString(ByteBuffer.wrap(response)));
          } catch (Authenticator.AuthenticationException e) {
            logger.info("Login failed for user \"{}\"", user);
            sleepQuietly(loginFailureDelayMillis);
            fatal("28P01", "password authentication failed for user \"" + user + "\"");
            return null;
          } catch (RuntimeException e) {
            // the cause stays in the log: the client is not authenticated and learns nothing
            logger.warn("Authentication of user \"{}\" failed", user, e);
            fatal("58000", "authentication service failed"); // system_error
            return null;
          }
          user = identity.user();
          superuser = identity.superuser();
        }
        send('R', new Msg().i32(0)); // AuthenticationOk
        reported.put("is_superuser", superuser ? "on" : "off");
        reported.put("session_authorization", user);
        for (Map.Entry<String, String> s : reported.entrySet()) {
          send('S', new Msg().str(s.getKey()).str(s.getValue()));
        }
        int pid = KEYS.nextInt();
        int secret = KEYS.nextInt(); // a cancel request must not be guessable
        java.util.concurrent.atomic.AtomicBoolean cancel =
            new java.util.concurrent.atomic.AtomicBoolean();
        cancelKey = cancelKey(pid, secret);
        CANCELS.put(cancelKey, cancel);
        send('K', new Msg().i32(pid).i32(secret));
        QueryProcessor processor = new QueryProcessor(manager, admin, database);
        Evaluator.setCancel(cancel); // this thread serves the session from here on
        processor.setDdlSettleMillis(ddlSettleMillis);
        processor.setAllowCoordinatorDrop(allowCoordinatorDrop);
        processor.setMaxRowsPerWriteCeiling(maxRowsPerWriteCeiling);
        processor.setMaxScaledPrecision(maxScaledPrecision);
        processor.setTypeStore(typeStore);
        if (identity != null) {
          processor.setIdentity(identity);
        } else if (!user.isEmpty()) {
          processor.setUser(user);
        }
        for (Map.Entry<String, String> e : defaults.entrySet()) {
          processor.setDefault(e.getKey(), e.getValue());
        }
        try {
          for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey().equals("options")) {
              // PGOPTIONS='-c client_min_messages=warning': settings given at connection time
              java.util.regex.Matcher option =
                  java.util.regex.Pattern.compile("-c\\s*([\\w.]+)=(\\S+)").matcher(e.getValue());
              while (option.find()) {
                processor.setClientDefault(option.group(1), option.group(2));
              }
            } else if (!e.getKey().equals("user") && !e.getKey().equals("database")) {
              // application_name, DateStyle, ...: the client's, so checked as SET checks them
              processor.setClientDefault(e.getKey(), e.getValue());
            }
          }
        } catch (PgException.Argument | PgException.State refused) {
          // as PostgreSQL refuses a bad startup option: FATAL, with the setting's own code
          fatal(((PgException) refused).sqlState(), String.valueOf(refused.getMessage()));
          return null;
        }
        readyForQuery(processor); // reports the settings, with the client's own applied
        return processor;
      }
    }

    private void serve(QueryProcessor processor) throws IOException {
      boolean failed = false; // extended protocol: after an error, skip messages until Sync
      while (true) {
        int type = in.read();
        if (type < 0 || type == 'X') {
          return;
        }
        int length = in.readInt();
        if (length < 4 || length > MAX_MESSAGE_LENGTH) {
          fatal("08P01", "invalid message length " + length); // the stream cannot be resynchronized
          return;
        }
        byte[] body = new byte[length - 4];
        in.readFully(body);
        ByteBuffer msg = ByteBuffer.wrap(body);
        if (type == 'S') {
          failed = false;
          if (implicit) {
            try {
              scoped(() -> endImplicit(processor)); // commits, or rolls back after an error
            } catch (Exception | Error e) {
              sendError(e);
            }
          }
          readyForQuery(processor);
          continue;
        }
        if (type == 'H') {
          out.flush();
          continue;
        }
        if ("QPBDECFdcf".indexOf(type) < 0) {
          // not a PostgreSQL message at all: as PostgreSQL does, end the session rather than guess
          fatal("08P01", "invalid frontend message type " + type);
          return;
        }
        if (failed) {
          continue;
        }
        try {
          scoped(() -> dispatch(processor, type, msg)); // as the session's user, if it has one
        } catch (Exception | Error e) {
          // an Error (a stack overflow on deeply nested SQL, a missing class) is reported like any
          // failure and the connection goes on, instead of dying with no message to the client
          String code = sendError(e);
          processor.fail(); // any error inside a transaction block aborts it, as in PostgreSQL
          if (code.equals("28000")) {
            out.flush();
            return; // the session's credentials are no longer valid: the client reconnects
          }
          if (type == 'Q') {
            readyForQuery(processor);
          } else {
            failed = true;
          }
        }
        // No flush per message: ReadyForQuery (after a Query or a Sync) and Flush send what is
        // buffered, as PostgreSQL does, so a pgjdbc Bind/Execute/Sync is answered in one write
      }
    }

    private void dispatch(QueryProcessor processor, int type, ByteBuffer msg) throws Exception {
      switch (type) {
        case 'Q':
          simpleQuery(processor, readString(msg));
          break;
        case 'P':
          String name = readString(msg);
          if (statements.size() >= MAX_PREPARED && !statements.containsKey(name)) {
            throw new PgException.Argument(
                "53000", "too many prepared statements: close some (at most " + MAX_PREPARED + ")");
          }
          String text = readString(msg);
          reserve(statementBytes, name, text.length());
          statements.put(name, text);
          int[] oids = new int[count(msg, 4)];
          for (int i = 0; i < oids.length; i++) {
            oids[i] = msg.getInt();
          }
          parameterTypes.put(name, oids);
          describedTypes.remove(name);
          send('1', new Msg()); // ParseComplete
          break;
        case 'B':
          bind(msg);
          break;
        case 'D':
          describe(processor, msg);
          break;
        case 'E':
          execute(processor, msg);
          break;
        case 'C':
          char kind = (char) msg.get();
          String closing = readString(msg);
          if (kind == 'S') {
            statements.remove(closing);
          } else {
            Portal closed = portals.remove(closing);
            if (closed != null) {
              closed.close();
            }
          }
          release(kind == 'S' ? statementBytes : portalBytes, closing);
          if (kind == 'S') {
            parameterTypes.remove(closing);
            describedTypes.remove(closing);
          }
          send('3', new Msg()); // CloseComplete
          break;
        default:
          throw new PgException.Argument("0A000", "Unsupported message type: " + (char) type);
      }
    }

    /**
     * Runs {@code body} as the session's user, once per message: the one place a connection thread
     * enters the identity, so no path can run as the service itself. Without an identity, the
     * connection is trusted and the body simply runs.
     */
    private <T extends Throwable> void scoped(ThrowableRunnable<T> body) throws T {
      if (identity == null) {
        body.run();
      } else {
        identity.<Void, T>run(
            () -> {
              body.run();
              return null;
            });
      }
    }

    // An implicit transaction holding the extended-protocol writes until the next Sync
    private boolean implicit;

    /** Ends the implicit transaction: COMMIT, which rolls back if a statement in it failed. */
    private void endImplicit(QueryProcessor processor) throws Exception {
      implicit = false;
      processor.execute("COMMIT");
    }

    private void simpleQuery(QueryProcessor processor, String sql) throws Exception {
      if (implicit) {
        endImplicit(processor); // a Query without the Sync that should have come before it
      }
      List<String> parts = split(sql);
      if (parts.isEmpty()) {
        send('I', new Msg()); // EmptyQueryResponse
      }
      // As PostgreSQL: the writes of one Query run in an implicit transaction, so a failure undoes
      // the ones before it (todo 009). Only a Query of writes alone: a read after a write in one
      // ScalarDB transaction fails (DB-CORE-10106), and failing a harmless read is worse
      boolean implicit =
          parts.size() > 1
              && !processor.inTransaction()
              && parts.stream().allMatch(part -> WRITES.contains(firstWord(part)));
      if (implicit) {
        processor.execute("BEGIN");
      }
      try {
        for (String part : parts) {
          run(processor, part, Collections.emptyList(), true, new short[0]);
        }
        if (implicit) {
          processor.execute("COMMIT");
        }
      } catch (Exception | Error e) {
        if (implicit && processor.inTransaction()) {
          try {
            processor.execute("ROLLBACK");
          } catch (Exception rollback) {
            e.addSuppressed(rollback);
          }
        }
        throw e;
      }
      readyForQuery(processor);
    }

    private final java.util.Set<String> WRITES =
        new java.util.HashSet<>(java.util.Arrays.asList("INSERT", "UPDATE", "DELETE"));

    private String firstWord(String sql) {
      String[] words = QueryParser.trimStatement(sql).split("\\s+", 2);
      return words[0].toUpperCase(Locale.ROOT);
    }

    /** Runs one statement, streaming its rows (with a RowDescription if asked) and command tag. */
    /**
     * Runs one statement, streaming its rows (with a RowDescription if asked) and command tag.
     * {@code formats} are the result-column format codes the client asked for in Bind (none for the
     * simple protocol): 0 text, 1 binary.
     */
    private void run(
        QueryProcessor processor,
        String sql,
        List<Object> parameters,
        boolean describe,
        short[] formats)
        throws Exception {
      sendRows(processor, processor.open(sql, parameters), null, describe, formats, 0);
    }

    /**
     * Sends a run's rows, at most {@code maxRows} of them unless it is 0, then its notices and
     * CommandComplete, and closes it. With rows left past {@code maxRows} it sends PortalSuspended
     * instead and leaves the run open for the portal's next Execute, returning the column types to
     * resume with; null when the run is complete.
     */
    @Nullable private int[] sendRows(
        QueryProcessor processor,
        QueryProcessor.Rows rows,
        @Nullable int[] resumed,
        boolean describe,
        short[] formats,
        int maxRows)
        throws Exception {
      boolean suspended = false;
      int[] oids = resumed;
      try {
        Map<String, DataType> columns = rows.columns();
        if (!columns.isEmpty() || rows.returnsRows()) {
          int sent = 0;
          if (oids == null) {
            // The first row is fetched before the RowDescription so computed columns get a type
            Map<String, Object> first = rows.hasNext() ? rows.next() : null;
            oids =
                oids(columns, first == null ? null : Collections.singletonList(first), rows.oids());
            if (describe) {
              sendRowDescription(rows.labels(), oids, rows.typmods(), formats);
            }
            if (first != null) {
              sendRow(columns, first, oids, rows.typmods(), formats);
              sent = 1;
            }
          }
          int[] typmods = rows.typmods();
          while (rows.hasNext()) {
            if (maxRows > 0 && sent == maxRows) {
              suspended = true;
              break;
            }
            sendRow(columns, rows.next(), oids, typmods, formats);
            if (++sent % 64 == 0) {
              out.flush();
            }
          }
        } else {
          while (rows.hasNext()) {
            rows.next();
          }
        }
      } finally {
        if (!suspended) {
          rows.close();
        }
      }
      if (suspended) {
        send('s', new Msg()); // PortalSuspended
        return oids;
      }
      List<String> notices = rows.notices();
      // client_min_messages above NOTICE (warning, error, ...) suppresses them, as in PostgreSQL
      String minimum = processor.currentSetting("client_min_messages").toLowerCase(Locale.ROOT);
      boolean noticesWanted =
          minimum.isEmpty()
              || java.util.Arrays.asList(
                      "debug5", "debug4", "debug3", "debug2", "debug1", "log", "notice", "info")
                  .contains(minimum);
      for (String notice : notices) {
        boolean warning = notice.startsWith(QueryProcessor.WARNING);
        if (warning
            ? java.util.Arrays.asList("error", "fatal", "panic").contains(minimum)
            : !noticesWanted) {
          continue;
        }
        String severity = warning ? "WARNING" : "NOTICE";
        send(
            'N',
            new Msg()
                .i8('S')
                .str(severity)
                .i8('V')
                .str(severity)
                .i8('C')
                .str("01000") // warning
                .i8('M')
                .str(warning ? notice.substring(QueryProcessor.WARNING.length()) : notice)
                .i8(0));
      }
      send('C', new Msg().str(processor.lastCommandTag()));
      return null;
    }

    private void sendRow(
        Map<String, DataType> columns,
        Map<String, Object> row,
        int[] oids,
        int[] typmods,
        short[] formats)
        throws IOException {
      Msg m = new Msg().i16(columns.size());
      int i = 0;
      for (String column : columns.keySet()) {
        Object value = row.get(column);
        if (value != null && oids[i] == 1042 && i < typmods.length && typmods[i] > 4) {
          value = padded(Evaluator.text(value), typmods[i] - 4); // char(n) is stored trimmed
        }
        if (value == null) {
          m.i32(-1);
        } else {
          byte[] bytes =
              format(formats, i) == 1
                  ? binary(oids[i], value)
                  : text(value).getBytes(StandardCharsets.UTF_8);
          m.i32(bytes.length).bytes(bytes);
        }
        i++;
      }
      send('D', m);
    }

    private void bind(ByteBuffer msg) throws IOException {
      String portal = readString(msg);
      String statement = readString(msg);
      String sql = statements.get(statement);
      if (sql == null) {
        throw new IllegalArgumentException("Unknown prepared statement");
      }
      int[] oids = parameterTypes.getOrDefault(statement, new int[0]);
      short[] formats = new short[count(msg, 2)];
      for (int i = 0; i < formats.length; i++) {
        formats[i] = msg.getShort();
      }
      int count = count(msg, 4);
      List<String> params = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        int length = msg.getInt();
        if (length < 0) {
          params.add(null);
          continue;
        }
        if (length > msg.remaining()) {
          throw new PgException.Argument("08P01", SHORT_MESSAGE);
        }
        byte[] value = new byte[length];
        msg.get(value);
        short format = formats.length == 0 ? 0 : formats.length == 1 ? formats[0] : formats[i];
        int[] described = describedTypes.getOrDefault(statement, new int[0]);
        int oid =
            i < oids.length && oids[i] != 0 ? oids[i] : i < described.length ? described[i] : 0;
        params.add(format == 0 ? new String(value, StandardCharsets.UTF_8) : binary(oid, value));
      }
      int needed = Math.max(QueryParser.parameterCount(sql), oids.length); // as PostgreSQL counts
      if (params.size() != needed) {
        throw new PgException.Argument(
            "08P01",
            "bind message supplies "
                + params.size()
                + " parameters, but prepared statement \""
                + statement
                + "\" requires "
                + needed);
      }
      short[] resultFormats = new short[count(msg, 2)];
      for (int i = 0; i < resultFormats.length; i++) {
        resultFormats[i] = msg.getShort();
      }
      List<Object> values = new ArrayList<>();
      for (int i = 0; i < params.size(); i++) {
        values.add(typed(params.get(i), i < oids.length ? oids[i] : 0));
      }
      if (portals.size() >= MAX_PREPARED && !portals.containsKey(portal)) {
        throw new PgException.Argument(
            "53000", "too many portals: close some (at most " + MAX_PREPARED + ")");
      }
      long size = sql.length();
      for (Object v : values) {
        size += v instanceof ByteBuffer ? ((ByteBuffer) v).remaining() : String.valueOf(v).length();
      }
      reserve(portalBytes, portal, size);
      Portal replaced = portals.put(portal, new Portal(sql, values, resultFormats));
      if (replaced != null) {
        replaced.close();
      }
      send('2', new Msg()); // BindComplete
    }

    private void describe(QueryProcessor processor, ByteBuffer msg) throws Exception {
      char kind = (char) msg.get();
      String name = readString(msg);
      Map<String, DataType> columns;
      short[] formats = new short[0];
      if (kind == 'S') {
        String sql = statements.get(name);
        if (sql == null) {
          throw new IllegalArgumentException("Unknown prepared statement");
        }
        int n = parameterCount(sql);
        if (n > MAX_PARAMETERS) {
          throw new PgException.Argument(
              "08P01", "too many parameters: $" + n); // $99999999 in the SQL
        }
        boolean described;
        try {
          columns = processor.describe(sql, Collections.nCopies(n, null));
          described = true;
        } catch (RuntimeException e) {
          columns = Collections.emptyMap(); // the row shape depends on the parameter values
          described = false;
        }
        int[] oids = parameterTypes.getOrDefault(name, new int[0]);
        int[] reported = new int[n];
        Msg m = new Msg().i16(n);
        for (int i = 0; i < n; i++) {
          // the type the client declared in Parse, else the one the statement gives $i+1
          int declared = i < oids.length ? oids[i] : 0;
          reported[i] =
              declared != 0 || !described ? declared : processor.describedParameterOid(i + 1);
          m.i32(reported[i]);
        }
        describedTypes.put(name, reported);
        send('t', m); // ParameterDescription
      } else {
        Portal portal = portals.get(name);
        if (portal == null) {
          throw new IllegalArgumentException("Unknown portal");
        }
        columns =
            portal.sql.trim().isEmpty()
                ? Collections.emptyMap()
                : processor.describe(portal.sql, portal.parameters);
        formats = portal.formats;
      }
      if (columns.isEmpty()) {
        send('n', new Msg()); // NoData
      } else {
        sendRowDescription(
            processor.describedLabels(),
            oids(columns, null, processor.describedOids()),
            processor.describedTypmods(),
            formats);
      }
    }

    private void execute(QueryProcessor processor, ByteBuffer msg) throws Exception {
      Portal portal = portals.get(readString(msg));
      if (portal == null) {
        throw new IllegalArgumentException("Unknown portal");
      }
      int maxRows = msg.getInt();
      QueryProcessor.Rows suspended = portal.rows;
      if (suspended != null) {
        // A suspended portal: the next rows of the same run
        portal.rows = null;
        suspended.resume();
        portal.oids = sendRows(processor, suspended, portal.oids, false, portal.formats, maxRows);
        portal.rows = portal.oids == null ? null : suspended;
        return;
      }
      if (portal.sql.trim().isEmpty()) {
        send('I', new Msg());
        return;
      }
      // As PostgreSQL: the writes up to a Sync run in an implicit transaction, so a batch, which
      // pgjdbc sends with one Sync, is all or nothing (todo 009). A read or any other statement
      // commits the writes before it first: a read after a write in one ScalarDB transaction
      // fails (DB-CORE-10106), as the simple protocol's rule also avoids
      boolean write = WRITES.contains(firstWord(portal.sql));
      if (implicit && !write) {
        endImplicit(processor);
      }
      if (write && !processor.inTransaction()) {
        processor.execute("BEGIN");
        implicit = true;
      }
      // A write runs to completion whatever the row limit, as in PostgreSQL, which holds its
      // RETURNING rows; so it sends them all
      QueryProcessor.Rows rows = processor.open(portal.sql, portal.parameters);
      portal.oids = sendRows(processor, rows, null, false, portal.formats, write ? 0 : maxRows);
      portal.rows = portal.oids == null ? null : rows;
    }

    /** Ends the runs of suspended portals, as PostgreSQL drops its portals with the transaction. */
    private void closeSuspended() {
      for (Portal portal : portals.values()) {
        portal.close();
      }
    }

    private void sendRowDescription(List<String> labels, int[] oids, int[] typmods, short[] formats)
        throws IOException {
      Msg m = new Msg().i16(labels.size());
      for (int i = 0; i < labels.size(); i++) {
        m.str(labels.get(i))
            .i32(0)
            .i16(0)
            .i32(oids[i])
            .i16(size(oids[i]))
            .i32(i < typmods.length ? typmods[i] : -1) // numeric(10,2): drivers read p and s here
            .i16(format(formats, i));
      }
      send('T', m);
    }

    /** A FATAL error, after which the connection closes, as at a refused login. */
    private void fatal(String code, String message) throws IOException {
      error("FATAL", code, message);
      out.flush();
    }

    private void error(String severity, String code, String message) throws IOException {
      error(severity, code, message, null);
    }

    private void error(String severity, String code, String message, @Nullable String detail)
        throws IOException {
      Msg m =
          new Msg()
              .i8('S')
              .str(severity)
              .i8('V')
              .str(severity)
              .i8('C')
              .str(code)
              .i8('M')
              .str(message);
      if (detail != null) {
        m.i8('D').str(detail);
      }
      send('E', m.i8(0));
    }

    /** A failure that is the server's fault, whose own text means nothing to the client. */
    private boolean isInternal(Throwable e) {
      return (e instanceof Error && !(e instanceof StackOverflowError))
          || e instanceof NullPointerException
          || e instanceof ClassCastException
          || e instanceof IndexOutOfBoundsException
          // ScalarDB's own refusals (DB-CORE-10143, encryption not enabled) carry an error code
          || (e instanceof UnsupportedOperationException
              && !String.valueOf(e.getMessage()).startsWith("DB-"))
          || e instanceof java.util.ConcurrentModificationException;
    }

    /** Reports a statement's failure to the client and returns its SQLSTATE. */
    private String sendError(Throwable failure) throws IOException {
      if (failure instanceof java.nio.BufferUnderflowException) {
        // a message body that ends early (a string without its zero byte, a missing count): the
        // client's fault, as PostgreSQL reports it, not an internal error
        failure = new PgException.Argument("08P01", SHORT_MESSAGE);
      }
      Throwable cause = failure.getCause();
      Throwable e = failure instanceof QueryParser.ReadFailure && cause != null ? cause : failure;
      String code = sqlState(e);
      String message =
          e instanceof StackOverflowError
              ? "stack depth limit exceeded"
              : e.getMessage() == null ? e.toString() : e.getMessage();
      // ScalarDB's "Creating the table failed" and the like keep the database's reason in the cause
      Throwable root = rootCause(e);
      if (root instanceof java.sql.SQLException
          && root.getMessage() != null
          && !message.contains(root.getMessage())) {
        message += " Details: " + root.getMessage();
      }
      if (isInternal(e)) {
        // A bug or a JVM failure: the client learns that much, the log gets the rest, since the
        // JDK's text names classes and variables the client has no use for
        logger.warn("Statement failed with an internal error", e);
        message = "internal error: the server log has the details";
      } else if (code.equals("40003")) {
        // Whether the transaction committed is unknown: the operator needs its ID (in ScalarDB's
        // message) to resolve it, whatever the client does with the error
        logger.warn("Transaction outcome unknown: {}", message);
      } else {
        logger.debug("Statement failed: {}", message); // the SQL text may hold data: not at INFO
      }
      error(
          code.equals("28000") ? "FATAL" : "ERROR", // 28000 closes the session
          code,
          message,
          e instanceof PgException ? ((PgException) e).detail() : null);
      return code;
    }

    /**
     * Tells the client about each reported setting whose value changed since it was last told, as
     * PostgreSQL does before ReadyForQuery: at startup, and after SET, RESET or a transaction end.
     */
    private void reportSettings(QueryProcessor processor) throws IOException {
      for (String name : REPORTED) {
        String value = processor.currentSetting(name);
        if (!value.equals(reported.put(name, value))) {
          send('S', new Msg().str(name).str(value));
        }
      }
    }

    private void readyForQuery(QueryProcessor processor) throws IOException {
      if (!processor.inTransaction()) {
        closeSuspended();
      }
      reportSettings(processor);
      send('Z', new Msg().i8(processor.isAborted() ? 'E' : processor.inTransaction() ? 'T' : 'I'));
      out.flush();
    }

    private void send(char type, Msg m) throws IOException {
      byte[] body = m.bytes.toByteArray();
      out.writeByte(type);
      out.writeInt(body.length + 4);
      out.write(body);
    }
  }

  /** A message body under construction. */
  private static final class Msg {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream data = new DataOutputStream(bytes);

    Msg i8(int v) throws IOException {
      data.writeByte(v);
      return this;
    }

    Msg i16(int v) throws IOException {
      data.writeShort(v);
      return this;
    }

    Msg i32(int v) throws IOException {
      data.writeInt(v);
      return this;
    }

    Msg bytes(byte[] v) throws IOException {
      data.write(v);
      return this;
    }

    Msg str(String s) throws IOException {
      data.write(s.getBytes(StandardCharsets.UTF_8));
      data.writeByte(0);
      return this;
    }
  }

  /**
   * A 16-bit count of items of at least {@code itemBytes} each, as an unsigned value checked
   * against what is left of the message.
   */
  private static int count(ByteBuffer msg, int itemBytes) {
    int n = msg.getShort() & 0xFFFF;
    if ((long) n * itemBytes > msg.remaining()) {
      throw new PgException.Argument("08P01", SHORT_MESSAGE);
    }
    return n;
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String readString(ByteBuffer b) {
    int start = b.position();
    while (b.get() != 0) {
      // find the terminator
    }
    return new String(
        b.array(), b.arrayOffset() + start, b.position() - start - 1, StandardCharsets.UTF_8);
  }

  /**
   * The SQLSTATE for a failure. The frontend's own errors are told apart by their message prefixes.
   */
  // ponytail: prefix matching on our own messages; a typed exception per error class if this
  // grows
  static String sqlState(Throwable e) {
    String m = e.getMessage() == null ? "" : e.getMessage();
    if (e instanceof StackOverflowError) {
      return "54001"; // statement_too_complex, PostgreSQL's "stack depth limit exceeded"
    }
    if (e instanceof Error) {
      return "XX000"; // internal_error
    }
    // The frontend's own errors carry their code; the rules below are for what comes from
    // ScalarDB (by error code) and the JDK (by type), whose messages are not ours (todo 026)
    if (e instanceof PgException) {
      return ((PgException) e).sqlState();
    }
    // Cluster's auth module: the credentials at login, a token that died mid-session, and every
    // other refusal, which is a missing privilege
    if (m.contains("DB-AUTH-10005")) {
      return "28P01"; // invalid_password
    }
    if (m.contains("DB-AUTH-10006")) {
      return "28000"; // invalid_authorization_specification: the session closes
    }
    if (m.contains("DB-AUTH-10000") || m.contains("DB-AUTH-10021")) {
      return "42710"; // duplicate_object: the user or role exists
    }
    if (m.contains("DB-AUTH-10001") || m.contains("DB-AUTH-10022")) {
      return "42704"; // undefined_object: no such user or role
    }
    if (m.contains("DB-AUTH-10003")) {
      return "3F000"; // invalid_schema_name
    }
    if (m.contains("DB-AUTH-10004")) {
      return "42P01"; // undefined_table
    }
    if (m.contains("DB-AUTH-10023")) {
      return "0LP01"; // invalid_grant_operation: a role cycle
    }
    if (m.contains("DB-AUTH-10013") || m.contains("DB-AUTH-10014")) {
      return "55006"; // object_in_use: your own SUPERUSER attribute, or dropping yourself
    }
    if (m.contains("DB-AUTH-3")) {
      return "XX000"; // internal_error: Cluster failed, not the client
    }
    if (m.contains("DB-AUTH-")) {
      return "42501"; // insufficient_privilege
    }
    // Cluster's ABAC: a data tag column in a WHERE or ORDER BY, a malformed tag, a dead token, and
    // every other refusal, which is a tag the user may not read or write
    if (m.contains("DB-ABAC-10037") || m.contains("DB-ABAC-10042")) {
      return "0A000"; // feature_not_supported
    }
    if (m.contains("DB-ABAC-10030")
        || m.contains("DB-ABAC-10031")
        || m.contains("DB-ABAC-10032")
        || m.contains("DB-ABAC-10033")) {
      return "22023"; // invalid_parameter_value
    }
    if (m.contains("DB-ABAC-10039")) {
      return "28000"; // invalid_authorization_specification, as DB-AUTH-10006
    }
    if (m.contains("DB-ABAC-3") || m.contains("DB-ENCRYPTION-3")) {
      return "XX000"; // internal_error: Cluster failed, not the client
    }
    if (m.contains("DB-ABAC-1")) {
      return "42501"; // insufficient_privilege
    }
    // Cluster's encryption refusing an encrypted key, index, rename or type change, core refusing
    // an encrypted column, or ABAC, when Cluster does not provide them, and LIKE under ICU
    if (m.contains("DB-ENCRYPTION-1")
        || m.contains("DB-CORE-10143")
        || m.contains("DB-CORE-10152")
        || m.contains("DB-CORE-10309")) {
      return "0A000"; // feature_not_supported
    }
    if (m.contains("DB-CORE-10026")) {
      return "22025"; // invalid_escape_sequence
    }
    if (m.contains("DB-CORE-10211")) {
      return "25006"; // read_only_sql_transaction
    }
    if (m.contains("DB-CORE-10020") || m.contains("DB-CORE-10017")) {
      return "22023"; // invalid_parameter_value: a key or value ScalarDB does not accept
    }
    if (m.contains("DB-CORE-10052")) {
      return "42P07"; // duplicate_table: CREATE TABLE of an existing table
    }
    if (m.contains("DB-CORE-10053")) {
      return "2BP01"; // dependent_objects_still_exist: DROP SCHEMA with tables in it
    }
    if (e instanceof QueryParser.UniqueViolationException) {
      return "23505"; // unique_violation
    }
    if (e instanceof QueryProcessor.AbortedTransactionException) {
      return "25P02"; // in_failed_sql_transaction
    }
    if (e instanceof CrudConflictException || e instanceof CommitConflictException) {
      return "40001"; // serialization_failure: the client may retry
    }
    if (e instanceof UnknownTransactionStatusException) {
      return "40003"; // statement_completion_unknown
    }
    if (e instanceof ArithmeticException) {
      return m.contains("by zero") ? "22012" : "22003"; // division_by_zero, out of range
    }
    if (e instanceof DateTimeException) {
      return "22007"; // invalid_datetime_format
    }
    if (e instanceof NumberFormatException) {
      return "22P02"; // invalid_text_representation
    }
    // A failure in the backing database (a CREATE TABLE it refused, a value it rejected) carries
    // its own SQLSTATE, which says more than internal_error; HY000 is a vendor's "other"
    Throwable root = rootCause(e);
    if (root instanceof java.sql.SQLException) {
      String state = ((java.sql.SQLException) root).getSQLState();
      if (state != null && state.length() == 5 && !state.startsWith("HY")) {
        return state;
      }
    }
    // a statement the engine could not make sense of is a syntax error; anything else, such as a
    // failed metadata read, is internal
    return e instanceof IllegalArgumentException ? "42601" : "XX000";
  }

  private static Throwable rootCause(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root;
  }

  /** Splits a simple-query string into statements on semicolons outside quotes. */
  static List<String> split(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean content = false; // the current statement has more than whitespace and comments
    int i = 0;
    while (i < sql.length()) {
      int after = QueryParser.skipQuoted(sql, i);
      if (after != i) {
        boolean comment = sql.startsWith("--", i) || sql.startsWith("/*", i);
        content |= !comment;
        current.append(sql, i, after);
        i = after;
        continue;
      }
      char c = sql.charAt(i++);
      if (c == ';') {
        if (content) {
          out.add(current.toString().trim());
        }
        current.setLength(0);
        content = false;
      } else {
        current.append(c);
        content |= !Character.isWhitespace(c);
      }
    }
    if (content) {
      out.add(current.toString().trim());
    }
    return out; // a Query of comments alone is empty, as in PostgreSQL
  }

  /** A bound statement: its SQL with {@code $n} placeholders, their values, and result formats. */
  private static final class Portal {
    final String sql;
    final List<Object> parameters;
    final short[] formats;
    // A suspended run (Execute stopped at its row limit) and its column types, until it completes
    @Nullable QueryProcessor.Rows rows;
    @Nullable int[] oids;

    Portal(String sql, List<Object> parameters, short[] formats) {
      this.sql = sql;
      this.parameters = parameters;
      this.formats = formats;
    }

    void close() {
      if (rows != null) {
        rows.close();
        rows = null;
      }
    }
  }

  /**
   * A parameter as the value its literal would have had: a Long or Double for a number, a Boolean,
   * else the text. Numbers and booleans are recognized by the declared type or, for an undeclared
   * one, by the text itself, as a client writing literals in simple-query mode would have sent.
   */
  @Nullable static Object typed(@Nullable String value, int oid) {
    if (value == null) {
      return null;
    }
    if (oid == 16 && (value.equals("t") || value.equals("f"))) {
      return value.equals("t");
    }
    if (!plain(value, oid)) {
      return value;
    }
    Object guess = guess(value, oid);
    // Undeclared, and the guess does not print back as what was sent ('007', 'true', '1e3'): keep
    // both, so text contexts see the text (Evaluator.Untyped)
    return oid == 0 && (guess instanceof Boolean || !Evaluator.text(guess).equals(value))
        ? new Evaluator.Untyped(value, guess)
        : guess;
  }

  private static Object guess(String value, int oid) {
    if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
      return Boolean.valueOf(value.toLowerCase(Locale.ROOT));
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      if (oid == 700 || oid == 701) {
        return Double.valueOf(value);
      }
      try {
        return Evaluator.decimal(value); // numeric, or an undeclared decimal: exact, as a literal
      } catch (NumberFormatException notDecimal) {
        return Double.valueOf(value); // NaN, Infinity
      }
    }
  }

  /** The format code of result column {@code i}: one code applies to all, or one per column. */
  private static int format(short[] formats, int i) {
    return formats.length == 0 ? 0 : formats.length == 1 ? formats[0] : formats[i];
  }

  private static int[] oids(
      Map<String, DataType> columns, @Nullable List<Map<String, Object>> rows, int[] inferred) {
    int[] oids = new int[columns.size()];
    int i = 0;
    for (Map.Entry<String, DataType> column : columns.entrySet()) {
      int oid = oid(column.getValue(), column.getKey(), rows);
      // A computed column: the type inferred from the expression is PostgreSQL's (name[] for
      // array_agg(attname), int2vector for indkey); the first value only decides when it is not
      if (column.getValue() == null
          && i < inferred.length
          && inferred[i] != 0
          && (inferred[i] != TEXT_OID || oid == TEXT_OID)) {
        oid = inferred[i];
      }
      oids[i++] = oid;
    }
    return oids;
  }

  /** A char(n) value as PostgreSQL returns it: blank-padded to n characters. */
  static String padded(String s, int n) {
    int missing = n - s.codePointCount(0, s.length());
    return missing > 0 ? s + " ".repeat(missing) : s;
  }

  private static final LocalDateTime PG_EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0);

  /** A value in PostgreSQL's binary result format for the type {@code oid}. */
  static byte[] binary(int oid, Object value) {
    switch (oid) {
      case 3802: // jsonb: a version byte, then the text
        {
          byte[] text = Evaluator.text(value).getBytes(StandardCharsets.UTF_8);
          byte[] out = new byte[text.length + 1];
          out[0] = 1;
          System.arraycopy(text, 0, out, 1, text.length);
          return out;
        }
      case 114: // json
        return Evaluator.text(value).getBytes(StandardCharsets.UTF_8);
      case 2950: // uuid
        {
          java.util.UUID uuid = java.util.UUID.fromString(Evaluator.text(value).trim());
          return ByteBuffer.allocate(16)
              .putLong(uuid.getMostSignificantBits())
              .putLong(uuid.getLeastSignificantBits())
              .array();
        }
      case 16:
        return new byte[] {(byte) (parseBoolean(value) ? 1 : 0)};
      case 21:
        return ByteBuffer.allocate(2).putShort((short) number(value).intValue()).array();
      case 23:
        return ByteBuffer.allocate(4).putInt(number(value).intValue()).array();
      case 20:
        return ByteBuffer.allocate(8).putLong(number(value).longValue()).array();
      case 700:
        return ByteBuffer.allocate(4).putFloat(number(value).floatValue()).array();
      case 701:
        return ByteBuffer.allocate(8).putDouble(number(value).doubleValue()).array();
      case 1700:
        if (value instanceof Number && !Evaluator.isFinite(value)) {
          // NaN and the infinities: no digits, the sign word says which
          double d = ((Number) value).doubleValue();
          int sign = Double.isNaN(d) ? 0xC000 : d > 0 ? 0xD000 : 0xF000;
          return ByteBuffer.allocate(8)
              .putShort((short) 0)
              .putShort((short) 0)
              .putShort((short) sign)
              .putShort((short) 0)
              .array();
        }
        return numeric(
            value instanceof java.math.BigDecimal
                ? (java.math.BigDecimal) value
                : new java.math.BigDecimal(number(value).toString()));
      case 26: // oid, and the reg* aliases of it
      case 2205:
      case 2206:
      case 4089:
        return ByteBuffer.allocate(4).putInt(number(value).intValue()).array();
      case 1186: // interval: microseconds, days, months
        {
          Interval iv = value instanceof Interval ? (Interval) value : Interval.parse(text(value));
          return ByteBuffer.allocate(16)
              .putLong(iv.micros)
              .putInt(iv.days)
              .putInt(iv.months)
              .array();
        }
      case 17:
        if (value instanceof ByteBuffer) {
          ByteBuffer b = ((ByteBuffer) value).duplicate();
          byte[] bytes = new byte[b.remaining()];
          b.get(bytes);
          return bytes;
        }
        return value instanceof byte[] ? (byte[]) value : QueryParser.hex(text(value));
      case 1082:
        return ByteBuffer.allocate(4)
            .putInt(
                (int)
                    java.time.temporal.ChronoUnit.DAYS.between(
                        PG_EPOCH.toLocalDate(), (LocalDate) value))
            .array();
      case 1083:
        return ByteBuffer.allocate(8).putLong(((LocalTime) value).toNanoOfDay() / 1000).array();
      case 1114:
        return ByteBuffer.allocate(8)
            .putLong(java.time.temporal.ChronoUnit.MICROS.between(PG_EPOCH, (LocalDateTime) value))
            .array();
      case 1184:
        return ByteBuffer.allocate(8)
            .putLong(
                java.time.temporal.ChronoUnit.MICROS.between(
                    PG_EPOCH.toInstant(ZoneOffset.UTC), (Instant) value))
            .array();
      case 22: // int2vector: an int2 array indexed from 0, as pg_index.indkey is
        return arrayBinary(21, Evaluator.elements(value), 0);
      default:
        if (Catalog.elementOid(oid) != 0) {
          // every array type the catalog knows, not a hand-kept list of them (todo 019)
          return arrayBinary((int) Catalog.elementOid(oid), Evaluator.elements(value), 1);
        }
        return text(value).getBytes(StandardCharsets.UTF_8); // text types: the same bytes
    }
  }

  private static Number number(Object value) {
    if (value instanceof Catalog.Reg) {
      return ((Catalog.Reg) value).oid;
    }
    return value instanceof Number ? (Number) value : new java.math.BigDecimal(value.toString());
  }

  private static boolean parseBoolean(Object value) {
    return value instanceof Boolean
        ? (Boolean) value
        : value.toString().equalsIgnoreCase("true") || value.toString().equalsIgnoreCase("t");
  }

  private static final java.util.regex.Pattern NUMBER =
      java.util.regex.Pattern.compile("-?\\d++(\\.\\d++)?([eE][-+]?\\d++)?");

  /** True if the parameter stands as it is in SQL: a number or a boolean. */
  private static boolean plain(String value, int oid) {
    switch (oid) {
      case 16: // bool
      case 20: // int8
      case 21: // int2
      case 23: // int4
      case 700: // float4
      case 701: // float8
      case 1700: // numeric
        return true;
      case 0: // undeclared: the text decides
        return NUMBER.matcher(value).matches()
            || value.equalsIgnoreCase("true")
            || value.equalsIgnoreCase("false");
      default:
        return false;
    }
  }

  /** The highest {@code $n} placeholder number in the statement. */
  static int parameterCount(String sql) {
    return QueryParser.parameterCount(sql);
  }

  /** Decodes a binary-format parameter into the text a client would have sent. */
  static String binary(int oid, byte[] value) {
    ByteBuffer b = ByteBuffer.wrap(value);
    switch (oid) {
      case 3802: // jsonb: a version byte, then the text
        return value.length > 0 && value[0] == 1
            ? new String(value, 1, value.length - 1, StandardCharsets.UTF_8)
            : new String(value, StandardCharsets.UTF_8);
      case 114:
        return new String(value, StandardCharsets.UTF_8);
      case 2950: // uuid: 16 raw bytes, kept as its text form (a uuid column is TEXT here)
        {
          if (value.length != 16) {
            throw new IllegalArgumentException("Invalid binary uuid length: " + value.length);
          }
          java.util.UUID uuid = new java.util.UUID(b.getLong(), b.getLong());
          return uuid.toString();
        }
      case 16:
        return value.length > 0 && value[0] != 0 ? "true" : "false";
      case 21:
        return String.valueOf(b.getShort());
      case 23:
        return String.valueOf(b.getInt());
      case 26: // oid and its reg* aliases: unsigned
      case 24:
      case 2205:
      case 2206:
      case 4089:
        return String.valueOf(Integer.toUnsignedLong(b.getInt()));
      case 20:
        return String.valueOf(b.getLong());
      case 700:
        return String.valueOf(b.getFloat());
      case 701:
        return String.valueOf(b.getDouble());
      case 17:
        return "\\x" + hex(value);
      case 1082:
        return LocalDate.of(2000, 1, 1).plusDays(b.getInt()).toString();
      case 1083:
        return LocalTime.ofNanoOfDay(b.getLong() * 1000).toString();
      case 1114:
        return timestamp(LocalDateTime.of(2000, 1, 1, 0, 0).plusNanos(b.getLong() * 1000));
      case 1184:
        return timestamp(LocalDateTime.of(2000, 1, 1, 0, 0).plusNanos(b.getLong() * 1000))
            + "+00:00";
      case 1700:
        return numeric(b);
      case 0:
      case 19:
      case 25:
      case 1042:
      case 1043:
        return new String(value, StandardCharsets.UTF_8);
      default:
        if (Catalog.elementOid(oid) != 0) {
          return arrayText(b);
        }
        throw new PgException.Argument(
            "0A000", "Binary parameters of type " + oid + " are not supported");
    }
  }

  /** A one-dimensional array in binary format: the mirror of {@link #arrayText(ByteBuffer)}. */
  private static byte[] arrayBinary(int elementOid, List<?> elements, int lowerBound) {
    List<byte[]> encoded = new ArrayList<>();
    int size = 20;
    boolean nulls = false;
    for (Object e : elements) {
      if (e == null) {
        encoded.add(null);
        nulls = true;
        size += 4;
        continue;
      }
      Object v = e;
      if (v instanceof String
          && elementOid != 25
          && elementOid != 1043
          && elementOid != 1042
          && elementOid != 19) { // name is text too
        // elements parsed from array text; NULL elements were handled above
        v = Objects.requireNonNull(Evaluator.cast(Catalog.typeName(elementOid), v));
      }
      byte[] bytes = binary(elementOid, v);
      encoded.add(bytes);
      size += 4 + bytes.length;
    }
    ByteBuffer b = ByteBuffer.allocate(size);
    b.putInt(1).putInt(nulls ? 1 : 0).putInt(elementOid).putInt(elements.size()).putInt(lowerBound);
    for (byte[] bytes : encoded) {
      if (bytes == null) {
        b.putInt(-1);
      } else {
        b.putInt(bytes.length).put(bytes);
      }
    }
    return b.array();
  }

  /**
   * A binary array as PostgreSQL's array text: the dimensions, a null flag and the element type
   * come first, then each element's length and bytes (-1 for NULL). One dimension is supported.
   */
  private static String arrayText(ByteBuffer b) {
    int dims = b.getInt();
    b.getInt(); // has nulls
    int elementOid = b.getInt();
    if (dims > 1) {
      throw new PgException.Argument("0A000", "Multidimensional arrays are not supported");
    }
    int count = dims == 0 ? 0 : b.getInt();
    if (dims == 1) {
      b.getInt(); // lower bound
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      int length = b.getInt();
      if (length < 0) {
        sb.append("NULL");
        continue;
      }
      if (length > b.remaining()) {
        throw new PgException.Argument("08P01", SHORT_MESSAGE);
      }
      byte[] bytes = new byte[length];
      b.get(bytes);
      String s = binary(elementOid, bytes);
      boolean quote =
          s.isEmpty()
              || s.equalsIgnoreCase("NULL")
              || s.chars()
                  .anyMatch(
                      c ->
                          c == '{'
                              || c == '}'
                              || c == ','
                              || c == '"'
                              || c == '\\'
                              || Character.isWhitespace(c));
      sb.append(quote ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : s);
    }
    return sb.append('}').toString();
  }

  /**
   * A binary numeric: base-10000 digit groups, the weight (power of 10000) of the first group, a
   * sign word and the display scale.
   */
  private static String numeric(ByteBuffer b) {
    int groups = b.getShort();
    int weight = b.getShort();
    int sign = b.getShort() & 0xFFFF;
    int scale = b.getShort();
    switch (sign) {
      case 0xC000:
        return "NaN";
      case 0xD000:
        return "Infinity";
      case 0xF000:
        return "-Infinity";
      default:
        break;
    }
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < groups; i++) {
      digits.append(String.format("%04d", b.getShort()));
    }
    java.math.BigDecimal value =
        groups == 0
            ? java.math.BigDecimal.ZERO
            : new java.math.BigDecimal(new java.math.BigInteger(digits.toString()))
                .scaleByPowerOfTen(4 * (weight - groups + 1));
    if (sign == 0x4000) {
      value = value.negate();
    }
    return value.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  /** A numeric in binary format: the mirror of {@link #numeric(ByteBuffer)}. */
  private static byte[] numeric(java.math.BigDecimal v) {
    if (v.scale() < 0) {
      v = v.setScale(0);
    }
    int scale = v.scale();
    int fractionGroups = (scale + 3) / 4;
    // Pad the fraction to whole groups, then split the digits into base-10000 groups
    java.math.BigInteger digits =
        v.unscaledValue().abs().multiply(java.math.BigInteger.TEN.pow(fractionGroups * 4 - scale));
    java.util.List<Integer> groups = new java.util.ArrayList<>(); // least significant first
    java.math.BigInteger base = java.math.BigInteger.valueOf(10000);
    while (digits.signum() > 0) {
      java.math.BigInteger[] qr = digits.divideAndRemainder(base);
      groups.add(qr[1].intValue());
      digits = qr[0];
    }
    int weight = groups.size() - fractionGroups - 1;
    java.util.Collections.reverse(groups);
    while (!groups.isEmpty() && groups.get(groups.size() - 1) == 0) {
      groups.remove(groups.size() - 1); // trailing zero groups are implied
    }
    ByteBuffer b = ByteBuffer.allocate(8 + 2 * groups.size());
    b.putShort((short) groups.size())
        .putShort((short) (groups.isEmpty() ? 0 : weight))
        .putShort((short) (v.signum() < 0 ? 0x4000 : 0))
        .putShort((short) scale);
    for (int g : groups) {
      b.putShort((short) g);
    }
    return b.array();
  }

  /** A value in PostgreSQL text format. */
  static String text(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value ? "t" : "f";
    }
    return Evaluator.text(value); // temporal and binary values in PostgreSQL's formats
  }

  private static String timestamp(LocalDateTime t) {
    return Evaluator.timestampText(t);
  }

  private static String hex(byte[] bytes) {
    return Evaluator.hex(bytes);
  }

  /** The PostgreSQL type OID for a column: from its ScalarDB type, else from its first value. */
  private static int oid(
      @Nullable DataType type, String column, @Nullable List<Map<String, Object>> rows) {
    if (type != null) {
      return (int) Catalog.typeOid(type);
    }
    if (rows != null) {
      for (Map<String, Object> row : rows) {
        Object v = row.get(column);
        if (v instanceof Boolean) {
          return 16;
        }
        if (v instanceof java.math.BigDecimal) {
          return 1700;
        }
        if (v instanceof Interval) {
          return 1186;
        }
        if (v instanceof Json.Value) {
          return ((Json.Value) v).binary ? 3802 : 114;
        }
        if (v instanceof Catalog.Int2Vector) {
          return 22;
        }
        if (v instanceof Evaluator.Row) {
          return 2249; // record
        }
        if (v instanceof List) {
          Object element = null;
          for (Object item : (List<?>) v) {
            if (item != null) {
              element = item;
              break;
            }
          }
          int elementOid =
              element == null
                  ? 25
                  : oid(null, "", Collections.singletonList(Collections.singletonMap("", element)));
          return (int) Catalog.arrayOid(elementOid);
        }
        if (v instanceof Integer || v instanceof Short) {
          return 23;
        }
        if (v instanceof Long) {
          return 20;
        }
        if (v instanceof Float) {
          return 700;
        }
        if (v instanceof Double) {
          return 701;
        }
        if (v != null) {
          return TEXT_OID;
        }
      }
    }
    return TEXT_OID;
  }

  private static int size(int oid) {
    return (int) Catalog.typeLength(oid);
  }
}
