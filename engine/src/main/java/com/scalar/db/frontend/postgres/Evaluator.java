package com.scalar.db.frontend.postgres;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnalyticType;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.AnyType;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.CollateExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.IntervalExpression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.RowGetExpression;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TimezoneExpression;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.BitwiseXor;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperator;
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperatorType;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Evaluates a SQL expression in memory over rows that ScalarDB returned. A row maps {@code
 * qualifier.column} keys to the Java values ScalarDB uses. Expressions are evaluated against a
 * group of rows so that aggregate functions work; scalar expressions read the first row of the
 * group. NULL follows SQL three-valued logic: comparisons with NULL yield null, and only {@code
 * Boolean.TRUE} passes a filter.
 */
final class Evaluator {
  private static final List<String> AGGREGATES =
      Arrays.asList(
          "JSON_AGG",
          "JSONB_AGG",
          "JSON_OBJECT_AGG",
          "JSONB_OBJECT_AGG",
          "COUNT",
          "SUM",
          "AVG",
          "MIN",
          "MAX",
          "BOOL_AND",
          "BOOL_OR",
          "EVERY",
          "STRING_AGG",
          "ARRAY_AGG");

  private Evaluator() {}

  /** The outer row of a correlated subquery and the plans of the subqueries in the expression. */
  static final class Context {
    static final Context EMPTY = new Context(null, Collections.emptyMap(), null);

    @Nullable final Map<String, Object> outer;
    final Map<Select, QueryParser.Plan> subplans;
    @Nullable final Catalog catalog;
    final List<Object> parameters; // the statement's $n values, 1-based
    final Map<String, com.scalar.db.io.DataType> types; // alias.column -> type, for NULL values
    final List<LogicalPlan.Source> sources; // the plan's FROM items, for plan-time types
    final Map<Expression, Integer> widenings; // CASE, COALESCE, ... -> what it widens to

    Context(
        @Nullable Map<String, Object> outer,
        Map<Select, QueryParser.Plan> subplans,
        @Nullable Catalog catalog) {
      this(outer, subplans, catalog, Collections.emptyList());
    }

    Context(
        @Nullable Map<String, Object> outer,
        Map<Select, QueryParser.Plan> subplans,
        @Nullable Catalog catalog,
        List<Object> parameters) {
      this(outer, subplans, catalog, parameters, Collections.emptyMap());
    }

    Context(
        @Nullable Map<String, Object> outer,
        Map<Select, QueryParser.Plan> subplans,
        @Nullable Catalog catalog,
        List<Object> parameters,
        Map<String, com.scalar.db.io.DataType> types) {
      this(
          outer,
          subplans,
          catalog,
          parameters,
          types,
          Collections.emptyList(),
          new java.util.IdentityHashMap<>());
    }

    Context(
        @Nullable Map<String, Object> outer,
        Map<Select, QueryParser.Plan> subplans,
        @Nullable Catalog catalog,
        List<Object> parameters,
        Map<String, com.scalar.db.io.DataType> types,
        List<LogicalPlan.Source> sources,
        Map<Expression, Integer> widenings) {
      this.outer = outer;
      this.subplans = subplans;
      this.catalog = catalog;
      this.parameters = parameters;
      this.types = types;
      this.sources = sources;
      this.widenings = widenings;
    }

    /** The declared type of a column of this plan's sources, or null if not known. */
    @Nullable com.scalar.db.io.DataType columnType(Column c) {
      String name = QueryParser.unquote(c.getColumnName());
      if (c.getTable() != null) {
        return types.get(QueryParser.unquote(c.getTable().getName()) + "." + name);
      }
      for (Map.Entry<String, com.scalar.db.io.DataType> e : types.entrySet()) {
        if (e.getKey().endsWith("." + name)) {
          return e.getValue();
        }
      }
      return null;
    }

    /**
     * The rows of an uncorrelated subquery as a hash set for IN, built once per execution; null
     * when the subquery is correlated, so its rows differ per outer row.
     */
    @Nullable InSet inSet(Select select, List<Map<String, Object>> group) {
      QueryParser.Plan plan = subplans.get(select);
      if (plan == null || plan.correlated) {
        return null;
      }
      List<Map<String, Object>> rows = subquery(select, group);
      if (plan.inSetRows != rows) {
        plan.inSet = InSet.of(rows);
        plan.inSetRows = rows;
      }
      return plan.inSet;
    }

    List<Map<String, Object>> subquery(Select select, List<Map<String, Object>> group) {
      QueryParser.Plan plan = subplans.get(select);
      if (plan == null) {
        throw new PgException.Argument("0A000", "Unsupported subquery: " + select);
      }
      if (!plan.correlated) {
        // the same rows for every outer row: read once per execution of the statement
        if (plan.cachedRows == null) {
          plan.cachedRows = plan.evaluate(outer);
        }
        return plan.cachedRows;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      if (outer != null) {
        row.putAll(outer);
      }
      if (!group.isEmpty()) {
        row.putAll(group.get(0));
      }
      return plan.evaluate(row);
    }
  }

  /**
   * The session's TimeZone, one per connection thread: timestamptz values print in it, a
   * timestamptz literal without an offset is read in it, and CURRENT_DATE and friends follow it.
   */
  private static final ThreadLocal<java.time.ZoneId> ZONE =
      ThreadLocal.withInitial(() -> ZoneOffset.UTC);

  static void setZone(java.time.ZoneId zone) {
    ZONE.set(zone);
  }

  static java.time.ZoneId zone() {
    return ZONE.get();
  }

  /** PostgreSQL's offset suffix: {@code +09}, {@code -03}, {@code +05:30}. */
  static String offsetText(ZoneOffset offset) {
    int total = offset.getTotalSeconds();
    int abs = Math.abs(total);
    String text = String.format("%s%02d", total < 0 ? "-" : "+", abs / 3600);
    return (abs % 3600) / 60 == 0 ? text : text + String.format(":%02d", (abs % 3600) / 60);
  }

  /**
   * A timestamp or time rounded to {@code precision} fractional digits, half up, as PostgreSQL does
   * for {@code now(3)} and a cast to {@code timestamp(3)} (56.5 is 57).
   */
  static Object roundedTo(Object v, int precision) {
    long unit = (long) Math.pow(10, Math.max(0, 9 - Math.min(precision, 9)));
    if (v instanceof Instant) {
      Instant i = (Instant) v;
      return Instant.ofEpochSecond(i.getEpochSecond()).plusNanos(rounded(i.getNano(), unit));
    }
    if (v instanceof LocalDateTime) {
      LocalDateTime t = (LocalDateTime) v;
      return t.withNano(0).plusNanos(rounded(t.getNano(), unit));
    }
    if (v instanceof LocalTime) {
      LocalTime t = (LocalTime) v;
      return t.withNano(0).plusNanos(rounded(t.getNano(), unit));
    }
    return v;
  }

  private static long rounded(long nanos, long unit) {
    return (nanos + unit / 2) / unit * unit;
  }

  /** A float sum as a double; PostgreSQL reports an overflow rather than returning Infinity. */
  private static double finiteSum(BigDecimal sum) {
    double d = sum.doubleValue();
    if (Double.isInfinite(d)) {
      throw new ArithmeticException("value out of range: overflow");
    }
    return d;
  }

  /**
   * A parameter sent without a type (OID 0, as node-postgres and psql's \\bind send them) whose
   * text looks like a number or boolean. PostgreSQL types it from where it is used: '007' stays
   * text when it is inserted into a text column or concatenated, and is 7 in {@code $1 + 1}. The
   * planner does not infer types from context, so both forms travel together: {@link #eval} gives
   * the guess, as every numeric operation needs, and the text contexts ({@link #evalText}, and
   * literals while planning) take the text the client sent.
   */
  static final class Untyped {
    final String text;
    final Object guess;

    Untyped(String text, Object guess) {
      this.text = text;
      this.guess = guess;
    }

    @Override
    public String toString() {
      return text;
    }
  }

  /** The guess of an untyped parameter, any other value unchanged. */
  @Nullable static Object typedValue(@Nullable Object v) {
    return v instanceof Untyped ? ((Untyped) v).guess : v;
  }

  /** As {@link #eval}, but an untyped parameter is the text the client sent. */
  @Nullable static Object evalText(Expression e, List<Map<String, Object>> group, Context ctx) {
    if (e instanceof JdbcParameter) {
      Object raw = parameter((JdbcParameter) e, ctx);
      if (raw instanceof Untyped) {
        return ((Untyped) raw).text;
      }
    }
    return eval(e, group, ctx);
  }

  @Nullable private static Object parameter(JdbcParameter p, Context ctx) {
    int i = p.getIndex() - 1;
    if (i < 0 || i >= ctx.parameters.size()) {
      throw new IllegalArgumentException("Unbound parameter: " + p);
    }
    return ctx.parameters.get(i);
  }

  /** The text of an untyped parameter; only call it where {@link #untyped} holds. */
  private static String untypedText(Expression e, Context ctx) {
    return ((Untyped) java.util.Objects.requireNonNull(parameter((JdbcParameter) e, ctx))).text;
  }

  /** True if {@code e} is a parameter bound without a type to number-like or boolean-like text. */
  private static boolean untyped(Expression e, Context ctx) {
    return e instanceof JdbcParameter && parameter((JdbcParameter) e, ctx) instanceof Untyped;
  }

  // String functions whose first argument (every argument for the concat family) is text
  private static final Set<String> TEXT_FUNCTIONS =
      new java.util.HashSet<>(
          Arrays.asList(
              "LENGTH",
              "CHAR_LENGTH",
              "CHARACTER_LENGTH",
              "OCTET_LENGTH",
              "UPPER",
              "LOWER",
              "INITCAP",
              "SUBSTRING",
              "SUBSTR",
              "LEFT",
              "RIGHT",
              "STRPOS",
              "POSITION",
              "REPLACE",
              "TRIM",
              "BTRIM",
              "LTRIM",
              "RTRIM",
              "LPAD",
              "RPAD",
              "REVERSE",
              "MD5",
              "SPLIT_PART",
              "REPEAT",
              "CONCAT",
              "CONCAT_WS",
              "ASCII",
              "TRANSLATE",
              "STARTS_WITH",
              "QUOTE_LITERAL",
              "QUOTE_IDENT",
              "REGEXP_REPLACE",
              "REGEXP_MATCH"));

  /**
   * The first column of a subquery's rows as a hash set, for IN. Only values whose hash equality
   * agrees with {@link #compare} are hashed: numbers (normalized, so 1 and 1.0 are one key), text,
   * booleans and dates and times. Other kinds, or a mix of kinds, leave the set unusable ({@link
   * #key} returns null) and IN compares one by one as before.
   */
  static final class InSet {
    final Set<Object> keys = new java.util.HashSet<>();
    boolean sawNull;
    boolean empty;
    @Nullable private Class<?> kind; // of every non-null value; null when unusable or all NULL

    static InSet of(List<Map<String, Object>> rows) {
      InSet set = new InSet();
      set.empty = rows.isEmpty();
      boolean usable = true;
      for (Map<String, Object> row : rows) {
        Object x = row.values().iterator().next();
        if (x == null) {
          set.sawNull = true;
          continue;
        }
        Class<?> k = kindOf(x);
        if (k == null || (set.kind != null && set.kind != k)) {
          usable = false;
          break;
        }
        set.kind = k;
        set.keys.add(Operators.normalize(x));
      }
      if (!usable) {
        set.kind = null;
        set.keys.clear();
      }
      return set;
    }

    /** The probe key for {@code v}, or null when the set cannot answer for it. */
    @Nullable Object key(Object v) {
      return kind != null && kindOf(v) == kind ? Operators.normalize(v) : null;
    }

    @Nullable private static Class<?> kindOf(Object x) {
      if (x instanceof Number) {
        return Number.class;
      }
      if (x instanceof String
          || x instanceof Boolean
          || x instanceof LocalDate
          || x instanceof LocalTime
          || x instanceof LocalDateTime
          || x instanceof Instant) {
        return x.getClass();
      }
      return null;
    }
  }

  /** {@code v >= lo AND v <= hi} in three-valued logic. */
  @Nullable private static Boolean between(@Nullable Object v, @Nullable Object lo, @Nullable Object hi) {
    Boolean ge = v == null || lo == null ? null : compare(v, lo) >= 0;
    Boolean le = v == null || hi == null ? null : compare(v, hi) <= 0;
    if (Boolean.FALSE.equals(ge) || Boolean.FALSE.equals(le)) {
      return false;
    }
    return ge == null || le == null ? null : Boolean.TRUE;
  }

  /** Characters (code points) {@code from} to {@code to} of {@code s}, both clipped to it. */
  private static String characters(String s, long from, long to) {
    long n = s.codePointCount(0, s.length());
    long a = Math.max(0, Math.min(from, n));
    long b = Math.max(a, Math.min(to, n));
    return s.substring(s.offsetByCodePoints(0, (int) a), s.offsetByCodePoints(0, (int) b));
  }

  /** How long one pattern match may run: Java's engine can backtrack exponentially (todo 016). */
  static final long REGEX_BUDGET_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

  /** What the statement running on this thread may use, and when it must stop. */
  static final class Statement {
    final Instant now; // now() and CURRENT_TIMESTAMP: the transaction's start, as in PostgreSQL
    final Instant start; // statement_timestamp()
    final long deadline; // System.nanoTime() past which statement_timeout fires; 0: none
    @Nullable final java.util.concurrent.atomic.AtomicBoolean cancel; // set by a CancelRequest
    long regexSpent; // matching time spent so far, against REGEX_BUDGET_NANOS
    private int tick;

    Statement(
        Instant now,
        long timeoutMillis,
        @Nullable java.util.concurrent.atomic.AtomicBoolean cancel) {
      this.now = now;
      this.start = Instant.now();
      this.deadline = timeoutMillis > 0 ? (System.nanoTime() + timeoutMillis * 1_000_000L) | 1 : 0;
      this.cancel = cancel;
    }
  }

  // The statement running on this thread; null outside one (a test, a pool thread), where nothing
  // is interrupted and each regex match gets the whole budget
  private static final ThreadLocal<Statement> STATEMENT = new ThreadLocal<>();

  // The session's cancel flag, on its connection thread: a CancelRequest sets it
  private static final ThreadLocal<java.util.concurrent.atomic.AtomicBoolean> CANCEL =
      new ThreadLocal<>();

  /** The session's settings, for current_setting and set_config. */
  interface Settings {
    /** The setting's current value; null for a name the session does not know. */
    @Nullable String get(String name);

    /** As SET [LOCAL] name = value. */
    void set(String name, String value, boolean local);
  }

  private static final ThreadLocal<Settings> SETTINGS = new ThreadLocal<>();

  /** The settings of the session whose statement runs on this thread. */
  static void setSettings(Settings settings) {
    SETTINGS.set(settings);
  }

  /** Gives the session running on this thread its cancel flag, once, at startup. */
  static void setCancel(java.util.concurrent.atomic.AtomicBoolean cancel) {
    CANCEL.set(cancel);
  }

  /**
   * Starts a statement on this thread: {@code now} is what now() returns throughout, a positive
   * {@code timeoutMillis} is its statement_timeout, and the session's cancel flag, cleared here,
   * stops it. The regex budget covers all the statement's matches over however many rows.
   */
  static void startStatement(Instant now, long timeoutMillis) {
    java.util.concurrent.atomic.AtomicBoolean cancel = CANCEL.get();
    if (cancel != null) {
      cancel.set(false); // a cancel sent between statements is for none of them
    }
    STATEMENT.set(new Statement(now, timeoutMillis, cancel));
  }

  static void startStatement() {
    startStatement(Instant.now(), 0);
  }

  /**
   * Fails the running statement with 57014 once it has been cancelled or has run past its
   * statement_timeout. Cheap enough to call per row: the clock is read every 256 calls.
   */
  static void checkInterrupt() {
    Statement s = STATEMENT.get();
    if (s == null || (++s.tick & 0xFF) != 0) {
      return;
    }
    if (s.cancel != null && s.cancel.get()) {
      throw new PgException.Argument("57014", "canceling statement due to user request");
    }
    if (s.deadline != 0 && System.nanoTime() - s.deadline > 0) {
      throw new PgException.Argument("57014", "canceling statement due to statement timeout");
    }
  }

  /** now(): the statement's (in a transaction, the transaction's) start time. */
  private static Instant statementNow() {
    Statement s = STATEMENT.get();
    return s == null ? Instant.now() : s.now;
  }

  /** Runs {@code p} against {@code s} within the statement's remaining regex budget. */
  static boolean match(Pattern p, String s, boolean whole) {
    Statement statement = STATEMENT.get();
    long start = System.nanoTime();
    try {
      java.util.regex.Matcher m =
          p.matcher(
              bounded(
                  s, start + REGEX_BUDGET_NANOS - (statement == null ? 0 : statement.regexSpent)));
      return whole ? m.matches() : m.find();
    } finally {
      if (statement != null) {
        statement.regexSpent += System.nanoTime() - start;
      }
    }
  }

  /**
   * The text a pattern matches against, failing the match once it has run past the budget. The
   * matcher reads the input through charAt, so the check rides on the backtracking itself.
   */
  static CharSequence bounded(String s, long deadline) {
    if (System.nanoTime() > deadline) {
      throw new PgException.Argument(
          "2201B", "invalid regular expression: matching took longer than the time allowed");
    }
    return new CharSequence() {
      private int reads;

      @Override
      public char charAt(int i) {
        if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadline) {
          throw new PgException.Argument(
              "2201B", "invalid regular expression: matching took longer than the time allowed");
        }
        return s.charAt(i);
      }

      @Override
      public int length() {
        return s.length();
      }

      @Override
      public CharSequence subSequence(int start, int end) {
        return s.subSequence(start, end);
      }

      @Override
      public String toString() {
        return s;
      }
    };
  }

  private static final Map<String, String> POSIX_CLASSES = new java.util.HashMap<>();

  static {
    for (String[] c :
        new String[][] {
          {"alpha", "\\p{Alpha}"}, {"digit", "\\p{Digit}"}, {"alnum", "\\p{Alnum}"},
          {"upper", "\\p{Upper}"}, {"lower", "\\p{Lower}"}, {"space", "\\s"},
          {"punct", "\\p{Punct}"}, {"xdigit", "\\p{XDigit}"}, {"word", "\\w"},
          {"blank", "\\p{Blank}"}, {"cntrl", "\\p{Cntrl}"}, {"print", "\\p{Print}"},
          {"graph", "\\p{Graph}"}
        }) {
      POSIX_CLASSES.put(c[0], c[1]);
    }
  }

  /**
   * A PostgreSQL (POSIX ARE) regular expression in Java's syntax: bracket classes such as
   * [[:alpha:]], and the escapes \\y, \\Y, \\m, \\M and \\Z.
   */
  static String posixRegex(String re) {
    StringBuilder out = new StringBuilder(re.length() + 8);
    for (int i = 0; i < re.length(); i++) {
      char c = re.charAt(i);
      if (c == '\\' && i + 1 < re.length()) {
        char next = re.charAt(++i);
        switch (next) {
          case 'y':
            out.append("\\b");
            break;
          case 'Y':
            out.append("\\B");
            break;
          case 'm':
            out.append("\\b(?=\\w)");
            break;
          case 'M':
            out.append("\\b(?<=\\w)");
            break;
          case 'Z':
            out.append("\\z");
            break;
          default:
            out.append(c).append(next);
        }
        continue;
      }
      if (c == '[' && re.startsWith("[:", i)) {
        int end = re.indexOf(":]", i + 2);
        String java = end < 0 ? null : POSIX_CLASSES.get(re.substring(i + 2, end));
        if (java != null) {
          out.append(java);
          i = end + 1;
          continue;
        }
      }
      out.append(c);
    }
    return out.toString();
  }

  static boolean isTrue(@Nullable Object value) {
    return Boolean.TRUE.equals(value);
  }

  static boolean isAggregate(Function f) {
    return AGGREGATES.contains(functionName(f));
  }

  static boolean isAggregateName(String upperCaseName) {
    return AGGREGATES.contains(upperCaseName);
  }

  /** An aggregate with a FILTER clause, such as {@code count(*) FILTER (WHERE active)}. */
  static boolean isFilteredAggregate(Expression e) {
    return e instanceof AnalyticExpression
        && ((AnalyticExpression) e).getType() == AnalyticType.FILTER_ONLY
        && AGGREGATES.contains(((AnalyticExpression) e).getName().toUpperCase(Locale.ROOT));
  }

  /** The upper-cased function name without a {@code pg_catalog.} schema prefix. */
  private static String functionName(Function f) {
    String name = f.getName().toUpperCase(Locale.ROOT);
    return name.substring(name.lastIndexOf('.') + 1);
  }

  static boolean allTrue(List<Expression> conditions, Map<String, Object> row, Context ctx) {
    List<Map<String, Object>> group = Collections.singletonList(row);
    for (Expression c : conditions) {
      if (!isTrue(eval(c, group, ctx))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Only byte-order collations exist here: ScalarDB orders text by its bytes, so any locale
   * collation would silently sort differently from PostgreSQL.
   */
  static void checkCollation(String name) {
    String collation = name.replace("\"", "").toLowerCase(Locale.ROOT);
    collation = collation.substring(collation.lastIndexOf('.') + 1);
    if (!collation.equals("c")
        && !collation.equals("posix")
        && !collation.equals("ucs_basic")
        && !collation.equals("default")) {
      throw new PgException.Argument(
          "0A000",
          "collation \""
              + name.replace("\"", "")
              + "\" is not supported: only \"C\" (byte order) is available");
    }
  }

  @Nullable static Object eval(Expression e, List<Map<String, Object>> group, Context ctx) {
    e = QueryParser.unwrap(e);
    if (e instanceof net.sf.jsqlparser.expression.BooleanValue) {
      return ((net.sf.jsqlparser.expression.BooleanValue) e).getValue();
    }
    if (e instanceof NullValue) {
      return null;
    }
    if (e instanceof LongValue) {
      return integer(((LongValue) e).getBigIntegerValue());
    }
    if (e instanceof DoubleValue) {
      // A literal with a decimal point or exponent is numeric in PostgreSQL, not a double
      return decimal(e.toString());
    }
    if (e instanceof StringValue) {
      return QueryParser.stringLiteral((StringValue) e);
    }
    if (e instanceof DateTimeLiteralExpression) {
      return temporal((DateTimeLiteralExpression) e);
    }
    if (e instanceof TimeKeyExpression) {
      return now(((TimeKeyExpression) e).getStringValue());
    }
    if (e instanceof net.sf.jsqlparser.expression.ArrayConstructor) {
      List<Object> out = new ArrayList<>();
      for (Expression item : ((net.sf.jsqlparser.expression.ArrayConstructor) e).getExpressions()) {
        out.add(eval(item, group, ctx));
      }
      return out;
    }
    if (e instanceof IntervalExpression) {
      IntervalExpression iv = (IntervalExpression) e;
      String p;
      if (iv.getExpression() != null) {
        Object amount = eval(iv.getExpression(), group, ctx);
        if (amount == null) {
          return null; // an interval of NULL is NULL
        }
        p = text(amount);
      } else {
        p = String.valueOf(iv.getParameter());
      }
      p = p.startsWith("'") && p.endsWith("'") ? p.substring(1, p.length() - 1) : p;
      // INTERVAL '1' DAY carries its unit apart from the amount (the leading field of HOUR TO
      // MINUTE)
      String unit =
          iv.getIntervalQualifier() != null
              ? iv.getIntervalQualifier().getLeadingField()
              : iv.getIntervalType();
      return Interval.parse(unit == null ? p : p + " " + unit);
    }
    if (e instanceof TimezoneExpression) {
      TimezoneExpression tz = (TimezoneExpression) e;
      return atTimeZone(
          eval(tz.getLeftExpression(), group, ctx),
          eval(tz.getTimezoneExpressions().get(0), group, ctx));
    }
    if (e instanceof SignedExpression) {
      Expression inner = ((SignedExpression) e).getExpression();
      if (((SignedExpression) e).getSign() == '-' && inner instanceof LongValue) {
        // -9223372036854775808 is in range although its absolute value is not
        return integer(((LongValue) inner).getBigIntegerValue().negate());
      }
      Object v = eval(inner, group, ctx);
      if (((SignedExpression) e).getSign() == '~') {
        return v == null ? null : (Object) ~integral(v, e);
      }
      return ((SignedExpression) e).getSign() == '-' ? negate(v) : v;
    }
    if (e instanceof Column) {
      return column((Column) e, group, ctx);
    }
    if (e instanceof JdbcParameter) {
      return typedValue(parameter((JdbcParameter) e, ctx));
    }
    if (e instanceof Function) {
      Function f = (Function) e;
      return isAggregate(f) ? aggregate(f, group, ctx) : scalar(f, group, ctx);
    }
    if (e instanceof AnalyticExpression
        && ((AnalyticExpression) e).getType() == AnalyticType.OVER) {
      // Computed by Windows beforehand and left in the row under the call's hidden key
      Map<String, Object> row = group.isEmpty() ? Collections.emptyMap() : group.get(0);
      String key = Windows.KEY + e; // ponytail: deparsed per row; cache per call if it shows up
      if (!row.containsKey(key)) {
        throw new PgException.Argument(
            "42P20", "Window functions are allowed only in the select list and ORDER BY: " + e);
      }
      return row.get(key);
    }
    if (isFilteredAggregate(e)) {
      AnalyticExpression a = (AnalyticExpression) e;
      Function f = new Function();
      f.setName(a.getName());
      f.setParameters(new ExpressionList<Expression>(a.getExpression()));
      f.setDistinct(a.isDistinct());
      List<Map<String, Object>> kept = new ArrayList<>();
      for (Map<String, Object> row : group) {
        if (isTrue(eval(a.getFilterExpression(), Collections.singletonList(row), ctx))) {
          kept.add(row);
        }
      }
      return aggregate(f, kept, ctx);
    }
    if (e instanceof BitwiseXor) {
      // PostgreSQL's ^ is exponentiation (# is XOR)
      Object l = eval(((BitwiseXor) e).getLeftExpression(), group, ctx);
      Object r = eval(((BitwiseXor) e).getRightExpression(), group, ctx);
      return l == null || r == null
          ? null
          : (Object) Math.pow(((Number) l).doubleValue(), ((Number) r).doubleValue());
    }
    if (e instanceof ExtractExpression) {
      ExtractExpression x = (ExtractExpression) e;
      return extract(x.getName(), eval(x.getExpression(), group, ctx));
    }
    if (e instanceof TrimFunction) {
      return trim((TrimFunction) e, group, ctx);
    }
    if (e instanceof CastExpression) {
      CastExpression cast = (CastExpression) e;
      String type = castType(cast.getColDataType());
      Object v = evalText(cast.getLeftExpression(), group, ctx); // '007'::text is 007
      String t = type.toLowerCase(Locale.ROOT);
      t = t.startsWith("pg_catalog.") ? t.substring("pg_catalog.".length()) : t;
      if (v instanceof String && ctx.catalog != null) {
        // An object name cast to its OID, as ORMs write 'table'::regclass in catalog queries
        switch (t) {
          case "regclass":
            return ctx.catalog.relationOid((String) v);
          case "regnamespace":
            return ctx.catalog.namespaceOid((String) v);
          case "regtype":
            return Catalog.typeOid((String) v);
          default:
            break;
        }
      }
      return cast(type, v);
    }
    if (e instanceof CaseExpression) {
      return caseOf((CaseExpression) e, group, ctx);
    }
    if (e instanceof CollateExpression
        || (e instanceof RowGetExpression
            && ((RowGetExpression) e).getExpression() instanceof CollateExpression)) {
      return eval(QueryParser.unwrap(e), group, ctx);
    }
    if (e instanceof RowGetExpression) {
      // (record).field, also over a set-returning function's list of records
      Object base = eval(((RowGetExpression) e).getExpression(), group, ctx);
      String field = ((RowGetExpression) e).getColumnName().toLowerCase(Locale.ROOT);
      if (base instanceof List) {
        List<Object> fields = new ArrayList<>();
        for (Object r : (List<?>) base) {
          fields.add(field(r, field, e));
        }
        return fields;
      }
      return base == null ? null : field(base, field, e);
    }
    if (e instanceof RegExpMatchOperator) {
      RegExpMatchOperator re = (RegExpMatchOperator) e;
      Object v = eval(re.getLeftExpression(), group, ctx);
      Object p = eval(re.getRightExpression(), group, ctx);
      if (v == null || p == null) {
        return null;
      }
      RegExpMatchOperatorType type = re.getOperatorType();
      boolean caseInsensitive =
          type == RegExpMatchOperatorType.MATCH_CASEINSENSITIVE
              || type == RegExpMatchOperatorType.NOT_MATCH_CASEINSENSITIVE;
      boolean negate =
          type == RegExpMatchOperatorType.NOT_MATCH_CASESENSITIVE
              || type == RegExpMatchOperatorType.NOT_MATCH_CASEINSENSITIVE;
      Pattern pattern;
      try {
        pattern =
            Pattern.compile(posixRegex(text(p)), caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
      } catch (java.util.regex.PatternSyntaxException bad) {
        throw new PgException.Argument(
            "2201B", "invalid regular expression: " + bad.getDescription(), bad);
      }
      boolean found = match(pattern, text(v), false);
      return found != negate;
    }
    if (e instanceof ParenthesedSelect) {
      List<Map<String, Object>> rows = ctx.subquery((ParenthesedSelect) e, group);
      if (rows.isEmpty()) {
        return null;
      }
      if (rows.get(0).size() != 1) {
        throw new PgException.Argument("42601", "subquery must return only one column");
      }
      if (rows.size() > 1) {
        throw new PgException.Argument(
            "21000", "more than one row returned by a subquery used as an expression");
      }
      return rows.get(0).values().iterator().next();
    }
    if (e instanceof ExistsExpression) {
      ExistsExpression exists = (ExistsExpression) e;
      if (!(exists.getRightExpression() instanceof ParenthesedSelect)) {
        throw new PgException.Argument("0A000", "Unsupported EXISTS expression: " + e);
      }
      boolean empty =
          ctx.subquery((ParenthesedSelect) exists.getRightExpression(), group).isEmpty();
      return empty == exists.isNot();
    }
    if (e instanceof NotExpression) {
      Object v = eval(((NotExpression) e).getExpression(), group, ctx);
      return v == null ? null : !isTrue(v);
    }
    if (e instanceof AndExpression) {
      Object l = eval(((AndExpression) e).getLeftExpression(), group, ctx);
      if (Boolean.FALSE.equals(l)) {
        return false; // b <> 0 AND a / b > 1 must not divide by zero
      }
      Object r = eval(((AndExpression) e).getRightExpression(), group, ctx);
      if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) {
        return false;
      }
      return l == null || r == null ? null : Boolean.TRUE;
    }
    if (e instanceof OrExpression) {
      Object l = eval(((OrExpression) e).getLeftExpression(), group, ctx);
      if (isTrue(l)) {
        return true;
      }
      Object r = eval(((OrExpression) e).getRightExpression(), group, ctx);
      if (isTrue(l) || isTrue(r)) {
        return true;
      }
      return l == null || r == null ? null : Boolean.FALSE;
    }
    if (e instanceof IsNullExpression) {
      IsNullExpression n = (IsNullExpression) e;
      return (eval(n.getLeftExpression(), group, ctx) == null) != n.isNot();
    }
    if (e instanceof IsBooleanExpression) {
      IsBooleanExpression b = (IsBooleanExpression) e;
      Object v = eval(b.getLeftExpression(), group, ctx);
      boolean matches = b.isTrue() ? Boolean.TRUE.equals(v) : Boolean.FALSE.equals(v);
      return matches != b.isNot();
    }
    if (e instanceof LikeExpression) {
      LikeExpression like = (LikeExpression) e;
      Object v = evalText(like.getLeftExpression(), group, ctx);
      Object p = evalText(like.getRightExpression(), group, ctx);
      if (v == null || p == null) {
        return null;
      }
      String escape =
          like.getEscape() == null ? "\\" : String.valueOf(eval(like.getEscape(), group, ctx));
      if (like.getLikeKeyWord() == LikeExpression.KeyWord.SIMILAR_TO) {
        boolean similar = match(similarPattern(String.valueOf(p), escape), String.valueOf(v), true);
        return similar != like.isNot();
      }
      if (like.getLikeKeyWord() != LikeExpression.KeyWord.LIKE
          && like.getLikeKeyWord() != LikeExpression.KeyWord.ILIKE) {
        throw new PgException.Argument("0A000", "Unsupported expression: " + e);
      }
      Pattern pattern = likePattern(String.valueOf(p), escape);
      if (like.getLikeKeyWord() == LikeExpression.KeyWord.ILIKE) {
        pattern =
            Pattern.compile(
                pattern.pattern(),
                pattern.flags() | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      }
      boolean matches = match(pattern, String.valueOf(v), true);
      return matches != like.isNot();
    }
    if (e instanceof InExpression) {
      InExpression in = (InExpression) e;
      Object v = eval(in.getLeftExpression(), group, ctx);
      List<Object> values = new ArrayList<>();
      if (in.getRightExpression() instanceof ParenthesedSelect) {
        // x IN (uncorrelated subquery): a hash probe instead of a scan per outer row (todo 007)
        InSet set = ctx.inSet((ParenthesedSelect) in.getRightExpression(), group);
        if (set != null) {
          if (v == null) {
            return set.empty ? Boolean.valueOf(in.isNot()) : null; // NULL IN () is false
          }
          Object key = set.key(v);
          if (key != null) {
            if (set.keys.contains(key)) {
              return !in.isNot();
            }
            return set.sawNull ? null : Boolean.valueOf(in.isNot());
          }
          // a value of another kind than the subquery's: compared one by one below, as before
        }
        for (Map<String, Object> row :
            ctx.subquery((ParenthesedSelect) in.getRightExpression(), group)) {
          values.add(row.values().iterator().next());
        }
      } else if (in.getRightExpression() instanceof ExpressionList) {
        for (Expression item : (ExpressionList<?>) in.getRightExpression()) {
          values.add(eval(item, group, ctx));
        }
      } else {
        throw new PgException.Argument("0A000", "Unsupported IN expression: " + e);
      }
      if (v == null) {
        return values.isEmpty() ? Boolean.valueOf(in.isNot()) : null; // NULL IN () is false
      }
      boolean sawNull = false;
      for (Object x : values) {
        if (x == null) {
          sawNull = true;
        } else if (compare(v, x) == 0) {
          return !in.isNot();
        }
      }
      return sawNull ? null : Boolean.valueOf(in.isNot());
    }
    if (e instanceof Between) {
      Between b = (Between) e;
      Object v = eval(b.getLeftExpression(), group, ctx);
      Object lo = eval(b.getBetweenExpressionStart(), group, ctx);
      Object hi = eval(b.getBetweenExpressionEnd(), group, ctx);
      // v >= lo AND v <= hi in three-valued logic: 5 BETWEEN NULL AND 3 is false (todo 017)
      Boolean in = between(v, lo, hi);
      if (b.isUsingSymmetric()) {
        Boolean swapped = between(v, hi, lo);
        in =
            Boolean.TRUE.equals(in) || Boolean.TRUE.equals(swapped)
                ? Boolean.TRUE
                : in == null || swapped == null ? null : Boolean.FALSE;
      }
      return in == null ? null : in != b.isNot();
    }
    if (e instanceof ComparisonOperator) {
      ComparisonOperator op = (ComparisonOperator) e;
      Object l = eval(op.getLeftExpression(), group, ctx);
      if (op.getRightExpression() instanceof AnyComparisonExpression) {
        AnyComparisonExpression any = (AnyComparisonExpression) op.getRightExpression();
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> row : ctx.subquery(any.getSelect(), group)) {
          values.add(row.values().iterator().next());
        }
        return anyOrAll(op, l, any.getAnyType() == AnyType.ALL, values);
      }
      Function quantifier = QueryParser.arrayQuantifier(op.getRightExpression());
      if (quantifier != null) {
        // op ANY(array) / op ALL(array), for an array computed at run time
        Object array = eval(quantifier.getParameters().get(0), group, ctx);
        if (array == null) {
          return null;
        }
        return anyOrAll(op, l, quantifier.getName().equalsIgnoreCase("ALL"), elements(array));
      }
      Object r = eval(op.getRightExpression(), group, ctx);
      if (l == null || r == null) {
        return null;
      }
      // An untyped parameter compared with text is text: name = $1 with '007' looks for 007
      if (r instanceof String && untyped(op.getLeftExpression(), ctx)) {
        l = untypedText(op.getLeftExpression(), ctx);
      } else if (l instanceof String && untyped(op.getRightExpression(), ctx)) {
        r = untypedText(op.getRightExpression(), ctx);
      }
      // rows compare field by field, a NULL field making the result unknown; arrays compare as
      // wholes, NULL elements equal (ARRAY[1,NULL] = ARRAY[1,NULL] is true), in compare() below
      if ((l instanceof Row || r instanceof Row) && l instanceof List && r instanceof List) {
        Integer order = rowCompare((List<?>) l, (List<?>) r);
        if (order == null) {
          return null;
        }
        l = order;
        r = 0;
      }
      Boolean result = compared(op, compare(l, r));
      if (result != null) {
        return result;
      }
    }
    if (e instanceof Concat) {
      Object l = evalText(((Concat) e).getLeftExpression(), group, ctx);
      Object r = evalText(((Concat) e).getRightExpression(), group, ctx);
      if (l == null || r == null) {
        return null;
      }
      if (l instanceof Json.Value || r instanceof Json.Value) {
        return new Json.Value(Json.concat(Json.of(l).node, Json.of(r).node), true, null);
      }
      return text(l) + text(r);
    }
    if (e instanceof Addition
        || e instanceof Subtraction
        || e instanceof Multiplication
        || e instanceof Division
        || e instanceof Modulo) {
      return arithmetic((BinaryExpression) e, group, ctx);
    }
    if (e instanceof net.sf.jsqlparser.expression.JsonExpression) {
      return json((net.sf.jsqlparser.expression.JsonExpression) e, group, ctx);
    }
    if (e instanceof net.sf.jsqlparser.expression.operators.relational.JsonOperator) {
      net.sf.jsqlparser.expression.operators.relational.JsonOperator op =
          (net.sf.jsqlparser.expression.operators.relational.JsonOperator) e;
      Object l = eval(op.getLeftExpression(), group, ctx);
      Object r = eval(op.getRightExpression(), group, ctx);
      if (l == null || r == null) {
        return null;
      }
      Json.Value a = Json.of(l);
      switch (op.getStringExpression()) {
        case "@>":
          return Json.contains(a.node, Json.of(r).node);
        case "<@":
          return Json.contains(Json.of(r).node, a.node);
        case "?":
          return Json.exists(a.node, text(r));
        case "?|":
        case "?&":
          {
            boolean all = op.getStringExpression().equals("?&");
            for (Object key : elements(r)) {
              if (Json.exists(a.node, text(key)) != all) {
                return !all;
              }
            }
            return all;
          }
        default:
          throw new PgException.Argument("0A000", "Unsupported JSON operator: " + e);
      }
    }
    if (e instanceof net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression) {
      net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression d =
          (net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression) e;
      Object l = eval(d.getLeftExpression(), group, ctx);
      Object r = eval(d.getRightExpression(), group, ctx);
      boolean distinct = l == null || r == null ? (l == null) != (r == null) : compare(l, r) != 0;
      return distinct != d.isNot();
    }
    if (e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseAnd
        || e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseOr
        || e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseLeftShift
        || e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseRightShift
        || e instanceof net.sf.jsqlparser.expression.operators.relational.Intersects) {
      // & | << >> and PostgreSQL's # (XOR), on integers
      BinaryExpression b = (BinaryExpression) e;
      Object l = eval(b.getLeftExpression(), group, ctx);
      Object r = eval(b.getRightExpression(), group, ctx);
      if (l == null || r == null) {
        return null;
      }
      long x = integral(l, e);
      long y = integral(r, e);
      if (e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseAnd) {
        return x & y;
      }
      if (e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseOr) {
        return x | y;
      }
      if (e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseLeftShift) {
        return x << y;
      }
      if (e instanceof net.sf.jsqlparser.expression.operators.arithmetic.BitwiseRightShift) {
        return x >> y;
      }
      return x ^ y;
    }
    if (e instanceof net.sf.jsqlparser.expression.ArrayExpression) {
      net.sf.jsqlparser.expression.ArrayExpression a =
          (net.sf.jsqlparser.expression.ArrayExpression) e;
      Object array = eval(a.getObjExpression(), group, ctx);
      if (array == null) {
        return null;
      }
      List<?> items = elements(array);
      Expression index = a.getIndexExpression();
      Expression startIndex = a.getStartIndexExpression();
      Expression stopIndex = a.getStopIndexExpression();
      if (index instanceof net.sf.jsqlparser.expression.JsonExpression
          && ((net.sf.jsqlparser.expression.JsonExpression) index).getIdents().size() == 1) {
        // JSQLParser reads the slice [from:to] as a JSON path "from : to"
        net.sf.jsqlparser.expression.JsonExpression slice =
            (net.sf.jsqlparser.expression.JsonExpression) index;
        startIndex = slice.getExpression();
        stopIndex = slice.getIdents().get(0);
        index = null;
      }
      if (index != null) {
        Object i = eval(index, group, ctx);
        if (i == null) {
          return null;
        }
        long k = integral(i, e) + (array instanceof Catalog.Int2Vector ? 1 : 0); // it counts from 0
        return k < 1 || k > items.size() ? null : items.get((int) k - 1);
      }
      // a slice [from:to], 1-based and inclusive, clipped to the array as PostgreSQL does
      Object from = startIndex == null ? null : eval(startIndex, group, ctx);
      Object to = stopIndex == null ? null : eval(stopIndex, group, ctx);
      int start = from == null ? 1 : (int) Math.max(1, integral(from, e));
      int end = to == null ? items.size() : (int) Math.min(items.size(), integral(to, e));
      return start > end ? new ArrayList<>() : new ArrayList<Object>(items.subList(start - 1, end));
    }
    if (e instanceof ParenthesedExpressionList) {
      // a row value such as (a, b), compared element-wise
      List<Object> row = new Row();
      for (Expression item : (ParenthesedExpressionList<?>) e) {
        row.add(eval(item, group, ctx));
      }
      return row;
    }
    throw new PgException.Argument("0A000", "Unsupported expression: " + e);
  }

  /**
   * {@code j -> 'a' ->> 'b'}: JSQLParser nests a chain of JSON operators to the right, so the steps
   * are flattened and applied left to right. {@code ->} and {@code #>} yield JSON, {@code ->>} and
   * {@code #>>} text.
   */
  @Nullable private static Object json(
      net.sf.jsqlparser.expression.JsonExpression j, List<Map<String, Object>> group, Context ctx) {
    List<Map.Entry<Expression, String>> steps = new ArrayList<>();
    jsonSteps(j, steps);
    Object cur = eval(j.getExpression(), group, ctx);
    for (Map.Entry<Expression, String> step : steps) {
      if (cur == null) {
        return null;
      }
      Json.Value v = Json.of(cur);
      Object key = eval(step.getKey(), group, ctx);
      com.fasterxml.jackson.databind.JsonNode node;
      switch (step.getValue()) {
        case "->":
        case "->>":
          node = Json.get(v.node, key);
          break;
        case "#>":
        case "#>>":
          node = key == null ? null : Json.path(v.node, elements(key));
          break;
        default:
          throw new PgException.Argument("0A000", "Unsupported JSON operator: " + step.getValue());
      }
      boolean asText = step.getValue().endsWith(">>");
      cur =
          node == null
              ? null
              : asText ? Json.scalarText(node, v.binary) : new Json.Value(node, v.binary, null);
    }
    return cur;
  }

  private static void jsonSteps(
      net.sf.jsqlparser.expression.JsonExpression j, List<Map.Entry<Expression, String>> steps) {
    for (Map.Entry<Expression, String> ident : j.getIdentList()) {
      if (ident.getKey() instanceof net.sf.jsqlparser.expression.JsonExpression) {
        net.sf.jsqlparser.expression.JsonExpression inner =
            (net.sf.jsqlparser.expression.JsonExpression) ident.getKey();
        steps.add(new java.util.AbstractMap.SimpleEntry<>(inner.getExpression(), ident.getValue()));
        jsonSteps(inner, steps);
      } else {
        steps.add(ident);
      }
    }
  }

  /** The last operator of a JSON chain, which decides whether the result is JSON or text. */
  static String lastJsonOperator(net.sf.jsqlparser.expression.JsonExpression j) {
    List<Map.Entry<Expression, String>> idents = j.getIdentList();
    Map.Entry<Expression, String> last = idents.get(idents.size() - 1);
    return last.getKey() instanceof net.sf.jsqlparser.expression.JsonExpression
        ? lastJsonOperator((net.sf.jsqlparser.expression.JsonExpression) last.getKey())
        : last.getValue();
  }

  /** An integer operand, for bitwise operators and subscripts. */
  private static long integral(Object v, Expression e) {
    if (!isIntegral(v)) {
      throw new IllegalArgumentException("Operator requires integer operands: " + e);
    }
    return ((Number) v).longValue();
  }

  /** Rows compared element-wise; null when a pair decides nothing because one side is NULL. */
  /** A row value, (a, b): a list, so list code keeps working, but a record, not an array. */
  static final class Row extends ArrayList<Object> {
    private static final long serialVersionUID = 1L;
  }

  @Nullable static Integer rowCompare(List<?> a, List<?> b) {
    if (a.size() != b.size()) {
      throw new IllegalArgumentException("Unequal number of entries in row expressions");
    }
    for (int i = 0; i < a.size(); i++) {
      if (a.get(i) == null || b.get(i) == null) {
        return null;
      }
      int c = compare(a.get(i), b.get(i));
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /**
   * PostgreSQL's SIMILAR TO pattern as a Java regex: {@code %} and {@code _} as in LIKE, the SQL
   * regex operators {@code | * + ? {} () []} kept, everything else literal, whole-string match.
   */
  static Pattern similarPattern(String pattern, String escape) {
    StringBuilder sb = new StringBuilder();
    boolean inClass = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (!escape.isEmpty() && c == escape.charAt(0) && i + 1 < pattern.length()) {
        sb.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
      } else if (inClass) {
        inClass = c != ']';
        sb.append(c == '\\' ? "\\\\" : String.valueOf(c));
      } else if (c == '%') {
        sb.append(".*");
      } else if (c == '_') {
        sb.append('.');
      } else if (c == '[') {
        inClass = true;
        sb.append(c);
      } else if ("|*+?{}()".indexOf(c) >= 0) {
        sb.append(c);
      } else {
        sb.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(sb.toString(), Pattern.DOTALL);
  }

  /** A numeric from its text, with a non-negative scale as PostgreSQL stores it (1e3 is 1000). */
  static BigDecimal decimal(String s) {
    BigDecimal v = checkedNumeric(new BigDecimal(s.trim()));
    return v.scale() < 0 ? v.setScale(0) : v;
  }

  /**
   * PostgreSQL's numeric range: 131,072 digits before the point and 16,383 after. Checked before
   * anything expands the value, since 1e500000000 is a short literal for a half-billion-digit
   * number (todo 028).
   */
  static BigDecimal checkedNumeric(BigDecimal v) {
    if (v.signum() != 0
        && ((long) v.precision() - v.scale() > 131_072 || v.scale() > 16_383 + 1000)) {
      throw new ArithmeticException("value overflows numeric format");
    }
    return v;
  }

  /**
   * A number as a numeric. A float converts as PostgreSQL's float8 and float4 to numeric casts do:
   * rounded to 15 (float8) or 6 (float4) significant digits, so {@code (0.1 + 0.2)::numeric} is 0.3
   * and a float8 1.005 is 1.005, not the 17-digit shortest form.
   */
  static BigDecimal toDecimal(Object v) {
    if (v instanceof BigDecimal) {
      return (BigDecimal) v;
    }
    if (isIntegral(v)) {
      return BigDecimal.valueOf(((Number) v).longValue());
    }
    if ((v instanceof Double || v instanceof Float) && isFinite(v)) {
      int digits = v instanceof Float ? 6 : 15; // FLT_DIG and DBL_DIG, as PostgreSQL's %.*g
      BigDecimal d =
          new BigDecimal(((Number) v).doubleValue())
              .round(new java.math.MathContext(digits, RoundingMode.HALF_EVEN))
              .stripTrailingZeros();
      return checkedNumeric(d.scale() < 0 ? d.setScale(0) : d);
    }
    return decimal(text(v));
  }

  /**
   * A numeric rounded to {@code numeric(precision, scale)}, half away from zero as PostgreSQL
   * rounds, failing with 22003 when it does not fit: the value must round to an absolute value
   * below 10^(precision - scale). The integer digits are checked before rounding as well, which
   * cannot reduce them, so a huge value fails without being expanded. A negative scale rounds to
   * tens, hundreds and so on, and the result never carries a negative scale (12345 in numeric(5,-2)
   * is 12300, not 1.23E+4). Shared by casts and by writes to a numeric(p,s) column.
   */
  static BigDecimal applyTypmod(BigDecimal d, int precision, int scale) {
    if (!integerDigitsFit(d, precision, scale)) {
      throw numericOverflow(precision, scale);
    }
    BigDecimal rounded = d.setScale(scale, RoundingMode.HALF_UP);
    if (!integerDigitsFit(rounded, precision, scale)) {
      throw numericOverflow(precision, scale);
    }
    return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
  }

  /** PostgreSQL's 22003 for a value too large for numeric(p,s), with its DETAIL line. */
  static PgException.Argument numericOverflow(int precision, int scale) {
    int digits = precision - scale;
    return new PgException.Argument(
        "22003",
        "numeric field overflow",
        "A field with precision "
            + precision
            + ", scale "
            + scale
            + " must round to an absolute value less than "
            + (digits > 0 ? "10^" + digits : "1")
            + ".");
  }

  /** PostgreSQL's numeric(p,s) range: an absolute value below 10^(precision - scale). */
  static boolean integerDigitsFit(BigDecimal d, int precision, int scale) {
    return d.signum() == 0 || (long) d.precision() - d.scale() <= (long) precision - scale;
  }

  /** Numeric division with PostgreSQL's result scale: at least 16 significant digits. */
  static BigDecimal divide(BigDecimal a, BigDecimal b) {
    if (b.signum() == 0) {
      throw new PgException.Argument("22012", "division by zero");
    }
    return a.divide(b, divScale(a, b), RoundingMode.HALF_UP);
  }

  /** PostgreSQL's select_div_scale, on base-10000 digit groups as its numeric is stored. */
  private static int divScale(BigDecimal a, BigDecimal b) {
    int[] first1 = leadingGroup(a);
    int[] first2 = leadingGroup(b);
    int qweight = first1[0] - first2[0];
    if (first1[1] <= first2[1]) {
      qweight--;
    }
    int scale = 16 - qweight * 4;
    scale = Math.max(scale, Math.max(a.scale(), b.scale()));
    return Math.max(0, Math.min(scale, 1000));
  }

  /** The weight (power of 10000) and value of the first non-zero base-10000 digit group. */
  private static int[] leadingGroup(BigDecimal x) {
    x = x.abs();
    if (x.signum() == 0) {
      return new int[] {0, 0};
    }
    int intDigits = x.precision() - x.scale(); // digits before the point; 0 or less below 1
    int weight = intDigits > 0 ? (intDigits - 1) / 4 : -((x.scale() - x.precision()) / 4 + 1);
    BigDecimal group = x.movePointLeft(4 * weight).setScale(0, RoundingMode.DOWN);
    return new int[] {weight, group.remainder(BigDecimal.valueOf(10000)).intValue()};
  }

  private static final Pattern TYPE_ARGS = Pattern.compile("^(.*?)\\s*\\(([^)]*)\\)(.*)$");

  /** The cast's type with its arguments, such as {@code numeric(10,2)}. */
  private static String castType(net.sf.jsqlparser.statement.create.table.ColDataType t) {
    String name = t.getDataType();
    List<String> args = t.getArgumentsStringList();
    java.util.regex.Matcher inline = TYPE_ARGS.matcher(name);
    if (args == null && inline.matches()) {
      // JSQLParser 5 keeps the arguments inside the name: numeric (10, 2)
      name = inline.group(1) + inline.group(3);
      args = Arrays.asList(inline.group(2).split("\\s*,\\s*"));
    }
    boolean array = t.getArrayData() != null && !t.getArrayData().isEmpty(); // int[] keeps [] apart
    return name
        + (args == null || args.isEmpty() ? "" : "(" + String.join(",", args) + ")")
        + (array ? "[]" : "");
  }

  /** Compares two values; a text value is converted to the other value's type first. */
  static int compare(Object a, Object b) {
    if (a instanceof Json.Value || b instanceof Json.Value) {
      Json.Value x = Json.of(a);
      Json.Value y = Json.of(b);
      return x.equals(y) ? 0 : x.toString().compareTo(y.toString());
    }
    if (a instanceof List && b instanceof List) {
      List<?> x = (List<?>) a;
      List<?> y = (List<?>) b;
      for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
        if (x.get(i) == null || y.get(i) == null) {
          if ((x.get(i) == null) != (y.get(i) == null)) {
            return x.get(i) == null ? 1 : -1;
          }
          continue;
        }
        int c = compare(x.get(i), y.get(i));
        if (c != 0) {
          return c;
        }
      }
      return Integer.compare(x.size(), y.size());
    }
    if (a instanceof String && !(b instanceof String)) {
      a = coerce((String) a, b);
    } else if (b instanceof String && !(a instanceof String)) {
      b = coerce((String) b, a);
    }
    if (a instanceof Number && b instanceof Number) {
      if ((a instanceof Long || a instanceof Integer)
          && (b instanceof Long || b instanceof Integer)) {
        return Long.compare(((Number) a).longValue(), ((Number) b).longValue());
      }
      if (!isFinite(a) || !isFinite(b)) {
        // PostgreSQL orders NaN above Infinity and treats NaN as equal to itself, as Double does
        return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
      }
      if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
        // a number next to a float is converted to double precision, as PostgreSQL resolves
        // numeric = float8 and bigint = float8: 0.30 is not 0.1::float8 + 0.2::float8
        double x = ((Number) a).doubleValue();
        double y = ((Number) b).doubleValue();
        return x < y ? -1 : x > y ? 1 : 0; // -0.0 equals 0.0
      }
      return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
    }
    if (a instanceof Comparable && a.getClass().equals(b.getClass())) {
      @SuppressWarnings("unchecked")
      int c = ((Comparable<Object>) a).compareTo(b);
      return c;
    }
    LocalDateTime ta = asTimestamp(a);
    LocalDateTime tb = asTimestamp(b);
    if (ta != null && tb != null) {
      return ta.compareTo(tb); // timestamp, timestamptz (UTC) and date compare as timestamps
    }
    throw new IllegalArgumentException("Cannot compare " + a + " with " + b);
  }

  /** Accepts the boolean spellings PostgreSQL accepts. */
  static boolean parseBoolean(String s) {
    switch (s.trim().toLowerCase(Locale.ROOT)) {
      case "t":
      case "true":
      case "1":
      case "y":
      case "yes":
      case "on":
        return true;
      case "f":
      case "false":
      case "0":
      case "n":
      case "no":
      case "off":
        return false;
      default:
        throw new IllegalArgumentException("Invalid boolean: " + s);
    }
  }

  private static Object coerce(String s, Object target) {
    try {
      if (target instanceof Number) {
        return checkedNumeric(new BigDecimal(s));
      }
      if (target instanceof Boolean) {
        return parseBoolean(s);
      }
      // the same parsers as the pushed-down path, so a predicate answers alike whichever path it
      // takes: a bare date for a timestamp, +09, +0900, Z (todo 021)
      if (target instanceof LocalDate) {
        return date(s);
      }
      if (target instanceof LocalTime) {
        return LocalTime.parse(s.trim());
      }
      if (target instanceof LocalDateTime) {
        return QueryParser.localDateTime(s);
      }
      if (target instanceof Instant) {
        return QueryParser.instant(s);
      }
      if (target instanceof ByteBuffer) {
        return ByteBuffer.wrap(QueryParser.hex(s));
      }
    } catch (ArithmeticException e) {
      throw e;
    } catch (RuntimeException e) {
      boolean temporal =
          target instanceof LocalDate
              || target instanceof LocalTime
              || target instanceof LocalDateTime
              || target instanceof Instant;
      throw new PgException.Argument(
          temporal ? "22007" : "22P02",
          "invalid input syntax for type " + pgTypeName(target) + ": \"" + s + "\"",
          e);
    }
    return s;
  }

  @Nullable private static Object column(Column c, List<Map<String, Object>> group, Context ctx) {
    String raw = QueryParser.unquote(c.getColumnName());
    // JSQLParser keeps an array subscript such as conkey[1] inside the column name
    int bracket = raw.indexOf('[');
    String name = bracket < 0 ? raw : raw.substring(0, bracket);
    if (c.getTable() == null && QueryParser.isBooleanLiteral(name)) {
      return name.equalsIgnoreCase("true");
    }
    Object value = columnValue(c, name, group, ctx);
    if (bracket < 0 && c.getArrayConstructor() == null) {
      return value;
    }
    Object subscript =
        bracket >= 0
            ? Long.parseLong(raw.substring(bracket + 1, raw.length() - 1).trim())
            : eval(c.getArrayConstructor().getExpressions().get(0), group, ctx);
    if (!(subscript instanceof Number)) {
      return null;
    }
    // int2vector (pg_index.indkey, indoption) counts from 0
    int index = ((Number) subscript).intValue() + (value instanceof Catalog.Int2Vector ? 1 : 0);
    List<?> array = value instanceof List ? (List<?>) value : null;
    return array == null || index < 1 || index > array.size() ? null : array.get(index - 1);
  }

  @Nullable private static Object columnValue(
      Column c, String name, List<Map<String, Object>> group, Context ctx) {
    Map<String, Object> row = group.isEmpty() ? Collections.emptyMap() : group.get(0);
    List<Map<String, Object>> candidates =
        ctx.outer == null ? Collections.singletonList(row) : Arrays.asList(row, ctx.outer);
    if (c.getTable() != null) {
      String key = QueryParser.unquote(c.getTable().getName()) + "." + name;
      for (Map<String, Object> candidate : candidates) {
        if (candidate.containsKey(key)) {
          return candidate.get(key);
        }
      }
    } else {
      // Not resolved by the planner (e.g. ORDER BY on an output name): match the column part
      for (Map<String, Object> candidate : candidates) {
        for (Map.Entry<String, Object> entry : candidate.entrySet()) {
          if (entry.getKey().equals(name) || entry.getKey().endsWith("." + name)) {
            return entry.getValue();
          }
        }
      }
    }
    if (c.getTable() == null) {
      // SQL keywords that read like columns, as drivers send them (pgjdbc: SELECT current_catalog)
      switch (name.toLowerCase(Locale.ROOT)) {
        case "current_catalog":
          return ctx.catalog == null ? null : ctx.catalog.database;
        case "current_schema":
          return ctx.catalog == null ? null : "public"; // the connected namespace shows as public
        case "current_user":
        case "user":
        case "session_user":
        case "current_role":
          return ctx.catalog == null ? Catalog.OWNER : ctx.catalog.user;
        default:
          break;
      }
    }
    throw new PgException.Argument("42703", "Unknown column: " + c);
  }

  private static Object temporal(DateTimeLiteralExpression e) {
    String v = e.getValue();
    v = v.startsWith("'") ? v.substring(1, v.length() - 1) : v;
    switch (e.getType()) {
      case DATE:
        return date(v);
      case TIME:
        return LocalTime.parse(v.trim());
      case TIMESTAMP:
        return QueryParser.localDateTime(v);
      default:
        return QueryParser.instant(v);
    }
  }

  /** A date, also from a timestamp's text ('2024-01-01 10:00' is 2024-01-01), as PostgreSQL. */
  private static LocalDate date(String s) {
    String t = s.trim();
    return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
  }

  /** PostgreSQL's name for the type a Java value stands for, for error messages. */
  private static String pgTypeName(Object v) {
    if (v instanceof LocalDate) {
      return "date";
    }
    if (v instanceof LocalTime) {
      return "time";
    }
    if (v instanceof LocalDateTime) {
      return "timestamp";
    }
    if (v instanceof Instant) {
      return "timestamp with time zone";
    }
    if (v instanceof Boolean) {
      return "boolean";
    }
    if (v instanceof ByteBuffer) {
      return "bytea";
    }
    return v instanceof Number ? "numeric" : "text";
  }

  private static Object now(String keyword) {
    switch (keyword.toUpperCase(Locale.ROOT).replace("()", "")) {
      case "CURRENT_TIMESTAMP":
      case "NOW":
        return statementNow();
      case "CURRENT_DATE":
        return LocalDate.ofInstant(statementNow(), zone());
      case "CURRENT_TIME":
      case "LOCALTIME":
        return LocalTime.ofInstant(statementNow(), zone());
      case "LOCALTIMESTAMP":
        return LocalDateTime.ofInstant(statementNow(), zone());
      default:
        throw new PgException.Argument("0A000", "Unsupported expression: " + keyword);
    }
  }

  @Nullable private static Object negate(@Nullable Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Integer) {
      if ((Integer) v == Integer.MIN_VALUE) {
        throw new ArithmeticException("integer out of range");
      }
      return -(Integer) v; // stays an int4, so later int4 arithmetic still checks its range
    }
    if (isIntegral(v)) {
      if (((Number) v).longValue() == Long.MIN_VALUE) {
        throw new ArithmeticException("bigint out of range");
      }
      return -((Number) v).longValue();
    }
    if (v instanceof BigDecimal) {
      return ((BigDecimal) v).negate();
    }
    if (v instanceof Interval) {
      return ((Interval) v).negate();
    }
    if (v instanceof Number) {
      return -((Number) v).doubleValue();
    }
    throw new IllegalArgumentException("Cannot negate " + v);
  }

  /** False for a floating-point NaN or Infinity, which BigDecimal cannot represent. */
  static boolean isFinite(Object v) {
    return !(v instanceof Double || v instanceof Float)
        || Double.isFinite(((Number) v).doubleValue());
  }

  static boolean isIntegral(Object v) {
    return v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte;
  }

  static String text(Object v) {
    if (v instanceof Json.Value) {
      return v.toString();
    }
    if (v instanceof BigDecimal) {
      return ((BigDecimal) v).toPlainString();
    }
    if (v instanceof Interval) {
      return v.toString();
    }
    if (v instanceof Catalog.Int2Vector) {
      return v.toString(); // pg_index.indkey prints as "1 2"
    }
    if (v instanceof Row) {
      // a record as PostgreSQL prints it: (1,,"a b"); NULL fields are empty
      StringBuilder sb = new StringBuilder("(");
      for (Object item : (Row) v) {
        if (sb.length() > 1) {
          sb.append(',');
        }
        if (item != null) {
          String s = text(item);
          boolean quote = s.isEmpty() || s.matches(".*[\\s(),\"\\\\].*");
          sb.append(quote ? '"' + s.replace("\\", "\\\\").replace("\"", "\"\"") + '"' : s);
        }
      }
      return sb.append(')').toString();
    }
    if (v instanceof List) {
      StringBuilder sb = new StringBuilder("{");
      for (Object item : (List<?>) v) {
        if (sb.length() > 1) {
          sb.append(',');
        }
        if (item == null) {
          sb.append("NULL");
          continue;
        }
        String s = text(item);
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
    if (v instanceof LocalTime) {
      LocalTime t = (LocalTime) v;
      return String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond())
          + fraction(t.getNano());
    }
    if (v instanceof LocalDateTime) {
      return timestampText((LocalDateTime) v);
    }
    if (v instanceof Instant) {
      // in the session's zone, with PostgreSQL's offset suffix (+00, +09, +05:30)
      java.time.ZonedDateTime z = ((Instant) v).atZone(zone());
      return timestampText(z.toLocalDateTime()) + offsetText(z.getOffset());
    }
    if (v instanceof ByteBuffer) {
      ByteBuffer b = ((ByteBuffer) v).duplicate();
      byte[] bytes = new byte[b.remaining()];
      b.get(bytes);
      return "\\x" + hex(bytes);
    }
    if (v instanceof byte[]) {
      return "\\x" + hex((byte[]) v);
    }
    if (v instanceof Double) {
      return floatText((Double) v, 15);
    }
    if (v instanceof Float) {
      return floatText((Float) v, 6);
    }
    return String.valueOf(v);
  }

  /** PostgreSQL's timestamp text: the date and time, and the fraction without trailing zeros. */
  static String timestampText(LocalDateTime t) {
    return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            t.getYear(),
            t.getMonthValue(),
            t.getDayOfMonth(),
            t.getHour(),
            t.getMinute(),
            t.getSecond())
        + fraction(t.getNano());
  }

  private static String fraction(int nanos) {
    return nanos == 0 ? "" : String.format(".%06d", nanos / 1000).replaceAll("0+$", "");
  }

  static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * A float in PostgreSQL's output format: the shortest digits that round-trip, in plain notation
   * for decimal exponents from -4 up to {@code maxPlainExponent} - 1 and as {@code 1.5e+20}
   * otherwise.
   */
  private static String floatText(double d, int maxPlainExponent) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "Infinity" : "-Infinity";
    }
    if (d == 0) {
      return 1 / d < 0 ? "-0" : "0";
    }
    // Float.toString keeps a float's shortest digits; widening to double first would not
    BigDecimal b =
        new BigDecimal(maxPlainExponent == 6 ? Float.toString((float) d) : Double.toString(d))
            .stripTrailingZeros();
    int exponent = b.precision() - b.scale() - 1;
    if (exponent >= -4 && exponent < maxPlainExponent) {
      return b.toPlainString();
    }
    String digits = b.unscaledValue().abs().toString();
    int e = Math.abs(exponent);
    return (b.signum() < 0 ? "-" : "")
        + digits.charAt(0)
        + (digits.length() > 1 ? "." + digits.substring(1) : "")
        + (exponent < 0 ? "e-" : "e+")
        + (e < 10 ? "0" : "")
        + e;
  }

  @Nullable private static Object arithmetic(
      BinaryExpression e, List<Map<String, Object>> group, Context ctx) {
    Object l = eval(e.getLeftExpression(), group, ctx);
    Object r = eval(e.getRightExpression(), group, ctx);
    if (l == null || r == null) {
      return null;
    }
    if (l instanceof Json.Value && e instanceof Subtraction) {
      return new Json.Value(Json.delete(((Json.Value) l).node, r), true, null); // jsonb - key
    }
    if (l instanceof LocalDate || r instanceof LocalDate) {
      // date + integer, date - integer and date - date (a number of days), as in PostgreSQL
      if (l instanceof LocalDate && r instanceof LocalDate && e instanceof Subtraction) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between((LocalDate) r, (LocalDate) l);
      }
      if (l instanceof LocalDate
          && isIntegral(r)
          && (e instanceof Addition || e instanceof Subtraction)) {
        long days = ((Number) r).longValue();
        return ((LocalDate) l).plusDays(e instanceof Addition ? days : -days);
      }
      if (isIntegral(l) && r instanceof LocalDate && e instanceof Addition) {
        return ((LocalDate) r).plusDays(((Number) l).longValue());
      }
    }
    Object temporal = temporalArithmetic(e, l, r);
    if (temporal != null) {
      return temporal;
    }
    // A quoted literal has no type of its own: next to a number it takes that number's type, as in
    // PostgreSQL ('5' + 1 is 6, '1.5' + 1 is 22P02). A text column stays text and is refused
    if (e.getLeftExpression() instanceof StringValue && r instanceof Number) {
      l = cast(isInt(r, e.getRightExpression()) ? "integer" : numericTypeName(r), l);
    } else if (e.getRightExpression() instanceof StringValue && l instanceof Number) {
      r = cast(isInt(l, e.getLeftExpression()) ? "integer" : numericTypeName(l), r);
    }
    if (!(l instanceof Number) || !(r instanceof Number)) {
      throw new PgException.Argument( // undefined_function, as PostgreSQL's text + integer
          "42883", "operator does not exist: " + e);
    }
    boolean division = e instanceof Division || e instanceof Modulo;
    if (division && ((Number) r).doubleValue() == 0) {
      throw new PgException.Argument("22012", "division by zero");
    }
    if (isIntegral(l) && isIntegral(r)) {
      long a = ((Number) l).longValue();
      long b = ((Number) r).longValue();
      long v;
      if (e instanceof Addition) {
        v = Math.addExact(a, b);
      } else if (e instanceof Subtraction) {
        v = Math.subtractExact(a, b);
      } else if (e instanceof Multiplication) {
        v = Math.multiplyExact(a, b);
      } else if (e instanceof Modulo) {
        v = a % b;
      } else if (a == Long.MIN_VALUE && b == -1) {
        throw new ArithmeticException("bigint out of range");
      } else {
        v = a / b;
      }
      // integer op integer stays integer in PostgreSQL and fails on overflow
      if (isInt(l, e.getLeftExpression()) && isInt(r, e.getRightExpression())) {
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
          throw new ArithmeticException("integer out of range");
        }
        return (int) v;
      }
      return v;
    }
    if (!(l instanceof Double || l instanceof Float || r instanceof Double || r instanceof Float)) {
      // numeric with numeric or an integer stays numeric: exact, with PostgreSQL's result scale
      BigDecimal a = toDecimal(l);
      BigDecimal b = toDecimal(r);
      if (e instanceof Addition) {
        return a.add(b);
      }
      if (e instanceof Subtraction) {
        return a.subtract(b);
      }
      if (e instanceof Multiplication) {
        return a.multiply(b);
      }
      return e instanceof Modulo ? a.remainder(b) : divide(a, b);
    }
    double a = ((Number) l).doubleValue();
    double b = ((Number) r).doubleValue();
    double v;
    if (e instanceof Addition) {
      v = a + b;
    } else if (e instanceof Subtraction) {
      v = a - b;
    } else if (e instanceof Multiplication) {
      v = a * b;
    } else {
      v = e instanceof Modulo ? a % b : a / b;
    }
    // PostgreSQL's float checks: an infinite result from finite operands, or zero from non-zero
    // ones
    if (Double.isInfinite(v) && !Double.isInfinite(a) && !Double.isInfinite(b)) {
      throw new ArithmeticException("value out of range: overflow");
    }
    if (v == 0
        && a != 0
        && (e instanceof Multiplication
            ? b != 0
            : e instanceof Division && !Double.isInfinite(b))) {
      throw new ArithmeticException("value out of range: underflow");
    }
    return v;
  }

  /**
   * Interval arithmetic: a timestamp, date or time plus or minus an interval, interval with
   * interval, interval times or divided by a number, and the difference of two timestamps or times,
   * which is an interval. Null when the operands are not of these kinds.
   */
  @Nullable private static Object temporalArithmetic(BinaryExpression e, Object l, Object r) {
    boolean add = e instanceof Addition;
    boolean sub = e instanceof Subtraction;
    if (r instanceof Interval && (add || sub)) {
      Interval iv = sub ? ((Interval) r).negate() : (Interval) r;
      if (l instanceof Interval) {
        return ((Interval) l).plus(iv);
      }
      Object shifted = shift(l, iv);
      if (shifted != null) {
        return shifted;
      }
    }
    if (l instanceof Interval && add) {
      Object shifted = shift(r, (Interval) l);
      if (shifted != null) {
        return shifted;
      }
    }
    if (l instanceof Interval
        && r instanceof Number
        && (e instanceof Multiplication || e instanceof Division)) {
      double f = ((Number) r).doubleValue();
      if (e instanceof Division && f == 0) {
        throw new PgException.Argument("22012", "division by zero");
      }
      return ((Interval) l).times(e instanceof Division ? 1 / f : f);
    }
    if (r instanceof Interval && l instanceof Number && e instanceof Multiplication) {
      return ((Interval) r).times(((Number) l).doubleValue());
    }
    if (sub && l instanceof Instant && r instanceof Instant) {
      // elapsed time between two instants, whatever the zone's offsets (23:00:00 across spring DST)
      return Interval.between(
          LocalDateTime.ofInstant((Instant) r, ZoneOffset.UTC),
          LocalDateTime.ofInstant((Instant) l, ZoneOffset.UTC));
    }
    if (sub) {
      LocalDateTime a = asTimestamp(l);
      LocalDateTime b = asTimestamp(r);
      if (a != null && b != null && !(l instanceof LocalDate && r instanceof LocalDate)) {
        return Interval.between(b, a);
      }
      if (l instanceof LocalTime && r instanceof LocalTime) {
        return new Interval(
            0, 0, java.time.temporal.ChronoUnit.MICROS.between((LocalTime) r, (LocalTime) l));
      }
    }
    return null;
  }

  /** A timestamp, timestamptz, date or time moved by an interval; null for other values. */
  @Nullable private static Object shift(Object v, Interval iv) {
    if (v instanceof LocalDateTime) {
      return iv.addTo((LocalDateTime) v);
    }
    if (v instanceof Instant) {
      return iv.addTo((Instant) v);
    }
    if (v instanceof LocalDate) {
      return iv.addTo(((LocalDate) v).atStartOfDay()); // date + interval is a timestamp
    }
    if (v instanceof LocalTime) {
      return iv.addTo((LocalTime) v);
    }
    return null;
  }

  /** A timestamp, timestamptz (in UTC) or date as a local timestamp; null for other values. */
  @Nullable private static LocalDateTime asTimestamp(Object v) {
    if (v instanceof LocalDateTime) {
      return (LocalDateTime) v;
    }
    if (v instanceof Instant) {
      return LocalDateTime.ofInstant((Instant) v, zone());
    }
    if (v instanceof LocalDate) {
      return ((LocalDate) v).atStartOfDay();
    }
    return null;
  }

  /**
   * {@code AT TIME ZONE}: a timestamptz becomes the local timestamp in the zone, and a timestamp
   * (taken as local time in the zone) becomes a timestamptz.
   */
  @Nullable private static Object atTimeZone(@Nullable Object v, @Nullable Object zone) {
    if (v == null || zone == null) {
      return null;
    }
    java.time.ZoneId z;
    if (zone instanceof Interval) {
      z = ZoneOffset.ofTotalSeconds((int) (((Interval) zone).micros / 1_000_000L));
    } else {
      String name = text(zone);
      try {
        z = java.time.ZoneId.of(name, java.time.ZoneId.SHORT_IDS);
      } catch (java.time.DateTimeException notAZone) {
        throw new IllegalArgumentException("time zone \"" + name + "\" not recognized");
      }
    }
    if (v instanceof Instant) {
      return LocalDateTime.ofInstant((Instant) v, z);
    }
    LocalDateTime local = asTimestamp(v);
    if (local != null) {
      return local.atZone(z).toInstant();
    }
    throw new IllegalArgumentException("AT TIME ZONE needs a timestamp: " + text(v));
  }

  /** An INT value, or an integer literal small enough to be int4 in PostgreSQL. */
  private static boolean isInt(Object v, Expression e) {
    if (e instanceof SignedExpression
        && ((SignedExpression) e).getExpression() instanceof LongValue) {
      // -2147483648 is an int4 literal too
      BigInteger n = ((LongValue) ((SignedExpression) e).getExpression()).getBigIntegerValue();
      return (((SignedExpression) e).getSign() == '-' ? n.negate() : n).bitLength() < 32;
    }
    return v instanceof Integer
        || (e instanceof LongValue && ((LongValue) e).getBigIntegerValue().bitLength() < 32);
  }

  /** An integer literal as a long, or as a numeric beyond bigint. */
  private static Object integer(BigInteger v) {
    return v.bitLength() < 64 ? (Object) v.longValue() : (Object) new BigDecimal(v);
  }

  /** The comparison's outcome given {@code compare(left, right)}; null for an unknown operator. */
  @Nullable private static Boolean compared(ComparisonOperator op, int c) {
    if (op instanceof EqualsTo) {
      return c == 0;
    }
    if (op instanceof NotEqualsTo) {
      return c != 0;
    }
    if (op instanceof GreaterThan) {
      return c > 0;
    }
    if (op instanceof GreaterThanEquals) {
      return c >= 0;
    }
    if (op instanceof MinorThan) {
      return c < 0;
    }
    if (op instanceof MinorThanEquals) {
      return c <= 0;
    }
    return null;
  }

  /** The elements of an array value: a list, or PostgreSQL's array text. */
  static List<?> elements(Object array) {
    return array instanceof List
        ? (List<?>) array
        : QueryParser.parseArrayText(String.valueOf(array));
  }

  /**
   * {@code x op ANY|SOME|ALL (subquery)} with SQL's three-valued logic: ANY is true if some
   * comparison is true, ALL is false if some comparison is false, and otherwise a NULL on either
   * side makes the result NULL.
   */
  @Nullable private static Boolean anyOrAll(
      ComparisonOperator op, @Nullable Object left, boolean all, List<?> values) {
    boolean sawNull = false;
    for (Object right : values) {
      if (left == null || right == null) {
        sawNull = true;
        continue;
      }
      Boolean result = compared(op, compare(left, right));
      if (result == null) {
        throw new PgException.Argument("0A000", "Unsupported expression: " + op);
      }
      if (result != all) {
        return result;
      }
    }
    return sawNull ? null : all;
  }

  /** {@code EXTRACT(field FROM v)} and {@code date_part}: an integer, or a fraction for seconds. */
  @Nullable static Object extract(String field, @Nullable Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Interval) {
      return ((Interval) v).extract(field);
    }
    String unit = field.toUpperCase(Locale.ROOT);
    if (v instanceof LocalTime
        && !Arrays.asList("HOUR", "MINUTE", "SECOND", "EPOCH").contains(unit)) {
      throw new PgException.Argument("0A000", "Unit \"" + field + "\" not supported for type time");
    }
    LocalDateTime t =
        v instanceof LocalDateTime
            ? (LocalDateTime) v
            : v instanceof LocalDate
                ? ((LocalDate) v).atStartOfDay()
                : v instanceof LocalTime
                    ? LocalDate.of(1970, 1, 1).atTime((LocalTime) v) // epoch: seconds of the day
                    : v instanceof Instant ? LocalDateTime.ofInstant((Instant) v, zone()) : null;
    if (t == null) {
      throw new IllegalArgumentException("Cannot extract " + field + " from " + text(v));
    }
    switch (field.toUpperCase(Locale.ROOT)) {
      case "YEAR":
        return (long) t.getYear();
      case "QUARTER":
        return (long) ((t.getMonthValue() - 1) / 3 + 1);
      case "MONTH":
        return (long) t.getMonthValue();
      case "WEEK":
        return (long) t.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
      case "DAY":
        return (long) t.getDayOfMonth();
      case "DOW":
        return (long) (t.getDayOfWeek().getValue() % 7); // Sunday is 0
      case "ISODOW":
        return (long) t.getDayOfWeek().getValue();
      case "DOY":
        return (long) t.getDayOfYear();
      case "HOUR":
        return (long) t.getHour();
      case "MINUTE":
        return (long) t.getMinute();
      case "SECOND":
        return BigDecimal.valueOf(t.getSecond()).add(BigDecimal.valueOf(t.getNano() / 1000, 6));
      case "EPOCH":
        return BigDecimal.valueOf(t.toEpochSecond(ZoneOffset.UTC))
            .add(BigDecimal.valueOf(t.getNano() / 1000, 6));
      default:
        throw new PgException.Argument("0A000", "Unsupported EXTRACT field: " + field);
    }
  }

  /** {@code date_trunc(unit, v)}: the timestamp truncated to the start of the unit. */
  @Nullable private static Object dateTrunc(String unit, @Nullable Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Instant) {
      // a timestamptz is truncated in the session's zone and stays a timestamptz
      Object local =
          java.util.Objects.requireNonNull(
              dateTrunc(unit, LocalDateTime.ofInstant((Instant) v, zone())));
      return ((LocalDateTime) local).atZone(zone()).toInstant();
    }
    LocalDateTime t = v instanceof LocalDate ? ((LocalDate) v).atStartOfDay() : (LocalDateTime) v;
    switch (unit.toLowerCase(Locale.ROOT)) {
      case "year":
        return t.toLocalDate().withDayOfYear(1).atStartOfDay();
      case "quarter":
        return t.toLocalDate()
            .withMonth((t.getMonthValue() - 1) / 3 * 3 + 1)
            .withDayOfMonth(1)
            .atStartOfDay();
      case "month":
        return t.toLocalDate().withDayOfMonth(1).atStartOfDay();
      case "week":
        return t.toLocalDate().minusDays(t.getDayOfWeek().getValue() - 1).atStartOfDay();
      case "day":
        return t.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
      case "hour":
        return t.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
      case "minute":
        return t.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
      case "second":
        return t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      default:
        throw new PgException.Argument("0A000", "Unsupported date_trunc unit: " + unit);
    }
  }

  /**
   * Orders two sort keys for one ORDER BY element: PostgreSQL puts NULLs last for ASC and first for
   * DESC unless NULLS FIRST/LAST says otherwise.
   */
  static int orderCompare(OrderByElement o, @Nullable Object a, @Nullable Object b) {
    if (a == null || b == null) {
      boolean nullsFirst =
          o.getNullOrdering() == null
              ? !o.isAsc()
              : o.getNullOrdering() == OrderByElement.NullOrdering.NULLS_FIRST;
      return a == b ? 0 : (a == null) == nullsFirst ? -1 : 1;
    }
    return o.isAsc() ? compare(a, b) : compare(b, a);
  }

  @Nullable private static Object caseOf(CaseExpression c, List<Map<String, Object>> group, Context ctx) {
    Object subject =
        c.getSwitchExpression() == null ? null : eval(c.getSwitchExpression(), group, ctx);
    for (WhenClause when : c.getWhenClauses()) {
      Object condition = eval(when.getWhenExpression(), group, ctx);
      boolean matches =
          c.getSwitchExpression() == null
              ? isTrue(condition)
              : subject != null && condition != null && compare(subject, condition) == 0;
      if (matches) {
        return widened(eval(when.getThenExpression(), group, ctx), c, branches(c), group, ctx);
      }
    }
    return c.getElseExpression() == null
        ? null
        : widened(eval(c.getElseExpression(), group, ctx), c, branches(c), group, ctx);
  }

  private static List<Expression> branches(CaseExpression c) {
    List<Expression> out = new ArrayList<>();
    for (WhenClause when : c.getWhenClauses()) {
      out.add(when.getThenExpression());
    }
    if (c.getElseExpression() != null) {
      out.add(c.getElseExpression());
    }
    return out;
  }

  /**
   * PostgreSQL types CASE, COALESCE, GREATEST, LEAST and NULLIF by all their branches, not by the
   * one taken: an integer result becomes numeric when another branch is numeric (a decimal literal
   * such as {@code 1.5} is numeric), and an integer or numeric result becomes double precision when
   * another branch is a float, so that a later {@code / 2} divides as PostgreSQL does. The type
   * comes from the plan, once per expression (a numeric column is numeric in a row where it is
   * NULL); only a branch the plan cannot type, such as an outer column, is looked at per row.
   */
  @Nullable private static Object widened(
      @Nullable Object v,
      Expression owner,
      List<? extends Expression> branches,
      List<Map<String, Object>> group,
      Context ctx) {
    if (!(v instanceof Long) && !(v instanceof Integer) && !(v instanceof BigDecimal)) {
      return v;
    }
    int widest = ctx.widenings.computeIfAbsent(owner, o -> planned(branches, ctx));
    if (widest == UNKNOWN) {
      widest = NOT_WIDER;
      for (Expression b : branches) {
        widest = Math.max(widest, widening(b, group, ctx));
        if (widest == FLOAT) {
          break; // nothing is wider
        }
      }
    }
    if (widest == FLOAT) {
      return ((Number) v).doubleValue();
    }
    if (widest == NUMERIC && !(v instanceof BigDecimal)) {
      return BigDecimal.valueOf(((Number) v).longValue());
    }
    return v;
  }

  /**
   * What the branches widen to by their plan-time types, or UNKNOWN when one of them has none and
   * nothing else already settles it.
   */
  private static int planned(List<? extends Expression> branches, Context ctx) {
    boolean unknown = false;
    for (Expression b : branches) {
      unknown |=
          !(QueryParser.unwrap(b) instanceof NullValue)
              && QueryParser.inferOid(b, ctx.sources) == 0;
    }
    int oid = QueryParser.unifiedOid(branches, ctx.sources);
    int widest = oid == 700 || oid == 701 ? FLOAT : oid == 1700 ? NUMERIC : NOT_WIDER;
    return unknown && widest != FLOAT ? UNKNOWN : widest;
  }

  private static final int UNKNOWN = -1;

  /** What a branch widens an integer result to: nothing, numeric or double precision. */
  private static final int NOT_WIDER = 0;

  private static final int NUMERIC = 1;
  private static final int FLOAT = 2;

  // ponytail: a correlated outer column that is NULL in this row is typed by neither the value
  // nor this plan's sources, so such a branch cannot widen; a plan-time typer would close that
  private static int widening(Expression e, List<Map<String, Object>> group, Context ctx) {
    e = QueryParser.unwrap(e);
    if (e instanceof DoubleValue) {
      return NUMERIC; // 1.5 and 1e3 are numeric literals in PostgreSQL
    }
    if (e instanceof SignedExpression) {
      return widening(((SignedExpression) e).getExpression(), group, ctx);
    }
    if (e instanceof Column) {
      Object v;
      try {
        v = column((Column) e, group, ctx);
      } catch (IllegalArgumentException unknown) {
        return NOT_WIDER;
      }
      if (v != null) {
        return v instanceof Double || v instanceof Float
            ? FLOAT
            : v instanceof BigDecimal ? NUMERIC : NOT_WIDER;
      }
      com.scalar.db.io.DataType t = ctx.columnType((Column) e);
      return t == com.scalar.db.io.DataType.DOUBLE || t == com.scalar.db.io.DataType.FLOAT
          ? FLOAT
          : NOT_WIDER;
    }
    if (e instanceof Addition
        || e instanceof Subtraction
        || e instanceof Multiplication
        || e instanceof Division
        || e instanceof Modulo) {
      BinaryExpression b = (BinaryExpression) e;
      return Math.max(
          widening(b.getLeftExpression(), group, ctx),
          widening(b.getRightExpression(), group, ctx));
    }
    if (e instanceof CastExpression) {
      String type = ((CastExpression) e).getColDataType().getDataType().toLowerCase(Locale.ROOT);
      return type.matches("(double|float|real).*")
          ? FLOAT
          : type.matches("(numeric|decimal).*") ? NUMERIC : NOT_WIDER;
    }
    int widest = NOT_WIDER;
    if (e instanceof CaseExpression) {
      for (Expression b : branches((CaseExpression) e)) {
        widest = Math.max(widest, widening(b, group, ctx));
      }
      return widest;
    }
    if (e instanceof Function && ((Function) e).getParameters() != null) {
      String name = ((Function) e).getName().toUpperCase(Locale.ROOT);
      if (SAME_TYPE_AS_ARGUMENTS.matcher(name).matches()) {
        for (Expression p : ((Function) e).getParameters()) {
          widest = Math.max(widest, widening(p, group, ctx));
        }
        return widest;
      }
    }
    // avg(), sum(), a scalar subquery and the like: the type the planner reports for it
    int oid = QueryParser.inferOid(e, ctx.sources);
    return oid == 700 || oid == 701 ? FLOAT : oid == 1700 ? NUMERIC : NOT_WIDER;
  }

  /** Functions whose result has the type of their (unified) arguments, as QueryParser.inferOid. */
  private static final Pattern SAME_TYPE_AS_ARGUMENTS =
      Pattern.compile("COALESCE|GREATEST|LEAST|NULLIF|ABS|MIN|MAX|ROUND|FLOOR|CEIL|CEILING|TRUNC");

  private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?\\d+");

  private static final Pattern TYPE_LENGTH = Pattern.compile("\\((\\d+)\\)");
  private static final Pattern TRAILING_SPACES = Pattern.compile(" +$");

  /**
   * Integer input as PostgreSQL reads it: digits with a sign, surrounding spaces allowed; '1.5' and
   * '1e3' are 22P02, not rounded (a number, as opposed to text, is rounded by the caller).
   */
  private static BigDecimal integerText(String s, String type) {
    String trimmed = s.trim();
    if (!INTEGER_TEXT.matcher(trimmed).matches()) {
      throw new PgException.Argument(
          "22P02", "invalid input syntax for type " + type + ": \"" + s + "\"");
    }
    return decimal(trimmed);
  }

  /** The SQL name of a number's type, for typing a quoted literal from it. */
  private static String numericTypeName(Object n) {
    if (n instanceof Integer) {
      return "integer";
    }
    if (n instanceof Long) {
      return "bigint";
    }
    if (n instanceof Short) {
      return "smallint";
    }
    if (n instanceof Float) {
      return "real";
    }
    return n instanceof Double ? "double precision" : "numeric";
  }

  private static final java.util.Set<String> NUMERIC_FUNCTIONS =
      new java.util.HashSet<>(
          Arrays.asList(
              "ABS", "ROUND", "FLOOR", "CEIL", "CEILING", "MOD", "POWER", "POW", "SQRT", "SIGN"));

  /**
   * Types the quoted literals among a numeric function's arguments as PostgreSQL resolves them: mod
   * takes its other argument's type, round(x, n) is numeric's, and the others double precision,
   * PostgreSQL's preferred numeric type (abs('-3') is 3, round('2.5') is 2).
   */
  private static void typeLiteralArguments(
      String name, @Nullable List<?> parameters, List<Object> args) {
    if (parameters == null || !NUMERIC_FUNCTIONS.contains(name)) {
      return;
    }
    for (int i = 0; i < args.size() && i < parameters.size(); i++) {
      if (!(parameters.get(i) instanceof StringValue) || args.get(i) == null) {
        continue;
      }
      String type = "double precision";
      if (name.equals("ROUND") && args.size() == 2) {
        type = "numeric";
      } else if (name.equals("MOD")) {
        Object other = args.size() == 2 ? args.get(1 - i) : null;
        type = other instanceof Number ? numericTypeName(other) : "integer";
      }
      args.set(i, cast(type, args.get(i)));
    }
  }

  @Nullable static Object cast(String type, @Nullable Object v) {
    if (v == null) {
      return null;
    }
    String t = type.toLowerCase(Locale.ROOT).trim();
    t = t.startsWith("pg_catalog.") ? t.substring("pg_catalog.".length()) : t;
    if (t.startsWith("time")) {
      // timestamp(p), timestamptz(p), time(p): the fraction is rounded to p digits
      java.util.regex.Matcher precision = Pattern.compile("\\s*\\((\\d+)\\)").matcher(t);
      if (precision.find()) {
        Object exact = cast(precision.replaceFirst("").trim(), v);
        return exact == null ? null : roundedTo(exact, Integer.parseInt(precision.group(1)));
      }
    }
    String s = text(v);
    try {
      if (t.equals("oid") || t.startsWith("reg")) {
        // regclass, regtype, regnamespace: an OID given as a number stays numeric
        return v instanceof Number
            ? ((Number) v).longValue()
            : s.matches("\\d+") ? Long.parseLong(s) : v;
      }
      if (t.equals("interval")) {
        return v instanceof Interval ? v : Interval.parse(s);
      }
      if (t.equals("uuid")) {
        return java.util.UUID.fromString(s.trim()).toString();
      }
      if (t.equals("json") || t.equals("jsonb")) {
        boolean binary = t.equals("jsonb");
        if (v instanceof Json.Value) {
          Json.Value j = (Json.Value) v;
          return j.binary == binary ? j : new Json.Value(j.node, binary, null);
        }
        return Json.cast(s, binary);
      }
      if (t.equals("bytea")) {
        return v instanceof ByteBuffer || v instanceof byte[]
            ? v
            : ByteBuffer.wrap(QueryParser.hex(s));
      }
      if (t.endsWith("[]")) {
        String element = t.substring(0, t.length() - 2).trim();
        List<Object> out = new ArrayList<>();
        for (Object item : elements(v)) {
          out.add(item == null || !(item instanceof String) ? item : cast(element, item));
        }
        return out;
      }
      if (t.equals("bigint") || t.equals("int8")) {
        try {
          return v instanceof Number
              ? rounded((Number) v)
              : integerText(s, "bigint").longValueExact();
        } catch (ArithmeticException e) {
          throw new ArithmeticException("bigint out of range");
        }
      }
      if (t.contains("int")) {
        boolean small = t.equals("smallint") || t.equals("int2");
        long n;
        try {
          n =
              v instanceof Number
                  ? rounded((Number) v)
                  : integerText(s, small ? "smallint" : "integer").longValueExact();
        } catch (ArithmeticException e) {
          n = Long.MAX_VALUE; // beyond bigint: out of range below
        }
        if (small ? n < Short.MIN_VALUE || n > Short.MAX_VALUE : n != (int) n) {
          throw new ArithmeticException((small ? "smallint" : "integer") + " out of range");
        }
        return (int) n;
      }
      if (t.startsWith("numeric") || t.startsWith("decimal")) {
        BigDecimal d = v instanceof Number ? toDecimal(v) : decimal(s.trim());
        long[] typmod = QueryParser.typeArguments(t);
        if (typmod.length > 0) {
          QueryParser.checkNumericModifiers(typmod); // numeric(0), numeric(5,-5000): 22023
          d = applyTypmod(d, (int) typmod[0], typmod.length > 1 ? (int) typmod[1] : 0);
        }
        return d;
      }
      if (t.contains("float") || t.contains("double") || t.equals("real")) {
        return v instanceof Number ? ((Number) v).doubleValue() : Double.parseDouble(s);
      }
      if (t.contains("char") || t.equals("text") || t.equals("string")) {
        java.util.regex.Matcher length = TYPE_LENGTH.matcher(t);
        // char(n) and varchar(n) casts truncate; a bare char or character is char(1)
        boolean bpchar = (t.startsWith("char") || t.startsWith("bpchar")) && !t.contains("varying");
        int n =
            length.find()
                ? Integer.parseInt(length.group(1))
                : bpchar && !t.equals("bpchar") ? 1 : Integer.MAX_VALUE;
        String out =
            s.codePointCount(0, s.length()) > n ? s.substring(0, s.offsetByCodePoints(0, n)) : s;
        // ponytail: a character(n) value is its text without trailing spaces here, which is how
        // PostgreSQL compares, concatenates and measures it; padding it back to n on output needs
        // the column typmod
        // (docs/plans/2026-10-14-001-feat-keep-type-modifiers-exact-numeric-plan.md)
        return bpchar ? TRAILING_SPACES.matcher(out).replaceFirst("") : out;
      }
      if (t.equals("boolean") || t.equals("bool")) {
        return v instanceof Boolean
            ? v
            : v instanceof Number ? ((Number) v).intValue() != 0 : parseBoolean(s);
      }
      if (t.equals("date")) {
        return v instanceof LocalDate
            ? v
            : v instanceof LocalDateTime
                ? ((LocalDateTime) v).toLocalDate()
                : v instanceof Instant
                    ? ((Instant) v).atZone(zone()).toLocalDate()
                    : LocalDate.parse(
                        s.trim().length() > 10 ? s.trim().substring(0, 10) : s.trim());
      }
      if (t.equals("time")) {
        return v instanceof LocalTime ? v : LocalTime.parse(s);
      }
      if (t.equals("timestamptz") || t.contains("with time zone")) {
        return v instanceof Instant
            ? v
            : v instanceof LocalDateTime
                ? ((LocalDateTime) v).atZone(zone()).toInstant()
                : QueryParser.instant(s);
      }
      if (t.startsWith("timestamp")) {
        return v instanceof LocalDateTime
            ? v
            : v instanceof Instant
                ? LocalDateTime.ofInstant((Instant) v, zone())
                : QueryParser.localDateTime(s);
      }
    } catch (ArithmeticException | PgException.Argument e) {
      throw e; // out of range (22003), or already PostgreSQL's wording
    } catch (RuntimeException e) {
      throw new PgException.Argument("22P02", "Cannot cast '" + s + "' to " + type, e);
    }
    throw new PgException.Argument("0A000", "Unsupported cast type: " + type);
  }

  /**
   * A number rounded to an integer the way PostgreSQL casts numeric (half away from zero), failing
   * when out of range. NUMERIC columns are doubles here, so this also applies to them; a true
   * float8 .5 rounds to even in PostgreSQL.
   */
  private static long rounded(Number n) {
    return new BigDecimal(n.toString()).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  @Nullable private static Object trim(TrimFunction t, List<Map<String, Object>> group, Context ctx) {
    boolean fromForm = t.getFromExpression() != null;
    Object str = eval(fromForm ? t.getFromExpression() : t.getExpression(), group, ctx);
    Object chars =
        fromForm && t.getExpression() != null ? eval(t.getExpression(), group, ctx) : " ";
    if (str == null || chars == null) {
      return null;
    }
    String spec = t.getTrimSpecification() == null ? "BOTH" : t.getTrimSpecification().name();
    return trim(text(str), text(chars), !spec.equals("TRAILING"), !spec.equals("LEADING"));
  }

  private static String trim(String s, String chars, boolean leading, boolean trailing) {
    int start = 0;
    int end = s.length();
    while (leading && start < end && chars.indexOf(s.charAt(start)) >= 0) {
      start++;
    }
    while (trailing && end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
      end--;
    }
    return s.substring(start, end);
  }

  @Nullable private static Object scalar(Function f, List<Map<String, Object>> group, Context ctx) {
    String name = functionName(f);
    if (name.equals("GROUPING")) {
      // grouping(a, b): a bit per argument, 1 when the grouping set left that expression out
      Object outside = group.isEmpty() ? null : group.get(0).get(Operators.GROUPING_KEY);
      long bits = 0;
      if (f.getParameters() != null) {
        for (Expression p : f.getParameters()) {
          boolean left = outside instanceof Set && ((Set<?>) outside).contains(p.toString());
          bits = bits * 2 + (left ? 1 : 0);
        }
      }
      return bits;
    }
    if (name.equals("ARRAY")
        && f.getParameters() != null
        && f.getParameters().size() == 1
        && f.getParameters().get(0) instanceof Select) {
      // ARRAY(SELECT ...): the first column of the subquery as a list
      List<Object> values = new ArrayList<>();
      for (Map<String, Object> row : ctx.subquery((Select) f.getParameters().get(0), group)) {
        values.add(row.values().iterator().next());
      }
      return values;
    }
    if (name.equals("COALESCE") && f.getParameters() != null) {
      // stops at the first non-null argument: coalesce(1, 1/0) is 1, as in PostgreSQL
      for (Expression p : f.getParameters()) {
        Object a = eval(p, group, ctx);
        if (a != null) {
          return widened(a, f, f.getParameters(), group, ctx);
        }
      }
      return null;
    }
    List<Object> args = new ArrayList<>();
    boolean textual = TEXT_FUNCTIONS.contains(name);
    boolean allText = name.equals("CONCAT") || name.equals("CONCAT_WS");
    if (f.getParameters() != null) {
      for (Expression p : f.getParameters()) {
        args.add(
            textual && (allText || args.isEmpty()) ? evalText(p, group, ctx) : eval(p, group, ctx));
      }
    } else if (f.getNamedParameters() != null) {
      // position(sub IN s), substring(s FROM n FOR m): the operands in written order
      for (Object p : f.getNamedParameters().getExpressions()) {
        args.add(eval((Expression) p, group, ctx));
      }
      if (name.equals("POSITION")) {
        Collections.reverse(args); // the same arguments as strpos(s, sub)
      }
    }
    typeLiteralArguments(name, f.getParameters(), args);
    Object catalogResult = catalogFunction(name, args, ctx);
    if (catalogResult != NOT_A_CATALOG_FUNCTION) {
      return catalogResult;
    }
    switch (name) {
      case "COALESCE":
        for (Object a : args) {
          if (a != null) {
            return widened(a, f, f.getParameters(), group, ctx);
          }
        }
        return null;
      case "CONCAT":
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
          if (a != null) {
            sb.append(text(a));
          }
        }
        return sb.toString();
      case "CONCAT_WS":
        if (arg(args, 0) == null) {
          return null;
        }
        StringJoiner joined = new StringJoiner(text(args.get(0)));
        for (Object a : args.subList(1, args.size())) {
          if (a != null) {
            joined.add(text(a));
          }
        }
        return joined.toString();
      case "NULLIF":
        return arg(args, 0) != null
                && arg(args, 1) != null
                && compare(args.get(0), args.get(1)) == 0
            ? null
            : widened(arg(args, 0), f, f.getParameters(), group, ctx);
      case "GREATEST":
      case "LEAST":
        {
          // not strict: NULL arguments are ignored, and the result is NULL only if all are
          Object best = null;
          for (Object a : args) {
            if (a != null && (best == null || (compare(a, best) > 0) == name.equals("GREATEST"))) {
              best = a;
            }
          }
          return widened(best, f, f.getParameters(), group, ctx);
        }
      case "JSON_BUILD_OBJECT":
      case "JSONB_BUILD_OBJECT":
        return new Json.Value(Json.buildObject(args), name.startsWith("JSONB"), null);
      case "JSON_BUILD_ARRAY":
      case "JSONB_BUILD_ARRAY":
        return new Json.Value(Json.buildArray(args), name.startsWith("JSONB"), null);
      case "NOW":
      case "TRANSACTION_TIMESTAMP":
        return statementNow();
      case "CURRENT_SETTING":
        {
          // current_setting(name [, missing_ok]): an unknown name is 42704 unless missing_ok
          String setting = str(args, 0);
          Settings settings = SETTINGS.get();
          String value = settings == null ? null : settings.get(setting);
          if (value == null && !(args.size() > 1 && isTrue(arg(args, 1)))) {
            throw new PgException.Argument(
                "42704", "unrecognized configuration parameter \"" + setting + "\"");
          }
          return value;
        }
      case "SET_CONFIG":
        {
          // set_config(name, value, is_local): SET [LOCAL], returning the new value
          Settings settings = SETTINGS.get();
          if (settings == null || arg(args, 1) == null) {
            throw new PgException.Argument("22023", "set_config needs a session and a value");
          }
          settings.set(str(args, 0), str(args, 1), isTrue(arg(args, 2)));
          return settings.get(str(args, 0));
        }
      case "STATEMENT_TIMESTAMP":
        {
          Statement s = STATEMENT.get();
          return s == null ? Instant.now() : s.start;
        }
      case "CLOCK_TIMESTAMP":
        return Instant.now(); // the one clock that moves within a statement
      case "VERSION":
        return "PostgreSQL 16.0 (ScalarDB frontend)";
      default:
        break;
    }
    // The remaining functions are strict: any NULL argument yields NULL
    for (Object a : args) {
      if (a == null) {
        return null;
      }
    }
    switch (name) {
      case "UPPER":
        return str(args, 0).toUpperCase(Locale.ROOT);
      case "LOWER":
        return str(args, 0).toLowerCase(Locale.ROOT);
      case "GEN_RANDOM_UUID":
        return java.util.UUID.randomUUID().toString();
      case "CURRENT_TIMESTAMP":
      case "LOCALTIMESTAMP":
      case "CURRENT_TIME":
      case "LOCALTIME":
        return roundedTo(now(name), ((Number) args.get(0)).intValue()); // the precision form
      case "TO_JSON":
      case "TO_JSONB":
        return new Json.Value(Json.toNode(args.get(0)), name.equals("TO_JSONB"), null);
      case "JSON_TYPEOF":
      case "JSONB_TYPEOF":
        return Json.typeOf(Json.of(args.get(0)).node);
      case "JSON_ARRAY_LENGTH":
      case "JSONB_ARRAY_LENGTH":
        return Json.arrayLength(Json.of(args.get(0)).node);
      case "JSON_EXTRACT_PATH":
      case "JSONB_EXTRACT_PATH":
      case "JSON_EXTRACT_PATH_TEXT":
      case "JSONB_EXTRACT_PATH_TEXT":
        {
          Json.Value v = Json.of(args.get(0));
          com.fasterxml.jackson.databind.JsonNode node =
              Json.path(v.node, args.subList(1, args.size()));
          boolean binary = name.startsWith("JSONB");
          return node == null
              ? null
              : name.endsWith("_TEXT")
                  ? Json.scalarText(node, binary)
                  : new Json.Value(node, binary, null);
        }
      case "JSON_ARRAY_ELEMENTS":
      case "JSONB_ARRAY_ELEMENTS":
      case "JSON_ARRAY_ELEMENTS_TEXT":
      case "JSONB_ARRAY_ELEMENTS_TEXT":
        return Json.elements(
            Json.of(args.get(0)).node, name.startsWith("JSONB"), name.endsWith("_TEXT"));
      case "LENGTH":
      case "CHAR_LENGTH":
      case "CHARACTER_LENGTH":
      case "OCTET_LENGTH":
        {
          Object a = arg(args, 0);
          if (a instanceof ByteBuffer) {
            return (long) ((ByteBuffer) a).remaining();
          }
          if (a instanceof byte[]) {
            return (long) ((byte[]) a).length;
          }
          String s = str(args, 0);
          return (long)
              (name.equals("OCTET_LENGTH")
                  ? s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  : s.codePointCount(0, s.length()));
        }
      case "SUBSTRING":
      case "SUBSTR":
        {
          // PostgreSQL's rules, by characters (code points): the window [start, start + length)
          // clipped to the string, so substring('hello' from 0 for 3) is 'he' (todo 013)
          String s = str(args, 0);
          long n = s.codePointCount(0, s.length());
          long start = num(args, 1).longValue();
          long end = n + 1;
          if (args.size() > 2) {
            long length = num(args, 2).longValue();
            if (length < 0) {
              throw new PgException.Argument("22011", "negative substring length not allowed");
            }
            end = Math.min(end, start + length);
          }
          return characters(s, Math.max(start, 1) - 1, end - 1);
        }
      case "LEFT":
        {
          // left(s, -k) is all but the last k characters
          String s = str(args, 0);
          long n = s.codePointCount(0, s.length());
          long k = num(args, 1).longValue();
          return characters(s, 0, k >= 0 ? k : n + k);
        }
      case "RIGHT":
        {
          // right(s, -k) is all but the first k characters
          String s = str(args, 0);
          long n = s.codePointCount(0, s.length());
          long k = num(args, 1).longValue();
          return characters(s, k >= 0 ? n - k : -k, n);
        }
      case "REPLACE":
        return str(args, 0).replace(str(args, 1), str(args, 2));
      case "STARTS_WITH":
        return str(args, 0).startsWith(str(args, 1));
      case "POSITION":
      case "STRPOS":
        {
          String s = str(args, 0);
          int at = s.indexOf(str(args, 1));
          return at < 0 ? 0L : (long) s.codePointCount(0, at) + 1; // in characters, not UTF-16
        }
      case "TRIM":
      case "BTRIM":
        return trim(str(args, 0), args.size() > 1 ? str(args, 1) : " ", true, true);
      case "LTRIM":
        return trim(str(args, 0), args.size() > 1 ? str(args, 1) : " ", true, false);
      case "RTRIM":
        return trim(str(args, 0), args.size() > 1 ? str(args, 1) : " ", false, true);
      case "ABS":
        if (isIntegral(args.get(0)) && num(args, 0).longValue() < 0) {
          return negate(args.get(0)); // with negate's overflow checks
        }
        return isIntegral(args.get(0))
            ? (Object) Math.abs(num(args, 0).longValue())
            : args.get(0) instanceof BigDecimal
                ? (Object) ((BigDecimal) args.get(0)).abs()
                : (Object) Math.abs(num(args, 0).doubleValue());
      case "ROUND":
        {
          int scale = args.size() > 1 ? num(args, 1).intValue() : 0;
          if (!isFinite(args.get(0))) {
            return args.get(0);
          }
          BigDecimal rounded =
              new BigDecimal(num(args, 0).toString()).setScale(scale, RoundingMode.HALF_UP);
          return isIntegral(args.get(0))
              ? (Object) rounded.longValue()
              : args.get(0) instanceof BigDecimal
                  ? (Object) rounded
                  : (Object) rounded.doubleValue();
        }
      case "FLOOR":
        return isIntegral(args.get(0))
            ? args.get(0)
            : args.get(0) instanceof BigDecimal
                ? (Object) ((BigDecimal) args.get(0)).setScale(0, RoundingMode.FLOOR)
                : (Object) Math.floor(num(args, 0).doubleValue());
      case "CEIL":
      case "CEILING":
        return isIntegral(args.get(0))
            ? args.get(0)
            : args.get(0) instanceof BigDecimal
                ? (Object) ((BigDecimal) args.get(0)).setScale(0, RoundingMode.CEILING)
                : (Object) Math.ceil(num(args, 0).doubleValue());
      case "MOD":
        if (isIntegral(args.get(0)) && isIntegral(args.get(1))) {
          return num(args, 0).longValue() % num(args, 1).longValue();
        }
        if (num(args, 0) instanceof Double
            || num(args, 0) instanceof Float
            || num(args, 1) instanceof Double
            || num(args, 1) instanceof Float) {
          return num(args, 0).doubleValue() % num(args, 1).doubleValue();
        }
        return toDecimal(args.get(0)).remainder(toDecimal(args.get(1)));
      case "POWER":
      case "POW":
        {
          double base = num(args, 0).doubleValue();
          double exponent = num(args, 1).doubleValue();
          if (base == 0 && exponent < 0) {
            throw new PgException.Argument("2201F", "zero raised to a negative power is undefined");
          }
          if (base < 0 && exponent != Math.rint(exponent)) {
            throw new PgException.Argument(
                "2201F", "a negative number raised to a non-integer power yields a complex result");
          }
          return Math.pow(base, exponent);
        }
      case "SQRT":
        if (num(args, 0).doubleValue() < 0) {
          throw new PgException.Argument("2201F", "cannot take square root of a negative number");
        }
        return Math.sqrt(num(args, 0).doubleValue());
      case "SIGN":
        {
          double d = num(args, 0).doubleValue();
          return isIntegral(args.get(0))
              ? (Object) (long) Math.signum(d)
              : args.get(0) instanceof BigDecimal
                  ? (Object) BigDecimal.valueOf(((BigDecimal) args.get(0)).signum())
                  : (Object) Math.signum(d);
        }
      case "REVERSE":
        return new StringBuilder(str(args, 0)).reverse().toString();
      case "DATE_PART":
        return extract(str(args, 0), arg(args, 1));
      case "UNNEST":
        return arg(args, 0) == null
            ? Collections.emptyList()
            : new ArrayList<>(elements(args.get(0)));
      case "_PG_EXPANDARRAY":
        {
          // pgjdbc's key-column queries: one (x, n) record per element, read as (... ).x and .n
          List<Object> records = new ArrayList<>();
          for (Object x : arg(args, 0) == null ? Collections.emptyList() : elements(args.get(0))) {
            Record r = new Record();
            r.put("x", x);
            r.put("n", (long) records.size() + 1);
            records.add(r);
          }
          return records;
        }
      case "GENERATE_SUBSCRIPTS":
        {
          List<Object> subscripts = new ArrayList<>();
          int count = arg(args, 0) == null ? 0 : elements(args.get(0)).size();
          for (long i = 1; i <= count; i++) {
            subscripts.add(i);
          }
          return subscripts;
        }
      case "CARDINALITY":
        return arg(args, 0) == null ? null : (Object) (long) elements(args.get(0)).size();
      case "AGE":
        {
          // age(a, b) is the calendar difference; age(a) counts from today's midnight
          Object a = arg(args, 0);
          Object b = args.size() > 1 ? arg(args, 1) : null;
          if (a == null || (args.size() > 1 && b == null)) {
            return null;
          }
          // with two arguments b is set (a NULL one returned above)
          LocalDateTime to =
              b != null
                  ? asTimestamp(a)
                  : LocalDate.ofInstant(statementNow(), zone()).atStartOfDay();
          LocalDateTime from = b != null ? asTimestamp(b) : asTimestamp(a);
          if (to == null || from == null) {
            throw new IllegalArgumentException("age needs timestamps: " + f);
          }
          return Interval.age(from, to);
        }
      case "DATE_TRUNC":
        return dateTrunc(str(args, 0), arg(args, 1));
      default:
        throw new PgException.Argument("0A000", "Unsupported function: " + f);
    }
  }

  private static final Object NOT_A_CATALOG_FUNCTION = new Object();

  /** A composite value, such as a row of _pg_expandarray(); its text is PostgreSQL's (a,b). */
  static final class Record extends LinkedHashMap<String, Object> {
    @Override
    public String toString() {
      StringJoiner fields = new StringJoiner(",", "(", ")");
      for (Object v : values()) {
        fields.add(v == null ? "" : text(v));
      }
      return fields.toString();
    }
  }

  @Nullable private static Object field(@Nullable Object record, String field, Expression e) {
    if (record == null) {
      return null;
    }
    if (!(record instanceof Record) || !((Record) record).containsKey(field)) {
      throw new PgException.Argument("42703", "could not identify column \"" + field + "\": " + e);
    }
    return ((Record) record).get(field);
  }

  /** The pg_catalog functions psql uses when describing objects. */
  @Nullable private static Object catalogFunction(String name, List<Object> args, Context ctx) {
    try {
      switch (name) {
        case "PG_GET_USERBYID":
          return Catalog.OWNER;
        case "PG_ENCODING_TO_CHAR":
          return "UTF8";
        case "PG_TABLE_IS_VISIBLE":
        case "PG_RELATION_IS_PUBLISHABLE":
          return true;
        case "ARRAY_LENGTH":
        case "ARRAY_UPPER":
          {
            // null for an empty array, as PostgreSQL has no dimension to report
            Object a = arg(args, 0);
            List<?> items = a == null ? Collections.emptyList() : elements(a);
            return items.isEmpty() ? null : (Object) (long) items.size();
          }
        case "ARRAY_TO_STRING":
          if (!(arg(args, 0) instanceof List)) {
            return null;
          }
          StringBuilder sb = new StringBuilder();
          for (Object v : (List<?>) args.get(0)) {
            sb.append(sb.length() == 0 ? "" : str(args, 1)).append(text(v));
          }
          return sb.toString();
        case "FORMAT_TYPE":
          // format_type(oid, typmod): numeric(10,2), character varying(20), character(3), ...
          return arg(args, 0) instanceof Number
              ? Catalog.typeName(
                  num(args, 0).longValue(),
                  args.size() > 1 && arg(args, 1) instanceof Number
                      ? Long.valueOf(num(args, 1).longValue())
                      : null)
              : null;
        case "TO_REGTYPE":
          try {
            return arg(args, 0) == null ? null : (Object) Catalog.typeOid(str(args, 0));
          } catch (IllegalArgumentException unknownType) {
            return null;
          }
        case "TO_REGCLASS":
          try {
            return arg(args, 0) == null || ctx.catalog == null
                ? null
                : (Object) ctx.catalog.relationOid(str(args, 0));
          } catch (IllegalArgumentException unknownRelation) {
            return null;
          }
        case "CURRENT_SCHEMAS":
          {
            if (ctx.catalog == null) {
              return null;
            }
            List<Object> schemas = new ArrayList<>();
            if (isTrue(arg(args, 0))) {
              schemas.add("pg_catalog");
            }
            schemas.add("public"); // the connected namespace shows as public
            return schemas;
          }
        case "PG_GET_INDEXDEF":
          if (ctx.catalog == null || !(arg(args, 0) instanceof Number)) {
            return null;
          }
          return args.size() > 1 && arg(args, 1) instanceof Number && num(args, 1).longValue() > 0
              ? ctx.catalog.indexColumn(num(args, 0).longValue(), num(args, 1).longValue())
              : ctx.catalog.indexDef(num(args, 0).longValue());
        case "PG_GET_CONSTRAINTDEF":
          return ctx.catalog == null || !(arg(args, 0) instanceof Number)
              ? null
              : ctx.catalog.constraintDef(num(args, 0).longValue());
        case "OBJ_DESCRIPTION": // a table's: its partition and clustering keys
          return ctx.catalog == null
                  || !(arg(args, 0) instanceof Number)
                  || (args.size() > 1 && !"pg_class".equals(arg(args, 1)))
              ? null
              : ctx.catalog.description(num(args, 0).longValue());
        case "PG_SIZE_PRETTY":
          if (arg(args, 0) != null) {
            return NOT_A_CATALOG_FUNCTION; // only the unknown sizes below reach it, from psql's
            // \dt+
          }
          return null;
        case "PG_TABLE_SIZE": // ScalarDB does not tell how much storage a table takes
        case "PG_RELATION_SIZE":
        case "PG_TOTAL_RELATION_SIZE":
        case "PG_INDEXES_SIZE":
        case "PG_GET_EXPR":
        case "PG_PARTITION_ANCESTORS":
        case "COL_DESCRIPTION":
        case "SHOBJ_DESCRIPTION":
        case "PG_DATABASE_SIZE":
        case "PG_GET_PARTKEYDEF": // ScalarDB tables are not partitioned in PostgreSQL's sense
          return null;
        case "PG_STAT_GET_NUMSCANS": // DBeaver's index list; ScalarDB keeps no statistics
          return 0L;
        case "HAS_DATABASE_PRIVILEGE": // connecting already proved it; Cluster has no such grant
        case "PG_TYPE_IS_VISIBLE":
        case "PG_OPCLASS_IS_VISIBLE":
        case "PG_OPFAMILY_IS_VISIBLE":
        case "PG_COLLATION_IS_VISIBLE":
          return true;
        case "PG_CHAR_TO_ENCODING":
          return "UTF8".equalsIgnoreCase(String.valueOf(arg(args, 0))) ? 6L : -1L;
        case "GETDATABASEENCODING":
          return "UTF8";
        case "PG_TABLESPACE_LOCATION": // the built-in tablespaces have none
          return "";
        case "CURRENT_SCHEMA":
          return ctx.catalog == null ? null : "public"; // the connected namespace shows as public
        case "CURRENT_DATABASE":
          return ctx.catalog == null ? null : ctx.catalog.database;
        case "PG_BACKEND_PID":
          return 0L;
        default:
          return NOT_A_CATALOG_FUNCTION;
      }
    } catch (com.scalar.db.exception.storage.ExecutionException e) {
      throw new IllegalStateException("Catalog lookup failed: " + e.getMessage(), e);
    }
  }

  @Nullable private static Object arg(List<Object> args, int i) {
    return i < args.size() ? args.get(i) : null;
  }

  private static String str(List<Object> args, int i) {
    if (i >= args.size()) {
      throw new IllegalArgumentException("Missing function argument " + (i + 1));
    }
    return text(args.get(i));
  }

  private static Number num(List<Object> args, int i) {
    if (i >= args.size() || !(args.get(i) instanceof Number)) {
      throw new PgException.Argument(
          "42883", "function argument " + (i + 1) + " is not a number"); // undefined_function
    }
    return (Number) args.get(i);
  }

  @Nullable private static Object aggregate(Function f, List<Map<String, Object>> group, Context ctx) {
    String name = f.getName().toUpperCase(Locale.ROOT);
    ExpressionList<?> params = f.getParameters();
    boolean star =
        f.isAllColumns()
            || (params != null && params.size() == 1 && params.get(0) instanceof AllColumns);
    int arity = name.equals("STRING_AGG") || name.endsWith("OBJECT_AGG") ? 2 : 1;
    if (!star && (params == null || params.size() != arity)) {
      throw new IllegalArgumentException("Expected " + arity + " argument(s): " + f);
    }
    if (f.getOrderByElements() != null) {
      // string_agg(x, ',' ORDER BY y): aggregate the rows in that order
      List<OrderByElement> orderBy = f.getOrderByElements();
      group = new ArrayList<>(group);
      group.sort(
          (x, y) -> {
            for (OrderByElement o : orderBy) {
              int c =
                  orderCompare(
                      o,
                      eval(o.getExpression(), Collections.singletonList(x), ctx),
                      eval(o.getExpression(), Collections.singletonList(y), ctx));
              if (c != 0) {
                return c;
              }
            }
            return 0;
          });
    }
    if (name.endsWith("OBJECT_AGG")) {
      // json_object_agg(key, value): one member per row, keys as text
      if (group.isEmpty()) {
        return null;
      }
      List<Object> pairs = new ArrayList<>();
      for (Map<String, Object> row : group) {
        Object key = eval(params.get(0), Collections.singletonList(row), ctx);
        if (key == null) {
          throw new IllegalArgumentException("field name must not be null");
        }
        pairs.add(key);
        pairs.add(eval(params.get(1), Collections.singletonList(row), ctx));
      }
      return new Json.Value(Json.buildObject(pairs), name.startsWith("JSONB"), null);
    }
    List<Object> values = new ArrayList<>();
    for (Map<String, Object> row : group) {
      Object v = star ? row : eval(params.get(0), Collections.singletonList(row), ctx);
      if (v != null
          || name.equals("ARRAY_AGG")
          || name.endsWith("JSON_AGG")
          || name.endsWith("JSONB_AGG")) {
        values.add(v); // array_agg and json_agg keep NULL elements
      }
    }
    if (f.isDistinct()) {
      Map<Object, Object> unique = new LinkedHashMap<>(); // INT 1000 and BIGINT 1000 are one value
      for (Object v : values) {
        unique.putIfAbsent(Operators.normalize(v), v);
      }
      values = new ArrayList<>(unique.values());
    }
    Object delimiter =
        name.equals("STRING_AGG") && !group.isEmpty()
            ? eval(params.get(1), group.subList(0, 1), ctx)
            : null;
    return aggregateValues(name, values, delimiter, f);
  }

  /** Applies an aggregate to the non-null values collected over its rows or window frame. */
  @Nullable static Object aggregateValues(
      String name, List<Object> values, @Nullable Object delimiter, Expression f) {
    switch (name) {
      case "COUNT":
        return (long) values.size();
      case "MIN":
      case "MAX":
        Object best = null;
        for (Object v : values) {
          if (best == null || (compare(v, best) < 0) == name.equals("MIN")) {
            best = v;
          }
        }
        return best;
      case "SUM":
      case "AVG":
        if (values.isEmpty()) {
          return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        double nonFinite = 0; // NaN and Infinity propagate as in floating-point addition
        boolean integral = true;
        boolean floating = false; // a double or float input makes the result a double
        boolean negativeZero = true; // floating-point addition gives -0 only from -0 inputs
        for (Object v : values) {
          if (!(v instanceof Number)) {
            throw new IllegalArgumentException(name + " requires numeric values: " + f);
          }
          if (isFinite(v)) {
            sum = sum.add(new BigDecimal(v.toString()));
          } else {
            nonFinite += ((Number) v).doubleValue();
          }
          integral &= isIntegral(v);
          floating |= v instanceof Double || v instanceof Float;
          double d = ((Number) v).doubleValue();
          negativeZero &= d == 0 && 1 / d < 0;
        }
        if (nonFinite != 0 || Double.isNaN(nonFinite)) {
          return nonFinite;
        }
        if (name.equals("AVG")) {
          // the average of integers or numerics is numeric, with PostgreSQL's division scale
          return floating
              ? (Object) (finiteSum(sum) / values.size()) // PostgreSQL accumulates from +0
              : divide(sum, BigDecimal.valueOf(values.size()));
        }
        if (floating) {
          return negativeZero ? -0.0 : finiteSum(sum); // sum starts from the first value
        }
        if (integral) {
          try {
            return sum.longValueExact();
          } catch (ArithmeticException beyondBigint) {
            return sum;
          }
        }
        return sum;
      case "BOOL_AND":
      case "EVERY":
      case "BOOL_OR":
        {
          if (values.isEmpty()) {
            return null;
          }
          boolean and = !name.equals("BOOL_OR");
          for (Object v : values) {
            if (!(v instanceof Boolean)) {
              throw new IllegalArgumentException(name + " requires boolean values: " + f);
            }
            if ((Boolean) v != and) {
              return !and;
            }
          }
          return and;
        }
      case "ARRAY_AGG":
        return values.isEmpty() ? null : new ArrayList<>(values);
      case "JSON_AGG":
      case "JSONB_AGG":
        return values.isEmpty()
            ? null
            : new Json.Value(Json.buildArray(values), name.equals("JSONB_AGG"), null);
      case "STRING_AGG":
        {
          if (values.isEmpty()) {
            return null;
          }
          StringBuilder joined = new StringBuilder();
          for (int i = 0; i < values.size(); i++) {
            if (i > 0 && delimiter != null) {
              joined.append(text(delimiter));
            }
            joined.append(text(values.get(i)));
          }
          return joined.toString();
        }
      default:
        throw new AssertionError();
    }
  }

  /** Translates a SQL LIKE pattern into a regex; {@code %} is any string and {@code _} any char. */
  static Pattern likePattern(String pattern, String escape) {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (!escape.isEmpty() && c == escape.charAt(0) && i + 1 < pattern.length()) {
        i++;
        regex.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
      } else if (c == '%') {
        regex.append(".*");
      } else if (c == '_') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(regex.toString(), Pattern.DOTALL);
  }
}
