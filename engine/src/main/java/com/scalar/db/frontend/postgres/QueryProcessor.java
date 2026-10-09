package com.scalar.db.frontend.postgres;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.DataType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Executes PostgreSQL-style SQL statements against ScalarDB, one session per instance. {@code
 * BEGIN}/{@code START TRANSACTION}, {@code COMMIT}/{@code END}, and {@code ROLLBACK} control an
 * explicit transaction; any other statement outside a transaction runs in its own auto-committed
 * transaction. DDL ({@code CREATE}/{@code DROP} {@code SCHEMA}, {@code TABLE}, {@code INDEX},
 * {@code ALTER TABLE}, {@code TRUNCATE}, {@code CREATE COORDINATOR TABLES}) runs through the admin
 * and is refused inside a transaction. {@code SET}, {@code RESET}, and {@code DISCARD} are accepted
 * and ignored. Not thread-safe.
 */
public class QueryProcessor {
  private final DistributedTransactionManager manager;
  private final QueryParser parser;
  @Nullable private DistributedTransaction transaction;
  // BEGIN was seen but no statement ran yet: the ScalarDB transaction starts with the first
  // statement, so SET TRANSACTION READ ONLY / ISOLATION LEVEL can still shape it, as in PostgreSQL
  private boolean pending;
  private boolean pendingReadOnly;
  private boolean transactionIsolationSet; // SHOW transaction_isolation reverts when it ends
  private boolean aborted; // a statement failed inside the transaction: only ROLLBACK/COMMIT now
  private String lastCommandTag = "";
  private long ddlSettleMillis;

  /** DDL after which the engine's cached metadata of an existing table is stale. */
  private static final java.util.Set<String> CHANGES_EXISTING_TABLES =
      new java.util.HashSet<>(
          java.util.Arrays.asList(
              "DROP TABLE", "DROP SCHEMA", "DROP INDEX", "ALTER TABLE", "CREATE INDEX"));

  // The statement describe() planned last: the extended protocol describes a portal right before
  // executing it, and planning it once serves both
  // Plans by statement text, most recently used last: a reusable plan is planned once per session
  private final Map<String, QueryParser.Plan> planCache =
      new LinkedHashMap<String, QueryParser.Plan>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, QueryParser.Plan> eldest) {
          return size() > 256;
        }
      };
  @Nullable private String describedSql;
  private List<Object> describedParameters = Collections.emptyList();
  @Nullable private QueryParser.Plan describedPlan;
  // Savepoints: accepted inside a transaction; ROLLBACK TO can only be honored while nothing was
  // written since the savepoint, as ScalarDB has no partial rollback
  private final java.util.Deque<String> savepoints = new java.util.ArrayDeque<>();
  private int writesSinceSavepoint;
  private static final java.util.Set<String> WRITES =
      new java.util.HashSet<>(java.util.Arrays.asList("INSERT", "UPDATE", "DELETE", "TRUNCATE"));
  private static final java.util.regex.Pattern SAVEPOINT_NAME =
      java.util.regex.Pattern.compile(
          "(?is)(?:SAVEPOINT|RELEASE\\s+(?:SAVEPOINT\\s+)?|ROLLBACK\\s+(?:WORK\\s+|TRANSACTION\\s+)?TO\\s+(?:SAVEPOINT\\s+)?)\"?([\\w$]+)\"?");
  // Session settings: what SET stored, over the server's defaults; SHOW reads them
  private final Map<String, String> settings = new LinkedHashMap<>();
  private final Map<String, String> defaults = new LinkedHashMap<>();

  private static final java.util.regex.Pattern READ_ONLY =
      java.util.regex.Pattern.compile("(?i)\\bREAD\\s+ONLY\\b");
  private static final java.util.regex.Pattern READ_WRITE =
      java.util.regex.Pattern.compile("(?i)\\bREAD\\s+WRITE\\b");
  private static final java.util.regex.Pattern ISOLATION =
      java.util.regex.Pattern.compile(
          "(?i)\\bISOLATION\\s+LEVEL\\s+(READ\\s+UNCOMMITTED|READ\\s+COMMITTED|REPEATABLE\\s+READ|SERIALIZABLE)\\b");
  private static final java.util.regex.Pattern SET_TRANSACTION =
      java.util.regex.Pattern.compile(
          "(?is)^\\s*SET\\s+(SESSION\\s+CHARACTERISTICS\\s+AS\\s+)?TRANSACTION\\s+(.+?)\\s*;?\\s*$");
  private static final java.util.regex.Pattern BEGIN_WORD =
      java.util.regex.Pattern.compile("(?is)^\\s*(?:BEGIN|START)(?:\\s+(?:WORK|TRANSACTION))?");
  // The most rows a read-then-write statement may write; 0 means no limit
  private static final String MAX_ROWS_PER_WRITE = "scalardb.max_rows_per_write";
  // SET ROLE and SET SESSION AUTHORIZATION, in every spelling, including RESET
  private static final java.util.regex.Pattern SET_IDENTITY =
      java.util.regex.Pattern.compile(
          "(?is)^\\s*(?:SET|RESET)\\s+(?:(?:SESSION|LOCAL)\\s+)?(?:ROLE\\b|SESSION\\s+AUTHORIZATION|SESSION_AUTHORIZATION\\b)");

  @Nullable private Authenticator.Identity identity; // the authenticated user, if any

  public QueryProcessor(
      DistributedTransactionManager manager,
      DistributedTransactionAdmin admin,
      String defaultNamespace) {
    this(manager, new QueryParser(admin, defaultNamespace));
  }

  QueryProcessor(DistributedTransactionManager manager, QueryParser parser) {
    this.manager = manager;
    this.parser = parser;
    defaults.put("server_version", "16.0");
    defaults.put("server_encoding", "UTF8");
    defaults.put("client_encoding", "UTF8");
    defaults.put("DateStyle", "ISO, MDY");
    defaults.put("TimeZone", "UTC");
    defaults.put("integer_datetimes", "on");
    defaults.put("standard_conforming_strings", "on");
    defaults.put(MAX_ROWS_PER_WRITE, String.valueOf(QueryParser.DEFAULT_MAX_ROWS_PER_WRITE));
    defaults.put("is_superuser", "off");
    defaults.put("search_path", "\"$user\", public");
    // ScalarDB's SNAPSHOT reads each record's committed version with no snapshot across records,
    // which is what PostgreSQL calls READ COMMITTED; the server raises this for SERIALIZABLE
    defaults.put("transaction_isolation", "read committed");
    defaults.put("default_transaction_isolation", "read committed");
    defaults.put("default_transaction_read_only", "off");
    defaults.put("application_name", "");
    defaults.put("max_identifier_length", "63");
    defaults.put("client_min_messages", "notice");
  }

  /** The user the client connected as, for {@code current_user}. */
  public void setUser(String user) {
    parser.setUser(user);
  }

  /**
   * The authenticated identity the session runs as. The caller enters it around every statement;
   * the processor passes it to plans, whose reads from other threads enter it there.
   *
   * @param identity the identity
   */
  public void setIdentity(Authenticator.Identity identity) {
    this.identity = identity;
    setUser(identity.user());
    defaults.put("is_superuser", identity.superuser() ? "on" : "off");
  }

  /** The session's current value of a setting, from SET or the defaults. */
  public String currentSetting(String name) {
    String key = settingKey(name);
    return current(key == null ? name : key);
  }

  /** Sets a session default, as the server does from its properties and the startup message. */
  public void setDefault(String name, String value) {
    String key = settingKey(name);
    defaults.put(key == null ? name : key, value);
    searchPathChanged(key);
  }

  private static final java.util.regex.Pattern SET_VALUE =
      java.util.regex.Pattern.compile(
          "(?is)SET\\s+(?:(SESSION|LOCAL)\\s+)?(?:([\\w.]+)\\s*(?:=|TO)\\s*(.+)|TIME\\s+ZONE\\s+(.+))");
  // SET LOCAL: the values to restore when the transaction ends (null: the setting was absent)
  private final Map<String, String> localSaved = new LinkedHashMap<>();
  private String zoneName = "";
  private java.time.ZoneId zone = java.time.ZoneOffset.UTC;
  private static final java.util.regex.Pattern RESET_NAME =
      java.util.regex.Pattern.compile("(?is)RESET\\s+([\\w.]+|TIME\\s+ZONE|ALL)");
  private static final java.util.regex.Pattern SHOW_NAME =
      java.util.regex.Pattern.compile("(?is)SHOW\\s+(.+)");

  /** The stored key of a setting named as in SHOW, matched without regard to case; null if none. */
  @Nullable private String settingKey(String name) {
    String n = name.trim().replaceAll("\\s+", " ");
    if (n.equalsIgnoreCase("transaction isolation level")) {
      n = "transaction_isolation";
    } else if (n.equalsIgnoreCase("time zone")) {
      n = "TimeZone";
    }
    for (String key : settings.keySet()) {
      if (key.equalsIgnoreCase(n)) {
        return key;
      }
    }
    for (String key : defaults.keySet()) {
      if (key.equalsIgnoreCase(n)) {
        return key;
      }
    }
    return null;
  }

  /** Records SET and RESET in the session settings; returns a warning for the client, if any. */
  @Nullable private String setting(String sql) {
    if (identity != null && SET_IDENTITY.matcher(sql).find()) {
      // session_authorization and current_role would lie about who the calls run as
      throw new IllegalArgumentException(
          "Unsupported: SET ROLE and SET SESSION AUTHORIZATION, since an authenticated session runs"
              + " as the user it logged in as");
    }
    java.util.regex.Matcher set = SET_VALUE.matcher(QueryParser.trimStatement(sql));
    if (set.matches()) {
      boolean local = "LOCAL".equalsIgnoreCase(set.group(1));
      if (local && !inTransaction()) {
        return WARNING + "SET LOCAL can only be used in transaction blocks";
      }
      String name = set.group(2) != null ? set.group(2) : "TimeZone"; // SET TIME ZONE 'x'
      String value = (set.group(2) != null ? set.group(3) : set.group(4)).trim();
      if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
        value = value.substring(1, value.length() - 1).replace("''", "'");
      } else if (value.equalsIgnoreCase("DEFAULT")) {
        String key = settingKey(name);
        if (key != null) {
          if (local) {
            localSaved.putIfAbsent(key, settings.get(key));
          }
          settings.remove(key);
          searchPathChanged(key);
        }
        return null;
      }
      String key = settingKey(name);
      if ("TimeZone".equals(key)) {
        value = zoneOf(value).getId().equals("Z") ? "UTC" : value; // validated here
      }
      if (local) {
        localSaved.putIfAbsent(key == null ? name : key, settings.get(key == null ? name : key));
      }
      if ("transaction_isolation".equals(key) || "default_transaction_isolation".equals(key)) {
        value = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        checkIsolation(value);
      }
      if (MAX_ROWS_PER_WRITE.equals(key) && !value.matches("\\d{1,9}")) {
        throw new IllegalArgumentException(
            "invalid value for parameter \"" + MAX_ROWS_PER_WRITE + "\": \"" + value + "\"");
      }
      settings.put(key == null ? name : key, value);
      searchPathChanged(key);
      return null;
    }
    java.util.regex.Matcher reset = RESET_NAME.matcher(QueryParser.trimStatement(sql));
    if (reset.matches()) {
      if (reset.group(1).equalsIgnoreCase("ALL")) {
        settings.clear();
      } else {
        String key = settingKey(reset.group(1));
        if (key != null) {
          settings.remove(key);
          searchPathChanged(key);
        }
      }
    } else if (firstWord(sql).equals("DISCARD")) {
      settings.clear();
    }
    searchPathChanged("search_path");
    return null;
  }

  private void searchPathChanged(@Nullable String key) {
    if ("search_path".equals(key)) {
      parser.setSearchPath(current("search_path"));
    }
  }

  /** PostgreSQL's TimeZone names: IANA zones, abbreviations such as JST, and fixed offsets. */
  // SET TIME ZONE INTERVAL '+09:00' HOUR TO MINUTE (Sequelize sends this on every connection)
  private static final Pattern INTERVAL_ZONE =
      Pattern.compile("(?i)^INTERVAL\\s+'([+-]?\\d{1,2}(?::\\d{2})?)'\\s+HOUR\\s+TO\\s+MINUTE$");

  // SET TIME ZONE 9, SET TIME ZONE '-3.5': hours east of UTC
  private static final Pattern NUMERIC_ZONE = Pattern.compile("^[+-]?\\d{1,2}(?:\\.\\d+)?$");
  // SET TIME ZONE '+05:30', 'UTC+9': a POSIX zone, whose offset is positive WEST of UTC
  private static final Pattern POSIX_ZONE =
      Pattern.compile("(?i)^(?:UTC|GMT)?([+-])(\\d{1,2})(?::(\\d{2}))?$");

  static java.time.ZoneId zoneOf(String name) {
    String text = name.trim();
    java.util.regex.Matcher m = INTERVAL_ZONE.matcher(text);
    if (m.matches()) {
      return offset(m.group(1), false);
    }
    if (NUMERIC_ZONE.matcher(text).matches()) {
      return java.time.ZoneOffset.ofTotalSeconds((int) Math.round(Double.parseDouble(text) * 3600));
    }
    if (POSIX_ZONE.matcher(text).matches()) {
      return offset(text.replaceFirst("(?i)^(UTC|GMT)", ""), true);
    }
    try {
      return java.time.ZoneId.of(name, java.time.ZoneId.SHORT_IDS);
    } catch (java.time.DateTimeException e) {
      try {
        return java.time.ZoneId.of(name.toUpperCase(Locale.ROOT), java.time.ZoneId.SHORT_IDS);
      } catch (java.time.DateTimeException again) {
        throw new IllegalArgumentException(
            "invalid value for parameter \"TimeZone\": \"" + name + "\"");
      }
    }
  }

  /** {@code [+-]hh[:mm]} as an offset; a POSIX spec counts the other way round. */
  private static java.time.ZoneOffset offset(String text, boolean posix) {
    java.util.regex.Matcher m = Pattern.compile("^([+-]?)(\\d{1,2})(?::(\\d{2}))?$").matcher(text);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "invalid value for parameter \"TimeZone\": \"" + text + "\"");
    }
    int sign = (m.group(1).equals("-") ? -1 : 1) * (posix ? -1 : 1);
    int minutes = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
    return java.time.ZoneOffset.ofHoursMinutes(sign * Integer.parseInt(m.group(2)), sign * minutes);
  }

  /** Puts the session's TimeZone on this thread before a statement runs. */
  private void applyZone() {
    String name = current("TimeZone");
    if (!name.equals(zoneName)) {
      zoneName = name;
      zone = zoneOf(name);
    }
    Evaluator.setZone(zone);
  }

  /** The session's cap on rows a read-then-write statement may write; 0 means no limit. */
  private int maxRowsPerWrite() {
    return Integer.parseInt(
        settings.getOrDefault(MAX_ROWS_PER_WRITE, defaults.get(MAX_ROWS_PER_WRITE)));
  }

  /** SHOW name, or SHOW ALL, as a constant result. */
  private QueryParser.Plan show(String sql) {
    java.util.regex.Matcher m = SHOW_NAME.matcher(QueryParser.trimStatement(sql));
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid SQL: " + sql);
    }
    Map<String, String> all = new LinkedHashMap<>(defaults);
    all.putAll(settings);
    if (m.group(1).equalsIgnoreCase("ALL")) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Map.Entry<String, String> e : all.entrySet()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", e.getKey());
        row.put("setting", e.getValue());
        row.put("description", null);
        rows.add(row);
      }
      return QueryParser.Plan.constant(
          java.util.Arrays.asList("name", "setting", "description"), rows, "SHOW");
    }
    String key = settingKey(m.group(1));
    if (key == null) {
      throw new IllegalArgumentException(
          "unrecognized configuration parameter \"" + m.group(1).trim() + "\"");
    }
    return QueryParser.Plan.constant(
        Collections.singletonList(key),
        Collections.singletonList(Collections.<String, Object>singletonMap(key, all.get(key))),
        "SHOW");
  }

  /** Thrown for any statement but COMMIT/ROLLBACK once a statement failed inside a transaction. */
  public static final class AbortedTransactionException extends IllegalStateException {
    AbortedTransactionException() {
      super("Current transaction is aborted, commands ignored until end of transaction block");
    }
  }

  /** The rows of one statement, pulled from ScalarDB as they are consumed; close it when done. */
  /** Prefix of a notice the server sends with WARNING severity instead of NOTICE. */
  public static final String WARNING = "WARNING: ";

  private Rows warning(String message) {
    Rows rows = new Rows(null, QueryParser.Cursor.EMPTY);
    rows.notices =
        Collections.singletonList(message.startsWith(WARNING) ? message : WARNING + message);
    return rows;
  }

  public final class Rows implements Iterator<Map<String, Object>>, AutoCloseable {
    @Nullable private final QueryParser.Plan plan;
    private final QueryParser.Cursor cursor;
    @Nullable private Map<String, Object> pending;
    private boolean exhausted;
    private int count;

    private List<String> notices = Collections.emptyList();

    private Rows(@Nullable QueryParser.Plan plan, QueryParser.Cursor cursor) {
      this.plan = plan;
      this.cursor = cursor;
      if (plan != null) {
        notices = plan.notices;
      }
    }

    /** Warnings to send the client before the command completes (PostgreSQL NOTICEs). */
    public List<String> notices() {
      return Collections.unmodifiableList(notices);
    }

    /** The output columns and their types where known (null for computed columns). */
    public Map<String, DataType> columns() {
      Map<String, DataType> columns = new LinkedHashMap<>();
      if (plan != null) {
        List<String> names = plan.getOutputColumns();
        List<DataType> types = plan.getOutputTypes();
        for (int i = 0; i < names.size(); i++) {
          columns.put(names.get(i), types.get(i));
        }
      }
      return columns;
    }

    /** The PostgreSQL type OIDs of the columns, inferred from the expressions; 0 when unknown. */
    public int[] oids() {
      return plan == null ? new int[0] : plan.outputOids();
    }

    /** The column names clients see, in the order of {@link #columns()}. */
    public List<String> labels() {
      return plan == null ? Collections.<String>emptyList() : plan.getColumnLabels();
    }

    /** True for a statement that produces a result set (even an empty one). */
    public boolean returnsRows() {
      return plan != null
          && (plan.commandTag(0).matches("(SELECT|EXPLAIN).*")
              || !plan.getOutputColumns().isEmpty()); // RETURNING
    }

    /**
     * @throws QueryParser.ReadFailure if a ScalarDB read fails; the cause is the original exception
     */
    @Override
    public boolean hasNext() {
      if (pending == null && !exhausted) {
        try {
          pending = cursor.next();
        } catch (RuntimeException e) {
          aborted |= transaction != null;
          throw e;
        }
        if (pending == null) {
          exhausted = true;
          if (plan != null) {
            lastCommandTag = plan.commandTag(count);
          }
        }
      }
      return pending != null;
    }

    @Override
    public Map<String, Object> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      Map<String, Object> row = Objects.requireNonNull(pending, "fetched by hasNext()");
      pending = null;
      count++;
      return row;
    }

    @Override
    public void close() {
      cursor.close();
    }
  }

  /**
   * Executes a SQL statement and returns all of its rows.
   *
   * @param sql a SQL statement
   * @return the rows for a {@code SELECT}, each a column-name-to-value map in select-list order; an
   *     empty list otherwise
   * @throws IllegalArgumentException if the SQL is invalid or unsupported
   * @throws IllegalStateException on {@code BEGIN} inside a transaction or {@code COMMIT}/{@code
   *     ROLLBACK} outside one
   * @throws TransactionException if the operation or the commit fails
   * @throws ExecutionException if the table metadata cannot be retrieved
   */
  public List<Map<String, Object>> execute(String sql)
      throws TransactionException, ExecutionException {
    try (Rows rows = open(sql)) {
      List<Map<String, Object>> out = new ArrayList<>();
      while (rows.hasNext()) {
        out.add(rows.next());
      }
      return out;
    } catch (QueryParser.ReadFailure e) {
      throw e.cause();
    }
  }

  /**
   * Executes a SQL statement, returning its rows as a stream to pull from. Control statements and
   * DML take effect before this method returns and yield no rows.
   */
  public Rows open(String sql) throws TransactionException, ExecutionException {
    return open(sql, Collections.emptyList());
  }

  /** As {@link #open(String)}, with values for the statement's {@code $n} placeholders. */
  public Rows open(String sql, List<Object> parameters)
      throws TransactionException, ExecutionException {
    applyZone();
    String word = firstWord(sql);
    if (aborted && !ENDS_TRANSACTION.contains(word)) {
      throw new AbortedTransactionException();
    }
    switch (word) {
      case "BEGIN":
      case "START":
        if (inTransaction()) {
          throw new IllegalStateException("A transaction is already active");
        }
        // BEGIN READ ONLY (what JDBC sends for a read-only connection) will start a read-only
        // ScalarDB transaction: no coordinator write and no validation at commit
        pendingReadOnly = isOn(current("default_transaction_read_only"));
        transactionCharacteristics(BEGIN_WORD.matcher(sql).replaceFirst(""));
        pending = true;
        lastCommandTag = word.equals("START") ? "START TRANSACTION" : "BEGIN";
        return new Rows(null, QueryParser.Cursor.EMPTY);
      case "COMMIT":
      case "END":
        if (!inTransaction()) {
          // As in PostgreSQL, only a warning: the error that ended the transaction stays visible
          lastCommandTag = "COMMIT";
          return new Rows(null, QueryParser.Cursor.EMPTY);
        }
        if (aborted) {
          // As in PostgreSQL, COMMIT of an aborted transaction rolls it back
          rollback();
          return new Rows(null, QueryParser.Cursor.EMPTY);
        }
        try {
          if (transaction != null) {
            transaction.commit();
          }
        } finally {
          endTransaction();
        }
        lastCommandTag = "COMMIT";
        return new Rows(null, QueryParser.Cursor.EMPTY);
      case "ROLLBACK":
      case "ABORT":
        if (SAVEPOINT_NAME.matcher(QueryParser.trimStatement(sql)).matches()
            && sql.toUpperCase(Locale.ROOT).contains(" TO ")) {
          // ROLLBACK TO SAVEPOINT: fine while the savepoint saw only reads, which also clears an
          // error since the failed statement wrote nothing
          if (!inTransaction()) {
            throw new IllegalStateException(
                "ROLLBACK TO SAVEPOINT can only be used in transaction blocks");
          }
          if (writesSinceSavepoint > 0) {
            throw new IllegalArgumentException(
                "Cannot roll back to a savepoint after writes: ScalarDB has no partial rollback");
          }
          aborted = false;
          lastCommandTag = "ROLLBACK";
          return new Rows(null, QueryParser.Cursor.EMPTY);
        }
        rollback();
        return new Rows(null, QueryParser.Cursor.EMPTY);
      case "SAVEPOINT":
        if (!inTransaction()) {
          throw new IllegalStateException("SAVEPOINT can only be used in transaction blocks");
        }
        java.util.regex.Matcher sp = SAVEPOINT_NAME.matcher(QueryParser.trimStatement(sql));
        savepoints.push(sp.matches() ? sp.group(1) : "");
        writesSinceSavepoint = 0;
        lastCommandTag = "SAVEPOINT";
        return new Rows(null, QueryParser.Cursor.EMPTY);
      case "RELEASE":
        if (!inTransaction()) {
          throw new IllegalStateException(
              "RELEASE SAVEPOINT can only be used in transaction blocks");
        }
        if (!savepoints.isEmpty()) {
          savepoints.pop();
        }
        lastCommandTag = "RELEASE";
        return new Rows(null, QueryParser.Cursor.EMPTY);
      case "SET":
        {
          java.util.regex.Matcher tx = SET_TRANSACTION.matcher(sql);
          if (tx.matches()) {
            if (tx.group(1) != null) {
              sessionTransactionDefaults(tx.group(2)); // SET SESSION CHARACTERISTICS AS ...
            } else if (!inTransaction()) {
              lastCommandTag = "SET";
              return warning("SET TRANSACTION can only be used in transaction blocks");
            } else if (transaction != null) {
              throw new IllegalStateException("SET TRANSACTION must be called before any query");
            } else {
              transactionCharacteristics(tx.group(2));
            }
            lastCommandTag = "SET";
            return new Rows(null, QueryParser.Cursor.EMPTY);
          }
        }
      // fall through
      case "RESET":
      case "DISCARD":
        // Session settings are recorded for SHOW; only the write cap changes behavior
        String warning = setting(sql);
        lastCommandTag = firstWord(sql).equals("DISCARD") ? "DISCARD ALL" : firstWord(sql);
        return warning == null ? new Rows(null, QueryParser.Cursor.EMPTY) : warning(warning);
      case "SHOW":
        {
          QueryParser.Plan plan = show(sql);
          return new Rows(plan, plan.open(manager, identity));
        }
      default:
        if (inTransaction() && WRITES.contains(word)) {
          writesSinceSavepoint++;
        }
        try {
          return statement(sql, parameters);
        } catch (RuntimeException | TransactionException | ExecutionException e) {
          aborted |= inTransaction();
          throw e;
        }
    }
  }

  /** BEGIN or SET TRANSACTION options: ISOLATION LEVEL, READ ONLY, READ WRITE. */
  private void transactionCharacteristics(String text) {
    java.util.regex.Matcher level = ISOLATION.matcher(text);
    if (level.find()) {
      String requested = level.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
      checkIsolation(requested); // throws before anything about the block changes
      settings.put("transaction_isolation", requested);
      transactionIsolationSet = true;
    }
    if (READ_ONLY.matcher(text).find()) {
      pendingReadOnly = true;
    } else if (READ_WRITE.matcher(text).find()) {
      pendingReadOnly = false;
    }
  }

  /** SET SESSION CHARACTERISTICS AS TRANSACTION ...: the defaults for later transactions. */
  private void sessionTransactionDefaults(String text) {
    java.util.regex.Matcher level = ISOLATION.matcher(text);
    if (level.find()) {
      String requested = level.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
      checkIsolation(requested);
      settings.put("default_transaction_isolation", requested);
    }
    if (READ_ONLY.matcher(text).find()) {
      settings.put("default_transaction_read_only", "on");
    } else if (READ_WRITE.matcher(text).find()) {
      settings.put("default_transaction_read_only", "off");
    }
  }

  /**
   * A requested isolation level must not exceed what the engine runs at: ScalarDB's SNAPSHOT is
   * READ COMMITTED here, SERIALIZABLE is SERIALIZABLE. A weaker request is accepted, as in
   * PostgreSQL. Ignoring a stronger one would silently drop a guarantee the client asked for.
   */
  private void checkIsolation(String requested) {
    String available = defaults.getOrDefault("transaction_isolation", "read committed");
    if (isolationRank(requested) > isolationRank(available)) {
      throw new IllegalArgumentException(
          "isolation level "
              + requested
              + " is not available: this ScalarDB runs at "
              + available
              + " (scalar.db.consensus_commit.isolation_level=SERIALIZABLE gives serializable)");
    }
  }

  private static int isolationRank(String level) {
    switch (level) {
      case "read uncommitted":
        return 0;
      case "read committed":
        return 1;
      case "repeatable read":
        return 2;
      default:
        return 3;
    }
  }

  private String current(String key) {
    return settings.getOrDefault(key, defaults.getOrDefault(key, ""));
  }

  private static boolean isOn(String value) {
    return value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equals("1");
  }

  /** Forgets the transaction and what BEGIN or SET TRANSACTION set for it. */
  private void endTransaction() {
    transaction = null;
    pending = false;
    pendingReadOnly = false;
    savepoints.clear();
    writesSinceSavepoint = 0;
    for (Map.Entry<String, String> saved : localSaved.entrySet()) {
      if (saved.getValue() == null) {
        settings.remove(saved.getKey()); // SET LOCAL ends with the transaction
      } else {
        settings.put(saved.getKey(), saved.getValue());
      }
    }
    localSaved.clear();
    searchPathChanged("search_path");
    if (transactionIsolationSet) {
      settings.remove("transaction_isolation");
      transactionIsolationSet = false;
    }
  }

  private static final java.util.Set<String> ENDS_TRANSACTION =
      new java.util.HashSet<>(java.util.Arrays.asList("COMMIT", "END", "ROLLBACK", "ABORT"));

  private void rollback() throws TransactionException {
    try {
      if (transaction != null) { // outside a transaction PostgreSQL only warns
        transaction.rollback();
      }
    } finally {
      endTransaction();
      aborted = false;
    }
    lastCommandTag = "ROLLBACK";
  }

  /** A statement other than transaction control and session settings. */
  private Rows statement(String sql, List<Object> parameters)
      throws TransactionException, ExecutionException {
    {
      QueryParser.Plan plan =
          describedPlan != null
                  && sql.equals(describedSql)
                  && parameters.equals(describedParameters)
              ? describedPlan
              : plan(sql, parameters);
      describedSql = null;
      describedPlan = null;
      if (plan.getDdl() != null) {
        // ScalarDB DDL is not transactional: inside BEGIN it takes effect at once and a later
        // ROLLBACK does not undo it. Migration tools wrap DDL in a transaction, so it is allowed.
        plan.getDdl().run();
        parser.invalidateMetadata();
        planCache.clear();
        lastCommandTag = plan.commandTag(0);
        settleMetadata(lastCommandTag);
        Rows rows = new Rows(null, QueryParser.Cursor.EMPTY);
        rows.notices = plan.notices;
        return rows;
      }
      if (plan.getWrite() != null) {
        // The read and the writes must share one transaction
        int written;
        if (inTransaction()) {
          written = plan.executeWrite(active(), maxRowsPerWrite(), identity);
        } else {
          DistributedTransaction own =
              isOn(current("default_transaction_read_only"))
                  ? manager.beginReadOnly()
                  : manager.begin();
          try {
            written = plan.executeWrite(own, maxRowsPerWrite(), identity);
            own.commit();
          } catch (RuntimeException | TransactionException e) {
            try {
              own.rollback();
            } catch (TransactionException rollback) {
              e.addSuppressed(rollback);
            }
            throw e;
          }
        }
        lastCommandTag = plan.commandTag(written);
        if (plan.getAnalyze() != null) {
          return new Rows(plan, plan.report());
        }
        return plan.getOutputColumns().isEmpty()
            ? new Rows(null, QueryParser.Cursor.EMPTY)
            : new Rows(plan, plan.returned());
      }
      QueryParser.Cursor cursor =
          inTransaction() ? plan.open(active(), identity) : plan.open(manager, identity);
      return new Rows(plan, cursor);
    }
  }

  /**
   * Plans a statement without executing it and returns its output columns.
   *
   * @param sql a SQL statement
   * @return the output column names mapped to their type where known from table metadata (null for
   *     computed columns); empty for statements that return no rows
   * @throws IllegalArgumentException if the SQL is invalid or unsupported
   * @throws ExecutionException if the table metadata cannot be retrieved
   */
  public Map<String, DataType> describe(String sql) throws ExecutionException {
    return describe(sql, Collections.emptyList());
  }

  /** As {@link #describe(String)}, with values for the statement's {@code $n} placeholders. */
  /** The PostgreSQL type OIDs of the columns {@link #describe} last returned; 0 when unknown. */
  public int[] describedOids() {
    return describedPlan == null ? new int[0] : describedPlan.outputOids();
  }

  /** The column names clients see for the statement {@link #describe} last returned columns for. */
  public List<String> describedLabels() {
    return describedPlan == null
        ? Collections.<String>emptyList()
        : describedPlan.getColumnLabels();
  }

  public Map<String, DataType> describe(String sql, List<Object> parameters)
      throws ExecutionException {
    applyZone();
    Map<String, DataType> columns = new LinkedHashMap<>();
    String word = firstWord(sql);
    if (aborted && !ENDS_TRANSACTION.contains(word)) {
      throw new AbortedTransactionException();
    }
    switch (word) {
      case "BEGIN":
      case "START":
      case "COMMIT":
      case "END":
      case "ROLLBACK":
      case "ABORT":
      case "SET":
      case "RESET":
      case "DISCARD":
      case "SAVEPOINT":
      case "RELEASE":
        return columns;
      case "SHOW":
        for (String name : show(sql).getOutputColumns()) {
          columns.put(name, null);
        }
        return columns;
      default:
        QueryParser.Plan plan = plan(sql, parameters);
        describedSql = sql;
        describedParameters = new ArrayList<>(parameters);
        describedPlan = plan;
        List<String> names = plan.getOutputColumns();
        List<DataType> types = plan.getOutputTypes();
        for (int i = 0; i < names.size(); i++) {
          columns.put(names.get(i), types.get(i));
        }
        return columns;
    }
  }

  /**
   * The cached plan for the statement, rebound to the values, or a new one (cached if it can be).
   */
  private QueryParser.Plan plan(String sql, List<Object> parameters) throws ExecutionException {
    QueryParser.Plan plan = planCache.get(sql);
    if (plan != null) {
      plan.rebind(parameters);
      return plan;
    }
    plan = parser.parse(sql, parameters);
    if (plan.isCacheable()) {
      planCache.put(sql, plan);
    }
    return plan;
  }

  /**
   * ScalarDB caches table metadata for {@code scalar.db.metadata.cache_expiration_time_secs} and
   * its admin does not refresh that cache, so after DDL that changes an existing table the engine
   * would reject the new columns or miss a new index until the entry expires. Answering such DDL
   * only after this many milliseconds keeps the client's next statement correct.
   */
  public void setDdlSettleMillis(long millis) {
    ddlSettleMillis = millis;
  }

  private void settleMetadata(String commandTag) {
    if (ddlSettleMillis > 0 && CHANGES_EXISTING_TABLES.contains(commandTag)) {
      // ponytail: sleeping past the cache expiry is the only hook; a core API to invalidate the
      // table metadata cache would make this immediate
      try {
        Thread.sleep(ddlSettleMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Plans cached in this session. */
  int cachedPlans() {
    return planCache.size();
  }

  /** The PostgreSQL command tag of the last executed statement, such as {@code SELECT 3}. */
  public String lastCommandTag() {
    return lastCommandTag;
  }

  public boolean inTransaction() {
    return transaction != null || pending;
  }

  /**
   * Records an error reported to the client outside {@link #open}, such as while describing a
   * statement: inside a transaction it aborts the transaction, which then accepts only {@code
   * ROLLBACK} or {@code COMMIT} (which rolls back).
   */
  public void fail() {
    aborted |= inTransaction();
  }

  /** True once a statement failed inside the transaction, until it is rolled back. */
  public boolean isAborted() {
    return aborted;
  }

  /** The transaction block's ScalarDB transaction, started on first use. */
  private DistributedTransaction active() throws TransactionException {
    if (transaction == null) {
      if (!pending) {
        throw new IllegalStateException("No active transaction");
      }
      transaction = pendingReadOnly ? manager.beginReadOnly() : manager.begin();
      pending = false;
    }
    return transaction;
  }

  private static String firstWord(String sql) {
    String[] words = sql.trim().replaceAll(";\\s*$", "").trim().split("\\s+", 2);
    return words[0].toUpperCase(Locale.ROOT);
  }
}
