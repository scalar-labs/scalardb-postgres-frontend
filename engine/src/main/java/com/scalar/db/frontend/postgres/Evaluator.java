package com.scalar.db.frontend.postgres;

import com.scalar.db.common.CollationComparator;
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
import java.util.HashSet;
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
  static final List<String> AGGREGATES =
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
          "ARRAY_AGG",
          "STDDEV",
          "STDDEV_SAMP",
          "STDDEV_POP",
          "VARIANCE",
          "VAR_SAMP",
          "VAR_POP");

  // ScalarDB's text order (scalar.db.collation), so text compared here agrees with what is pushed
  // down
  private static volatile CollationComparator collation = CollationComparator.binary();
  // its name in pg_collation under ICU, as PostgreSQL names ICU collations: und-x-icu for root
  private static volatile String icuCollationName = "";

  private Evaluator() {}

  /**
   * Sets ScalarDB's collation.
   *
   * @param c the comparator
   * @param icuLocale the ICU locale, or null under BINARY
   */
  static void setCollation(CollationComparator c, @Nullable String icuLocale) {
    collation = c;
    icuCollationName = icuLocale == null ? "" : icuLocale + "-x-icu";
  }

  /** The ICU collation's name in pg_collation, or empty under BINARY. */
  static String icuCollationName() {
    return icuCollationName;
  }

  /**
   * upper() or lower() as PostgreSQL maps case: under the C collation the frontend reports with
   * BINARY, only ASCII letters change; under ICU, every letter, by Unicode's rules.
   */
  private static String caseMapped(String s, boolean upper) {
    if (!icuCollationName.isEmpty()) {
      return upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT);
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      sb.append(
          upper && c >= 'a' && c <= 'z'
              ? (char) (c - 32)
              : !upper && c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
    }
    return sb.toString();
  }

  static CollationComparator collation() {
    return collation;
  }

  /**
   * Refuses pattern matching on table text under ICU, whose equality is many-to-one, as PostgreSQL
   * before 18 refuses it on a nondeterministic collation.
   */
  private static void checkDeterministic(String what, Context ctx, List<?> operands) {
    if (!collation.hasCanonicalTextForm()) {
      return;
    }
    for (Object o : operands) {
      if (QueryParser.collatedText((Expression) o, ctx.sources)) {
        throw new PgException.Argument(
            "0A000", "nondeterministic collations are not supported for " + what);
      }
    }
  }

  /** The outer row of a correlated subquery and the plans of the subqueries in the expression. */
  static final class Context {
    static final Context EMPTY =
        new Context(null, Collections.emptyMap(), null, Collections.emptyList());

    @Nullable final Map<String, Object> outer;
    final Map<Select, Plan> subplans;
    @Nullable final Catalog catalog;
    final List<Object> parameters; // the statement's $n values, 1-based
    final Map<String, com.scalar.db.io.DataType> types; // alias.column -> type, for NULL values
    final List<LogicalPlan.Source> sources; // the plan's FROM items, for plan-time types
    final Map<Expression, Integer> widenings; // CASE, COALESCE, ... -> what it widens to
    // a comparison's right operand -> whether it compares as bpchar (see Evaluator.bpchar)
    final Map<Expression, Boolean> bpchars = new java.util.IdentityHashMap<>();
    // whether an arithmetic expression is smallint op smallint, decided once from plan-time types
    final Map<Expression, Boolean> smallints = new java.util.IdentityHashMap<>();

    Context(
        @Nullable Map<String, Object> outer,
        Map<Select, Plan> subplans,
        @Nullable Catalog catalog,
        List<Object> parameters) {
      this(
          outer,
          subplans,
          catalog,
          parameters,
          Collections.emptyMap(),
          Collections.emptyList(),
          new java.util.IdentityHashMap<>());
    }

    Context(
        @Nullable Map<String, Object> outer,
        Map<Select, Plan> subplans,
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
      Plan plan = subplans.get(select);
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
      Plan plan = subplans.get(select);
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
    StringBuilder sb = pad(new StringBuilder(total < 0 ? "-" : "+"), abs / 3600, 2);
    return ((abs % 3600) / 60 == 0 ? sb : pad(sb.append(':'), (abs % 3600) / 60, 2)).toString();
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

  // rotated ^ expressions by their parsed node; only rotated ones, whose value never refers to the
  // key, so the weak keys can go once the statement cache drops the statement
  private static final Map<BitwiseXor, Expression> CARETS =
      Collections.synchronizedMap(new java.util.WeakHashMap<>());

  /**
   * {@code a * b ^ c} as PostgreSQL reads it, {@code a * (b ^ c)}: JSQLParser gives {@code ^} the
   * precedence of {@code * / %}, left to right, where PostgreSQL binds it tighter (and left to
   * right among {@code ^}). A {@code ^} whose left operand is an unparenthesised {@code * / %}
   * takes only that operation's right operand.
   */
  static Expression caret(BitwiseXor e) {
    Expression cached = CARETS.get(e);
    if (cached != null) {
      return cached;
    }
    Expression left = e.getLeftExpression();
    if (left instanceof BitwiseXor) {
      left = caret((BitwiseXor) left); // ((a * b) ^ c) ^ d: the inner one first
    }
    Expression rotated;
    if (left instanceof Multiplication || left instanceof Division || left instanceof Modulo) {
      BinaryExpression m = (BinaryExpression) left;
      BitwiseXor inner = new BitwiseXor();
      inner.setLeftExpression(m.getRightExpression());
      inner.setRightExpression(e.getRightExpression());
      BinaryExpression out =
          left instanceof Multiplication
              ? new Multiplication()
              : left instanceof Division ? new Division() : new Modulo();
      out.setLeftExpression(m.getLeftExpression());
      out.setRightExpression(caret(inner));
      rotated = out;
    } else if (left != e.getLeftExpression()) {
      BitwiseXor out = new BitwiseXor();
      out.setLeftExpression(left);
      out.setRightExpression(e.getRightExpression());
      rotated = out;
    } else {
      return e; // nothing to rotate: not cached, as the entry's value would hold its weak key
    }
    CARETS.put(e, rotated);
    return rotated;
  }

  /**
   * A local timestamp in a zone, as PostgreSQL resolves it: a time that occurs twice (when DST
   * ends) takes the later, standard-time offset; one that does not occur (when DST starts) is moved
   * forward by the gap.
   */
  static Instant instant(LocalDateTime local, java.time.ZoneId zone) {
    return java.time.ZonedDateTime.ofLocal(local, zone, null)
        .withLaterOffsetAtOverlap()
        .toInstant();
  }

  /**
   * time input as PostgreSQL reads it: rounded half to even to microseconds. 24:00:00, given or
   * rounded to (23:59:59.9999999), is {@link LocalTime#MAX}, which prints as 24:00:00 and sorts
   * last.
   */
  static LocalTime time(String s) {
    Object special = specialInput(s, "time");
    if (special != null) {
      return (LocalTime) special;
    }
    String t = s.trim();
    String rest = t.startsWith("24:00") ? t.substring(5) : null;
    if (rest != null
        && (rest.isEmpty()
            || rest.equals(":00")
            || rest.startsWith(":00.") && rest.substring(4).chars().allMatch(c -> c == '0'))) {
      return LocalTime.MAX;
    }
    long nanos = LocalTime.parse(t).toNanoOfDay();
    long micros = nanos / 1000;
    long remainder = nanos % 1000;
    if (remainder > 500 || remainder == 500 && micros % 2 == 1) {
      micros++; // rint, as PostgreSQL rounds the fraction
    }
    return micros >= 86_400_000_000L ? LocalTime.MAX : LocalTime.ofNanoOfDay(micros * 1000);
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

  /** A quoted literal beside an array is PostgreSQL's array text; any other value stays as is. */
  @Nullable private static Object arrayLiteral(Expression e, @Nullable Object value) {
    if (value == null || value instanceof List || !(e instanceof StringValue)) {
      return value;
    }
    try {
      return QueryParser.parseArrayText(String.valueOf(value));
    } catch (IllegalArgumentException malformed) {
      throw new PgException.Argument("22P02", "malformed array literal: \"" + value + "\"");
    }
  }

  /** How deeply arrays nest in {@code v}: 0 for an element, 1 for an array of elements. */
  private static int depth(Object v) {
    int depth = 0;
    while (v instanceof List) {
      depth++;
      Object first = null;
      for (Object element : (List<?>) v) {
        if (element != null) {
          first = element;
          break;
        }
      }
      v = first;
    }
    return depth;
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
  // what PostgreSQL refuses on a nondeterministic collation as "substring searches"
  private static final Set<String> SUBSTRING_SEARCHES =
      new java.util.HashSet<>(Arrays.asList("REPLACE", "STARTS_WITH", "POSITION", "STRPOS"));

  static final Set<String> TEXT_FUNCTIONS =
      new java.util.HashSet<>(
          Arrays.asList(
              "LENGTH",
              "CHAR_LENGTH",
              "CHARACTER_LENGTH",
              "OCTET_LENGTH",
              "UPPER",
              "LOWER",
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
              "CONCAT",
              "CONCAT_WS",
              "STARTS_WITH",
              "QUOTE_LITERAL",
              "QUOTE_IDENT",
              "SPLIT_PART",
              "STRING_TO_ARRAY",
              "REPEAT",
              "OVERLAY"));

  /** quote_ident: quoted unless a lower-case name that is not a reserved keyword. */
  static String quoteIdent(String name) {
    if (name.matches("[a-z_][a-z0-9_]*") && !RESERVED.contains(name)) {
      return name;
    }
    return '"' + name.replace("\"", "\"\"") + '"';
  }

  private static final Set<String> RESERVED = new HashSet<>();

  static {
    for (Map<String, Object> keyword : Catalog.keywords()) {
      if (!"U".equals(keyword.get("catcode"))) {
        RESERVED.add((String) keyword.get("word"));
      }
    }
  }

  /** quote_literal: an E'' string when it holds a backslash, as PostgreSQL writes it. */
  static String quoteLiteral(String value) {
    String quoted = "'" + value.replace("'", "''").replace("\\", "\\\\") + "'";
    return value.contains("\\") ? "E" + quoted : quoted;
  }

  /** format(fmt, args...): %s, %I, %L and %% in order; widths and n$ positions are not taken. */
  private static String format(String fmt, List<Object> args) {
    StringBuilder out = new StringBuilder();
    int next = 1;
    for (int i = 0; i < fmt.length(); i++) {
      char c = fmt.charAt(i);
      if (c != '%') {
        out.append(c);
        continue;
      }
      char spec = ++i < fmt.length() ? fmt.charAt(i) : 0;
      if (spec == '%') {
        out.append('%');
        continue;
      }
      if (spec != 's' && spec != 'I' && spec != 'L') {
        throw new PgException.Argument(
            spec == 0 ? "22023" : "0A000",
            spec == 0
                ? "unterminated format() type specifier"
                : "format() specifier %" + spec + " is not supported");
      }
      if (next >= args.size()) {
        throw new PgException.Argument("22023", "too few arguments for format()");
      }
      Object value = args.get(next++);
      if (spec == 's') {
        out.append(value == null ? "" : outputText(value));
      } else if (spec == 'L') {
        out.append(value == null ? "NULL" : quoteLiteral(outputText(value)));
      } else if (value == null) {
        throw new PgException.Argument(
            "22004", "null values cannot be formatted as an SQL identifier");
      } else {
        out.append(quoteIdent(outputText(value)));
      }
    }
    return out.toString();
  }

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

  /**
   * True when PostgreSQL compares {@code a} with {@code b} as bpchar, where trailing spaces do not
   * count: one is a char(n) value and the other a char, a varchar, a quoted literal or a parameter.
   * Against text, char loses its padding and the text keeps its own ('ab'::char(5) = 'ab '::text is
   * false). Decided once per operand pair from plan-time types.
   */
  // ponytail: a parameter is taken as untyped or varchar (pgjdbc's default), so one the client
  // declared text compares as bpchar here; and ANY(ARRAY['ab ']), rewritten to IN, compares as
  // bpchar where PostgreSQL's text[] does not. Parameter types in the Context would close both
  private static boolean bpchar(Expression a, Expression b, Context ctx) {
    return ctx.bpchars.computeIfAbsent(
        b,
        k ->
            (isBpchar(a, ctx) && comparesAsBpchar(b, ctx))
                || (isBpchar(b, ctx) && comparesAsBpchar(a, ctx)));
  }

  private static boolean isBpchar(Expression e, Context ctx) {
    return QueryParser.inferOid(e, ctx.sources) == 1042;
  }

  private static boolean comparesAsBpchar(Expression e, Context ctx) {
    Expression u = QueryParser.unwrap(e);
    if (u instanceof StringValue || u instanceof JdbcParameter) {
      return true;
    }
    if (u instanceof CastExpression) {
      // inferOid types every character cast as text
      String type = ((CastExpression) u).getColDataType().getDataType().toLowerCase(Locale.ROOT);
      return BPCHAR_PEER_CAST.matcher(type).lookingAt();
    }
    int oid = QueryParser.inferOid(u, ctx.sources);
    return oid == 1042 || oid == 1043;
  }

  /** A text value without its trailing spaces; anything else unchanged. */
  private static Object rtrim(Object v) {
    return v instanceof String ? TRAILING_SPACES.matcher((String) v).replaceFirst("") : v;
  }

  @Nullable private static Object rtrimNullable(@Nullable Object v) {
    return v == null ? null : rtrim(v);
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
      this.start = clock();
      this.deadline = timeoutMillis > 0 ? (System.nanoTime() + timeoutMillis * 1_000_000L) | 1 : 0;
      this.cancel = cancel;
    }
  }

  // The statement running on this thread; null outside one (a test, a pool thread), where nothing
  // is interrupted and each regex match gets the whole budget
  private static final ThreadLocal<Statement> STATEMENT = new ThreadLocal<>();

  // The session served on this thread, for its cancel flag and pg_backend_pid
  private static final ThreadLocal<Catalog.Activity> SESSION = new ThreadLocal<>();

  /** The session's settings, for current_setting and set_config. */
  interface Settings {
    /** The setting's current value; null for a name the session does not know. */
    @Nullable String get(String name);

    /** As SET [LOCAL] name = value; the value as set, which a SET LOCAL outside a block is not. */
    String set(String name, String value, boolean local);

    /** pg_settings.source: session after SET, client from the startup message, or default. */
    String source(String name);

    /** pg_settings.reset_val: what RESET returns the setting to. */
    String resetValue(String name);
  }

  private static final ThreadLocal<Settings> SETTINGS = new ThreadLocal<>();

  /** The settings of the session whose statement runs on this thread. */
  static void setSettings(Settings settings) {
    SETTINGS.set(settings);
  }

  /** The settings of the session on this thread; null outside one. */
  @Nullable static Settings settings() {
    return SETTINGS.get();
  }

  /**
   * The SQL clock (now(), clock_timestamp(), ...), at millisecond resolution: ScalarDB keeps
   * timestamps to the millisecond, so a reading with microseconds could not be stored as it is.
   */
  static Instant clock() {
    return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  }

  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

  /** The session's IntervalStyle, which interval output and sql_standard input follow. */
  static String intervalStyle() {
    Settings settings = settings();
    String style = settings == null ? null : settings.get("IntervalStyle");
    return style == null ? "postgres" : style;
  }

  /** Gives this thread the session it serves, once, at startup. */
  static void setSession(Catalog.Activity session) {
    SESSION.set(session);
  }

  /**
   * Starts a statement on this thread: {@code now} is what now() returns throughout, a positive
   * {@code timeoutMillis} is its statement_timeout, and the session's cancel flag, cleared here,
   * stops it. The regex budget covers all the statement's matches over however many rows.
   */
  static void startStatement(Instant now, long timeoutMillis) {
    Catalog.Activity session = SESSION.get();
    java.util.concurrent.atomic.AtomicBoolean cancel = session == null ? null : session.cancel;
    if (cancel != null) {
      cancel.set(false); // a cancel sent between statements is for none of them
    }
    STATEMENT.set(new Statement(now, timeoutMillis, cancel));
  }

  static void startStatement() {
    startStatement(clock(), 0);
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

  /**
   * PostgreSQL's special datetime input, alone in the text: 'epoch', 'now', 'today', 'tomorrow',
   * 'yesterday', and 'allballs' for a time; as a {@code type} (timestamptz, timestamp, date or
   * time) value, or null for other text. 'now' is now(), and the days are in the session's zone.
   */
  @Nullable static Object specialInput(String s, String type) {
    String word = s.trim().toLowerCase(Locale.ROOT);
    Instant now = statementNow();
    LocalDate today = LocalDate.ofInstant(now, zone());
    switch (word) {
      case "epoch":
        return type.equals("timestamptz")
            ? Instant.EPOCH
            : type.equals("timestamp")
                ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : type.equals("date") ? LocalDate.of(1970, 1, 1) : null;
      case "now":
        return type.equals("timestamptz")
            ? now
            : type.equals("timestamp")
                ? LocalDateTime.ofInstant(now, zone())
                : type.equals("date") ? today : LocalTime.ofInstant(now, zone());
      case "today":
      case "tomorrow":
      case "yesterday":
        LocalDate day = today.plusDays(word.equals("today") ? 0 : word.equals("tomorrow") ? 1 : -1);
        return type.equals("timestamptz")
            ? instant(day.atStartOfDay(), zone())
            : type.equals("timestamp") ? day.atStartOfDay() : type.equals("date") ? day : null;
      case "allballs":
        return type.equals("time") ? LocalTime.MIDNIGHT : null;
      default:
        return null;
    }
  }

  /** now(): the statement's (in a transaction, the transaction's) start time. */
  private static Instant statementNow() {
    Statement s = STATEMENT.get();
    return s == null ? clock() : s.now;
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
    return Boolean.TRUE.equals(condition(value));
  }

  /**
   * A value used as a condition (WHERE, AND, OR, NOT, CASE WHEN, ...): a boolean or NULL, as
   * PostgreSQL requires. Anything else, text included, fails with 42804 rather than being given a
   * truth value of its own.
   */
  @Nullable static Boolean condition(@Nullable Object v) {
    if (v == null || v instanceof Boolean) {
      return (Boolean) v;
    }
    throw new PgException.Argument(
        "42804", "argument must be type boolean, not type " + pgTypeName(v));
  }

  // PostgreSQL's aggregates (pg_proc.prokind 'a'): one not implemented here must not run as a plain
  // function, which over no rows would answer no row instead of one
  private static final java.util.Set<String> PG_AGGREGATES =
      new java.util.HashSet<>(
          Arrays.asList(
              ("ANY_VALUE ARRAY_AGG AVG BIT_AND BIT_OR BIT_XOR BOOL_AND BOOL_OR CORR COUNT COVAR_POP"
                      + " COVAR_SAMP CUME_DIST DENSE_RANK EVERY JSON_AGG JSON_AGG_STRICT JSON_OBJECT_AGG"
                      + " JSON_OBJECT_AGG_STRICT JSON_OBJECT_AGG_UNIQUE JSON_OBJECT_AGG_UNIQUE_STRICT"
                      + " JSONB_AGG JSONB_AGG_STRICT JSONB_OBJECT_AGG JSONB_OBJECT_AGG_STRICT"
                      + " JSONB_OBJECT_AGG_UNIQUE JSONB_OBJECT_AGG_UNIQUE_STRICT MAX MIN MODE PERCENT_RANK"
                      + " PERCENTILE_CONT PERCENTILE_DISC RANGE_AGG RANGE_INTERSECT_AGG RANK REGR_AVGX"
                      + " REGR_AVGY REGR_COUNT REGR_INTERCEPT REGR_R2 REGR_SLOPE REGR_SXX REGR_SXY REGR_SYY"
                      + " STDDEV STDDEV_POP STDDEV_SAMP STRING_AGG SUM VAR_POP VAR_SAMP VARIANCE XMLAGG")
                  .split(" ")));

  static boolean isAggregate(Function f) {
    String name = functionName(f);
    if (AGGREGATES.contains(name)) {
      return true;
    }
    if (PG_AGGREGATES.contains(name)) {
      throw new PgException.Argument("0A000", "Unsupported aggregate function: " + f.getName());
    }
    return false;
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

  /** A grouping set's rows as read, before the set's NULLs: what an aggregate's arguments see. */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> originals(List<Map<String, Object>> group) {
    if (group.isEmpty() || !group.get(0).containsKey(Operators.ORIGINAL_KEY)) {
      return group;
    }
    List<Map<String, Object>> out = new ArrayList<>(group.size());
    for (Map<String, Object> row : group) {
      out.add((Map<String, Object>) row.get(Operators.ORIGINAL_KEY));
    }
    return out;
  }

  /** The upper-cased function name without a {@code pg_catalog.} schema prefix. */
  static String functionName(Function f) {
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
    String collation = collationName(name);
    if (!icuCollationName.isEmpty()) {
      // under ICU, byte order would sort and compare differently from the stored text
      if (!collation.equals("default") && !collation.equalsIgnoreCase(icuCollationName)) {
        throw new PgException.Argument(
            "0A000",
            "collation \""
                + name.replace("\"", "")
                + "\" is not supported: only \""
                + icuCollationName
                + "\" is available");
      }
      return;
    }
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

  /**
   * A column has the one collation ScalarDB is configured with, so a column definition can name
   * only that, as {@code "default"}.
   */
  static void checkColumnCollation(String name) {
    if (!collationName(name).equals("default")) {
      throw new PgException.Argument(
          "0A000",
          "collation \""
              + name.replace("\"", "")
              + "\" is not supported for a column: every column uses the database's collation");
    }
  }

  /** The collation's name without quotes or schema, lower-cased. */
  private static String collationName(String name) {
    String collation = name.replace("\"", "").toLowerCase(Locale.ROOT);
    return collation.substring(collation.lastIndexOf('.') + 1);
  }

  @Nullable static Object eval(Expression e, List<Map<String, Object>> group, Context ctx) {
    e = QueryParser.unwrap(e);
    if (!group.isEmpty() && !(e instanceof Column)) {
      // a grouping item this grouping set leaves out is NULL in its rows, as q1 + 0 in the total
      // row of ROLLUP(q1 + 0); a column is blanked in the rows themselves
      Object outside = group.get(0).get(Operators.GROUPING_KEY);
      if (outside instanceof Set && ((Set<?>) outside).contains(e.toString())) {
        return null;
      }
    }
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
      return rectangular(
          out, "multidimensional arrays must have array expressions with matching dimensions");
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
      net.sf.jsqlparser.expression.IntervalQualifier q = iv.getIntervalQualifier();
      String unit = q != null ? q.getLeadingField() : iv.getIntervalType();
      if (unit != null
          && (!INTEGER_TEXT.matcher(p.trim()).matches()
              || (q != null
                  && (q.getTrailingField() != null
                      || q.getLeadingFieldPrecision() != null
                      || q.getFractionalSecondsPrecision() != null)))) {
        // ponytail: a qualifier truncates the fields below it and gives a bare number to its last
        // field ('1' YEAR TO MONTH is 1 mon); only a whole number with one field is read so far
        throw new PgException.Argument("0A000", "Unsupported interval qualifier: " + e);
      }
      return Interval.parse(unit == null ? p : p + " " + unit);
    }
    if (e instanceof TimezoneExpression) {
      TimezoneExpression tz = (TimezoneExpression) e;
      return atTimeZone(
          eval(tz.getLeftExpression(), group, ctx),
          eval(tz.getTimezoneExpressions().get(0), group, ctx));
    }
    if (e instanceof SignedExpression) {
      Expression inner = QueryParser.unwrap(((SignedExpression) e).getExpression());
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
      for (Map<String, Object> row : originals(group)) {
        if (isTrue(eval(a.getFilterExpression(), Collections.singletonList(row), ctx))) {
          kept.add(row);
        }
      }
      return aggregate(f, kept, ctx);
    }
    if (e instanceof BitwiseXor) {
      Expression rotated = caret((BitwiseXor) e);
      if (rotated != e) {
        return eval(rotated, group, ctx);
      }
      // PostgreSQL's ^ is exponentiation (# is XOR)
      Object l = eval(((BitwiseXor) e).getLeftExpression(), group, ctx);
      Object r = eval(((BitwiseXor) e).getRightExpression(), group, ctx);
      return l == null || r == null ? null : power(Arrays.asList(l, r));
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
      String procName =
          v instanceof String ? ((String) v).trim().replaceFirst("^pg_catalog\\.", "") : "";
      if (t.equals("regproc")
          && Builtins.overloads(procName).isEmpty()
          && ctx.catalog != null
          && ctx.catalog.ioFunction(procName)) {
        // ponytail: pg_type's I/O function columns hold names of functions pg_proc does not list,
        // which such a name compares with (pgjdbc's 'pg_catalog.array_in')
        return procName;
      }
      boolean reg =
          t.equals("regclass")
              || t.equals("regtype")
              || t.equals("regnamespace")
              || t.equals("regproc")
              || t.equals("regprocedure");
      if (reg && v != null && ctx.catalog != null) {
        // an OID, or an object's name as ORMs write 'table'::regclass, that prints as the name
        Object o = v instanceof Catalog.Reg ? (Object) ((Catalog.Reg) v).oid : v;
        long oid;
        if (o instanceof Number) {
          oid = ((Number) o).longValue();
        } else if (DIGITS.matcher(text(o).trim()).matches()) {
          oid = Long.parseLong(text(o).trim());
        } else {
          String name = text(o);
          oid =
              t.equals("regclass")
                  ? ctx.catalog.relationOid(name)
                  : t.equals("regnamespace")
                      ? ctx.catalog.namespaceOid(name)
                      : t.startsWith("regproc")
                          ? Builtins.procOid(name, t.equals("regprocedure"))
                          : Catalog.typeOid(name);
        }
        return ctx.catalog.reg(t, oid);
      }
      return cast(type, v);
    }
    if (e instanceof CaseExpression) {
      return caseOf((CaseExpression) e, group, ctx);
    }
    if (e instanceof CollateExpression
        || (e instanceof RowGetExpression
            && ((RowGetExpression) e).getExpression() instanceof CollateExpression)) {
      Object v = eval(QueryParser.unwrap(e), group, ctx);
      if (v != null && !(v instanceof String) && !(v instanceof List)) {
        // only text (and arrays of it) has a collation
        throw new PgException.Argument(
            "42804", "collations are not supported by type " + pgTypeName(v));
      }
      return v;
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
      checkDeterministic(
          "regular expressions",
          ctx,
          Arrays.asList(re.getLeftExpression(), re.getRightExpression()));
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
      Boolean v = condition(eval(((NotExpression) e).getExpression(), group, ctx));
      return v == null ? null : !v;
    }
    if (e instanceof AndExpression) {
      Boolean l = condition(eval(((AndExpression) e).getLeftExpression(), group, ctx));
      if (Boolean.FALSE.equals(l)) {
        return false; // b <> 0 AND a / b > 1 must not divide by zero
      }
      Boolean r = condition(eval(((AndExpression) e).getRightExpression(), group, ctx));
      if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) {
        return false;
      }
      return l == null || r == null ? null : Boolean.TRUE;
    }
    if (e instanceof OrExpression) {
      Boolean l = condition(eval(((OrExpression) e).getLeftExpression(), group, ctx));
      if (isTrue(l)) {
        return true;
      }
      Boolean r = condition(eval(((OrExpression) e).getRightExpression(), group, ctx));
      if (isTrue(l) || isTrue(r)) {
        return true;
      }
      return l == null || r == null ? null : Boolean.FALSE;
    }
    if (e instanceof IsNullExpression) {
      IsNullExpression n = (IsNullExpression) e;
      return (eval(n.getLeftExpression(), group, ctx) == null) != QueryParser.isNotNull(n);
    }
    if (e instanceof IsBooleanExpression) {
      IsBooleanExpression b = (IsBooleanExpression) e;
      Object v = eval(b.getLeftExpression(), group, ctx);
      boolean matches = b.isTrue() ? isTrue(v) : Boolean.FALSE.equals(condition(v));
      return matches != b.isNot();
    }
    if (e instanceof LikeExpression) {
      LikeExpression like = (LikeExpression) e;
      checkDeterministic(
          like.getLikeKeyWord() == LikeExpression.KeyWord.SIMILAR_TO
              ? "regular expressions"
              : like.getLikeKeyWord().toString(),
          ctx,
          Arrays.asList(like.getLeftExpression(), like.getRightExpression()));
      Object v = evalText(like.getLeftExpression(), group, ctx);
      Object p = evalText(like.getRightExpression(), group, ctx);
      if (v == null || p == null) {
        return null;
      }
      Object escapeValue = like.getEscape() == null ? "\\" : eval(like.getEscape(), group, ctx);
      if (escapeValue == null) {
        return null; // as PostgreSQL's strict like_escape and similar_to_escape
      }
      String escape = String.valueOf(escapeValue);
      if (escape.codePointCount(0, escape.length()) > 1) {
        throw new PgException.Argument(
            "22025", "invalid escape string: Escape string must be empty or one character.");
      }
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
        if (v instanceof Number && textTyped(in.getRightExpression(), ctx)) {
          // no implicit cast between text and numbers: bigint = text is no operator
          throw new PgException.Argument(
              "42883", "operator does not exist: " + numericTypeName(v) + " = text");
        }
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
          Object x = eval(item, group, ctx);
          if (bpchar(in.getLeftExpression(), item, ctx)) {
            v = rtrimNullable(v);
            x = rtrimNullable(x);
          }
          values.add(x);
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
      if (bpchar(b.getLeftExpression(), b.getBetweenExpressionStart(), ctx)
          || bpchar(b.getLeftExpression(), b.getBetweenExpressionEnd(), ctx)) {
        v = rtrimNullable(v);
        lo = rtrimNullable(lo);
        hi = rtrimNullable(hi);
      }
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
      // text has no implicit cast to a number: bigint = text is no operator in PostgreSQL
      if (l instanceof Number && r instanceof String && textTyped(op.getRightExpression(), ctx)
          || r instanceof Number && l instanceof String && textTyped(op.getLeftExpression(), ctx)) {
        throw new PgException.Argument(
            "42883",
            "operator does not exist: "
                + (l instanceof Number
                    ? numericTypeName(l) + " " + op.getStringExpression() + " text"
                    : "text " + op.getStringExpression() + " " + numericTypeName(r)));
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
      } else if (bpchar(op.getLeftExpression(), op.getRightExpression(), ctx)) {
        l = rtrim(l);
        r = rtrim(r);
      }
      Boolean result = compared(op, compare(l, r));
      if (result != null) {
        return result;
      }
    }
    if (e instanceof Concat) {
      Object l = evalText(((Concat) e).getLeftExpression(), group, ctx);
      Object r = evalText(((Concat) e).getRightExpression(), group, ctx);
      if ((l instanceof List && !(l instanceof Row))
          || (r instanceof List && !(r instanceof Row))) {
        // array || array, array || element, element || array; a NULL array is the other side, and
        // a quoted literal beside an array is an array literal ('{b,c}'), as in PostgreSQL
        l = arrayLiteral(((Concat) e).getLeftExpression(), l);
        r = arrayLiteral(((Concat) e).getRightExpression(), r);
        if (l == null || r == null) {
          return l == null ? r : l;
        }
        List<Object> out = new ArrayList<>();
        int depth = Math.max(depth(l), depth(r));
        for (Object side : new Object[] {l, r}) {
          if (depth(side) == depth) {
            out.addAll((List<?>) side);
          } else {
            out.add(side); // an element, or a row of a two-dimensional array
          }
        }
        return out;
      }
      if (l == null || r == null) {
        return null;
      }
      if (!(l instanceof String || r instanceof String)
          && !(l instanceof Json.Value || r instanceof Json.Value)
          && !(isBytea(l) && isBytea(r))) {
        // || is text || anything: 3 || 4.0 has no operator in PostgreSQL
        throw new PgException.Argument(
            "42883", "operator does not exist: " + typeLabel(l) + " || " + typeLabel(r));
      }
      if (l instanceof Json.Value || r instanceof Json.Value) {
        return new Json.Value(Json.concat(Json.of(l).node, Json.of(r).node), true, null);
      }
      if (isBytea(l) && isBytea(r)) {
        byte[] a = bytes(l);
        byte[] b = bytes(r);
        byte[] both = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
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
      if (bpchar(d.getLeftExpression(), d.getRightExpression(), ctx)) {
        l = rtrimNullable(l);
        r = rtrimNullable(r);
      }
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
      int depth = 0;
      for (Expression x = e;
          x instanceof net.sf.jsqlparser.expression.ArrayExpression;
          x = ((net.sf.jsqlparser.expression.ArrayExpression) x).getObjExpression()) {
        depth++;
      }
      if (depth > 6) {
        throw new PgException.Argument(
            "54000", "number of array dimensions (" + depth + ") exceeds the maximum allowed (6)");
      }
      net.sf.jsqlparser.expression.ArrayExpression a =
          (net.sf.jsqlparser.expression.ArrayExpression) e;
      Object array = eval(a.getObjExpression(), group, ctx);
      if (array == null) {
        return null;
      }
      if (array instanceof Json.Value && a.getIndexExpression() != null) {
        return jsonSubscript(
            (Json.Value) array,
            Collections.singletonList(eval(a.getIndexExpression(), group, ctx)));
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
        long k = integral(i, e) + 1 - lowerBound(array); // int2vector counts from 0
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
      List<?> path;
      switch (step.getValue()) {
        case "->":
        case "->>":
          path = Collections.singletonList(key);
          break;
        case "#>":
        case "#>>":
          if (key == null) {
            return null;
          }
          path = elements(key);
          break;
        default:
          throw new PgException.Argument("0A000", "Unsupported JSON operator: " + step.getValue());
      }
      com.fasterxml.jackson.databind.JsonNode node =
          step.getValue().startsWith("->") ? Json.get(v.node, key) : Json.path(v.node, path);
      cur = Json.extracted(v, node, path, v.binary, step.getValue().endsWith(">>"));
    }
    return cur;
  }

  private static void jsonSteps(
      net.sf.jsqlparser.expression.JsonExpression j, List<Map.Entry<Expression, String>> steps) {
    for (Map.Entry<Expression, String> ident : j.getIdentList()) {
      net.sf.jsqlparser.expression.JsonExpression inner = chained(ident.getKey());
      if (inner != null) {
        // j -> -1 -> 'b' is read as j -> -(1 -> 'b'): the sign belongs to the first step
        Expression first =
            ident.getKey() instanceof SignedExpression
                ? new SignedExpression(
                    ((SignedExpression) ident.getKey()).getSign(), inner.getExpression())
                : inner.getExpression();
        steps.add(new java.util.AbstractMap.SimpleEntry<>(first, ident.getValue()));
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
    net.sf.jsqlparser.expression.JsonExpression inner = chained(last.getKey());
    return inner != null ? lastJsonOperator(inner) : last.getValue();
  }

  /** The rest of a JSON chain that JSQLParser nests in a step, possibly behind its sign. */
  @Nullable private static net.sf.jsqlparser.expression.JsonExpression chained(Expression step) {
    Expression e =
        step instanceof SignedExpression ? ((SignedExpression) step).getExpression() : step;
    return e instanceof net.sf.jsqlparser.expression.JsonExpression
        ? (net.sf.jsqlparser.expression.JsonExpression) e
        : null;
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
      } else if (!escape.isEmpty() && c == escape.charAt(0)) {
        continue; // a trailing escape escapes nothing: PostgreSQL drops it ('ab\\' is ^(?:ab)$)
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

  /**
   * A jsonb argument: a jsonb value, or text (a literal, a json column's stored text) read as one.
   */
  private static Json.Value jsonb(Object v) {
    return v instanceof String ? Json.cast((String) v, true) : Json.of(v);
  }

  /** A numeric-only function's argument: an integer converts, a float does not (42883). */
  private static BigDecimal numericArgument(List<Object> args, int i, String function) {
    Number n = num(args, i);
    if (n instanceof Double || n instanceof Float) {
      throw new PgException.Argument(
          "42883",
          "function " + function.toLowerCase(Locale.ROOT) + "(double precision) does not exist");
    }
    return toDecimal(n);
  }

  private static void checkLogArgument(double x) {
    if (x == 0) {
      throw new PgException.Argument("2201E", "cannot take logarithm of zero");
    }
    if (x < 0) {
      throw new PgException.Argument("2201E", "cannot take logarithm of a negative number");
    }
  }

  /** ln or log10 of a double precision: dlog1 and dlog10. */
  private static double floatLog(double x, boolean ten) {
    checkLogArgument(x);
    return ten ? Math.log10(x) : Math.log(x);
  }

  /** exp of a double precision: dexp, with its range errors. */
  private static double floatExp(double x) {
    if (Double.isNaN(x)) {
      return x;
    }
    if (Double.isInfinite(x)) {
      return x > 0 ? x : 0;
    }
    double result = Math.exp(x);
    if (Double.isInfinite(result)) {
      throw new PgException.Argument("22003", "value out of range: overflow");
    }
    if (result == 0) {
      throw new PgException.Argument("22003", "value out of range: underflow");
    }
    return result;
  }

  // ponytail: these functions compute with BigDecimal, quadratic in the digits where PostgreSQL's
  // arithmetic is not (log of a 16000-digit number took a minute); longer arguments are refused
  private static final int MAX_NUMERIC_DIGITS = 2000;

  /** {@code x}, unless written out in full it takes more than MAX_NUMERIC_DIGITS digits. */
  private static BigDecimal bounded(BigDecimal x, String function) {
    long digits = Math.max(x.precision(), Math.max(x.precision() - (long) x.scale(), x.scale()));
    if (digits > MAX_NUMERIC_DIGITS) {
      throw new PgException.Argument(
          "54000",
          "numeric argument of " + function + " longer than " + MAX_NUMERIC_DIGITS + " digits");
    }
    return x;
  }

  // numeric.c: NUMERIC_MIN_SIG_DIGITS and NUMERIC_MAX_DISPLAY_SCALE
  private static final int MIN_SIG_DIGITS = 16;
  private static final int MAX_DISPLAY_SCALE = 1000;

  private static int resultScale(int scale, BigDecimal... inputs) {
    for (BigDecimal x : inputs) {
      scale = Math.max(scale, x.scale()); // a numeric's display scale
    }
    return Math.max(0, Math.min(scale, MAX_DISPLAY_SCALE));
  }

  /**
   * numeric_ln: ln(x) rounded to PostgreSQL's scale for it. The value is computed beyond that scale
   * and rounded once, as PostgreSQL's ln_var does with its guard digits.
   */
  static BigDecimal numericLn(BigDecimal x) {
    bounded(x, "ln");
    checkLogArgument(x.signum());
    int rscale = resultScale(MIN_SIG_DIGITS - lnDweight(x), x);
    return ln(x, rscale + 30).setScale(rscale, RoundingMode.HALF_UP);
  }

  /** numeric_log: log_base(x), at log_var's scale: ln(x) / ln(base), rounded once. */
  static BigDecimal numericLog(BigDecimal base, BigDecimal x) {
    bounded(base, "log");
    bounded(x, "log");
    checkLogArgument(base.signum());
    checkLogArgument(x.signum());
    int baseDweight = lnDweight(base);
    int xDweight = lnDweight(x);
    int resultDweight = xDweight - baseDweight;
    int rscale = resultScale(MIN_SIG_DIGITS - resultDweight, base, x);
    // enough digits that the quotient is exact well past rscale, however small ln(base) is
    int digits = rscale + 30 + Math.abs(baseDweight) + Math.abs(xDweight) + Math.abs(resultDweight);
    BigDecimal lnBase = ln(base, digits);
    if (lnBase.signum() == 0) {
      throw new PgException.Argument("22012", "division by zero");
    }
    return ln(x, digits)
        .divide(lnBase, new java.math.MathContext(digits + 10))
        .setScale(rscale, RoundingMode.HALF_UP);
  }

  /** numeric_exp: e^x at PostgreSQL's scale, and its overflow and underflow. */
  static BigDecimal numericExp(BigDecimal x) {
    double val = bounded(x, "exp").doubleValue();
    double weight = Math.max(-2000, Math.min(val * 0.434294481903252, 2000));
    int rscale = resultScale(MIN_SIG_DIGITS - (int) weight, x);
    if (Math.abs(val) >= 6000) { // exp_var's limit
      if (val > 0) {
        throw new PgException.Argument("22003", "value overflows numeric format");
      }
      return BigDecimal.ZERO.setScale(rscale);
    }
    // e^x reduced to |x| <= 0.01 by halving, the Taylor series, then squared back
    int halvings = 0;
    for (double v = val; Math.abs(v) > 0.01; v /= 2) {
      halvings++;
    }
    // the result's own weight, not the one clamped for the scale: e^5999 has 2606 integer digits
    int digits = Math.max(0, (int) (val * 0.434294481903252)) + rscale + 30 + halvings;
    java.math.MathContext mc = new java.math.MathContext(digits);
    BigDecimal r = x.divide(BigDecimal.valueOf(2).pow(halvings)); // exact: a power of two
    BigDecimal sum = BigDecimal.ONE;
    BigDecimal term = BigDecimal.ONE;
    for (int n = 1; ; n++) {
      term = term.multiply(r, mc).divide(BigDecimal.valueOf(n), mc);
      if (term.signum() == 0
          || term.abs().compareTo(sum.abs().movePointLeft(mc.getPrecision() + 2)) < 0) {
        break;
      }
      sum = sum.add(term, mc);
    }
    for (int i = 0; i < halvings; i++) {
      sum = sum.multiply(sum, mc);
    }
    return sum.setScale(rscale, RoundingMode.HALF_UP);
  }

  /** ln(x), x > 0, within 10^-scale. */
  private static BigDecimal ln(BigDecimal x, int scale) {
    if (x.compareTo(BigDecimal.ONE) == 0) {
      return BigDecimal.ZERO;
    }
    int k = x.precision() - x.scale() - 1; // x = m * 10^k, 1 <= m < 10
    java.math.MathContext mc = new java.math.MathContext(scale + 10);
    BigDecimal lnM = lnNearOne(x.movePointLeft(k), mc);
    return k == 0 ? lnM : lnM.add(lnNearOne(BigDecimal.TEN, mc).multiply(BigDecimal.valueOf(k)));
  }

  /**
   * ln(m) for 0.1 < m <= 10: square roots bring m within 0.001 of 1, where ln(m) = 2 atanh((m - 1)
   * / (m + 1)) converges fast; each root halves the logarithm, so the sum is doubled back.
   */
  private static BigDecimal lnNearOne(BigDecimal m, java.math.MathContext mc) {
    int roots = 0;
    BigDecimal near = new BigDecimal("0.001");
    while (m.subtract(BigDecimal.ONE).abs().compareTo(near) > 0) {
      m = m.sqrt(mc);
      roots++;
    }
    BigDecimal z = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), mc);
    BigDecimal z2 = z.multiply(z, mc);
    BigDecimal sum = z;
    BigDecimal power = z;
    for (int n = 3; power.signum() != 0; n += 2) {
      power = power.multiply(z2, mc);
      BigDecimal term = power.divide(BigDecimal.valueOf(n), mc);
      if (term.abs().compareTo(sum.abs().movePointLeft(mc.getPrecision() + 2)) < 0) {
        break;
      }
      sum = sum.add(term, mc);
    }
    return sum.multiply(BigDecimal.valueOf(2).pow(roots + 1));
  }

  /** numeric.c's estimate_ln_dweight: the decimal weight PostgreSQL expects of ln(x). */
  private static int lnDweight(BigDecimal x) {
    if (x.signum() <= 0) {
      return 0;
    }
    if (x.compareTo(new BigDecimal("0.9")) >= 0 && x.compareTo(new BigDecimal("1.1")) <= 0) {
      // ln(1 + d) ~= d: the weight of d's leading decimal digit
      BigDecimal d = x.subtract(BigDecimal.ONE);
      return d.signum() == 0 ? 0 : d.precision() - d.scale() - 1;
    }
    // from the first two base-10000 digit groups, as PostgreSQL stores the number
    int weight = leadingGroup(x)[0];
    BigDecimal v = x.movePointLeft(4 * weight); // 1 <= v < 10000
    BigDecimal first = v.setScale(0, RoundingMode.DOWN);
    BigDecimal rest = v.subtract(first);
    double digits = first.doubleValue();
    int dweight = weight * 4;
    if (rest.signum() != 0) {
      digits = digits * 10000 + rest.movePointRight(4).setScale(0, RoundingMode.DOWN).doubleValue();
      dweight -= 4;
    }
    return (int) Math.log10(Math.abs(Math.log(digits) + dweight * 2.302585092994046));
  }

  /** width_bucket_float8. */
  private static int widthBucket(double operand, double low, double high, int count) {
    checkBucketArguments(count, Double.isNaN(operand) || Double.isNaN(low) || Double.isNaN(high));
    if (Double.isInfinite(low) || Double.isInfinite(high)) {
      throw new PgException.Argument("2201G", "lower and upper bounds must be finite");
    }
    if (low == high) {
      throw new PgException.Argument("2201G", "lower bound cannot equal upper bound");
    }
    boolean up = low < high;
    if (up ? operand < low : operand > low) {
      return 0;
    }
    if (up ? operand >= high : operand <= high) {
      if (count == Integer.MAX_VALUE) {
        throw new PgException.Argument("22003", "integer out of range");
      }
      return count + 1;
    }
    double fraction =
        !Double.isInfinite(high - low)
            ? (operand - low) / (high - low)
            : (operand / 2 - low / 2) / (high / 2 - low / 2);
    int result = (int) (count * fraction);
    return Math.min(result, count - 1) + 1; // the quotient could round to 1.0
  }

  /** width_bucket_numeric: the bucket by exact floor division. */
  private static int widthBucket(BigDecimal operand, BigDecimal low, BigDecimal high, int count) {
    checkBucketArguments(count, false);
    bounded(operand, "width_bucket");
    bounded(low, "width_bucket");
    bounded(high, "width_bucket");
    int order = low.compareTo(high);
    if (order == 0) {
      throw new PgException.Argument("2201G", "lower bound cannot equal upper bound");
    }
    long result;
    if (order < 0 ? operand.compareTo(low) < 0 : operand.compareTo(low) > 0) {
      result = 0;
    } else if (order < 0 ? operand.compareTo(high) >= 0 : operand.compareTo(high) <= 0) {
      result = count + 1L;
    } else {
      result =
          operand
                  .subtract(low)
                  .multiply(BigDecimal.valueOf(count))
                  .divideToIntegralValue(high.subtract(low))
                  .longValueExact()
              + 1;
    }
    if (result != (int) result) {
      throw new PgException.Argument("22003", "integer out of range");
    }
    return (int) result;
  }

  private static void checkBucketArguments(int count, boolean nan) {
    if (count <= 0) {
      throw new PgException.Argument("2201G", "count must be greater than zero");
    }
    if (nan) {
      throw new PgException.Argument(
          "2201G", "operand, lower bound, and upper bound cannot be NaN");
    }
  }

  /** width_bucket(operand, thresholds): PostgreSQL's binary search, sorted thresholds assumed. */
  private static int widthBucket(Object operand, Object thresholds) {
    List<?> elements = (List<?>) thresholds;
    if (elements.stream().anyMatch(e -> e instanceof List)) {
      throw new PgException.Argument("2202E", "thresholds must be one-dimensional array");
    }
    if (elements.contains(null)) {
      throw new PgException.Argument("22004", "thresholds array must not contain NULLs");
    }
    if (elements.stream().anyMatch(e -> (e instanceof Number) != (operand instanceof Number))) {
      // anycompatible: text and numbers have no common type ('5'::text against integer[])
      throw new PgException.Argument(
          "42883", "function width_bucket(" + pgTypeName(operand) + ", array) does not exist");
    }
    int left = 0;
    int right = elements.size();
    while (left < right) {
      int mid = (left + right) / 2;
      if (compare(operand, elements.get(mid)) < 0) {
        right = mid;
      } else {
        left = mid + 1;
      }
    }
    return left;
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
    a = a instanceof Catalog.Reg ? (Object) ((Catalog.Reg) a).oid : a;
    b = b instanceof Catalog.Reg ? (Object) ((Catalog.Reg) b).oid : b;
    if (a instanceof Json.Value || b instanceof Json.Value) {
      if (a instanceof Json.Value && !((Json.Value) a).binary
          || b instanceof Json.Value && !((Json.Value) b).binary) {
        // as PostgreSQL: json has no =, <, ordering or grouping; jsonb does
        throw new PgException.Argument(
            "42883", "could not identify a comparison operator for type json");
      }
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
    if (a instanceof String && b instanceof String) {
      return collation.textComparator().compare((String) a, (String) b);
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

  /**
   * A float's shortest digits that read back as it, as PostgreSQL prints a real; Float.toString
   * before JDK 19 can give more (4.5678899E15 for 4.56789e+15).
   */
  /** The shortest digits for {@code d}, as PostgreSQL's float8out (Ryu) writes it. */
  private static BigDecimal shortest(double d) {
    BigDecimal exact = new BigDecimal(d);
    return shortest(
        exact,
        midpoint(exact, Math.nextDown(d), Math.ulp(d)),
        midpoint(exact, Math.nextUp(d), Math.ulp(d)),
        new BigDecimal(Double.toString(d)).stripTrailingZeros().precision());
  }

  /** The shortest digits for {@code f}, as PostgreSQL's float4out (Ryu) writes it. */
  private static BigDecimal shortest(float f) {
    BigDecimal exact = new BigDecimal(f);
    return shortest(
        exact,
        midpoint(exact, Math.nextDown(f), Math.ulp(f)),
        midpoint(exact, Math.nextUp(f), Math.ulp(f)),
        new BigDecimal(Float.toString(f)).stripTrailingZeros().precision());
  }

  /** Halfway from {@code exact} to its neighbor; past the largest value, half an ulp beyond. */
  private static BigDecimal midpoint(BigDecimal exact, double neighbor, double ulp) {
    BigDecimal other =
        Double.isInfinite(neighbor)
            ? exact.add(new BigDecimal(Math.copySign(ulp, neighbor)))
            : new BigDecimal(neighbor);
    return exact.add(other).divide(BigDecimal.valueOf(2)); // exact: a binary fraction halved
  }

  /**
   * The fewest digits strictly between the midpoints {@code low} and {@code high}: Ryu as
   * PostgreSQL builds it never takes a midpoint itself (1e23, halfway between two doubles, is
   * written 9.999999999999999e+22), since a reader may round a tie either way. Fewer digits fit
   * only if more do, so the search starts from a length that reads back and moves one way.
   */
  private static BigDecimal shortest(BigDecimal exact, BigDecimal low, BigDecimal high, int start) {
    java.util.function.IntFunction<BigDecimal> digits =
        p -> exact.round(new java.math.MathContext(p, RoundingMode.HALF_EVEN));
    java.util.function.Predicate<BigDecimal> inside =
        c -> c.compareTo(low) > 0 && c.compareTo(high) < 0;
    int p = Math.max(start, 1);
    while (!inside.test(digits.apply(p))) {
      p++;
    }
    while (p > 1 && inside.test(digits.apply(p - 1))) {
      p--;
    }
    return digits.apply(p);
  }

  /** base ^ exponent, and power(base, exponent), in double precision as PostgreSQL's float8. */
  private static double power(List<Object> args) {
    Number b = num(args, 0);
    Number x = num(args, 1);
    if ((b instanceof BigDecimal || x instanceof BigDecimal)
        && !(b instanceof Double
            || b instanceof Float
            || x instanceof Double
            || x instanceof Float)) {
      // ponytail: numeric ^ numeric is exact at PostgreSQL's result scale (power_var); refused
      // until then rather than answered in double precision
      throw new PgException.Argument("0A000", "Unsupported operator: numeric ^ numeric");
    }
    double base = b.doubleValue();
    double exponent = x.doubleValue();
    // as PostgreSQL's dpow, after POSIX: 1 ^ NaN, NaN ^ 0 and -1 ^ Infinity are 1
    if (Double.isNaN(base) || Double.isNaN(exponent)) {
      return base == 1 || exponent == 0 ? 1 : Double.NaN;
    }
    if (Math.abs(base) == 1 && Double.isInfinite(exponent)) {
      return 1;
    }
    if (base == 0 && exponent < 0) {
      throw new PgException.Argument("2201F", "zero raised to a negative power is undefined");
    }
    if (base < 0 && exponent != Math.rint(exponent)) {
      throw new PgException.Argument(
          "2201F", "a negative number raised to a non-integer power yields a complex result");
    }
    double v = Math.pow(base, exponent);
    if (Double.isInfinite(v) && Double.isFinite(base) && Double.isFinite(exponent)) {
      throw new ArithmeticException("value out of range: overflow");
    }
    if (v == 0 && base != 0 && Double.isFinite(base) && Double.isFinite(exponent)) {
      throw new ArithmeticException("value out of range: underflow");
    }
    return v;
  }

  private static final java.util.regex.Pattern INTEGER_TEXT =
      java.util.regex.Pattern.compile("[+-]?\\d+");

  /** A double rounded to real, which fails as PostgreSQL's float8 to float4 does. */
  private static float real(double d) {
    float f = (float) d;
    if (Float.isInfinite(f) && !Double.isInfinite(d)) {
      throw new ArithmeticException("value out of range: overflow");
    }
    if (f == 0 && d != 0) {
      throw new ArithmeticException("value out of range: underflow");
    }
    return f;
  }

  private static Object coerce(String s, Object target) {
    if (target instanceof Float) {
      return java.util.Objects.requireNonNull(
          cast("real", s)); // real = '324.78' compares in single precision
    }
    if (target instanceof Integer || target instanceof Long || target instanceof Short) {
      // a quoted literal next to an integer is integer input: '0x10', '1_000'; 22003 beyond range
      String type = target instanceof Long ? "bigint" : "integer";
      BigInteger v = integerInput(s);
      if (v == null) {
        throw new PgException.Argument(
            "22P02", "invalid input syntax for type " + type + ": \"" + s + "\"");
      }
      if (v.bitLength() > (target instanceof Long ? 63 : 31)) {
        throw new PgException.Argument(
            "22003", "value \"" + s + "\" is out of range for type " + type);
      }
      return v.longValue();
    }
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
        return time(s);
      }
      if (target instanceof LocalDateTime) {
        return QueryParser.localDateTime(s);
      }
      if (target instanceof Instant) {
        return QueryParser.instant(s);
      }
      if (target instanceof ByteBuffer) {
        return ByteBuffer.wrap(QueryParser.bytea(s));
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
    if (raw.equals(QueryParser.WHOLE_ROW) && c.getTable() != null) {
      // t as a value: t's row, a record of its columns, from this query's row or, for a
      // correlated reference, the enclosing one's; NULL where an outer join found no row for t
      String prefix = QueryParser.unquote(c.getTable().getName()) + ".";
      Map<String, Object> row = group.isEmpty() ? Collections.emptyMap() : group.get(0);
      if (ctx.outer != null && row.keySet().stream().noneMatch(k -> k.startsWith(prefix))) {
        row = ctx.outer;
      }
      if (row.isEmpty() || row.containsKey(prefix + NULL_EXTENDED)) {
        return null;
      }
      Record record = new Record();
      row.forEach(
          (key, value) -> {
            if (key.startsWith(prefix)
                && !key.equals(prefix + Catalog.TABLEOID)
                && !key.equals(prefix + NULL_EXTENDED)) {
              record.put(key.substring(prefix.length()), value);
            }
          });
      return record;
    }
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
    if (value instanceof Json.Value && c.getArrayConstructor() != null) {
      List<Object> keys = new ArrayList<>();
      for (Expression k : c.getArrayConstructor().getExpressions()) {
        keys.add(eval(k, group, ctx));
      }
      return jsonSubscript((Json.Value) value, keys);
    }
    Object subscript =
        bracket >= 0
            ? Long.parseLong(raw.substring(bracket + 1, raw.length() - 1).trim())
            : eval(c.getArrayConstructor().getExpressions().get(0), group, ctx);
    if (!(subscript instanceof Number)) {
      return null;
    }
    // int2vector (pg_index.indkey, indoption) counts from 0
    int index = ((Number) subscript).intValue() + 1 - (int) lowerBound(value);
    List<?> array = value instanceof List ? (List<?>) value : null;
    return array == null || index < 1 || index > array.size() ? null : array.get(index - 1);
  }

  /**
   * {@code j['a'][0]}: jsonb subscripting, a member by text key or an element by integer index
   * (negative from the end), in turn; NULL where there is none. json has no subscripts.
   */
  @Nullable private static Object jsonSubscript(Json.Value value, List<Object> keys) {
    if (!value.binary) {
      throw new PgException.Argument(
          "42804", "cannot subscript type json because it does not support subscripting");
    }
    com.fasterxml.jackson.databind.JsonNode node = value.node;
    for (Object key : keys) {
      if (key == null || node == null) {
        return null;
      }
      if (!(key instanceof String || key instanceof Integer || key instanceof Long)) {
        throw new PgException.Argument(
            "42804", "subscript type " + pgTypeName(key) + " is not supported");
      }
      Object k = key;
      if (node.isArray() && key instanceof String) {
        try {
          k = Long.parseLong(((String) key).trim()); // '1' indexes an array, as in PostgreSQL
        } catch (NumberFormatException notAnIndex) {
          return null;
        }
      } else if (node.isObject() && !(key instanceof String)) {
        k = text(key);
      }
      node = Json.get(node, k);
    }
    return node == null ? null : new Json.Value(node, true, null);
  }

  @Nullable private static Object columnValue(
      Column c, String name, List<Map<String, Object>> group, Context ctx) {
    if (group instanceof Operators.Aggregate.EmptyGroup) {
      return null; // the empty grouping set's row over no input: its grouping columns are NULL
    }
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
          return ctx.catalog == null ? null : ctx.catalog.currentSchema();
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
        return time(v);
      case TIMESTAMP:
        return QueryParser.localDateTime(v);
      default:
        return QueryParser.instant(v);
    }
  }

  /** A date, also from a timestamp's text ('2024-01-01 10:00' is 2024-01-01), as PostgreSQL. */
  static LocalDate date(String s) {
    Object special = specialInput(s, "date");
    if (special != null) {
      return (LocalDate) special;
    }
    boolean[] bc = {false};
    String t = QueryParser.withoutEra(s, bc);
    if (t.length() > 10) {
      QueryParser.instant(t); // what follows the date must be a time ('2024-01-01 10:00')
    }
    return QueryParser.era(LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t), bc[0], s);
  }

  /** A date as PostgreSQL writes it: 0044-03-15 BC for the year -43, which is 44 BC. */
  static String dateText(LocalDate d) {
    StringBuilder sb = new StringBuilder(13);
    pad(sb, d.getYear() <= 0 ? 1 - d.getYear() : d.getYear(), 4).append('-');
    pad(sb, d.getMonthValue(), 2).append('-');
    return pad(sb, d.getDayOfMonth(), 2).append(d.getYear() <= 0 ? " BC" : "").toString();
  }

  /** A timestamp's text with an offset, which PostgreSQL writes before BC. */
  static String withOffset(String timestamp, String offset) {
    return timestamp.endsWith(" BC")
        ? timestamp.substring(0, timestamp.length() - 3) + offset + " BC"
        : timestamp + offset;
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
    return v instanceof Number ? numericTypeName(v) : "text";
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
    if (v instanceof Float) {
      return -(Float) v;
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

  /** An array's first subscript: 0 for int2vector (pg_index.indkey), as in PostgreSQL; else 1. */
  static long lowerBound(@Nullable Object array) {
    return array instanceof Catalog.Int2Vector ? 0 : 1;
  }

  static boolean isIntegral(Object v) {
    return v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte;
  }

  /** A value as its type's output function writes it, as concat and format take it: t, not true. */
  static String outputText(Object v) {
    return v instanceof Boolean ? ((Boolean) v ? "t" : "f") : text(v);
  }

  /** bytea_output = escape: printable ASCII as is, a backslash doubled, other bytes as \\nnn. */
  private static String escapeBytes(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      int c = b & 0xff;
      if (c == '\\') {
        sb.append("\\\\");
      } else if (c < 0x20 || c > 0x7e) {
        sb.append(String.format("\\%03o", c));
      } else {
        sb.append((char) c);
      }
    }
    return sb.toString();
  }

  static String text(Object v) {
    if (v instanceof Json.Value || v instanceof Catalog.Reg) {
      return v.toString();
    }
    if (v instanceof BigDecimal) {
      return ((BigDecimal) v).toPlainString();
    }
    if (v instanceof Interval) {
      return ((Interval) v).text(intervalStyle());
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
        if (item instanceof List && !(item instanceof Row)) {
          sb.append(s); // a row of a multi-dimensional array: {{1,2},{3,4}}
          continue;
        }
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
      if (t.equals(LocalTime.MAX)) {
        return "24:00:00"; // see time(String)
      }
      StringBuilder sb = new StringBuilder();
      pad(sb, t.getHour(), 2).append(':');
      pad(sb, t.getMinute(), 2).append(':');
      return fraction(pad(sb, t.getSecond(), 2), t.getNano()).toString();
    }
    if (v instanceof LocalDate) {
      return dateText((LocalDate) v);
    }
    if (v instanceof LocalDateTime) {
      return timestampText((LocalDateTime) v);
    }
    if (v instanceof Instant) {
      // in the session's zone, with PostgreSQL's offset suffix (+00, +09, +05:30)
      java.time.ZonedDateTime z = ((Instant) v).atZone(zone());
      return withOffset(timestampText(z.toLocalDateTime()), offsetText(z.getOffset()));
    }
    if (isBytea(v)) {
      Settings settings = settings();
      return settings != null && "escape".equals(settings.get("bytea_output"))
          ? escapeBytes(bytes(v))
          : "\\x" + hex(bytes(v));
    }
    if (v instanceof Double) {
      return floatText((Double) v, 15);
    }
    if (v instanceof Float) {
      return floatText((Float) v, 6);
    }
    return String.valueOf(v);
  }

  /**
   * An array whose rows, if it has any, are arrays of one length all the way down, as PostgreSQL's
   * multi-dimensional arrays are; 2202E otherwise.
   */
  /** Whether an aggregate's one argument is written as an array: a cast to one, or ARRAY[...]. */
  private static boolean arrayArgument(Expression f) {
    if (!(f instanceof Function) || ((Function) f).getParameters() == null) {
      return false;
    }
    List<?> params = ((Function) f).getParameters();
    Expression p = params.size() == 1 ? QueryParser.unwrap((Expression) params.get(0)) : null;
    return p instanceof net.sf.jsqlparser.expression.ArrayConstructor
        || p instanceof CastExpression
            && ((CastExpression) p).getColDataType().toString().trim().endsWith("]");
  }

  private static List<Object> rectangular(List<Object> array, String message) {
    boolean nested = array.stream().anyMatch(v -> v instanceof List && !(v instanceof Row));
    if (nested) {
      int length = -1;
      for (Object row : array) {
        if (!(row instanceof List) || row instanceof Row) {
          throw new PgException.Argument("2202E", message);
        }
        @SuppressWarnings("unchecked")
        List<Object> inner = rectangular((List<Object>) row, message);
        if (length >= 0 && inner.size() != length) {
          throw new PgException.Argument("2202E", message);
        }
        length = inner.size();
      }
    }
    return array;
  }

  private static boolean isBytea(@Nullable Object v) {
    return v instanceof ByteBuffer || v instanceof byte[];
  }

  /** float8in: digits, or Infinity, inf and NaN in any case; a value past the range fails. */
  static double floatIn(String text) {
    String t = text.trim();
    switch (t.toLowerCase(Locale.ROOT)) {
      case "infinity":
      case "+infinity":
      case "inf":
      case "+inf":
        return Double.POSITIVE_INFINITY;
      case "-infinity":
      case "-inf":
        return Double.NEGATIVE_INFINITY;
      case "nan":
        return Double.NaN;
      default:
        break;
    }
    BigDecimal exact;
    try {
      exact = new BigDecimal(t); // decimal digits only: not Java's 1.5f, 0x1p3 or Infinity
    } catch (NumberFormatException e) {
      throw new PgException.Argument(
          "22P02", "invalid input syntax for type double precision: \"" + text + "\"");
    }
    double d = Double.parseDouble(t);
    if (Double.isInfinite(d) || d == 0 && exact.signum() != 0) {
      throw new PgException.Argument(
          "22003", "\"" + text + "\" is out of range for type double precision");
    }
    return d;
  }

  /** A bytea value's bytes. */
  private static byte[] bytes(Object v) {
    if (v instanceof byte[]) {
      return (byte[]) v;
    }
    ByteBuffer b = ((ByteBuffer) v).duplicate();
    byte[] bytes = new byte[b.remaining()];
    b.get(bytes);
    return bytes;
  }

  /** PostgreSQL's timestamp text: the date and time, and the fraction without trailing zeros. */
  static String timestampText(LocalDateTime t) {
    StringBuilder sb = new StringBuilder(29);
    pad(sb, t.getYear() <= 0 ? 1 - t.getYear() : t.getYear(), 4).append('-');
    pad(sb, t.getMonthValue(), 2).append('-');
    pad(sb, t.getDayOfMonth(), 2).append(' ');
    pad(sb, t.getHour(), 2).append(':');
    pad(sb, t.getMinute(), 2).append(':');
    return fraction(pad(sb, t.getSecond(), 2), t.getNano())
        .append(t.getYear() <= 0 ? " BC" : "")
        .toString();
  }

  /** Appends {@code n} zero-padded to {@code width}, as {@code %0<width>d} does. */
  private static StringBuilder pad(StringBuilder sb, int n, int width) {
    String digits = Integer.toString(Math.abs(n));
    if (n < 0) {
      sb.append('-');
      width--;
    }
    for (int i = digits.length(); i < width; i++) {
      sb.append('0');
    }
    return sb.append(digits);
  }

  /** Appends the microseconds' fraction without trailing zeros, nothing if there is none. */
  private static StringBuilder fraction(StringBuilder sb, int nanos) {
    if (nanos / 1000 != 0) { // microseconds: a nanosecond rest alone must not print a bare "."
      pad(sb.append('.'), nanos / 1000, 6);
      while (sb.charAt(sb.length() - 1) == '0') {
        sb.setLength(sb.length() - 1);
      }
    }
    return sb;
  }

  static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }

  /**
   * A float in PostgreSQL's output format: the shortest digits that round-trip, in plain notation
   * for decimal exponents from -4 up to {@code maxPlainExponent} - 1 and as {@code 1.5e+20}
   * otherwise.
   */
  /** The session's extra_float_digits: above 0 floats are written shortest, as by default. */
  private static int extraFloatDigits() {
    Settings settings = settings();
    String v = settings == null ? null : settings.get("extra_float_digits");
    try {
      return v == null ? 1 : Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return 1;
    }
  }

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
    boolean real = maxPlainExponent == 6;
    int extra = extraFloatDigits();
    BigDecimal b;
    if (extra > 0) {
      b = real ? shortest((float) d) : shortest(d);
    } else {
      // extra_float_digits <= 0: printf's %.*g at FLT_DIG or DBL_DIG plus it, as float8out does
      maxPlainExponent = Math.max(1, maxPlainExponent + extra);
      b =
          (real ? new BigDecimal((float) d) : new BigDecimal(d))
              .round(new java.math.MathContext(maxPlainExponent, RoundingMode.HALF_EVEN));
    }
    b = b.stripTrailingZeros();
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
      // smallint op smallint stays smallint in PostgreSQL, and integer op integer stays integer;
      // both fail on overflow
      if (ctx.smallints.computeIfAbsent(
          e,
          k ->
              QueryParser.inferOid(e.getLeftExpression(), ctx.sources) == 21
                  && QueryParser.inferOid(e.getRightExpression(), ctx.sources) == 21)) {
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
          throw new ArithmeticException("smallint out of range");
        }
        return (int) v;
      }
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
    // real op real is real, computed in double and rounded once: a double holds two floats' sum,
    // difference, product or quotient closely enough that the rounding is float's own
    boolean real = l instanceof Float && r instanceof Float;
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
    v = real ? (float) v : v;
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
    return real ? (Object) (float) v : v;
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
      z = atZone(text(zone));
    }
    if (v instanceof Instant) {
      return LocalDateTime.ofInstant((Instant) v, z);
    }
    LocalDateTime local = asTimestamp(v);
    if (local != null) {
      return instant(local, z);
    }
    throw new IllegalArgumentException("AT TIME ZONE needs a timestamp: " + text(v));
  }

  /**
   * A POSIX zone spec without daylight time, such as '+02', 'UTC+3' or '<+03>-3': its offset in
   * seconds EAST of UTC (the spec counts west), or null for anything else.
   */
  @Nullable private static Integer posixOffset(String name) {
    int i = 0;
    if (name.startsWith("<")) {
      i = name.indexOf('>') + 1;
      if (i == 0) {
        return null;
      }
    } else {
      while (i < name.length() && name.charAt(i) < 128 && Character.isLetter(name.charAt(i))) {
        i++;
      }
      if (i > 0 && i < 3) {
        return null; // a zone name is at least three letters
      }
    }
    boolean east = i < name.length() && name.charAt(i) == '-';
    if (i < name.length() && (name.charAt(i) == '+' || name.charAt(i) == '-')) {
      i++;
    }
    String[] fields = name.substring(i).split(":", -1);
    if (fields.length > 3) {
      return null;
    }
    int seconds = 0;
    for (int f = 0; f < fields.length; f++) {
      String digits = fields[f];
      boolean ok =
          (f == 0 ? digits.length() >= 1 && digits.length() <= 2 : digits.length() == 2)
              && digits.chars().allMatch(c -> c >= '0' && c <= '9');
      if (!ok || (f > 0 && Integer.parseInt(digits) >= 60)) {
        return null;
      }
      seconds += Integer.parseInt(digits) * (f == 0 ? 3600 : f == 1 ? 60 : 1);
    }
    return east ? seconds : -seconds;
  }

  /**
   * A zone as PostgreSQL's AT TIME ZONE reads it, where Java would read it the same: a POSIX
   * offset, UTC, or a zone database name with a region (matched without regard to case).
   * Abbreviations are fixed offsets in PostgreSQL ('CET' has no summer time) and names such as
   * 'Japan' go through its own lookup order, so those are refused rather than read Java's way.
   */
  static java.time.ZoneId atZone(String name) {
    Integer offset = posixOffset(name);
    if (offset != null) {
      if (Math.abs(offset) > 18 * 3600) {
        throw new PgException.Argument("0A000", "Unsupported time zone: " + name);
      }
      return ZoneOffset.ofTotalSeconds(offset);
    }
    if (Set.of("UTC", "GMT", "Z", "UT").contains(name.toUpperCase(Locale.ROOT))) {
      return ZoneOffset.UTC;
    }
    if (name.contains("/")) {
      for (String id : java.time.ZoneId.getAvailableZoneIds()) {
        if (id.equalsIgnoreCase(name)) {
          return java.time.ZoneId.of(id);
        }
      }
      throw new PgException.Argument("22023", "time zone \"" + name + "\" not recognized");
    }
    throw new PgException.Argument(
        "0A000",
        "Unsupported time zone: " + name + " (an abbreviation or a name without a region)");
  }

  /** An INT value, or an integer literal small enough to be int4 in PostgreSQL. */
  private static boolean isInt(Object v, Expression e) {
    e = QueryParser.unwrap(e); // (1) is the int4 literal 1
    if (e instanceof SignedExpression
        && QueryParser.unwrap(((SignedExpression) e).getExpression()) instanceof LongValue) {
      // -2147483648 is an int4 literal too
      BigInteger n =
          ((LongValue) QueryParser.unwrap(((SignedExpression) e).getExpression()))
              .getBigIntegerValue();
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
        && !Arrays.asList("HOUR", "MINUTE", "SECOND", "MILLISECONDS", "MICROSECONDS", "EPOCH")
            .contains(unit)) {
      throw new PgException.Argument("0A000", "Unit \"" + field + "\" not supported for type time");
    }
    if (v instanceof LocalDate
        && Arrays.asList(
                "HOUR",
                "MINUTE",
                "SECOND",
                "MILLISECONDS",
                "MICROSECONDS",
                "TIMEZONE",
                "TIMEZONE_HOUR",
                "TIMEZONE_MINUTE")
            .contains(unit)) {
      throw new PgException.Argument(
          "0A000", "unit \"" + field.toLowerCase(Locale.ROOT) + "\" not supported for type date");
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
        return (long) (t.getYear() <= 0 ? t.getYear() - 1 : t.getYear()); // no year 0: 1 BC is -1
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
      case "MILLISECONDS":
        return BigDecimal.valueOf(t.getSecond() * 1_000_000L + t.getNano() / 1000, 3);
      case "MICROSECONDS":
        return t.getSecond() * 1_000_000L + t.getNano() / 1000;
      case "EPOCH":
        // a date's epoch is whole seconds, as PostgreSQL's numeric result for a date
        return v instanceof LocalDate
            ? BigDecimal.valueOf(t.toEpochSecond(ZoneOffset.UTC))
            : BigDecimal.valueOf(t.toEpochSecond(ZoneOffset.UTC))
                .add(BigDecimal.valueOf(t.getNano() / 1000, 6));
      default:
        throw new PgException.Argument("0A000", "Unsupported EXTRACT field: " + field);
    }
  }

  /** {@code date_trunc(unit, v)}: the timestamp truncated to the start of the unit. */
  // datetime.c's deltatktbl: the units date_trunc reads, matched on their first 10 characters
  private static final Map<String, String> UNITS = new java.util.HashMap<>();

  static {
    String[][] units = {
      {"century", "c", "cent", "centuries", "century"},
      {"day", "d", "day", "days"},
      {"decade", "dec", "decade", "decades", "decs"},
      {"hour", "h", "hour", "hours", "hr", "hrs"},
      {"minute", "m", "min", "mins", "minute", "minutes"},
      {"microseconds", "microsecon", "us", "usec", "usecond", "useconds", "usecs"},
      {"millennium", "mil", "millennia", "millennium", "mils"},
      {"milliseconds", "millisecon", "ms", "msec", "msecond", "mseconds", "msecs"},
      {"month", "mon", "mons", "month", "months"},
      {"quarter", "qtr", "quarter"},
      {"second", "s", "sec", "second", "seconds", "secs"},
      {"timezone", "timezone", "timezone_h", "timezone_m"},
      {"week", "w", "week", "weeks"},
      {"year", "y", "year", "years", "yr", "yrs"}
    };
    for (String[] u : units) {
      for (int i = 1; i < u.length; i++) {
        UNITS.put(u[i], u[0]);
      }
    }
  }

  private static final Set<String> DAY_OR_LONGER =
      Set.of("millennium", "century", "decade", "year", "quarter", "month", "week", "day");

  /** A date_trunc unit as PostgreSQL's DecodeUnits reads it: lower-cased, 10 characters matched. */
  private static String truncUnit(String unit, Object v) {
    StringBuilder lower = new StringBuilder(unit.length());
    unit.chars().forEach(c -> lower.append((char) (c >= 'A' && c <= 'Z' ? c + 32 : c)));
    String low = lower.toString();
    String type = v instanceof Instant ? "timestamp with time zone" : "timestamp without time zone";
    String found = UNITS.get(low.length() > 10 ? low.substring(0, 10) : low);
    if (found == null) {
      throw new PgException.Argument(
          "22023", "unit \"" + low + "\" not recognized for type " + type);
    }
    if (found.equals("timezone")) {
      throw new PgException.Argument(
          "0A000", "unit \"" + low + "\" not supported for type " + type);
    }
    return found;
  }

  @Nullable private static Object dateTrunc(String unit, @Nullable Object v) {
    if (v == null) {
      return null;
    }
    if (!(v instanceof Instant || v instanceof LocalDateTime)) {
      // a date is read as timestamptz in PostgreSQL, and an interval is truncated otherwise
      throw new PgException.Argument("0A000", "Unsupported: date_trunc of " + pgTypeName(v));
    }
    String u = truncUnit(unit, v);
    if (v instanceof Instant) {
      // in the session's zone; from a day up the offset is found again for the truncated time,
      // below a day the original offset is kept, as timestamptz_trunc does
      java.time.ZonedDateTime z = ((Instant) v).atZone(zone());
      LocalDateTime local = truncated(u, z.toLocalDateTime());
      return DAY_OR_LONGER.contains(u) ? instant(local, zone()) : local.toInstant(z.getOffset());
    }
    return truncated(u, (LocalDateTime) v);
  }

  /** timestamp_trunc's fields: the year is astronomical (1 BC is 0), as LocalDateTime's. */
  private static LocalDateTime truncated(String unit, LocalDateTime t) {
    int year = t.getYear();
    switch (unit) {
      case "millennium":
        year =
            year > 0 ? (year + 999) / 1000 * 1000 - 999 : -((999 - (year - 1)) / 1000) * 1000 + 1;
        year = year > 0 ? (year + 99) / 100 * 100 - 99 : -((99 - (year - 1)) / 100) * 100 + 1;
        return LocalDateTime.of(year, 1, 1, 0, 0);
      case "century":
        year = year > 0 ? (year + 99) / 100 * 100 - 99 : -((99 - (year - 1)) / 100) * 100 + 1;
        return LocalDateTime.of(year, 1, 1, 0, 0);
      case "decade":
        year = year > 0 ? year / 10 * 10 : -((8 - (year - 1)) / 10) * 10;
        return LocalDateTime.of(year, 1, 1, 0, 0);
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
      case "milliseconds":
        return t.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      default: // microseconds: a timestamp holds nothing finer
        return t;
    }
  }

  // PostgreSQL's timestamp range: 4714-11-24 BC to 294276 AD
  private static final LocalDateTime MIN_TIMESTAMP = LocalDateTime.of(-4713, 11, 24, 0, 0);
  private static final LocalDateTime END_TIMESTAMP = LocalDateTime.of(294277, 1, 1, 0, 0);

  private static LocalDateTime checkedTimestamp(LocalDateTime t, String message) {
    if (t.isBefore(MIN_TIMESTAMP) || !t.isBefore(END_TIMESTAMP)) {
      throw new PgException.Argument("22008", message);
    }
    return t;
  }

  /** make_timestamp_internal: the fields checked as PostgreSQL checks them; year < 0 is BC. */
  private static LocalDateTime makeTimestamp(List<Object> args, String name) {
    int year = int4(args, 0, name);
    int month = int4(args, 1, name);
    int day = int4(args, 2, name);
    int hour = int4(args, 3, name);
    int minute = int4(args, 4, name);
    double sec = num(args, 5).doubleValue();
    int y = year < 0 ? year + 1 : year; // 1 BC is the year 0
    boolean dateOk =
        year != 0
            && month >= 1
            && month <= 12
            && day >= 1
            && day <= java.time.YearMonth.of(y, month).lengthOfMonth();
    String date = String.format(Locale.ROOT, "%d-%02d-%02d", year, month, day);
    if (!dateOk) {
      throw new PgException.Argument("22008", "date field value out of range: " + date);
    }
    if (y < -4713 || (y == -4713 && month < 11) || y > 5874898 || (y == 5874898 && month >= 6)) {
      throw new PgException.Argument("22008", "date out of range: " + date);
    }
    double usec = Math.rint(sec * 1_000_000);
    String time = String.format(Locale.ROOT, "%d:%02d:%s", hour, minute, cSeconds(sec));
    if (hour < 0
        || hour > 24
        || minute < 0
        || minute >= 60
        || Double.isNaN(sec)
        || usec < 0
        || usec > 60_000_000
        || (hour * 60L + minute) * 60_000_000L + (long) usec > 86_400_000_000L) {
      throw new PgException.Argument("22008", "time field value out of range: " + time);
    }
    LocalDateTime t =
        LocalDate.of(y, month, day)
            .atStartOfDay()
            .plusNanos(((hour * 60L + minute) * 60_000_000L + (long) usec) * 1000);
    return checkedTimestamp(t, "timestamp out of range: " + date + " " + time);
  }

  /** printf's %02g, as PostgreSQL's messages show the seconds. */
  private static String cSeconds(double sec) {
    String g = String.format(Locale.ROOT, "%.6g", sec);
    if (g.contains("e")) {
      return g;
    }
    g = g.contains(".") ? g.replaceAll("0+$", "").replaceAll("\\.$", "") : g;
    return g.length() < 2 ? "0" + g : g;
  }

  /** timestamp_bin and timestamptz_bin: the start of source's stride-wide bin from origin. */
  private static Object dateBin(Object stride, Object source, Object origin) {
    // quoted literals take the type of the typed arguments, as PostgreSQL resolves them
    stride = stride instanceof String ? Interval.parse((String) stride) : stride;
    if (source instanceof String && !(origin instanceof String)) {
      source =
          java.util.Objects.requireNonNull(
              cast(origin instanceof Instant ? "timestamptz" : "timestamp", source));
    }
    if (origin instanceof String && !(source instanceof String)) {
      origin =
          java.util.Objects.requireNonNull(
              cast(source instanceof Instant ? "timestamptz" : "timestamp", origin));
    }
    if (!(stride instanceof Interval)
        || !(source instanceof Instant || source instanceof LocalDateTime)
        || source.getClass() != origin.getClass()) {
      throw new PgException.Argument("0A000", "Unsupported: date_bin of these types");
    }
    Interval i = (Interval) stride;
    if (i.months != 0) {
      throw new PgException.Argument(
          "0A000", "timestamps cannot be binned into intervals containing months or years");
    }
    long strideMicros;
    try {
      strideMicros = Math.addExact(Math.multiplyExact(i.days, 86_400_000_000L), i.micros);
    } catch (ArithmeticException e) {
      throw new PgException.Argument("22008", "interval out of range");
    }
    if (strideMicros <= 0) {
      throw new PgException.Argument("22008", "stride must be greater than zero");
    }
    long diff;
    try {
      diff = Math.subtractExact(epochMicros(source), epochMicros(origin));
    } catch (ArithmeticException e) {
      throw new PgException.Argument("22008", "interval out of range");
    }
    long modulo = diff % strideMicros;
    long delta = diff - modulo - (modulo < 0 ? strideMicros : 0); // toward -infinity
    if (source instanceof Instant) {
      Instant out = ((Instant) origin).plus(delta, java.time.temporal.ChronoUnit.MICROS);
      checkedTimestamp(LocalDateTime.ofInstant(out, ZoneOffset.UTC), "timestamp out of range");
      return out;
    }
    return checkedTimestamp(
        ((LocalDateTime) origin).plus(delta, java.time.temporal.ChronoUnit.MICROS),
        "timestamp out of range");
  }

  private static long epochMicros(Object t) {
    Instant i = t instanceof Instant ? (Instant) t : ((LocalDateTime) t).toInstant(ZoneOffset.UTC);
    return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1000);
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
      boolean bpchar =
          c.getSwitchExpression() != null
              && bpchar(c.getSwitchExpression(), when.getWhenExpression(), ctx);
      boolean matches =
          c.getSwitchExpression() == null
              ? isTrue(condition)
              : subject != null
                  && condition != null
                  && (bpchar
                      ? compare(rtrim(subject), rtrim(condition)) == 0
                      : compare(subject, condition) == 0);
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
      String name = functionName((Function) e);
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

  // PostgreSQL 16's integer input: decimal, or 0x, 0o, 0b digits; single underscores between
  // digits and after the prefix; whitespace around
  private static final Pattern INTEGER_INPUT =
      Pattern.compile(
          "[ \\t\\n\\r\\f\\u000B]*([+-]?)(?:0[xX]((?:_?[0-9a-fA-F])+)|0[oO]((?:_?[0-7])+)"
              + "|0[bB]((?:_?[01])+)|([0-9](?:_?[0-9])*))[ \\t\\n\\r\\f\\u000B]*");

  /** {@code s} read as int2in, int4in and int8in read it, before the range check; null if not. */
  @Nullable static BigInteger integerInput(String s) {
    java.util.regex.Matcher m = INTEGER_INPUT.matcher(s);
    if (!m.matches()) {
      return null;
    }
    int group = m.group(2) != null ? 2 : m.group(3) != null ? 3 : m.group(4) != null ? 4 : 5;
    int radix = group == 2 ? 16 : group == 3 ? 8 : group == 4 ? 2 : 10;
    BigInteger v = new BigInteger(m.group(group).replace("_", ""), radix);
    return m.group(1).equals("-") ? v.negate() : v;
  }

  private static final Pattern TYPE_LENGTH = Pattern.compile("\\((\\d+)\\)");
  private static final Pattern TRAILING_SPACES = Pattern.compile(" +$");
  private static final Pattern BPCHAR_PEER_CAST =
      Pattern.compile("(pg_catalog\\.)?(varchar|character varying|char|character|bpchar)\\b");

  /**
   * Integer input as PostgreSQL reads it: digits with a sign, surrounding spaces allowed; '1.5' and
   * '1e3' are 22P02, not rounded (a number, as opposed to text, is rounded by the caller).
   */
  private static BigDecimal integerText(String s, String type) {
    BigInteger v = integerInput(s);
    if (v == null) {
      throw new PgException.Argument(
          "22P02", "invalid input syntax for type " + type + ": \"" + s + "\"");
    }
    return new BigDecimal(v);
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

  static final java.util.Set<String> NUMERIC_FUNCTIONS =
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
      } else if ((name.equals("POWER") || name.equals("POW"))
          && args.size() == 2
          && args.get(1 - i) instanceof BigDecimal) {
        type = "numeric"; // power(numeric, '3') is power(numeric, numeric)
      }
      args.set(i, cast(type, args.get(i)));
    }
  }

  /**
   * An aclitem, grantee=privileges/grantor as relacl holds it, checked as PostgreSQL 16's aclitemin
   * checks it; without a grantor, the bootstrap superuser is.
   */
  static String aclitem(String s) {
    String text = s.trim();
    int eq = text.indexOf('=');
    if (eq < 0) {
      throw new PgException.Argument(
          "22P02", "unrecognized key word: \"" + text.split("[\\s/]", 2)[0] + "\"");
    }
    int slash = text.indexOf('/', eq);
    String privileges = text.substring(eq + 1, slash < 0 ? text.length() : slash);
    if (!privileges.matches("([arwdDxtXUCTcsA]\\*?)*")) {
      throw new PgException.Argument(
          "22P02", "invalid mode character: must be one of \"arwdDxtXUCTcsA\"");
    }
    if (slash >= 0 && text.substring(slash + 1).trim().isEmpty()) {
      throw new PgException.Argument("22P02", "a name must follow the \"/\" sign");
    }
    return slash < 0 ? text + "/" + Catalog.OWNER : text;
  }

  private static final Pattern PRECISION = Pattern.compile("\\s*\\((\\d+)\\)");
  private static final Pattern DIGITS = Pattern.compile("\\d+");

  @Nullable static Object cast(String type, @Nullable Object v) {
    if (v == null) {
      return null;
    }
    String t = type.toLowerCase(Locale.ROOT).trim();
    t = t.startsWith("pg_catalog.") ? t.substring("pg_catalog.".length()) : t;
    if (t.startsWith("time")) {
      // timestamp(p), timestamptz(p), time(p): the fraction is rounded to p digits
      java.util.regex.Matcher precision = PRECISION.matcher(t);
      if (precision.find()) {
        Object exact = cast(precision.replaceFirst("").trim(), v);
        return exact == null ? null : roundedTo(exact, Integer.parseInt(precision.group(1)));
      }
    }
    if (v instanceof Catalog.Reg && !(t.contains("char") || t.equals("text") || t.equals("name"))) {
      v = ((Catalog.Reg) v).oid; // to oid or a number; to text it is the name
    }
    String s = text(v);
    try {
      if (t.equals("oid")
          && (v instanceof Number || DIGITS.matcher(s).matches() || s.trim().startsWith("-"))) {
        // an integer's bits (-1 is 4294967295), a bigint in 0..4294967295, text in
        // -2147483648..4294967295
        // ponytail: a bigint within integer's range is taken as an integer, so (-1)::int8::oid
        // wraps where PostgreSQL fails; the value carries no int4/int8 distinction here
        long n;
        try {
          n = v instanceof Number ? ((Number) v).longValue() : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
          throw new PgException.Argument(
              "22P02", "invalid input syntax for type oid: \"" + s + "\"");
        }
        if (n < Integer.MIN_VALUE || n > 0xFFFFFFFFL) {
          throw new ArithmeticException(
              v instanceof Number
                  ? "OID out of range"
                  : "value \"" + s + "\" is out of range for type oid");
        }
        return n < 0 ? n + 0x100000000L : n;
      }
      if (t.equals("oid") || t.startsWith("reg")) {
        // regclass, regtype, regnamespace: an OID given as a number stays numeric
        return v instanceof Number
            ? ((Number) v).longValue()
            : DIGITS.matcher(s).matches() ? Long.parseLong(s) : v;
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
            : ByteBuffer.wrap(QueryParser.bytea(s));
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
        double d = v instanceof Number ? ((Number) v).doubleValue() : floatIn(s);
        if (v instanceof BigDecimal
            && (Double.isInfinite(d) || d == 0 && ((BigDecimal) v).signum() != 0)) {
          throw new PgException.Argument(
              "22003", "\"" + s + "\" is out of range for type double precision");
        }
        boolean real =
            t.equals("real")
                || t.equals("float4")
                || (t.startsWith("float(") && QueryParser.typeArguments(t)[0] <= 24);
        return real ? (Object) real(d) : d;
      }
      if (t.equals("\"char\"") && v instanceof String) {
        // charin: \ooo is that byte, anything else its first byte; charout writes a byte past
        // ASCII as \ooo, which is how it is kept here
        int b =
            s.matches("\\\\[0-3][0-7][0-7]")
                ? Integer.parseInt(s.substring(1), 8)
                : s.isEmpty() ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)[0] & 0xff;
        return b == 0 ? "" : b < 0x80 ? String.valueOf((char) b) : String.format("\\%03o", b);
      }
      if (t.equals("\"char\"") && v instanceof Number) {
        // an integer to "char" is the byte of that code; past ASCII it is not text in UTF8
        long code = ((Number) v).longValue();
        if (code < 0 || code > 127) {
          throw new PgException.Argument("0A000", "Unsupported cast: " + code + " to \"char\"");
        }
        return code == 0 ? "" : String.valueOf((char) code);
      }
      if (t.equals("aclitem")) {
        return aclitem(s);
      }
      if (t.equals("name")) {
        // the text in at most 63 bytes, cut at a character boundary, without a notice
        int end = 0;
        for (int bytes = 0; end < s.length(); ) {
          int c = s.codePointAt(end);
          bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4; // its UTF-8 length
          if (bytes > 63) {
            break;
          }
          end += Character.charCount(c);
        }
        return s.substring(0, end);
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
        if (v instanceof Number
            && !(v instanceof Integer || v instanceof Long || v instanceof Short)) {
          // PostgreSQL casts only integer to boolean (not numeric 1, not real 1)
          throw new PgException.Argument(
              "42846", "cannot cast type " + pgTypeName(v) + " to boolean");
        }
        return v instanceof Boolean
            ? v
            : v instanceof Number ? ((Number) v).longValue() != 0 : parseBoolean(s);
      }
      if (t.equals("date")) {
        return v instanceof LocalDate
            ? v
            : v instanceof LocalDateTime
                ? ((LocalDateTime) v).toLocalDate()
                : v instanceof Instant ? ((Instant) v).atZone(zone()).toLocalDate() : date(s);
      }
      if (t.equals("time")) {
        return v instanceof LocalTime ? v : time(s);
      }
      if (t.startsWith("time with time zone") || t.equals("timetz")) {
        // a time with an offset has no type here; "with time zone" below would read it as a
        // timestamptz and convert it to UTC
        throw new PgException.Argument("0A000", "Unsupported cast type: time with time zone");
      }
      if (t.equals("timestamptz") || t.contains("with time zone")) {
        return v instanceof Instant
            ? v
            : v instanceof LocalDateTime
                ? instant((LocalDateTime) v, zone())
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
   * A number rounded to an integer as PostgreSQL casts it, failing (ArithmeticException) when it is
   * beyond bigint: a real or double precision rounds half to even (rint, so -2.5 is -2) and
   * converts exactly, a numeric rounds half away from zero (2.5 is 3).
   */
  static long rounded(Number n) {
    if (n instanceof Double || n instanceof Float) {
      double d = Math.rint(n.doubleValue());
      if (!Double.isFinite(d)) {
        throw new ArithmeticException("out of range"); // NaN and the infinities
      }
      return new BigDecimal(d).longValueExact(); // exact: not through the shortest decimal text
    }
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
      Object outside =
          group instanceof Operators.Aggregate.EmptyGroup
              ? ((Operators.Aggregate.EmptyGroup) group).outside
              : group.isEmpty() ? null : group.get(0).get(Operators.GROUPING_KEY);
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
    if (name.equals("PG_TYPEOF")
        && f.getParameters() != null
        && f.getParameters().size() == 1
        && !(QueryParser.unwrap(f.getParameters().get(0)) instanceof StringValue)
        && ctx.catalog != null) {
      // the argument's declared type, as planning infers it; a quoted literal's is unknown, which
      // the frontend does not name, so it falls through to fail as unsupported
      int oid = QueryParser.inferOid(f.getParameters().get(0), ctx.sources);
      if (oid == 0) {
        // the value alone cannot tell, and a NULL would give NULL: refused rather than guessed
        throw new PgException.Argument(
            "0A000", "Unsupported: pg_typeof of an expression whose type is not inferred");
      }
      return ctx.catalog.reg("regtype", oid);
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
    if (SUBSTRING_SEARCHES.contains(name)) {
      checkDeterministic(
          "substring searches",
          ctx,
          f.getParameters() != null
              ? f.getParameters()
              : f.getNamedParameters() != null
                  ? f.getNamedParameters().getExpressions()
                  : Collections.emptyList());
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
    if (GATED && !SYNTAX.contains(name) && !Builtins.accepts(name, args.size())) {
      // only what pg_proc lists runs (Builtins)
      throw new PgException.Argument("0A000", "Unsupported function: " + f);
    }
    if ((textual || name.equals("STRING_AGG"))
        && !name.endsWith("LENGTH")
        && args.stream().anyMatch(Evaluator::isBytea)) {
      // the text functions but length would read bytea as its text; their bytea forms are not here
      throw new PgException.Argument("0A000", "Unsupported function: " + f + " on bytea");
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
            sb.append(outputText(a));
          }
        }
        return sb.toString();
      case "QUOTE_IDENT":
        return arg(args, 0) == null ? null : quoteIdent(text(args.get(0)));
      case "QUOTE_LITERAL":
        return arg(args, 0) == null ? null : quoteLiteral(text(args.get(0)));
      case "FORMAT":
        return arg(args, 0) == null ? null : format(text(args.get(0)), args);
      case "CONCAT_WS":
        if (arg(args, 0) == null) {
          return null;
        }
        StringJoiner joined = new StringJoiner(text(args.get(0)));
        for (Object a : args.subList(1, args.size())) {
          if (a != null) {
            joined.add(outputText(a));
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
        return Json.buildObjectText(args);
      case "JSONB_BUILD_OBJECT":
        return new Json.Value(Json.buildObject(args), true, null);
      case "JSON_BUILD_ARRAY":
        return Json.buildArrayText(args, false);
      case "JSONB_BUILD_ARRAY":
        return new Json.Value(Json.buildArray(args), true, null);
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
          return settings.set(str(args, 0), str(args, 1), isTrue(arg(args, 2)));
        }
      case "STATEMENT_TIMESTAMP":
        {
          Statement s = STATEMENT.get();
          return s == null ? clock() : s.start;
        }
      case "CLOCK_TIMESTAMP":
        return clock(); // the one clock that moves within a statement
      case "VERSION":
        return "PostgreSQL 16.0 (ScalarDB frontend)";
      case "STRING_TO_ARRAY":
        {
          // not strict: a NULL delimiter splits into characters, a NULL null_string matches none
          if (arg(args, 0) == null) {
            return null;
          }
          String s = str(args, 0);
          List<Object> fields = new ArrayList<>();
          if (s.isEmpty()) {
            return fields;
          }
          String delimiter = arg(args, 1) == null ? null : str(args, 1);
          // counted before splitting: a field is an object here, far more than its characters
          long count = 1;
          if (delimiter == null) {
            count = s.codePointCount(0, s.length());
          } else if (!delimiter.isEmpty()) {
            for (int at = s.indexOf(delimiter);
                at >= 0;
                at = s.indexOf(delimiter, at + delimiter.length())) {
              count++;
            }
          }
          if (count > QueryParser.MAX_SERIES_ROWS) {
            throw new PgException.Argument(
                "54000",
                "string_to_array makes more than " + QueryParser.MAX_SERIES_ROWS + " fields");
          }
          if (delimiter == null) {
            s.codePoints().forEach(c -> fields.add(new String(Character.toChars(c))));
          } else if (delimiter.isEmpty()) {
            fields.add(s);
          } else {
            fields.addAll(Arrays.asList(s.split(Pattern.quote(delimiter), -1)));
          }
          if (args.size() > 2 && arg(args, 2) != null) {
            String nullString = str(args, 2);
            fields.replaceAll(x -> nullString.equals(x) ? null : x);
          }
          return fields;
        }
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
        return caseMapped(str(args, 0), true);
      case "LOWER":
        return caseMapped(str(args, 0), false);
      case "GEN_RANDOM_UUID":
        return java.util.UUID.randomUUID().toString();
      case "CURRENT_TIMESTAMP":
      case "LOCALTIMESTAMP":
      case "CURRENT_TIME":
      case "LOCALTIME":
        return roundedTo(now(name), ((Number) args.get(0)).intValue()); // the precision form
      case "TO_JSON":
        return args.get(0) instanceof Json.Value && !((Json.Value) args.get(0)).binary
            ? args.get(0)
            : Json.cast(Json.jsonText(args.get(0)), false);
      case "TO_JSONB":
        return new Json.Value(Json.toNode(args.get(0)), true, null);
      case "ROW_TO_JSON":
        {
          // a record's fields as a json object; pretty puts each after the first on a line
          if (arg(args, 0) == null) {
            return null;
          }
          if (!(args.get(0) instanceof Record)) {
            throw new PgException.Argument(
                "0A000", "Unsupported: row_to_json of a " + pgTypeName(args.get(0)));
          }
          boolean pretty = args.size() > 1 && Boolean.TRUE.equals(args.get(1));
          return Json.cast(Json.recordText((Record) args.get(0), pretty ? ",\n " : ","), false);
        }
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
          boolean binary = name.startsWith("JSONB");
          // text (a literal, or a json column's stored text) is read as the function's type
          Json.Value v =
              args.get(0) instanceof String
                  ? Json.cast((String) args.get(0), binary)
                  : Json.of(args.get(0));
          List<Object> path = args.subList(1, args.size());
          return Json.extracted(v, Json.path(v.node, path), path, binary, name.endsWith("_TEXT"));
        }
      case "JSONB_SET":
      case "JSONB_INSERT":
        {
          Json.Value target = jsonb(args.get(0));
          List<?> path = elements(args.get(1));
          boolean flag = args.size() > 3 ? (Boolean) args.get(3) : name.equals("JSONB_SET");
          if (path.stream().anyMatch(e -> e instanceof List)) {
            throw new PgException.Argument("2202E", "wrong number of array subscripts");
          }
          if (!target.node.isContainerNode()) {
            throw new PgException.Argument("22023", "cannot set path in scalar");
          }
          if (path.isEmpty() || (name.equals("JSONB_SET") && target.node.isEmpty() && !flag)) {
            return target;
          }
          int op =
              name.equals("JSONB_SET")
                  ? (flag ? Json.PATH_CREATE : Json.PATH_REPLACE)
                  : (flag ? Json.PATH_INSERT_AFTER : Json.PATH_INSERT_BEFORE);
          return new Json.Value(
              Json.setPath(target.node, path, 0, jsonb(args.get(2)).node, op), true, null);
        }
      case "JSONB_STRIP_NULLS":
        return new Json.Value(Json.stripNulls(jsonb(args.get(0)).node), true, null);
      case "JSON_ARRAY_ELEMENTS":
      case "JSONB_ARRAY_ELEMENTS":
      case "JSON_ARRAY_ELEMENTS_TEXT":
      case "JSONB_ARRAY_ELEMENTS_TEXT":
        return Json.elements(
            args.get(0) instanceof String
                ? Json.cast((String) args.get(0), name.startsWith("JSONB"))
                : Json.of(args.get(0)),
            name.startsWith("JSONB"),
            name.endsWith("_TEXT"));
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
          if (s.isEmpty() && bitLiterals(f)) {
            return 0L; // bitposition: nothing is found in an empty bit string, not even B''
          }
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
          if (args.get(0) instanceof Double || args.get(0) instanceof Float) {
            if (args.size() > 1) {
              throw new PgException.Argument(
                  "42883", "function round(double precision, integer) does not exist");
            }
            return Math.rint(num(args, 0).doubleValue()); // dround: half to even, -0 kept
          }
          BigDecimal rounded =
              new BigDecimal(num(args, 0).toString()).setScale(scale, RoundingMode.HALF_UP);
          return isIntegral(args.get(0))
              ? (Object) rounded.longValue()
              : (Object) checkedNumeric(rounded); // round(5.5e131071, -131072) overflows
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
        return power(args);
      case "SQRT":
        if (num(args, 0) instanceof BigDecimal) {
          // ponytail: numeric sqrt has PostgreSQL's own result scale (sqrt_var); refused until then
          throw new PgException.Argument("0A000", "Unsupported function: sqrt(numeric)");
        }
        if (num(args, 0).doubleValue() < 0) {
          throw new PgException.Argument("2201F", "cannot take square root of a negative number");
        }
        return Math.sqrt(num(args, 0).doubleValue());
      case "LN":
      case "LOG10":
        return num(args, 0) instanceof BigDecimal
            ? (name.equals("LN")
                ? numericLn((BigDecimal) args.get(0))
                : numericLog(BigDecimal.TEN, (BigDecimal) args.get(0)))
            : (Object) floatLog(num(args, 0).doubleValue(), name.equals("LOG10"));
      case "LOG":
        if (args.size() == 1) {
          return num(args, 0) instanceof BigDecimal
              ? numericLog(BigDecimal.TEN, (BigDecimal) args.get(0))
              : (Object) floatLog(num(args, 0).doubleValue(), true);
        }
        return numericLog(numericArgument(args, 0, name), numericArgument(args, 1, name));
      case "EXP":
        return num(args, 0) instanceof BigDecimal
            ? numericExp((BigDecimal) args.get(0))
            : (Object) floatExp(num(args, 0).doubleValue());
      case "TRUNC":
        if (args.size() == 1 && !(num(args, 0) instanceof BigDecimal)) {
          double d = num(args, 0).doubleValue();
          return d >= 0 ? Math.floor(d) : -Math.floor(-d); // dtrunc, -0 for -0.5
        }
        {
          BigDecimal x = numericArgument(args, 0, name);
          // PostgreSQL clamps the scale, and gives no negative display scale
          int scale = args.size() > 1 ? Math.max(-131072, Math.min(int4(args, 1, name), 16383)) : 0;
          BigDecimal truncated = x.setScale(scale, RoundingMode.DOWN);
          return scale < 0 ? truncated.setScale(0) : truncated;
        }
      case "WIDTH_BUCKET":
        if (args.size() == 2) {
          return widthBucket(args.get(0), args.get(1));
        }
        {
          List<Object> operands = args.subList(0, 3);
          int count = int4(args, 3, name);
          // integers resolve to the float8 form, as in PostgreSQL; numerics alone to numeric
          return operands.stream().anyMatch(a -> a instanceof Double || a instanceof Float)
                  || operands.stream().allMatch(Evaluator::isIntegral)
              ? (Object)
                  widthBucket(
                      num(args, 0).doubleValue(),
                      num(args, 1).doubleValue(),
                      num(args, 2).doubleValue(),
                      count)
              : (Object)
                  widthBucket(
                      toDecimal(num(args, 0)),
                      toDecimal(num(args, 1)),
                      toDecimal(num(args, 2)),
                      count);
        }
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
      case "SPLIT_PART":
        {
          String s = str(args, 0);
          String delimiter = str(args, 1);
          int n = int4(args, 2, name);
          if (n == 0) {
            throw new PgException.Argument("22023", "field position must not be zero");
          }
          // an empty delimiter leaves the whole string as the one field; n < 0 counts from the end
          List<String> fields =
              delimiter.isEmpty()
                  ? Collections.singletonList(s)
                  : Arrays.asList(s.split(Pattern.quote(delimiter), -1));
          int i = n > 0 ? n - 1 : fields.size() + n;
          return i >= 0 && i < fields.size() ? fields.get(i) : "";
        }
      case "REPEAT":
        {
          String s = str(args, 0);
          int n = int4(args, 1, name);
          if (n <= 0 || s.isEmpty()) {
            return "";
          }
          if ((long) n * s.length() > 10_000_000) { // pad's cap: one JVM serves every session
            throw new PgException.Argument("54000", "requested length too large");
          }
          return s.repeat(n);
        }
      case "CHR":
        {
          int c = int4(args, 0, name);
          if (c < 0) {
            throw new PgException.Argument("22023", "character number must be positive");
          }
          if (c == 0) {
            throw new PgException.Argument("54000", "null character not permitted");
          }
          if (c > Character.MAX_CODE_POINT) {
            throw new PgException.Argument(
                "54000", "requested character too large for encoding: " + c);
          }
          if (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE) {
            throw new PgException.Argument(
                "54000", "requested character not valid for encoding: " + c);
          }
          return new String(Character.toChars(c));
        }
      case "OVERLAY":
        {
          // PostgreSQL's text_overlay: substring(s, 1, start - 1) || placing || substring(s,
          // start + count), count defaulting to placing's length
          String s = str(args, 0);
          String placing = str(args, 1);
          int start = int4(args, 2, name);
          int count =
              args.size() > 3 ? int4(args, 3, name) : placing.codePointCount(0, placing.length());
          if (start <= 0) {
            throw new PgException.Argument("22011", "negative substring length not allowed");
          }
          long rest = (long) start + count;
          if (rest != (int) rest) {
            throw new PgException.Argument("22003", "integer out of range");
          }
          long n = s.codePointCount(0, s.length());
          return characters(s, 0, start - 1L) + placing + characters(s, Math.max(rest, 1) - 1, n);
        }
      case "LPAD":
      case "RPAD":
        return pad(
            str(args, 0),
            int4(args, 1, name),
            args.size() > 2 ? str(args, 2) : " ",
            name.equals("LPAD"));
      case "RANDOM":
        return RANDOM.nextDouble(); // [0, 1), per call
      case "DATE_PART":
        {
          Object part = extract(str(args, 0), arg(args, 1));
          return part == null ? null : (Object) ((Number) part).doubleValue(); // float8
        }
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
      case "GENERATE_SERIES":
        {
          // integer series in the select list, or over another FROM item's columns
          List<Object> series = new ArrayList<>();
          if (arg(args, 0) == null
              || arg(args, 1) == null
              || (args.size() > 2 && arg(args, 2) == null)) {
            return series;
          }
          if (args.stream().anyMatch(a -> a instanceof BigDecimal)) {
            // generate_series(numeric, numeric[, numeric]): a numeric series
            BigDecimal start = decimal(text(args.get(0)));
            BigDecimal stop = decimal(text(args.get(1)));
            BigDecimal step = args.size() > 2 ? decimal(text(args.get(2))) : BigDecimal.ONE;
            if (step.signum() == 0) {
              throw new PgException.Argument("22023", "step size cannot equal zero");
            }
            for (BigDecimal v = start;
                step.signum() > 0 ? v.compareTo(stop) <= 0 : v.compareTo(stop) >= 0;
                v = v.add(step)) {
              series.add(v);
              if (series.size() > QueryParser.MAX_SERIES_ROWS) {
                throw new PgException.Argument("54000", "generate_series produces too many rows");
              }
            }
            return series;
          }
          long start = num(args, 0).longValue();
          long stop = num(args, 1).longValue();
          long step = args.size() > 2 ? num(args, 2).longValue() : 1;
          if (step == 0) {
            throw new PgException.Argument("22023", "step size cannot equal zero");
          }
          for (long v = start; step > 0 ? v <= stop : v >= stop; ) {
            series.add(v);
            if (series.size() > QueryParser.MAX_SERIES_ROWS) {
              throw new PgException.Argument("54000", "generate_series produces too many rows");
            }
            try {
              v = Math.addExact(v, step);
            } catch (ArithmeticException pastTheEnd) {
              break; // the next value is beyond bigint, so beyond stop
            }
          }
          return series;
        }
      case "GENERATE_SUBSCRIPTS":
        {
          List<Object> subscripts = new ArrayList<>();
          int count = arg(args, 0) == null ? 0 : elements(args.get(0)).size();
          long lower = lowerBound(arg(args, 0));
          for (long i = lower; i < lower + count; i++) {
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
      case "DATE_BIN":
        return args.contains(null) ? null : dateBin(args.get(0), args.get(1), args.get(2));
      case "MAKE_TIMESTAMP":
      case "MAKE_TIMESTAMPTZ":
        {
          if (args.contains(null)) {
            return null;
          }
          if (args.size() > 6) {
            // ponytail: the zone argument reads numeric offsets as ISO 8601 (parse_sane_timezone),
            // unlike AT TIME ZONE here; refused until zones are read as PostgreSQL reads them
            throw new PgException.Argument("0A000", "Unsupported function: " + f);
          }
          LocalDateTime t = makeTimestamp(args, name);
          return name.equals("MAKE_TIMESTAMP") ? t : instant(t, zone());
        }
      case "DATE_TRUNC":
        if (args.size() > 2) {
          // date_trunc(field, timestamptz, zone) truncates in that zone, which is not done here
          throw new PgException.Argument("0A000", "Unsupported function: " + f);
        }
        return dateTrunc(str(args, 0), arg(args, 1));
      case "TO_CHAR":
        return arg(args, 0) == null || arg(args, 1) == null
            ? null
            : ToChar.toChar(args.get(0), str(args, 1), zone());
      case "PG_BLOCKING_PIDS": // ScalarDB's transactions are optimistic: no session waits on a lock
        return arg(args, 0) == null ? null : Collections.emptyList();
      default:
        throw new PgException.Argument("0A000", "Unsupported function: " + f);
    }
  }

  private static final Object NOT_A_CATALOG_FUNCTION = new Object();

  /** pg_size_pretty, as PostgreSQL 16's dbsize.c: bytes below 10 kB, then halves rounded away. */
  static String sizePretty(long size) {
    String[] units = {"bytes", "kB", "MB", "GB", "TB", "PB"};
    for (int i = 0; ; i++) {
      long limit = i == 0 ? 10 * 1024 : 20 * 1024 - 1;
      if (i == units.length - 1 || Math.abs(size) < limit) {
        return (i == 0 ? size : (size + (size < 0 ? -1 : 1)) / 2) + " " + units[i];
      }
      size /= 1L << (i == 0 ? 9 : 10); // the next unit keeps one more bit, for the rounding
    }
  }

  // PostgreSQL's encodings by number, as pg_encoding_to_char names them
  private static final List<String> ENCODINGS =
      Arrays.asList(
          "SQL_ASCII",
          "EUC_JP",
          "EUC_CN",
          "EUC_KR",
          "EUC_TW",
          "EUC_JIS_2004",
          "UTF8",
          "MULE_INTERNAL",
          "LATIN1",
          "LATIN2",
          "LATIN3",
          "LATIN4",
          "LATIN5",
          "LATIN6",
          "LATIN7",
          "LATIN8",
          "LATIN9",
          "LATIN10",
          "WIN1256",
          "WIN1258",
          "WIN866",
          "WIN874",
          "KOI8R",
          "WIN1251",
          "WIN1252",
          "ISO_8859_5",
          "ISO_8859_6",
          "ISO_8859_7",
          "ISO_8859_8",
          "WIN1250",
          "WIN1253",
          "WIN1254",
          "WIN1255",
          "WIN1257",
          "KOI8U",
          "SJIS",
          "BIG5",
          "GBK",
          "UHC",
          "GB18030",
          "JOHAB",
          "SHIFT_JIS_2004");

  // off only for difftest/catalog/builtins.py, which finds what runs to write Builtins' list
  private static final boolean GATED = !Boolean.getBoolean("scalardb.frontend.builtins.ungated");

  // calls that are SQL syntax rather than functions pg_proc lists, and _pg_expandarray, which is
  // information_schema's
  static final Set<String> SYNTAX =
      new java.util.HashSet<>(
          Arrays.asList(
              "COALESCE",
              "NULLIF",
              "GREATEST",
              "LEAST",
              "POSITION",
              "TRIM",
              "CURRENT_TIME",
              "CURRENT_TIMESTAMP",
              "LOCALTIME",
              "LOCALTIMESTAMP",
              "_PG_EXPANDARRAY"));

  // the key an outer join adds as qualifier.NULL_EXTENDED to a row where that source found no row:
  // a whole-row reference to it is NULL, not a record of nulls
  static final String NULL_EXTENDED = "\u0000null-extended";

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
  @Nullable private static Object catalogFunction(String name, List<Object> rawArgs, Context ctx) {
    // an OID argument may be written 't'::regclass, as psql and drivers do
    List<Object> args = new ArrayList<>(rawArgs);
    args.replaceAll(a -> a instanceof Catalog.Reg ? (Object) ((Catalog.Reg) a).oid : a);
    try {
      switch (name) {
        case "PG_GET_USERBYID":
          return ctx.catalog == null || arg(args, 0) == null
              ? null
              : ctx.catalog.roleName(num(args, 0).longValue());
        case "PG_ENCODING_TO_CHAR":
          {
            if (arg(args, 0) == null) {
              return null;
            }
            long id = num(args, 0).longValue();
            return id >= 0 && id < ENCODINGS.size() ? ENCODINGS.get((int) id) : "";
          }
        case "PG_TABLE_IS_VISIBLE":
          return ctx.catalog == null || !(arg(args, 0) instanceof Number)
              ? null
              : ctx.catalog.visible(num(args, 0).longValue());
        case "PG_RELATION_IS_PUBLISHABLE":
          return ctx.catalog == null || arg(args, 0) == null
              ? null
              : ctx.catalog.publishable(num(args, 0).longValue());
        case "ARRAY_LENGTH":
        case "ARRAY_UPPER":
        case "ARRAY_LOWER":
          {
            // dimension d of the array (nested lists, 1-based); null for an empty array or a
            // dimension it does not have, as PostgreSQL has none to report
            Object a = arg(args, 0);
            Object d = args.size() > 1 ? arg(args, 1) : 1L;
            if (a == null || !(d instanceof Number) || ((Number) d).longValue() < 1) {
              return null;
            }
            long lower = lowerBound(a);
            List<?> items = elements(a);
            for (long k = ((Number) d).longValue(); k > 1; k--) {
              if (items.isEmpty() || !(items.get(0) instanceof List)) {
                return null;
              }
              items = (List<?>) items.get(0);
              lower = 1;
            }
            return items.isEmpty()
                ? null
                : (Object)
                    (name.equals("ARRAY_LENGTH")
                        ? (long) items.size()
                        : name.equals("ARRAY_LOWER") ? lower : lower + items.size() - 1);
          }
        case "ARRAY_TO_STRING":
          {
            // the elements of every dimension in order; a NULL one is skipped, or written as the
            // third argument when that is not NULL; a NULL separator gives NULL
            if (!(arg(args, 0) instanceof List) || arg(args, 1) == null) {
              return null;
            }
            String nullText = args.size() > 2 && arg(args, 2) != null ? str(args, 2) : null;
            List<Object> flat = new ArrayList<>();
            flatten((List<?>) args.get(0), flat);
            StringJoiner joined = new StringJoiner(str(args, 1));
            for (Object v : flat) {
              if (v != null || nullText != null) {
                joined.add(v == null ? nullText : text(v));
              }
            }
            return joined.toString();
          }
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
            return arg(args, 0) == null || ctx.catalog == null
                ? null
                : ctx.catalog.reg("regtype", Catalog.typeOid(str(args, 0)));
          } catch (IllegalArgumentException unknownType) {
            return null;
          }
        case "TO_REGCLASS":
          try {
            return arg(args, 0) == null || ctx.catalog == null
                ? null
                : ctx.catalog.reg("regclass", ctx.catalog.relationOid(str(args, 0)));
          } catch (IllegalArgumentException unknownRelation) {
            return null;
          }
        case "CURRENT_SCHEMAS":
          {
            if (ctx.catalog == null) {
              return null;
            }
            return arg(args, 0) == null ? null : ctx.catalog.schemas(isTrue(arg(args, 0)));
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
              : ctx.catalog.constraintDef(
                  num(args, 0).longValue(),
                  // pretty, which SQLAlchemy passes as the literal 't'
                  arg(args, 1) != null && isTrue(cast("boolean", args.get(1))));
        case "OBJ_DESCRIPTION": // a table's: its partition and clustering keys
          return ctx.catalog == null
                  || !(arg(args, 0) instanceof Number)
                  || (args.size() > 1 && arg(args, 1) == null)
              ? null
              : ctx.catalog.description(
                  num(args, 0).longValue(), args.size() > 1 ? text(args.get(1)) : "pg_class");
        case "PG_SIZE_PRETTY":
          return arg(args, 0) == null ? null : sizePretty(num(args, 0).longValue());
        case "PG_TABLE_SIZE": // ScalarDB does not tell how much storage a table takes
        case "PG_RELATION_SIZE":
        case "PG_TOTAL_RELATION_SIZE":
        case "PG_INDEXES_SIZE":
        case "PG_GET_EXPR":
        case "COL_DESCRIPTION":
        case "SHOBJ_DESCRIPTION":
        case "PG_DATABASE_SIZE":
        case "PG_TABLESPACE_SIZE":
        case "PG_GET_PARTKEYDEF": // ScalarDB tables are not partitioned in PostgreSQL's sense
          return null;
        case "PG_STAT_GET_NUMSCANS": // DBeaver's index list; ScalarDB keeps no statistics
          return 0L; // PostgreSQL's answer for a relation with no statistics entry
        case "PG_FUNCTION_IS_VISIBLE": // every function is in pg_catalog: visible, if it exists
          return arg(args, 0) == null || Builtins.function(num(args, 0).longValue()) == null
              ? null
              : true;
        case "PG_GET_FUNCTION_ARGUMENTS":
        case "PG_GET_FUNCTION_IDENTITY_ARGUMENTS":
        case "PG_GET_FUNCTION_RESULT":
        case "PG_GET_FUNCTIONDEF":
          {
            Builtins.Function fn =
                arg(args, 0) == null ? null : Builtins.function(num(args, 0).longValue());
            if (fn != null
                && name.equals("PG_GET_FUNCTIONDEF")
                && "a".equals(fn.row.get("prokind"))) {
              throw new PgException.Argument(
                  "42809", "\"" + fn.name + "\" is an aggregate function");
            }
            return fn == null ? null : fn.row.get(name.toLowerCase(Locale.ROOT));
          }
        case "PG_PARTITION_ANCESTORS": // ScalarDB tables are not partitions: no rows
          return Collections.emptyList();
        case "ACLEXPLODE":
          return arg(args, 0) == null || ctx.catalog == null
              ? Collections.emptyList()
              : ctx.catalog.explodeAcl(elements(args.get(0)));
        case "HAS_DATABASE_PRIVILEGE": // connecting already proved it; Cluster has no such grant
          return true;
        case "HAS_TABLE_PRIVILEGE":
        case "HAS_SCHEMA_PRIVILEGE":
          {
            // (object, privilege) for the session's user, or (user, object, privilege); each by
            // name or OID
            if (args.contains(null) || ctx.catalog == null) {
              return null;
            }
            Catalog catalog = ctx.catalog;
            int t = args.size() - 2;
            String role =
                t == 0
                    ? catalog.user
                    : args.get(0) instanceof Number
                        ? catalog.roleName(num(args, 0).longValue())
                        : text(args.get(0));
            boolean schema = name.equals("HAS_SCHEMA_PRIVILEGE");
            long object =
                args.get(t) instanceof Number
                    ? num(args, t).longValue()
                    : schema
                        ? catalog.namespaceOid(text(args.get(t)))
                        : catalog.relationOid(text(args.get(t)));
            return schema
                ? catalog.schemaPrivilege(role, object, text(args.get(t + 1)))
                : catalog.tablePrivilege(role, object, text(args.get(t + 1)));
          }
        case "PG_TYPE_IS_VISIBLE":
        case "PG_OPERATOR_IS_VISIBLE":
        case "PG_OPCLASS_IS_VISIBLE":
        case "PG_OPFAMILY_IS_VISIBLE":
        case "PG_COLLATION_IS_VISIBLE":
          {
            // in pg_catalog, so visible; null for no such object, as PostgreSQL
            String table = "pg_" + name.substring(3, name.indexOf("_IS_")).toLowerCase(Locale.ROOT);
            Catalog catalog = ctx.catalog;
            if (catalog == null || arg(args, 0) == null) {
              return null;
            }
            Map<String, Object> object = catalog.rowOf(table, num(args, 0).longValue());
            if (object == null) {
              return null;
            }
            // a type outside pg_catalog, as information_schema's domains, only on search_path
            Object namespace = object.get("x.typnamespace");
            return !(namespace instanceof Long) || catalog.onSearchPath((Long) namespace);
          }
        case "PG_CHAR_TO_ENCODING":
          return arg(args, 0) == null
              ? null
              : (Object) (long) ENCODINGS.indexOf(text(args.get(0)).toUpperCase(Locale.ROOT));
        case "GETDATABASEENCODING":
          return "UTF8";
        case "PG_TABLESPACE_LOCATION": // the built-in tablespaces have none
          return "";
        case "CURRENT_SCHEMA": // the first schema of search_path that exists
          {
            return ctx.catalog == null ? null : ctx.catalog.currentSchema();
          }
        case "CURRENT_DATABASE":
          return ctx.catalog == null ? null : ctx.catalog.database;
        case "PG_BACKEND_PID":
          { // the session's process ID; 0 outside a server session, as in a test
            Catalog.Activity session = SESSION.get();
            return session == null ? 0L : (long) session.pid;
          }
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

  /**
   * PostgreSQL's lpad and rpad: {@code s} filled to {@code n} characters with {@code fill}
   * repeated, or cut to its first n when longer; an empty fill leaves it as it is.
   */
  static String pad(String s, long n, String fill, boolean left) {
    int length = s.codePointCount(0, s.length());
    if (n <= 0) {
      return "";
    }
    if (length >= n) {
      return characters(s, 0, n);
    }
    if (fill.isEmpty()) {
      return s;
    }
    // ponytail: a fixed 10M-character cap, far under PostgreSQL's 1 GB, because one shared JVM
    // serves every session; make it a property if someone needs longer padded values
    if (n > 10_000_000) {
      throw new PgException.Argument("54000", "requested length too large");
    }
    StringBuilder padding = new StringBuilder();
    int[] points = fill.codePoints().toArray();
    for (long i = 0; i < n - length; i++) {
      padding.appendCodePoint(points[(int) (i % points.length)]);
    }
    return left ? padding + s : s + padding;
  }

  private static String str(List<Object> args, int i) {
    if (i >= args.size()) {
      throw new IllegalArgumentException("Missing function argument " + (i + 1));
    }
    Object v = args.get(i);
    if (v instanceof Number || v instanceof Boolean) {
      // no implicit cast to text: length(42) is no function in PostgreSQL
      throw new PgException.Argument(
          "42883",
          "function does not exist: argument " + (i + 1) + " is " + typeLabel(v) + ", not text");
    }
    return text(v);
  }

  /** Whether a call's arguments are bit-string literals (B'...'), the only bit strings here. */
  private static boolean bitLiterals(Function f) {
    List<?> params =
        f.getParameters() != null
            ? f.getParameters()
            : f.getNamedParameters() != null
                ? f.getNamedParameters().getExpressions()
                : Collections.emptyList();
    return params.stream()
        .anyMatch(
            p ->
                QueryParser.unwrap((Expression) p) instanceof StringValue
                    && "B"
                        .equalsIgnoreCase(
                            ((StringValue) QueryParser.unwrap((Expression) p)).getPrefix()));
  }

  /** A value's type for an error message. */
  private static String typeLabel(Object v) {
    return v instanceof Number
        ? numericTypeName(v)
        : v instanceof Boolean ? "boolean" : v.getClass().getSimpleName().toLowerCase(Locale.ROOT);
  }

  /** Whether {@code e} is typed text, varchar or bpchar as planning infers it; a literal is not. */
  private static boolean textTyped(Expression e, Context ctx) {
    e = QueryParser.unwrap(e);
    if (e instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect
        && ((net.sf.jsqlparser.statement.select.ParenthesedSelect) e).getSelect()
            instanceof net.sf.jsqlparser.statement.select.PlainSelect) {
      // a subquery's quoted literal comes out as text, as in PostgreSQL
      Expression out =
          ((net.sf.jsqlparser.statement.select.PlainSelect)
                  ((net.sf.jsqlparser.statement.select.ParenthesedSelect) e).getSelect())
              .getSelectItem(0)
              .getExpression();
      if (QueryParser.unwrap(out) instanceof StringValue) {
        return true;
      }
      Plan plan = ctx.subplans.get(e); // the subquery's own column types, from its own FROM
      int[] oids = plan == null ? new int[0] : plan.outputOids();
      return oids.length > 0 && (oids[0] == 25 || oids[0] == 1043 || oids[0] == 1042);
    }
    if (e instanceof StringValue || e instanceof JdbcParameter) {
      return false; // unknown until compared, as in PostgreSQL
    }
    int oid = ctx.catalog == null ? 0 : QueryParser.inferOid(e, ctx.sources);
    return oid == 25 || oid == 1043 || oid == 1042;
  }

  /**
   * An argument PostgreSQL declares {@code integer}: a bigint, numeric or double value has no such
   * function there (42883), rather than being cut to fit.
   */
  private static int int4(List<Object> args, int i, String function) {
    Number n = num(args, i);
    if ((n instanceof Long || n instanceof Integer || n instanceof Short)
        && n.longValue() == n.intValue()) {
      return n.intValue();
    }
    String type =
        n instanceof Long ? "bigint" : n instanceof BigDecimal ? "numeric" : "double precision";
    throw new PgException.Argument(
        "42883",
        "function " + function.toLowerCase(Locale.ROOT) + "(text, " + type + ") does not exist");
  }

  private static Number num(List<Object> args, int i) {
    if (i >= args.size() || !(args.get(i) instanceof Number)) {
      throw new PgException.Argument(
          "42883", "function argument " + (i + 1) + " is not a number"); // undefined_function
    }
    return (Number) args.get(i);
  }

  @Nullable private static Object aggregate(Function f, List<Map<String, Object>> group, Context ctx) {
    group = originals(group);
    String name = functionName(f); // pg_catalog.string_agg, as psql's \dAf writes it
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
      return name.startsWith("JSONB")
          ? new Json.Value(Json.buildObject(pairs), true, null)
          : Json.objectAggText(pairs);
    }
    // count(t.*): t's whole row, which is NULL in a row an outer join extended with NULLs for t
    String wholeRow =
        params != null
                && params.size() == 1
                && params.get(0) instanceof net.sf.jsqlparser.statement.select.AllTableColumns
            ? QueryParser.unquote(
                    ((net.sf.jsqlparser.statement.select.AllTableColumns) params.get(0))
                        .getTable()
                        .getName())
                + "."
            : null;
    List<Object> values = new ArrayList<>();
    for (Map<String, Object> row : group) {
      Object v = star ? row : eval(params.get(0), Collections.singletonList(row), ctx);
      if (wholeRow != null && nullExtended(row, wholeRow)) {
        v = null;
      }
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
    if (name.equals("ARRAY_AGG")
        && values.stream().anyMatch(java.util.Objects::isNull)
        && ctx.catalog != null
        && params != null
        && params.size() == 1
        && Catalog.elementOid(QueryParser.inferOid(params.get(0), ctx.sources)) != 0) {
      // the argument is typed an array, though its values may all be NULL: (VALUES (null::int[]))
      throw new PgException.Argument("22004", "cannot accumulate null arrays");
    }
    return aggregateValues(name, values, delimiter, f);
  }

  /**
   * The variance or standard deviation, of the sample or the population: for floats PostgreSQL's
   * float8_accum (Youngs-Cramer) in row order, otherwise exact sums with numeric_stddev_internal's
   * scales.
   */
  @Nullable private static Object variance(
      List<Object> values, boolean stddev, boolean sample, Expression f) {
    for (Object v : values) {
      if (!(v instanceof Number)) {
        throw new IllegalArgumentException("requires numeric values: " + f);
      }
    }
    int n = values.size();
    if (n == 0 || (sample && n == 1)) {
      return null;
    }
    if (values.get(0) instanceof Double || values.get(0) instanceof Float) {
      double count = 0;
      double sx = 0;
      double sxx = 0;
      for (Object v : values) {
        double x = ((Number) v).doubleValue();
        double previousSx = sx;
        count++;
        sx += x;
        if (count > 1) {
          double tmp = x * count - sx;
          sxx += tmp * tmp / (count * (count - 1));
          if (Double.isInfinite(sx) || Double.isInfinite(sxx)) {
            if (!Double.isInfinite(previousSx) && !Double.isInfinite(x)) {
              throw new PgException.Argument("22003", "value out of range: overflow");
            }
            sxx = Double.NaN;
          }
        } else if (Double.isNaN(x) || Double.isInfinite(x)) {
          sxx = Double.NaN;
        }
      }
      double result = sxx / (sample ? count - 1 : count);
      return stddev ? Math.sqrt(result) : result;
    }
    BigDecimal sumX = BigDecimal.ZERO;
    BigDecimal sumX2 = BigDecimal.ZERO;
    for (Object v : values) {
      if (!isFinite(v)) {
        return v; // numeric NaN or infinity: NaN out, as PostgreSQL gives
      }
      BigDecimal x = bounded(toDecimal(v), "stddev and variance");
      sumX = sumX.add(x);
      sumX2 = sumX2.add(x.multiply(x));
    }
    BigDecimal count = BigDecimal.valueOf(n);
    BigDecimal numerator = count.multiply(sumX2).subtract(sumX.multiply(sumX));
    if (numerator.signum() <= 0) {
      return BigDecimal.ZERO; // roundoff cannot happen here, but PostgreSQL gives 0, scale 0
    }
    BigDecimal denominator = count.multiply(sample ? count.subtract(BigDecimal.ONE) : count);
    int rscale = divScale(numerator, denominator);
    BigDecimal variance = numerator.divide(denominator, rscale, RoundingMode.HALF_UP);
    if (!stddev) {
      return variance;
    }
    // sqrt_var of the rounded variance, to the same scale
    int digits = variance.precision() - variance.scale() + rscale + 10;
    return variance
        .sqrt(new java.math.MathContext(Math.max(digits, 10)))
        .setScale(rscale, RoundingMode.HALF_UP);
  }

  /** The elements of a (possibly multi-dimensional) array, in order. */
  private static void flatten(List<?> array, List<Object> out) {
    for (Object v : array) {
      if (v instanceof List) {
        flatten((List<?>) v, out);
      } else {
        out.add(v);
      }
    }
  }

  /** Whether an outer join extended the row with NULLs for the source with this prefix (t.). */
  private static boolean nullExtended(Map<String, Object> row, String prefix) {
    return row.containsKey(prefix + NULL_EXTENDED);
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
        boolean reals = true; // sum(real) is real
        boolean negativeZero = true; // floating-point addition gives -0 only from -0 inputs
        for (Object v : values) {
          if (!(v instanceof Number)) {
            throw new IllegalArgumentException(name + " requires numeric values: " + f);
          }
          reals &= v instanceof Float;
          if (isFinite(v)) {
            // a real's own value, which avg (in double precision) reads, not its shortest digits
            sum =
                sum.add(
                    v instanceof Float ? new BigDecimal((Float) v) : new BigDecimal(v.toString()));
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
        if (reals) {
          // the exact sum rounded once: PostgreSQL adds in scan order, which this does not depend
          // on
          float realSum = sum.floatValue();
          if (Float.isInfinite(realSum)) {
            throw new ArithmeticException("value out of range: overflow");
          }
          return negativeZero ? -0.0f : realSum;
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
      case "STDDEV":
      case "STDDEV_SAMP":
      case "STDDEV_POP":
      case "VARIANCE":
      case "VAR_SAMP":
      case "VAR_POP":
        return variance(values, name.startsWith("STDDEV"), !name.endsWith("_POP"), f);
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
        if (values.stream().anyMatch(v -> v instanceof List && !(v instanceof Row))
            || arrayArgument(f)) {
          // array_agg of arrays stacks them: each must be a non-empty array, as in PostgreSQL
          for (Object v : values) {
            if (v == null) {
              throw new PgException.Argument("22004", "cannot accumulate null arrays");
            }
            if (v instanceof List && ((List<?>) v).isEmpty()) {
              throw new PgException.Argument("2202E", "cannot accumulate empty arrays");
            }
          }
        }
        return values.isEmpty()
            ? null
            : rectangular(
                new ArrayList<>(values), "cannot accumulate arrays of different dimensionality");
      case "JSON_AGG":
        return values.isEmpty() ? null : Json.buildArrayText(values, true);
      case "JSONB_AGG":
        return values.isEmpty() ? null : new Json.Value(Json.buildArray(values), true, null);
      case "STRING_AGG":
        {
          if (values.isEmpty()) {
            return null;
          }
          if (values.get(0) instanceof ByteBuffer) {
            // string_agg(bytea, bytea): the bytes joined, not their text
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            // an untyped delimiter literal ('') is bytea input
            Object d = delimiter instanceof String ? cast("bytea", delimiter) : delimiter;
            for (int i = 0; i < values.size(); i++) {
              if (i > 0 && d != null) {
                out.writeBytes(bytes(d));
              }
              out.writeBytes(bytes(values.get(i)));
            }
            return ByteBuffer.wrap(out.toByteArray());
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

  /** Whether the pattern ends with an escape character that escapes nothing. */
  static boolean endsWithEscape(String pattern, String escape) {
    for (int i = 0; !escape.isEmpty() && i < pattern.length(); i++) {
      if (pattern.charAt(i) == escape.charAt(0) && ++i == pattern.length()) {
        return true;
      }
    }
    return false;
  }

  /** Translates a SQL LIKE pattern into a regex; {@code %} is any string and {@code _} any char. */
  static Pattern likePattern(String pattern, String escape) {
    if (endsWithEscape(pattern, escape)) {
      throw new PgException.Argument("22025", "LIKE pattern must not end with escape character");
    }
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
