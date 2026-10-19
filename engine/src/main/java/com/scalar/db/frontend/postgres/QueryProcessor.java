package com.scalar.db.frontend.postgres;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import com.scalar.db.io.DataType;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
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
  private boolean allowCoordinatorDrop; // scalar.db.postgres.allow_drop_coordinator_tables
  private int maxRowsPerWriteCeiling; // scalar.db.postgres.max_rows_per_write_ceiling; 0: none
  // When the transaction block began: now() for every statement in it, as in PostgreSQL
  @Nullable private Instant transactionStart;
  private boolean pendingReadOnly;
  private boolean transactionIsolationSet; // SHOW transaction_isolation reverts when it ends
  private boolean aborted; // a statement failed inside the transaction: only ROLLBACK/COMMIT now
  private String lastCommandTag = "";
  private long ddlSettleMillis;
  @Nullable private String ddlNotice; // when the cache is too long to wait out

  /** DDL after which the engine's cached metadata of an existing table is stale. */
  private static final Set<String> CHANGES_EXISTING_TABLES =
      new HashSet<>(
          Arrays.asList("DROP TABLE", "DROP SCHEMA", "DROP INDEX", "ALTER TABLE", "CREATE INDEX"));

  // The statement describe() planned last: the extended protocol describes a portal right before
  // executing it, and planning it once serves both
  // Plans by statement text, most recently used last: a reusable plan is planned once per session
  // Plans whose run is still open, such as a suspended portal's: a plan holds its run's operators,
  // so another run of the same statement must not be handed the same one
  private final Set<Plan> openPlans = Collections.newSetFromMap(new IdentityHashMap<>());

  private final Map<String, Plan> planCache =
      new LinkedHashMap<String, Plan>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Plan> eldest) {
          return size() > 256;
        }
      };
  @Nullable private String describedSql;
  private List<Object> describedParameters = Collections.emptyList();
  @Nullable private Plan describedPlan;
  // Savepoints: accepted inside a transaction; ROLLBACK TO can only be honored while nothing was
  // written since the savepoint, as ScalarDB has no partial rollback
  // Each savepoint with the transaction's write count when it was set, innermost first
  private final Deque<Map.Entry<String, Integer>> savepoints = new ArrayDeque<>();
  private int writes; // write statements run in this transaction, failed ones included
  // Whether every write so far was a plain INSERT: a conflict at commit then means an existing key
  private boolean onlyInserts = true;
  private static final Pattern ON_CONFLICT =
      Pattern.compile("\\bON\\s+CONFLICT\\b", Pattern.CASE_INSENSITIVE);
  private static final Set<String> WRITES =
      new HashSet<>(Arrays.asList("INSERT", "UPDATE", "DELETE", "TRUNCATE"));
  private static final Pattern SAVEPOINT_NAME =
      Pattern.compile(
          "(?is)(?:SAVEPOINT\\s+|RELEASE\\s+(?:SAVEPOINT\\s+)?|ROLLBACK\\s+(?:WORK\\s+|TRANSACTION\\s+)?TO\\s+(?:SAVEPOINT\\s+)?)\"?([\\w$]+)\"?");
  // Session settings: what SET stored, over the server's defaults; SHOW reads them
  private final Map<String, String> settings = new LinkedHashMap<>();
  private final Map<String, String> defaults = new LinkedHashMap<>();

  private static final Pattern READ_ONLY = Pattern.compile("(?i)\\bREAD\\s+ONLY\\b");
  private static final Pattern READ_WRITE = Pattern.compile("(?i)\\bREAD\\s+WRITE\\b");
  private static final Pattern ISOLATION =
      Pattern.compile(
          "(?i)\\bISOLATION\\s+LEVEL\\s+(READ\\s+UNCOMMITTED|READ\\s+COMMITTED|REPEATABLE\\s+READ|SERIALIZABLE)\\b");
  private static final Pattern SET_TRANSACTION =
      Pattern.compile("(?is)SET\\s+(SESSION\\s+CHARACTERISTICS\\s+AS\\s+)?TRANSACTION\\s+(.+)");
  private static final Pattern BEGIN_WORD =
      Pattern.compile("(?is)^\\s*(?:BEGIN|START)(?:\\s+(?:WORK|TRANSACTION))?");
  // The most rows a read-then-write statement may write; 0 means no limit
  static final String MAX_ROWS_PER_WRITE = "scalardb.max_rows_per_write";
  // SET ROLE and SET SESSION AUTHORIZATION, in every spelling, including RESET
  private static final Pattern SET_IDENTITY =
      Pattern.compile(
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
    for (String[] s : Catalog.SETTINGS) {
      defaults.put(s[0], s[1]);
    }
    defaults.put("is_superuser", "off"); // shown by SHOW, not by SHOW ALL or pg_settings
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
    parser.setCoordinatorDropAllowed(allowCoordinatorDrop || identity.superuser());
    parser.setUserManagement(true);
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
    sources.put(key == null ? name : key, "configuration file");
    searchPathChanged(key);
  }

  // pg_settings.source of the settings not SET in the session; absent: "default"
  private final Map<String, String> sources = new HashMap<>();

  private static final Pattern SET_VALUE =
      Pattern.compile(
          "(?is)SET\\s+(?:(SESSION|LOCAL)\\s+)?(?:([\\w.]+)\\s*(?:=|TO)\\s*(.+)|TIME\\s+ZONE\\s+(.+))");
  // SET LOCAL: the values to restore when the transaction ends (null: the setting was absent)
  private final Map<String, String> localSaved = new LinkedHashMap<>();
  // the session settings at BEGIN, which ROLLBACK restores
  // ponytail: ROLLBACK TO SAVEPOINT keeps SETs made after the savepoint; snapshot per savepoint
  // if a client relies on it
  private Map<String, String> settingsAtBegin = new LinkedHashMap<>();
  private String zoneName = "";
  private ZoneId zone = ZoneOffset.UTC;
  private static final Pattern RESET_NAME =
      Pattern.compile("(?is)RESET\\s+([\\w.]+|TIME\\s+ZONE|ALL)");
  private static final Pattern SHOW_NAME = Pattern.compile("(?is)SHOW\\s+(.+)");

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
      throw new PgException.Argument(
          "0A000",
          "Unsupported: SET ROLE and SET SESSION AUTHORIZATION, since an authenticated session runs"
              + " as the user it logged in as");
    }
    Matcher set = SET_VALUE.matcher(QueryParser.trimStatement(sql));
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
        String key = knownKey(name);
        if (key != null) {
          checkChangeable(key);
          put(key, null, local);
        }
        return null;
      }
      String key = knownKey(name);
      put(key == null ? name : key, checkedValue(key, name, value), local);
      return null;
    }
    return resetOrDiscard(sql);
  }

  /**
   * The stored key of a setting, null for a custom one (a dotted name such as myapp.x); as in
   * PostgreSQL, any other name the frontend does not know fails.
   */
  @Nullable private String knownKey(String name) {
    String key = settingKey(name);
    if (key == null && name.indexOf('.') < 0) {
      throw new PgException.Argument(
          "42704", "unrecognized configuration parameter \"" + name + "\"");
    }
    return key;
  }

  /** SET or SET LOCAL of a setting; a null value returns it to its default. */
  private void put(String key, @Nullable String value, boolean local) {
    if (local) {
      if (!localSaved.containsKey(key)) {
        localSaved.put(key, settings.get(key));
      }
    } else if (localSaved.containsKey(key)) {
      localSaved.put(key, value); // as in PostgreSQL, a later SET outlasts the SET LOCAL
    }
    if (value == null) {
      settings.remove(key);
    } else {
      settings.put(key, value);
    }
    searchPathChanged(key);
  }

  /** As PostgreSQL: is_superuser reports the login, and the internal settings the server. */
  private static void checkChangeable(@Nullable String key) {
    String[] known = key == null ? null : Catalog.setting(key);
    if ("is_superuser".equals(key) || known != null && known[3].equals("internal")) {
      throw new PgException.Argument("55P02", "parameter \"" + key + "\" cannot be changed");
    }
  }

  /**
   * A setting's value as SET would store it, after the checks SET makes; every way a client sets
   * one (SET, set_config, the startup message and its options) goes through here.
   */
  private String checkedValue(@Nullable String key, String name, String value) {
    if (identity != null
        && (name.equalsIgnoreCase("role") || name.equalsIgnoreCase("session_authorization"))) {
      throw new PgException.Argument(
          "0A000",
          "Unsupported: SET ROLE and SET SESSION AUTHORIZATION, since an authenticated session runs"
              + " as the user it logged in as");
    }
    if ("TimeZone".equals(key)) {
      value = zoneOf(value).getId().equals("Z") ? "UTC" : value; // validated here
    }
    if ("DateStyle".equals(key)) {
      value = dateStyle(value, current("DateStyle"));
    }
    if ("IntervalStyle".equals(key) || "bytea_output".equals(key)) {
      String style = value.trim().replace("'", "").toLowerCase(Locale.ROOT);
      if (!style.matches("postgres|postgres_verbose|sql_standard|iso_8601|hex|escape")
          || (style.matches("hex|escape") != "bytea_output".equals(key))) {
        throw new PgException.Argument(
            "22023", "invalid value for parameter \"" + key + "\": \"" + value + "\"");
      }
      value = style;
    }
    if ("transaction_isolation".equals(key) || "default_transaction_isolation".equals(key)) {
      value = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
      checkIsolation(value);
    }
    checkChangeable(key);
    String[] known = key == null ? null : Catalog.setting(key);
    if (known != null && "ms".equals(known[4])) { // statement_timeout, lock_timeout
      if (!DURATION.matcher(value.trim().replace("'", "")).matches()) {
        throw new PgException.Argument(
            "22023", "invalid value for parameter \"" + key + "\": \"" + value + "\"");
      }
      value = duration(millis(value)); // as SHOW writes it: 5000 is 5s
    }
    if (MAX_ROWS_PER_WRITE.equals(key) && !value.matches("\\d{1,9}")) {
      throw new PgException.Argument(
          "22023", "invalid value for parameter \"" + MAX_ROWS_PER_WRITE + "\": \"" + value + "\"");
    }
    if (MAX_ROWS_PER_WRITE.equals(key) && maxRowsPerWriteCeiling > 0) {
      int rows = Integer.parseInt(value);
      if (rows == 0 || rows > maxRowsPerWriteCeiling) {
        // 0 is no cap at all; the operator's ceiling bounds what a client may ask for
        throw new PgException.Argument(
            "42501",
            "permission denied to set parameter \""
                + MAX_ROWS_PER_WRITE
                + "\" above "
                + maxRowsPerWriteCeiling
                + " (scalar.db.postgres.max_rows_per_write_ceiling)");
      }
    }
    return value;
  }

  /**
   * DateStyle as PostgreSQL stores it, "ISO, MDY": an output style and a field order, each kept
   * from the current value when not given (ymd is "ISO, YMD"). Clients parse the reported value
   * (psycopg fails on a bare "ymd"). Only ISO output is implemented, so another style is refused.
   */
  private static String dateStyle(String value, String current) {
    String style = null;
    String order = null;
    for (String word : value.replace("'", "").split("[,\\s]+")) {
      String w = word.toUpperCase(Locale.ROOT);
      String o =
          w.equals("YMD")
              ? "YMD"
              : w.equals("DMY") || w.startsWith("EURO")
                  ? "DMY"
                  : w.equals("MDY") || w.equals("US") || w.startsWith("NONEURO") ? "MDY" : null;
      if (w.isEmpty()) {
        continue;
      } else if (o != null) {
        if (order != null && !order.equals(o)) {
          throw new PgException.Argument(
              "22023", "invalid value for parameter \"DateStyle\": \"" + value + "\"");
        }
        order = o;
      } else if (w.equals("ISO") || w.equals("SQL") || w.equals("POSTGRES") || w.equals("GERMAN")) {
        style = w;
      } else if (!w.equals("DEFAULT")) {
        throw new PgException.Argument(
            "22023", "invalid value for parameter \"DateStyle\": \"" + value + "\"");
      }
    }
    if (style != null && !style.equals("ISO")) {
      throw new PgException.Argument(
          "0A000", "DateStyle " + style + " is not supported: dates are written in ISO style here");
    }
    String[] now = current == null ? new String[0] : current.split(",\\s*");
    return "ISO, " + (order != null ? order : now.length > 1 ? now[1].trim() : "MDY");
  }

  /**
   * A setting the client gave at connection time (a startup parameter, or {@code -c} in its
   * options): a session default, checked as SET checks it.
   */
  public void setClientDefault(String name, String value) {
    String key = knownKey(name);
    defaults.put(key == null ? name : key, checkedValue(key, name, value));
    sources.put(key == null ? name : key, "client");
    searchPathChanged(key);
  }

  @Nullable private String resetOrDiscard(String sql) {
    Matcher reset = RESET_NAME.matcher(QueryParser.trimStatement(sql));
    if (reset.matches()) {
      if (reset.group(1).equalsIgnoreCase("ALL")) {
        settings.clear();
      } else {
        String key = knownKey(reset.group(1));
        if (key != null) {
          checkChangeable(key);
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

  // SET TIME ZONE INTERVAL '+09:00' HOUR TO MINUTE (Sequelize sends this on every connection)
  private static final Pattern INTERVAL_ZONE =
      Pattern.compile("(?i)^INTERVAL\\s+'([+-]?\\d{1,2}(?::\\d{2})?)'\\s+HOUR\\s+TO\\s+MINUTE$");

  // SET TIME ZONE 9, SET TIME ZONE '-3.5': hours east of UTC
  private static final Pattern NUMERIC_ZONE = Pattern.compile("^[+-]?\\d{1,2}(?:\\.\\d+)?$");
  // SET TIME ZONE '+05:30', 'UTC+9': a POSIX zone, whose offset is positive WEST of UTC
  private static final Pattern POSIX_ZONE =
      Pattern.compile("(?i)^(?:UTC|GMT)?([+-])(\\d{1,2})(?::(\\d{2}))?$");

  static ZoneId zoneOf(String name) {
    String text = name.trim();
    Matcher m = INTERVAL_ZONE.matcher(text);
    if (m.matches()) {
      return offset(m.group(1), false);
    }
    if (NUMERIC_ZONE.matcher(text).matches()) {
      return ZoneOffset.ofTotalSeconds((int) Math.round(Double.parseDouble(text) * 3600));
    }
    if (POSIX_ZONE.matcher(text).matches()) {
      return offset(text.replaceFirst("(?i)^(UTC|GMT)", ""), true);
    }
    try {
      return ZoneId.of(name, ZoneId.SHORT_IDS);
    } catch (DateTimeException e) {
      try {
        return ZoneId.of(name.toUpperCase(Locale.ROOT), ZoneId.SHORT_IDS);
      } catch (DateTimeException again) {
        throw new PgException.Argument(
            "22023", "invalid value for parameter \"TimeZone\": \"" + name + "\"");
      }
    }
  }

  /** {@code [+-]hh[:mm]} as an offset; a POSIX spec counts the other way round. */
  private static ZoneOffset offset(String text, boolean posix) {
    Matcher m = Pattern.compile("^([+-]?)(\\d{1,2})(?::(\\d{2}))?$").matcher(text);
    if (!m.matches()) {
      throw new PgException.Argument(
          "22023", "invalid value for parameter \"TimeZone\": \"" + text + "\"");
    }
    int sign = (m.group(1).equals("-") ? -1 : 1) * (posix ? -1 : 1);
    int minutes = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
    return ZoneOffset.ofHoursMinutes(sign * Integer.parseInt(m.group(2)), sign * minutes);
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
  private Plan show(String sql) {
    Matcher m = SHOW_NAME.matcher(QueryParser.trimStatement(sql));
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid SQL: " + sql);
    }
    Map<String, String> all = new LinkedHashMap<>(defaults);
    all.putAll(settings);
    if (m.group(1).equalsIgnoreCase("ALL")) {
      List<Map<String, Object>> rows = new ArrayList<>();
      // as in PostgreSQL: the settings pg_settings has, by name; not is_superuser or custom ones
      for (String[] s : Catalog.SETTINGS) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", s[0]);
        row.put("setting", all.get(s[0]));
        row.put("description", s[5]);
        rows.add(row);
      }
      return Plan.constant(Arrays.asList("name", "setting", "description"), rows, "SHOW");
    }
    String key = settingKey(m.group(1));
    if (key == null) {
      throw new PgException.Argument(
          "42704", "unrecognized configuration parameter \"" + m.group(1).trim() + "\"");
    }
    return Plan.constant(
        Collections.singletonList(key),
        Collections.singletonList(Collections.<String, Object>singletonMap(key, all.get(key))),
        "SHOW");
  }

  /** Thrown for any statement but COMMIT/ROLLBACK once a statement failed inside a transaction. */
  public static final class AbortedTransactionException extends IllegalStateException {
    AbortedTransactionException() {
      super("current transaction is aborted, commands ignored until end of transaction block");
    }
  }

  /** The rows of one statement, pulled from ScalarDB as they are consumed; close it when done. */
  /** Prefix of a notice the server sends with WARNING severity instead of NOTICE. */
  public static final String WARNING = "WARNING: ";

  private Rows warning(String message) {
    Rows rows = new Rows(null, Plan.Cursor.EMPTY);
    rows.notices =
        Collections.singletonList(message.startsWith(WARNING) ? message : WARNING + message);
    return rows;
  }

  public final class Rows implements Iterator<Map<String, Object>>, AutoCloseable {
    @Nullable private final Plan plan;
    private final Plan.Cursor cursor;
    @Nullable private Map<String, Object> pending;
    private boolean exhausted;
    private int count;

    private List<String> notices = Collections.emptyList();

    private Rows(@Nullable Plan plan, Plan.Cursor cursor) {
      this.plan = plan;
      this.cursor = cursor;
      if (plan != null) {
        notices = plan.notices;
        openPlans.add(plan);
      }
    }

    /**
     * Starts a new statement for the rows still to come, as a suspended portal's next Execute is in
     * PostgreSQL: a fresh statement_timeout and regex budget.
     */
    public void resume() {
      startStatement();
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

    /** The type modifiers of the columns (numeric(10,2) and the like); -1 where there is none. */
    public int[] typmods() {
      return plan == null ? new int[0] : plan.outputTypmods();
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
     * @throws Plan.ReadFailure if a ScalarDB read fails; the cause is the original exception
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
      if (plan != null) {
        openPlans.remove(plan);
        plan.release(); // a cached plan must not keep this run's rows and operators (todo 008)
      }
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
    } catch (Plan.ReadFailure e) {
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

  private void startStatement() {
    applyZone();
    Instant now = inTransaction() && transactionStart != null ? transactionStart : Instant.now();
    Evaluator.startStatement(now, millis(current("statement_timeout")));
    Evaluator.setSettings(sessionSettings);
  }

  /** As {@link #open(String)}, with values for the statement's {@code $n} placeholders. */
  public Rows open(String sql, List<Object> parameters)
      throws TransactionException, ExecutionException {
    startStatement();
    String word = firstWord(sql);
    if (aborted && !ENDS_TRANSACTION.contains(word)) {
      throw new AbortedTransactionException();
    }
    if (ENDS_TRANSACTION.contains(word) && AND_CHAIN.matcher(sql).find()) {
      return chained(word, sql, parameters);
    }
    switch (word) {
      case "BEGIN":
      case "START":
        if (inTransaction()) {
          // As in PostgreSQL: a warning, and the block goes on (todo 010)
          lastCommandTag = "BEGIN";
          return warning("there is already a transaction in progress");
        }
        // BEGIN READ ONLY (what JDBC sends for a read-only connection) will start a read-only
        // ScalarDB transaction: no coordinator write and no validation at commit
        pendingReadOnly = isOn(current("default_transaction_read_only"));
        transactionCharacteristics(BEGIN_WORD.matcher(sql).replaceFirst(""));
        pending = true;
        settingsAtBegin = new LinkedHashMap<>(settings);
        transactionStart = Instant.now();
        lastCommandTag = word.equals("START") ? "START TRANSACTION" : "BEGIN";
        return new Rows(null, Plan.Cursor.EMPTY);
      case "COMMIT":
      case "END":
        if (!inTransaction()) {
          // As in PostgreSQL, only a warning: the error that ended the transaction stays visible
          lastCommandTag = "COMMIT";
          return warning("there is no transaction in progress");
        }
        if (aborted) {
          // As in PostgreSQL, COMMIT of an aborted transaction rolls it back
          rollback();
          return new Rows(null, Plan.Cursor.EMPTY);
        }
        boolean committed = false;
        try {
          if (transaction != null) {
            transaction.commit();
          }
          committed = true;
        } catch (TransactionException e) {
          // ScalarDB finds an INSERT's existing key only at commit, as the conflict DB-CORE-20013,
          // which is also what a write-write conflict raises. After plain INSERTs alone it is
          // PostgreSQL's unique violation, as in a one-shot INSERT, and must not be retried
          if (onlyInserts
              && writes > 0
              && String.valueOf(e.getMessage()).contains("DB-CORE-20013")) {
            throw new Plan.UniqueViolationException(e);
          }
          throw e;
        } finally {
          endTransaction(committed);
        }
        lastCommandTag = "COMMIT";
        return new Rows(null, Plan.Cursor.EMPTY);
      case "ROLLBACK":
      case "ABORT":
        if (SAVEPOINT_NAME.matcher(QueryParser.trimStatement(sql)).matches()
            && sql.toUpperCase(Locale.ROOT).contains(" TO ")) {
          // ROLLBACK TO SAVEPOINT: fine while the savepoint saw only reads, which also clears an
          // error since the failed statement wrote nothing
          if (!inTransaction()) {
            throw new PgException.State(
                "25P01", "ROLLBACK TO SAVEPOINT can only be used in transaction blocks");
          }
          Map.Entry<String, Integer> target = savepoint(sql);
          if (writes > target.getValue()) {
            throw new PgException.Argument(
                "0A000",
                "Cannot roll back to a savepoint after writes: ScalarDB has no partial rollback");
          }
          while (savepoints.peek() != target) {
            savepoints.pop(); // the savepoint itself stays, as in PostgreSQL
          }
          aborted = false;
          lastCommandTag = "ROLLBACK";
          return new Rows(null, Plan.Cursor.EMPTY);
        }
        boolean active = inTransaction();
        rollback();
        return active
            ? new Rows(null, Plan.Cursor.EMPTY)
            : warning("there is no transaction in progress");
      case "SAVEPOINT":
        if (!inTransaction()) {
          throw new PgException.State("25P01", "SAVEPOINT can only be used in transaction blocks");
        }
        Matcher sp = SAVEPOINT_NAME.matcher(QueryParser.trimStatement(sql));
        savepoints.push(
            new AbstractMap.SimpleImmutableEntry<>(sp.matches() ? sp.group(1) : "", writes));
        lastCommandTag = "SAVEPOINT";
        return new Rows(null, Plan.Cursor.EMPTY);
      case "RELEASE":
        if (!inTransaction()) {
          throw new PgException.State(
              "25P01", "RELEASE SAVEPOINT can only be used in transaction blocks");
        }
        Map.Entry<String, Integer> released = savepoint(sql);
        while (savepoints.pop() != released) {
          // releases the savepoints set after it too
        }
        lastCommandTag = "RELEASE";
        return new Rows(null, Plan.Cursor.EMPTY);
      case "SET":
        {
          // on the trimmed text, as the other statement patterns: no backtracking tail (todo 029)
          Matcher tx = SET_TRANSACTION.matcher(QueryParser.trimStatement(sql));
          if (tx.matches()) {
            if (tx.group(1) != null) {
              sessionTransactionDefaults(tx.group(2)); // SET SESSION CHARACTERISTICS AS ...
            } else if (!inTransaction()) {
              lastCommandTag = "SET";
              return warning("SET TRANSACTION can only be used in transaction blocks");
            } else if (transaction != null) {
              throw new PgException.State(
                  "25001", "SET TRANSACTION must be called before any query");
            } else {
              transactionCharacteristics(tx.group(2));
            }
            lastCommandTag = "SET";
            return new Rows(null, Plan.Cursor.EMPTY);
          }
        }
      // fall through
      case "RESET":
      case "DISCARD":
        // Session settings are recorded for SHOW; only the write cap changes behavior
        String warning = setting(sql);
        lastCommandTag = firstWord(sql).equals("DISCARD") ? "DISCARD ALL" : firstWord(sql);
        return warning == null ? new Rows(null, Plan.Cursor.EMPTY) : warning(warning);
      case "SHOW":
        {
          Plan plan = show(sql);
          return new Rows(plan, plan.open(manager, identity));
        }
      default:
        if (inTransaction() && WRITES.contains(word)) {
          writes++;
          if (!word.equals("INSERT") || ON_CONFLICT.matcher(sql).find()) {
            onlyInserts = false;
          }
        }
        try {
          checkWritable(word, sql);
          return statement(sql, parameters);
        } catch (RuntimeException | TransactionException | ExecutionException e) {
          aborted |= inTransaction();
          throw e;
        }
    }
  }

  private static final java.util.Set<String> DDL =
      new java.util.HashSet<>(
          Arrays.asList("CREATE", "DROP", "ALTER", "TRUNCATE", "GRANT", "REVOKE", "COMMENT"));

  /**
   * As PostgreSQL: a write or DDL in a read-only transaction is 25006, whether the block was begun
   * READ ONLY or default_transaction_read_only (SET SESSION CHARACTERISTICS) makes a single
   * statement's own transaction read-only.
   */
  private void checkWritable(String word, String sql) {
    boolean readOnly =
        inTransaction() ? pendingReadOnly : isOn(current("default_transaction_read_only"));
    if (!readOnly || !(WRITES.contains(word) || DDL.contains(word))) {
      return;
    }
    String[] words = QueryParser.trimStatement(sql).split("\\s+");
    String command =
        word.equals("TRUNCATE")
            ? "TRUNCATE TABLE"
            : DDL.contains(word) && words.length > 1
                ? word
                    + " "
                    + words[words[1].equalsIgnoreCase("UNIQUE") && words.length > 2 ? 2 : 1]
                        .toUpperCase(Locale.ROOT)
                : word;
    throw new PgException.Argument(
        "25006", "cannot execute " + command + " in a read-only transaction");
  }

  private static final Pattern AND_CHAIN = Pattern.compile("(?i)\\s+AND\\s+CHAIN\\b");

  /**
   * COMMIT or ROLLBACK AND CHAIN: ends the block, then begins a new one with the same isolation
   * level and read-only status, as PostgreSQL does; outside a block it is 25P01.
   */
  private Rows chained(String word, String sql, List<Object> parameters)
      throws TransactionException, ExecutionException {
    if (!inTransaction()) {
      throw new PgException.State(
          "25P01",
          (word.equals("ROLLBACK") || word.equals("ABORT") ? "ROLLBACK" : "COMMIT")
              + " AND CHAIN can only be used in transaction blocks");
    }
    boolean readOnly = pendingReadOnly;
    String isolation = transactionIsolationSet ? settings.get("transaction_isolation") : null;
    Rows ended = open(AND_CHAIN.matcher(sql).replaceFirst(""), parameters);
    pending = true;
    pendingReadOnly = readOnly;
    settingsAtBegin = new LinkedHashMap<>(settings);
    transactionStart = Instant.now();
    if (isolation != null) {
      settings.put("transaction_isolation", isolation);
      transactionIsolationSet = true;
    }
    return ended;
  }

  /** The innermost savepoint named in a ROLLBACK TO or RELEASE statement. */
  private Map.Entry<String, Integer> savepoint(String sql) {
    Matcher m = SAVEPOINT_NAME.matcher(QueryParser.trimStatement(sql));
    String name = m.matches() ? m.group(1) : "";
    for (Map.Entry<String, Integer> s : savepoints) {
      if (s.getKey().equalsIgnoreCase(name)) {
        return s;
      }
    }
    throw new PgException.Argument("3B001", "savepoint \"" + name + "\" does not exist");
  }

  /** BEGIN or SET TRANSACTION options: ISOLATION LEVEL, READ ONLY, READ WRITE. */
  private void transactionCharacteristics(String text) {
    Matcher level = ISOLATION.matcher(text);
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
    Matcher level = ISOLATION.matcher(text);
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
      throw new PgException.Argument(
          "0A000",
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

  private static final Pattern DURATION =
      Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|min|h|d)?", Pattern.CASE_INSENSITIVE);

  /** A statement_timeout in milliseconds, PostgreSQL's default unit; 0 (the default) for none. */
  static long millis(String value) {
    Matcher m = DURATION.matcher(value.trim().replace("'", ""));
    if (!m.matches()) {
      return 0;
    }
    double amount = Double.parseDouble(m.group(1));
    String unit = m.group(2) == null ? "ms" : m.group(2).toLowerCase(Locale.ROOT);
    double millis;
    switch (unit) {
      case "s":
        millis = amount * 1_000;
        break;
      case "min":
        millis = amount * 60_000;
        break;
      case "h":
        millis = amount * 3_600_000;
        break;
      case "d":
        millis = amount * 86_400_000;
        break;
      default:
        millis = amount;
    }
    return Math.round(Math.min(millis, Integer.MAX_VALUE));
  }

  /** Milliseconds as SHOW writes them: in the largest unit that holds them whole. */
  private static String duration(long millis) {
    long[] sizes = {86_400_000, 3_600_000, 60_000, 1_000};
    String[] units = {"d", "h", "min", "s"};
    for (int i = 0; millis != 0 && i < sizes.length; i++) {
      if (millis % sizes[i] == 0) {
        return millis / sizes[i] + units[i];
      }
    }
    return millis == 0 ? "0" : millis + "ms";
  }

  private String current(String key) {
    return settings.getOrDefault(key, defaults.getOrDefault(key, ""));
  }

  private static boolean isOn(String value) {
    return value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equals("1");
  }

  /** Forgets the transaction and what BEGIN, SET TRANSACTION and, if it rolled back, SET set. */
  private void endTransaction(boolean committed) {
    transaction = null;
    transactionStart = null;
    pending = false;
    pendingReadOnly = false;
    savepoints.clear();
    writes = 0;
    onlyInserts = true;
    if (!committed) {
      localSaved.clear();
      settings.clear();
      settings.putAll(settingsAtBegin);
    }
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

  private static final Set<String> ENDS_TRANSACTION =
      new HashSet<>(Arrays.asList("COMMIT", "END", "ROLLBACK", "ABORT"));

  private void rollback() throws TransactionException {
    try {
      if (transaction != null) { // outside a transaction PostgreSQL only warns
        transaction.rollback();
      }
    } finally {
      endTransaction(false);
      aborted = false;
    }
    lastCommandTag = "ROLLBACK";
  }

  /** A statement other than transaction control and session settings. */
  private Rows statement(String sql, List<Object> parameters)
      throws TransactionException, ExecutionException {
    {
      Plan plan =
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
        Rows rows =
            plan.getOutputColumns().isEmpty()
                ? new Rows(null, Plan.Cursor.EMPTY)
                : new Rows(plan, plan.open(manager, identity)); // what the DDL reports
        rows.notices = plan.notices;
        if (ddlNotice != null && CHANGES_EXISTING_TABLES.contains(lastCommandTag)) {
          rows.notices = new ArrayList<>(plan.notices);
          rows.notices.add(ddlNotice);
        }
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
          } catch (UnknownTransactionStatusException e) {
            // The commit may have landed: as ScalarDB advises, no rollback, and the client checks
            // the outcome by the transaction ID in the message (40003), as after a COMMIT
            throw e;
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
            ? new Rows(null, Plan.Cursor.EMPTY)
            : new Rows(plan, plan.returned());
      }
      Plan.Cursor cursor =
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

  /** The type of placeholder {@code $index} in the statement {@link #describe} last planned. */
  public int describedParameterOid(int index) {
    return describedPlan == null ? 0 : describedPlan.parameterOid(index);
  }

  /** The type modifiers of the columns {@link #describe} last returned; -1 where there is none. */
  public int[] describedTypmods() {
    return describedPlan == null ? new int[0] : describedPlan.outputTypmods();
  }

  /** The column names clients see for the statement {@link #describe} last returned columns for. */
  public List<String> describedLabels() {
    return describedPlan == null
        ? Collections.<String>emptyList()
        : describedPlan.getColumnLabels();
  }

  public Map<String, DataType> describe(String sql, List<Object> parameters)
      throws ExecutionException {
    describedPlan = null; // nothing of a previous statement, if this one plans nothing
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
        {
          // the labels and OIDs of the RowDescription come from the described plan: without this
          // they were the previous statement's, and the client rejected the rows (todo 004)
          Plan plan = show(sql);
          describedSql = null; // SHOW is answered afresh, never from this plan
          describedPlan = plan;
          for (String name : plan.getOutputColumns()) {
            columns.put(name, null);
          }
          return columns;
        }
      default:
        Plan plan = plan(sql, parameters);
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
  private Plan plan(String sql, List<Object> parameters) throws ExecutionException {
    if (parser.metadataChanged()) {
      planCache.clear(); // another session (or this one) changed a table
    }
    // An unqualified table resolves through search_path, so a plan is only valid under the path
    // it was made with
    String key = current("search_path") + '\u0000' + sql;
    Plan plan = planCache.get(key);
    if (plan != null && !openPlans.contains(plan)) {
      plan.rebind(parameters);
      return plan;
    }
    plan = parser.parse(sql, parameters);
    if (plan.isCacheable()) {
      planCache.put(key, plan);
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

  /** The NOTICE sent after DDL on an existing table when the cache is too long to wait out. */
  void setDdlNotice(@Nullable String notice) {
    ddlNotice = notice;
  }

  /** How often cached tables are compared for DDL from another process; 0 for never. */
  void setMetadataCheckNanos(long nanos) {
    parser.setCheckIntervalNanos(nanos);
  }

  /**
   * The most digits a numeric(p,s) column created in this session may have to be stored as a scaled
   * bigint: 18, or 15 where the storage keeps a bigint exact only up to 2^53 (see {@link
   * ColumnType#maxScaledPrecision}).
   */
  /** The deployment's store of declared column types. */
  void setTypeStore(TypeStore store) {
    parser.setTypeStore(store);
  }

  public void setMaxScaledPrecision(int digits) {
    parser.setMaxScaledPrecision(digits);
  }

  private static final Pattern SETTING_NAME = Pattern.compile("[A-Za-z_][\\w.]*");

  // current_setting and set_config read and write the settings SHOW and SET do
  private final Evaluator.Settings sessionSettings =
      new Evaluator.Settings() {
        @Override
        @Nullable public String get(String name) {
          String key = settingKey(name);
          return key == null ? null : current(key);
        }

        @Override
        public String set(String name, String value, boolean local) {
          if (!SETTING_NAME.matcher(name).matches()) {
            throw new PgException.Argument(
                "42704", "unrecognized configuration parameter \"" + name + "\"");
          }
          // outside a transaction a local set_config is not kept, as SET LOCAL is not, but it
          // still answers the value it would have set, as PostgreSQL does
          setting(
              "SET " + (local ? "LOCAL " : "") + name + " TO '" + value.replace("'", "''") + "'");
          String key = settingKey(name);
          return local && !inTransaction()
              ? checkedValue(key, name, value)
              : current(key == null ? name : key);
        }

        @Override
        public String source(String name) {
          String key = settingKey(name);
          return settings.containsKey(key) ? "session" : sources.getOrDefault(key, "default");
        }

        @Override
        public String resetValue(String name) {
          return defaults.getOrDefault(name, ""); // a name from Catalog.SETTINGS, as stored
        }
      };

  /** Lets DROP COORDINATOR TABLES run for any session, not only a Cluster superuser's. */
  public void setAllowCoordinatorDrop(boolean allow) {
    allowCoordinatorDrop = allow;
    parser.setCoordinatorDropAllowed(allow || (identity != null && identity.superuser()));
  }

  /**
   * The most rows per write a session may set {@code scalardb.max_rows_per_write} to; 0 for no
   * ceiling. The default is lowered to it if higher.
   */
  public void setMaxRowsPerWriteCeiling(int ceiling) {
    maxRowsPerWriteCeiling = ceiling;
    if (ceiling > 0 && Long.parseLong(defaults.get(MAX_ROWS_PER_WRITE)) > ceiling) {
      defaults.put(MAX_ROWS_PER_WRITE, String.valueOf(ceiling));
    }
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
        throw new PgException.State("25P01", "No active transaction");
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
