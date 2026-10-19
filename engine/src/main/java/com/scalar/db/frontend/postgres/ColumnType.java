package com.scalar.db.frontend.postgres;

import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * A column's declared PostgreSQL type where ScalarDB cannot hold it, and the rules that keep it.
 * ScalarDB has no decimal type, no type modifiers and no free-form metadata, so the declaration is
 * recorded in the frontend's own table, {@link TypeStore}: {@code amount numeric(10,2)} is a
 * ScalarDB BIGINT column {@code amount} holding the value times 100, and a row saying so. A column
 * without a row is {@link Kind#NATIVE} and behaves as before (see docs/type-encoding.md).
 *
 * <p>Values cross three ways. {@link #coerce} applies the write rules (round to the scale, strip
 * trailing spaces, refuse what does not fit). {@link #exact} turns an already coerced value, or a
 * value in a predicate, into the stored column only when it is represented exactly, and otherwise
 * throws {@link PgException.OutOfRange} or {@link PgException.NotRepresentable}, so a comparison
 * with {@code 1.005} on a numeric(10,2) key matches nothing instead of the 1.01 row. {@link
 * #decode} reads a stored value back and refuses one that breaks the contract (written by another
 * client) instead of wrapping, padding or guessing.
 */
final class ColumnType {
  /** What the declaration is, and how ScalarDB stores it. */
  enum Kind {
    /** No declaration beyond the ScalarDB type: the column as it has always been. */
    NATIVE,
    /** numeric(p,s) with p up to the scaled limit: a BIGINT holding value * 10^s. */
    NUMERIC_SCALED,
    /** numeric without a precision, or with more digits than a BIGINT holds: exact decimal text. */
    NUMERIC_TEXT,
    /** varchar(n): text of at most n characters. */
    VARCHAR,
    /** char(n): text of at most n characters, stored without trailing spaces. */
    CHAR,
    /** smallint: an INT within -32768..32767. */
    SMALLINT,
    /** A recorded type this frontend cannot read: shown as stored, never written. */
    UNKNOWN_ENCODING
  }

  // ponytail: fixed limits, chosen at CREATE TABLE from the configured storage; a property when a
  // deployment needs a different one (docs/type-encoding.md warns about n16-n18 on 2^53 storages)
  /** The most digits a scaled BIGINT holds exactly on every storage but the two below. */
  static final int MAX_SCALED_PRECISION = 18;

  /** Cosmos DB and object storage keep a BIGINT exact only up to 2^53: 15 decimal digits. */
  static final int MAX_SCALED_PRECISION_53_BITS = 15;

  private static final Pattern DECIMAL_TEXT = Pattern.compile("-?\\d+(\\.\\d+)?");

  final Kind kind;
  final DataType storage;
  final int precision; // numeric: p, or 0 when unbounded
  final int scale; // numeric: s
  final int length; // varchar(n), char(n): n
  private final long scaledBound; // numeric(p,s) stored scaled: 10^p, the first unscaled too big

  private ColumnType(Kind kind, DataType storage, int precision, int scale, int length) {
    this.kind = kind;
    this.storage = storage;
    this.precision = precision;
    this.scale = scale;
    this.length = length;
    this.scaledBound =
        kind == Kind.NUMERIC_SCALED ? BigInteger.TEN.pow(precision).longValueExact() : 0;
  }

  /** A column whose ScalarDB type is all there is to it. */
  static ColumnType of(DataType storage) {
    return new ColumnType(Kind.NATIVE, storage, 0, 0, 0);
  }

  /** numeric(p, s), stored scaled when p fits {@code maxScaledPrecision}, else as text. */
  static ColumnType numeric(int precision, int scale, int maxScaledPrecision) {
    return precision <= maxScaledPrecision
        ? new ColumnType(Kind.NUMERIC_SCALED, DataType.BIGINT, precision, scale, 0)
        : new ColumnType(Kind.NUMERIC_TEXT, DataType.TEXT, precision, scale, 0);
  }

  /** numeric or decimal without a precision: any value, exact, as text. */
  static ColumnType unboundedNumeric() {
    return new ColumnType(Kind.NUMERIC_TEXT, DataType.TEXT, 0, 0, 0);
  }

  /**
   * The type a column definition declares, with its modifiers checked as PostgreSQL checks them
   * (22023). A declaration ScalarDB can hold as is (integer, text, varchar without a length, ...)
   * is {@link Kind#NATIVE}.
   */
  static ColumnType declared(String declared, int maxScaledPrecision) {
    String base = declared.toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "").trim();
    long[] args = QueryParser.typeArguments(declared);
    switch (base) {
      case "numeric":
      case "decimal":
      case "dec":
        QueryParser.checkNumericModifiers(args);
        return args.length == 0
            ? unboundedNumeric()
            : numeric((int) args[0], args.length > 1 ? (int) args[1] : 0, maxScaledPrecision);
      case "varchar":
      case "character varying":
        QueryParser.checkLength("varchar", args);
        return args.length == 0
            ? of(DataType.TEXT)
            : new ColumnType(Kind.VARCHAR, DataType.TEXT, 0, 0, (int) args[0]);
      case "char":
      case "character":
      case "bpchar":
        QueryParser.checkLength("char", args);
        if (args.length == 0 && base.equals("bpchar")) {
          return of(DataType.TEXT); // bpchar without a length has no limit
        }
        return new ColumnType(Kind.CHAR, DataType.TEXT, 0, 0, args.length == 0 ? 1 : (int) args[0]);
      case "smallint":
      case "int2":
        return new ColumnType(Kind.SMALLINT, DataType.INT, 0, 0, 0);
      default:
        return of(QueryParser.columnType(declared));
    }
  }

  /**
   * The most digits a numeric(p,s) may have to be stored as a scaled BIGINT, from the ScalarDB
   * properties: 15 where a BIGINT is exact only up to 2^53 (Cosmos DB, object storage,
   * multi-storage which may include them) and in Cluster mode, where the node's storage is not
   * visible; else 18.
   */
  static int maxScaledPrecision(Properties properties) {
    String storage = properties.getProperty("scalar.db.storage", "cassandra").trim();
    String manager = properties.getProperty("scalar.db.transaction_manager", "").trim();
    boolean exactTo53Bits =
        storage.equals("cosmos")
            || storage.equals("blob-storage")
            || storage.equals("cloud-storage")
            || storage.equals("s3")
            || storage.equals("multi-storage");
    return exactTo53Bits || manager.equals("cluster")
        ? MAX_SCALED_PRECISION_53_BITS
        : MAX_SCALED_PRECISION;
  }

  /**
   * The declared type a {@link TypeStore} row records for a column ScalarDB stores as {@code
   * stored}: {@link Kind#UNKNOWN_ENCODING} for a row this frontend cannot read (a newer encoding,
   * or values out of range), which keeps the column readable as stored and unwritable.
   */
  static ColumnType fromRow(
      String sqlType,
      @Nullable Integer precision,
      @Nullable Integer scale,
      @Nullable Integer length,
      String encoding,
      DataType stored) {
    ColumnType unknown = new ColumnType(Kind.UNKNOWN_ENCODING, stored, 0, 0, 0);
    int p = precision == null ? 0 : precision;
    int s = scale == null ? 0 : scale;
    int n = length == null ? 0 : length;
    ColumnType t;
    switch (sqlType + "/" + encoding) {
      case "NUMERIC/SCALED_BIGINT":
        if (p < 1 || p > MAX_SCALED_PRECISION || s < -1000 || s > 1000) {
          return unknown;
        }
        t = new ColumnType(Kind.NUMERIC_SCALED, DataType.BIGINT, p, s, 0);
        break;
      case "NUMERIC/DECIMAL_TEXT":
        if (precision == null ? scale != null : p < 1 || p > 1000 || s < -1000 || s > 1000) {
          return unknown;
        }
        t = new ColumnType(Kind.NUMERIC_TEXT, DataType.TEXT, p, s, 0);
        break;
      case "VARCHAR/TEXT":
      case "CHAR/TRIMMED_TEXT":
        if (n < 1 || n > 10485760) {
          return unknown;
        }
        t =
            new ColumnType(
                sqlType.equals("VARCHAR") ? Kind.VARCHAR : Kind.CHAR, DataType.TEXT, 0, 0, n);
        break;
      case "SMALLINT/INT":
        t = new ColumnType(Kind.SMALLINT, DataType.INT, 0, 0, 0);
        break;
      default:
        return unknown;
    }
    return t.storage == stored ? t : unknown;
  }

  /** The row's {@code sql_type}: the standard SQL name of the declared type. */
  String sqlType() {
    switch (kind) {
      case NUMERIC_SCALED:
      case NUMERIC_TEXT:
        return "NUMERIC";
      case VARCHAR:
        return "VARCHAR";
      case CHAR:
        return "CHAR";
      case SMALLINT:
        return "SMALLINT";
      default:
        throw new IllegalStateException("No row for a " + kind + " column");
    }
  }

  /** The row's {@code type_precision}: a numeric's digits; null without a precision. */
  @Nullable Integer rowPrecision() {
    return isNumeric() && precision > 0 ? precision : null;
  }

  /** The row's {@code type_scale}: a numeric's scale; null without a precision. */
  @Nullable Integer rowScale() {
    return isNumeric() && precision > 0 ? scale : null;
  }

  /** The row's {@code type_length}: n of varchar(n) and char(n); null for the others. */
  @Nullable Integer rowLength() {
    return kind == Kind.VARCHAR || kind == Kind.CHAR ? length : null;
  }

  /** The row's {@code encoding}: how the value is stored. */
  String encoding() {
    switch (kind) {
      case NUMERIC_SCALED:
        return "SCALED_BIGINT";
      case NUMERIC_TEXT:
        return "DECIMAL_TEXT";
      case VARCHAR:
        return "TEXT";
      case CHAR:
        return "TRIMMED_TEXT";
      case SMALLINT:
        return "INT";
      default:
        throw new IllegalStateException("No row for a " + kind + " column");
    }
  }

  /**
   * True when ScalarDB compares the stored values as PostgreSQL compares the declared ones, so
   * keys, ranges and conditions on the column may be pushed down. A text-stored numeric is the
   * exception: as text, "1.50" differs from "1.5" and "10" sorts before "9", so every comparison on
   * it but IS NULL must be evaluated in memory, and such a column cannot be a key or index column.
   */
  boolean comparableWhenStored() {
    return kind != Kind.NUMERIC_TEXT && kind != Kind.UNKNOWN_ENCODING;
  }

  /**
   * Refuses this type as a key or index column when ScalarDB would compare its stored form
   * differently from PostgreSQL (a text-stored numeric).
   */
  void checkKeyable(String column, int maxScaledPrecision) {
    if (!comparableWhenStored()) {
      throw new PgException.Argument(
          "0A000",
          "column \""
              + column
              + "\" cannot be a key or index column: a NUMERIC without a precision, or with more"
              + " than "
              + maxScaledPrecision
              + " digits, is stored as text here, which ScalarDB would order and match as text."
              + " Declare it NUMERIC(p,s) with p <= "
              + maxScaledPrecision);
    }
  }

  /**
   * True when values compare as text, as PostgreSQL compares text, varchar and char: a quoted value
   * is taken as written, and a number against it is 42883. A numeric stored as text is not.
   */
  boolean comparesAsText() {
    return storage == DataType.TEXT && !isNumeric();
  }

  /**
   * True when a value needs converting to its stored form, so a predicate's value goes through
   * {@link #exact} (a numeric scaled, a char trimmed, a smallint range-checked). A varchar is
   * stored as its text and converts as text does.
   */
  boolean convertsValues() {
    return encoded() && kind != Kind.VARCHAR;
  }

  /** True for a declared numeric, stored as a scaled bigint or as text. */
  boolean isNumeric() {
    return kind == Kind.NUMERIC_SCALED || kind == Kind.NUMERIC_TEXT;
  }

  /** True for a type recorded in the frontend's table (any kind but NATIVE). */
  boolean encoded() {
    return kind != Kind.NATIVE;
  }

  /** The PostgreSQL type OID clients see. */
  long oid() {
    switch (kind) {
      case NUMERIC_SCALED:
      case NUMERIC_TEXT:
        return 1700;
      case VARCHAR:
        return 1043;
      case CHAR:
        return 1042;
      case SMALLINT:
        return 21;
      default:
        return Catalog.typeOid(storage);
    }
  }

  /** The type modifier clients see (atttypmod, RowDescription): -1 when there is none. */
  int typmod() {
    switch (kind) {
      case NUMERIC_SCALED:
      case NUMERIC_TEXT:
        return precision == 0 ? -1 : ((precision << 16) | (scale & 0x7ff)) + 4;
      case VARCHAR:
      case CHAR:
        return length + 4;
      default:
        return -1;
    }
  }

  /** The declared type as format_type prints it: numeric(10,2), character varying(20), ... */
  String sqlName() {
    return Catalog.typeName(oid(), (long) typmod());
  }

  // ---- write coercion ----

  /**
   * A value about to be stored, coerced as PostgreSQL coerces it on INSERT and UPDATE: a numeric is
   * rounded half away from zero to the scale, a varchar or char longer than n is cut only when the
   * excess is spaces, a char loses its trailing spaces, and anything that does not fit fails with
   * PostgreSQL's SQLSTATE and message. NULL stays NULL. The result goes to {@link #exact}.
   */
  @Nullable Object coerce(@Nullable Object v) {
    if (v == null) {
      return null;
    }
    switch (kind) {
      case NUMERIC_SCALED:
      case NUMERIC_TEXT:
        return coerceNumeric(v);
      case VARCHAR:
      case CHAR:
        {
          String s = noNul(Evaluator.text(v));
          int n = s.codePointCount(0, s.length());
          if (n > length) {
            int cut = s.offsetByCodePoints(0, length);
            if (!s.substring(cut).chars().allMatch(c -> c == ' ')) {
              throw new PgException.Argument("22001", "value too long for type " + sqlName());
            }
            s = s.substring(0, cut); // only spaces beyond n: cut, as PostgreSQL does
          }
          return kind == Kind.CHAR ? stripTrailingSpaces(s) : s;
        }
      case SMALLINT:
        return Evaluator.cast("smallint", v); // integer rounding and the range check, 22003
      case UNKNOWN_ENCODING:
        throw unknownEncoding();
      default:
        return v;
    }
  }

  private Object coerceNumeric(Object v) {
    Object n = number(v);
    if (n instanceof Double) {
      double d = (Double) n;
      if (Double.isNaN(d)) {
        if (kind == Kind.NUMERIC_SCALED) {
          throw new PgException.Argument(
              "0A000",
              "NaN cannot be stored in a column of type "
                  + sqlName()
                  + " here: the frontend keeps it as a scaled bigint, which has no NaN");
        }
        return n;
      }
      if (precision != 0) {
        throw new PgException.Argument(
            "22003",
            "numeric field overflow",
            "A field with precision "
                + precision
                + ", scale "
                + scale
                + " cannot hold an infinite value.");
      }
      return n;
    }
    BigDecimal d = (BigDecimal) n;
    if (precision == 0 && d.scale() > 16_383) {
      // PostgreSQL's limit on a numeric's fraction digits (its integer digits are checked on parse)
      throw new PgException.Argument("22003", "value overflows numeric format");
    }
    return precision == 0 ? d : Evaluator.applyTypmod(d, precision, scale);
  }

  // ---- exact conversion ----

  /**
   * The stored column for a value, only when the value is represented exactly: a coerced value
   * always is; a value in a predicate may not be. A value beyond the column's range throws {@link
   * PgException.OutOfRange}; one in range without an exact stored form ({@code 1.005} for
   * numeric(10,2), a string longer than n) throws {@link PgException.NotRepresentable}. NULL gives
   * the column's NULL. For a text-stored numeric the result is for storing only: a predicate on it
   * must not be pushed down (see {@link #comparableWhenStored}).
   */
  Column<?> exact(String storageName, @Nullable Object v) {
    switch (kind) {
      case NUMERIC_SCALED:
        {
          if (v == null) {
            return BigIntColumn.ofNull(storageName);
          }
          return BigIntColumn.of(storageName, scaled(number(v)));
        }
      case NUMERIC_TEXT:
        return v == null
            ? TextColumn.ofNull(storageName)
            : TextColumn.of(storageName, decimalText(number(v)));
      case VARCHAR:
      case CHAR:
        {
          if (v == null) {
            return TextColumn.ofNull(storageName);
          }
          String s = noNul(Evaluator.text(v));
          if (kind == Kind.CHAR) {
            s = stripTrailingSpaces(s); // trailing spaces never matter in a char
          }
          if (s.codePointCount(0, s.length()) > length) {
            throw new PgException.NotRepresentable("22001", "value too long for type " + sqlName());
          }
          return TextColumn.of(storageName, s);
        }
      case SMALLINT:
        {
          if (v == null) {
            return IntColumn.ofNull(storageName);
          }
          if (!(v instanceof Number)) {
            // quoted text is smallint input, as PostgreSQL reads WHERE s = '40000': 22003, or
            // 22P02 for '1.5', never a comparison that matches nothing (a number literal comes
            // here as a number, see QueryParser.column)
            String text = Evaluator.text(v).trim();
            if (!QueryParser.isInteger(text)) {
              throw new PgException.Argument(
                  "22P02", "invalid input syntax for type smallint: \"" + text + "\"");
            }
            BigDecimal d =
                new BigDecimal(java.util.Objects.requireNonNull(Evaluator.integerInput(text)));
            if (d.compareTo(BigDecimal.valueOf(Short.MIN_VALUE)) < 0
                || d.compareTo(BigDecimal.valueOf(Short.MAX_VALUE)) > 0) {
              throw new PgException.Argument(
                  "22003", "value \"" + text + "\" is out of range for type smallint");
            }
            return IntColumn.of(storageName, d.intValueExact());
          }
          Object n = number(v);
          if (!(n instanceof BigDecimal)) {
            throw new PgException.NotRepresentable("22P02", "not a smallint: " + v);
          }
          BigDecimal d = (BigDecimal) n;
          if (d.compareTo(BigDecimal.valueOf(Short.MIN_VALUE)) < 0
              || d.compareTo(BigDecimal.valueOf(Short.MAX_VALUE)) > 0) {
            throw new PgException.OutOfRange("smallint out of range", d.signum() > 0);
          }
          if (d.stripTrailingZeros().scale() > 0) {
            throw new PgException.NotRepresentable("22P02", "not a smallint: " + v);
          }
          return IntColumn.of(storageName, d.intValue());
        }
      case UNKNOWN_ENCODING:
        throw unknownEncoding();
      default:
        throw new IllegalStateException("A native column converts through columnFromText");
    }
  }

  /**
   * The ScalarDB condition for {@code column op v} on a numeric(p,s) column stored scaled, exact
   * for every value, or null when the comparison is false for every row (or v is NULL). A value
   * with more fraction digits than the column keeps becomes the nearest bound on the stored integer
   * ({@code x > 20.005} on numeric(10,2) is {@code stored >= 2001}); a value beyond the column's
   * range, NaN, or an unmatched equality decides the comparison by itself (nothing, or every
   * non-NULL row). So the condition can always be pushed down, and a lookup never mistakes "not
   * representable" for "no row". A float compares as PostgreSQL compares numeric with double
   * precision: the stored value converted to double.
   */
  @Nullable com.scalar.db.api.ConditionalExpression scaledCondition(
      String name, com.scalar.db.api.ConditionalExpression.Operator op, @Nullable Object v) {
    if (kind != Kind.NUMERIC_SCALED) {
      throw new IllegalStateException("Not a scaled numeric: " + this);
    }
    if (v == null) {
      return null;
    }
    if ((v instanceof Double || v instanceof Float)
        && Double.isFinite(((Number) v).doubleValue())) {
      return floatCondition(name, op, ((Number) v).doubleValue());
    }
    Object n = number(v);
    if (n instanceof Double) {
      double d = (Double) n;
      // NaN sorts above every number in PostgreSQL; an infinity lies beyond every column range
      return constant(name, op, Double.isNaN(d) ? Boolean.TRUE : d > 0, Double.isNaN(d));
    }
    BigDecimal d = (BigDecimal) n;
    if (!Evaluator.integerDigitsFit(d, precision, scale)) {
      return constant(name, op, d.signum() > 0, false);
    }
    BigDecimal unscaled = d.movePointRight(scale);
    if (unscaled.signum() == 0 || unscaled.stripTrailingZeros().scale() <= 0) {
      return com.scalar.db.api.ConditionBuilder.buildConditionalExpression(
          BigIntColumn.of(name, unscaled.longValueExact()), op);
    }
    long floor = unscaled.setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
    switch (op) {
      case EQ:
        return null; // no stored value equals it
      case NE:
        return notNull(name);
      case GT:
      case GTE:
        return com.scalar.db.api.ConditionBuilder.buildConditionalExpression(
            BigIntColumn.of(name, floor + 1), com.scalar.db.api.ConditionalExpression.Operator.GTE);
      case LT:
      case LTE:
        return com.scalar.db.api.ConditionBuilder.buildConditionalExpression(
            BigIntColumn.of(name, floor), com.scalar.db.api.ConditionalExpression.Operator.LTE);
      default:
        throw new IllegalArgumentException("Unsupported comparison: " + op);
    }
  }

  /**
   * {@code column op d} for a finite double d, which PostgreSQL evaluates as {@code column::float8
   * op d}. Converting to double never reverses an order, so the stored integers it holds for form a
   * range: from {@code lo}, the least whose double is not below d, to {@code hi}, the greatest
   * whose double is not above d (several integers share one double beyond 2^53).
   */
  @Nullable private com.scalar.db.api.ConditionalExpression floatCondition(
      String name, com.scalar.db.api.ConditionalExpression.Operator op, double d) {
    long max = BigDecimal.TEN.pow(precision).longValueExact() - 1;
    BigDecimal c = new BigDecimal(d).movePointRight(scale); // d exactly, as a stored integer
    Long lo = null; // none: every stored value converts below d
    Long hi = null; // none: every stored value converts above d
    if (c.compareTo(BigDecimal.valueOf(max)) > 0) {
      hi = max;
    } else if (c.compareTo(BigDecimal.valueOf(-max)) < 0) {
      lo = -max;
    } else {
      // a value at or below d converts at or below it, as d is a double itself
      long h = c.setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
      while (h < max && asDouble(h + 1) <= d) {
        h++;
      }
      long l = c.setScale(0, java.math.RoundingMode.CEILING).longValueExact();
      while (l > -max && asDouble(l - 1) >= d) {
        l--;
      }
      lo = asDouble(l) >= d ? l : null;
      hi = asDouble(h) <= d ? h : null;
    }
    switch (op) {
      case GT:
        return hi == null ? notNull(name) : hi == max ? null : bound(name, hi + 1, true);
      case GTE:
        return lo == null ? null : bound(name, lo, true);
      case LT:
        return lo == null ? notNull(name) : lo == -max ? null : bound(name, lo - 1, false);
      case LTE:
        return hi == null ? null : bound(name, hi, false);
      case EQ:
      case NE:
        if (lo == null || hi == null || lo > hi) {
          return op == com.scalar.db.api.ConditionalExpression.Operator.EQ ? null : notNull(name);
        }
        if (!lo.equals(hi)) {
          // ponytail: one ScalarDB condition cannot say "between"; only numeric(16..18) columns
          // hold several values that convert to one double. Push a range when it is needed.
          throw new PgException.Argument(
              "0A000",
              "comparing a column of type "
                  + sqlName()
                  + " with a double precision value this large is not supported: cast the column"
                  + " to double precision, or the value to numeric");
        }
        return com.scalar.db.api.ConditionBuilder.buildConditionalExpression(
            BigIntColumn.of(name, lo), op);
      default:
        throw new IllegalArgumentException("Unsupported comparison: " + op);
    }
  }

  /** A stored integer as the double PostgreSQL converts its numeric to. */
  private double asDouble(long stored) {
    return BigDecimal.valueOf(stored, scale).doubleValue();
  }

  private static com.scalar.db.api.ConditionalExpression bound(
      String name, long stored, boolean lower) {
    return com.scalar.db.api.ConditionBuilder.buildConditionalExpression(
        BigIntColumn.of(name, stored),
        lower
            ? com.scalar.db.api.ConditionalExpression.Operator.GTE
            : com.scalar.db.api.ConditionalExpression.Operator.LTE);
  }

  /**
   * A comparison decided by where the value lies: above every stored value, or below. NaN is above
   * every number but equal to nothing a scaled column can hold.
   */
  @Nullable private static com.scalar.db.api.ConditionalExpression constant(
      String name,
      com.scalar.db.api.ConditionalExpression.Operator op,
      boolean above,
      boolean nan) {
    boolean true_;
    switch (op) {
      case EQ:
        true_ = false;
        break;
      case NE:
        true_ = true;
        break;
      case GT:
      case GTE:
        true_ = !above;
        break;
      case LT:
      case LTE:
        true_ = above || nan;
        break;
      default:
        throw new IllegalArgumentException("Unsupported comparison: " + op);
    }
    return true_ ? notNull(name) : null;
  }

  private static com.scalar.db.api.ConditionalExpression notNull(String name) {
    return com.scalar.db.api.ConditionBuilder.buildConditionalExpression(
        BigIntColumn.ofNull(name), com.scalar.db.api.ConditionalExpression.Operator.IS_NOT_NULL);
  }

  /** The unscaled long of a numeric(p,s) value, or the typed failure. */
  private long scaled(Object n) {
    if (n instanceof Double) {
      double d = (Double) n;
      if (Double.isNaN(d)) {
        throw new PgException.NotRepresentable("0A000", "NaN in " + sqlName());
      }
      throw new PgException.OutOfRange("numeric field overflow", d > 0);
    }
    BigDecimal d = (BigDecimal) n;
    checkFits(d);
    return d.signum() == 0 ? 0 : d.setScale(scale).unscaledValue().longValueExact();
  }

  /** The canonical text of a decimal for a text-stored numeric, or the typed failure. */
  private String decimalText(Object n) {
    if (n instanceof Double) {
      double d = (Double) n;
      if (Double.isNaN(d)) {
        return "NaN";
      }
      if (precision != 0) {
        throw new PgException.OutOfRange("numeric field overflow", d > 0);
      }
      return d > 0 ? "Infinity" : "-Infinity";
    }
    BigDecimal d = (BigDecimal) n;
    if (precision != 0) {
      checkFits(d);
      d = d.signum() == 0 ? BigDecimal.ZERO.setScale(Math.max(scale, 0)) : d.setScale(scale);
    }
    return (d.scale() < 0 ? d.setScale(0) : d).toPlainString();
  }

  /** A value fits numeric(p,s) exactly: its integer digits and its fraction digits. */
  private void checkFits(BigDecimal d) {
    if (d.signum() == 0) {
      return;
    }
    if (!Evaluator.integerDigitsFit(d, precision, scale)) {
      throw new PgException.OutOfRange("numeric field overflow", d.signum() > 0);
    }
    if (d.stripTrailingZeros().scale() > scale) {
      throw new PgException.NotRepresentable(
          "22003", "more fraction digits than " + sqlName() + " keeps");
    }
  }

  // ---- decoding ----

  /**
   * The value of a stored column as the evaluator sees it: a numeric as a BigDecimal at its scale
   * (NaN and the infinities of a text-stored numeric as a Double), a varchar or char as its text, a
   * smallint as an Integer. A stored value that breaks the column's contract (written by another
   * client or an older frontend) fails with XX001 naming the table and column, never echoing the
   * value, instead of being wrapped, padded or guessed.
   */
  @Nullable Object decode(Column<?> stored, String table) {
    if (stored.hasNullValue()) {
      return null;
    }
    switch (kind) {
      case NUMERIC_SCALED:
        {
          long u = stored.getBigIntValue();
          if (u == Long.MIN_VALUE || Math.abs(u) >= scaledBound) {
            throw corrupted(stored, table);
          }
          BigDecimal d = BigDecimal.valueOf(u, scale);
          return d.scale() < 0 ? d.setScale(0) : d;
        }
      case NUMERIC_TEXT:
        {
          String s = stored.getTextValue();
          if (s == null) {
            return null;
          }
          switch (s) {
            case "NaN":
              return Double.NaN;
            case "Infinity":
            case "-Infinity":
              if (precision != 0) {
                throw corrupted(stored, table);
              }
              return s.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            default:
              break;
          }
          if (!DECIMAL_TEXT.matcher(s).matches()) {
            throw corrupted(stored, table);
          }
          BigDecimal d = new BigDecimal(s);
          if (precision != 0) {
            try {
              checkFits(d);
            } catch (IllegalArgumentException doesNotFit) {
              throw corrupted(stored, table);
            }
            d = d.setScale(Math.max(scale, 0));
          }
          return d;
        }
      case VARCHAR:
      case CHAR:
        {
          String s = stored.getTextValue();
          if (s == null) {
            return null;
          }
          if (kind == Kind.CHAR) {
            s = stripTrailingSpaces(s); // accepts a value another client padded
          }
          if (s.codePointCount(0, s.length()) > length) {
            throw corrupted(stored, table);
          }
          return s;
        }
      case SMALLINT:
        {
          int i = stored.getIntValue();
          if (i < Short.MIN_VALUE || i > Short.MAX_VALUE) {
            throw corrupted(stored, table);
          }
          return i;
        }
      default:
        return stored.getValueAsObject();
    }
  }

  private PgException.Argument corrupted(Column<?> stored, String table) {
    return new PgException.Argument(
        "XX001", // data_corrupted
        "invalid value stored in column \""
            + stored.getName()
            + "\" of table \""
            + table
            + "\": it does not fit "
            + sqlName()
            + ", the type recorded for it; another client may have written it");
  }

  private PgException.Argument unknownEncoding() {
    return new PgException.Argument(
        "0A000",
        "a column of this table uses a type encoding this frontend does not know (a newer"
            + " frontend may have created it), so it is read as stored and never written");
  }

  // ---- helpers ----

  /**
   * A value as a number for a numeric column: a BigDecimal, or a Double for NaN and the infinities.
   * Text is read as PostgreSQL reads numeric input (spaces around it allowed, NaN and Infinity in
   * any case, inf), and a float converts with PostgreSQL's significant digits.
   */
  private static Object number(Object v) {
    if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      return Double.isNaN(d) || Double.isInfinite(d) ? (Object) d : Evaluator.toDecimal(v);
    }
    if (v instanceof Number) {
      return Evaluator.toDecimal(v);
    }
    String s = Evaluator.text(v).trim();
    Double special = special(s);
    if (special != null) {
      return special;
    }
    try {
      return Evaluator.decimal(s);
    } catch (NumberFormatException e) {
      throw new PgException.Argument(
          "22P02", "invalid input syntax for type numeric: \"" + Evaluator.text(v) + "\"");
    }
  }

  /** PostgreSQL's spellings of NaN and the infinities in numeric input, or null. */
  @Nullable private static Double special(String s) {
    switch (s.toLowerCase(Locale.ROOT)) {
      case "nan":
        return Double.NaN;
      case "infinity":
      case "+infinity":
      case "inf":
      case "+inf":
        return Double.POSITIVE_INFINITY;
      case "-infinity":
      case "-inf":
        return Double.NEGATIVE_INFINITY;
      default:
        return null;
    }
  }

  private static String noNul(String s) {
    if (s.indexOf('\0') >= 0) {
      throw new PgException.Argument("22021", "invalid byte sequence for encoding \"UTF8\": 0x00");
    }
    return s;
  }

  private static String stripTrailingSpaces(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == ' ') {
      end--;
    }
    return s.substring(0, end);
  }

  @Override
  public String toString() {
    return kind == Kind.NATIVE ? storage.toString() : sqlName();
  }
}
