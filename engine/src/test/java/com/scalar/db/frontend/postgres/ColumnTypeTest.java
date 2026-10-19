package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The declared-type codec: rows, write coercion, exact conversion and validated decoding. */
public class ColumnTypeTest {
  private static final int MAX = ColumnType.MAX_SCALED_PRECISION;

  private static ColumnType declared(String type) {
    return ColumnType.declared(type, MAX);
  }

  /** The SQLSTATE a client would see for a failure. */
  private static String state(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      return PostgresServer.sqlState(e);
    }
    throw new AssertionError("expected a failure");
  }

  @ParameterizedTest
  @CsvSource({
    "'NUMERIC(10,2)', NUMERIC_SCALED, NUMERIC, SCALED_BIGINT, 10, 2, ",
    "'decimal(10, 2)', NUMERIC_SCALED, NUMERIC, SCALED_BIGINT, 10, 2, ",
    "'numeric(10)', NUMERIC_SCALED, NUMERIC, SCALED_BIGINT, 10, 0, ",
    "'numeric(18,4)', NUMERIC_SCALED, NUMERIC, SCALED_BIGINT, 18, 4, ",
    "'numeric(19,4)', NUMERIC_TEXT, NUMERIC, DECIMAL_TEXT, 19, 4, ",
    "'numeric(30,10)', NUMERIC_TEXT, NUMERIC, DECIMAL_TEXT, 30, 10, ",
    "'numeric', NUMERIC_TEXT, NUMERIC, DECIMAL_TEXT, , , ",
    "'DECIMAL', NUMERIC_TEXT, NUMERIC, DECIMAL_TEXT, , , ",
    "'numeric(5,-2)', NUMERIC_SCALED, NUMERIC, SCALED_BIGINT, 5, -2, ",
    "'numeric(2,5)', NUMERIC_SCALED, NUMERIC, SCALED_BIGINT, 2, 5, ",
    "'varchar(255)', VARCHAR, VARCHAR, TEXT, , , 255",
    "'character varying(20)', VARCHAR, VARCHAR, TEXT, , , 20",
    "'char(3)', CHAR, CHAR, TRIMMED_TEXT, , , 3",
    "'character', CHAR, CHAR, TRIMMED_TEXT, , , 1",
    "'CHAR', CHAR, CHAR, TRIMMED_TEXT, , , 1",
    "'bpchar(4)', CHAR, CHAR, TRIMMED_TEXT, , , 4",
    "'smallint', SMALLINT, SMALLINT, INT, , , ",
    "'int2', SMALLINT, SMALLINT, INT, , , ",
    "'json', JSON, JSON, TEXT, , , ",
    "'JSONB', JSONB, JSONB, TEXT, , , ",
  })
  public void declared_ModifiedTypes_ShouldHaveTheirRowAndReadItBack(
      String type,
      ColumnType.Kind kind,
      String sqlType,
      String encoding,
      Integer precision,
      Integer scale,
      Integer length) {
    ColumnType t = declared(type);
    assertThat(t.kind).isEqualTo(kind);
    assertThat(t.sqlType()).isEqualTo(sqlType);
    assertThat(t.encoding()).isEqualTo(encoding);
    assertThat(t.rowPrecision()).isEqualTo(precision);
    assertThat(t.rowScale()).isEqualTo(scale);
    assertThat(t.rowLength()).isEqualTo(length);
    ColumnType read = ColumnType.fromRow(sqlType, precision, scale, length, encoding, t.storage);
    assertThat(read.kind).isEqualTo(t.kind);
    assertThat(read.storage).isEqualTo(t.storage);
    assertThat(read.precision).isEqualTo(t.precision);
    assertThat(read.scale).isEqualTo(t.scale);
    assertThat(read.length).isEqualTo(t.length);
  }

  @Test
  public void declared_TypesScalarDbKeeps_ShouldBeNative() {
    assertThat(declared("varchar").kind).isEqualTo(ColumnType.Kind.NATIVE);
    assertThat(declared("varchar").storage).isEqualTo(DataType.TEXT);
    assertThat(declared("bpchar").kind).isEqualTo(ColumnType.Kind.NATIVE);
    assertThat(declared("integer").storage).isEqualTo(DataType.INT);
    assertThat(declared("float(10)").storage).isEqualTo(DataType.FLOAT);
    assertThat(declared("double precision").storage).isEqualTo(DataType.DOUBLE);
    assertThat(declared("timestamptz").storage).isEqualTo(DataType.TIMESTAMPTZ);
  }

  @Test
  public void declared_ModifiersPostgresRefuses_ShouldFailWith22023() {
    assertThat(state(() -> declared("numeric(1001)"))).isEqualTo("22023");
    assertThat(state(() -> declared("numeric(10,1001)"))).isEqualTo("22023");
    assertThat(state(() -> declared("varchar(0)"))).isEqualTo("22023");
    assertThat(state(() -> declared("char(10485761)"))).isEqualTo("22023");
  }

  @Test
  public void declared_WhereBigintIsExactTo53Bits_ShouldStoreSixteenDigitsAsText() {
    assertThat(ColumnType.declared("numeric(15,2)", 15).kind)
        .isEqualTo(ColumnType.Kind.NUMERIC_SCALED);
    assertThat(ColumnType.declared("numeric(16,2)", 15).kind)
        .isEqualTo(ColumnType.Kind.NUMERIC_TEXT);
  }

  @Test
  public void maxScaledPrecision_ShouldFollowTheStorage() {
    Properties p = new Properties();
    assertThat(ColumnType.maxScaledPrecision(p)).isEqualTo(18); // cassandra by default
    for (String storage : new String[] {"jdbc", "dynamo", "cassandra"}) {
      p.setProperty("scalar.db.storage", storage);
      assertThat(ColumnType.maxScaledPrecision(p)).as(storage).isEqualTo(18);
    }
    for (String storage :
        new String[] {"cosmos", "blob-storage", "cloud-storage", "s3", "multi-storage"}) {
      p.setProperty("scalar.db.storage", storage);
      assertThat(ColumnType.maxScaledPrecision(p)).as(storage).isEqualTo(15);
    }
    p.setProperty("scalar.db.storage", "jdbc");
    p.setProperty("scalar.db.transaction_manager", "cluster");
    assertThat(ColumnType.maxScaledPrecision(p)).isEqualTo(15);
  }

  @Test
  public void fromRow_RowsThisFrontendCannotRead_ShouldBeUnknownEncoding() {
    // an encoding that does not match the stored type, an unknown one, values out of bounds
    Object[][] rows = {
      {"NUMERIC", 10, 2, null, "SCALED_BIGINT", DataType.TEXT},
      {"CHAR", null, null, 3, "TRIMMED_TEXT", DataType.INT},
      {"NUMERIC", 10, 2, null, "PACKED_DECIMAL", DataType.BIGINT},
      {"MONEY", null, null, null, "INT", DataType.INT},
      {"NUMERIC", 19, 2, null, "SCALED_BIGINT", DataType.BIGINT},
      {"NUMERIC", 10, -1001, null, "SCALED_BIGINT", DataType.BIGINT},
      {"NUMERIC", null, 2, null, "DECIMAL_TEXT", DataType.TEXT},
      {"VARCHAR", null, null, 0, "TEXT", DataType.TEXT},
      {"CHAR", null, null, null, "TRIMMED_TEXT", DataType.TEXT},
      {"VARCHAR", null, null, 10485761, "TEXT", DataType.TEXT},
    };
    for (Object[] r : rows) {
      ColumnType t =
          ColumnType.fromRow(
              (String) r[0],
              (Integer) r[1],
              (Integer) r[2],
              (Integer) r[3],
              (String) r[4],
              (DataType) r[5]);
      assertThat(t.kind)
          .as(java.util.Arrays.toString(r))
          .isEqualTo(ColumnType.Kind.UNKNOWN_ENCODING);
      assertThat(t.storage).isEqualTo(r[5]); // read as stored
    }
  }

  @Test
  public void unknownEncoding_ShouldReadRawAndRefuseWrites() {
    ColumnType t = ColumnType.fromRow("NUMERIC", null, null, null, "ZZ", DataType.TEXT);
    assertThat(t.decode(TextColumn.of("x", "raw"), "t")).isEqualTo("raw");
    assertThat(state(() -> t.coerce("a"))).isEqualTo("0A000");
    assertThat(state(() -> t.exact("x", "a"))).isEqualTo("0A000");
  }

  @Test
  public void oidTypmodAndSqlName_ShouldBePostgresOnes() {
    assertThat(declared("numeric(10,2)").oid()).isEqualTo(1700);
    assertThat(declared("numeric(10,2)").typmod()).isEqualTo(655366);
    assertThat(declared("numeric(10,2)").sqlName()).isEqualTo("numeric(10,2)");
    assertThat(declared("numeric(5,-2)").typmod()).isEqualTo(329730); // PostgreSQL 18's value
    assertThat(declared("numeric(5,-2)").sqlName()).isEqualTo("numeric(5,-2)");
    assertThat(declared("numeric(2,5)").typmod()).isEqualTo(131081);
    assertThat(declared("numeric(30,10)").sqlName()).isEqualTo("numeric(30,10)");
    assertThat(declared("numeric").typmod()).isEqualTo(-1);
    assertThat(declared("numeric").sqlName()).isEqualTo("numeric");
    assertThat(declared("varchar(20)").oid()).isEqualTo(1043);
    assertThat(declared("varchar(20)").typmod()).isEqualTo(24);
    assertThat(declared("varchar(20)").sqlName()).isEqualTo("character varying(20)");
    assertThat(declared("char(3)").oid()).isEqualTo(1042);
    assertThat(declared("char(3)").sqlName()).isEqualTo("character(3)");
    assertThat(declared("smallint").oid()).isEqualTo(21);
    assertThat(declared("smallint").sqlName()).isEqualTo("smallint");
    assertThat(declared("integer").oid()).isEqualTo(23);
  }

  // ---- write coercion ----

  @Test
  public void coerce_ScaledNumeric_ShouldRoundHalfUpAndRefuseOverflow() {
    ColumnType t = declared("numeric(10,2)");
    assertThat(t.coerce("19.905")).isEqualTo(new BigDecimal("19.91"));
    assertThat(t.coerce(new BigDecimal("19.904"))).isEqualTo(new BigDecimal("19.90"));
    assertThat(t.coerce(-19.905)).isEqualTo(new BigDecimal("-19.91")); // half away from zero
    assertThat(t.coerce(0.1 + 0.2)).isEqualTo(new BigDecimal("0.30")); // 15 digits, as PostgreSQL
    assertThat(t.coerce(1.005)).isEqualTo(new BigDecimal("1.01"));
    assertThat(t.coerce(" 1e3 ")).isEqualTo(new BigDecimal("1000.00"));
    assertThat(t.coerce("-0")).isEqualTo(new BigDecimal("0.00"));
    assertThat(t.coerce("0012.5")).isEqualTo(new BigDecimal("12.50"));
    assertThat(t.coerce(7L)).isEqualTo(new BigDecimal("7.00"));
    assertThat(t.coerce(null)).isNull();
    assertThat(state(() -> t.coerce("123456789.995"))).isEqualTo("22003");
    assertThat(state(() -> t.coerce("99999999.995"))).isEqualTo("22003"); // rounds to 10^8
    assertThat(t.coerce("99999999.994")).isEqualTo(new BigDecimal("99999999.99"));
    assertThat(state(() -> t.coerce("Infinity"))).isEqualTo("22003");
    assertThat(state(() -> t.coerce("NaN"))).isEqualTo("0A000"); // no NaN in a scaled bigint
    assertThat(state(() -> t.coerce("abc"))).isEqualTo("22P02");
  }

  @Test
  public void coerce_HugeExponent_ShouldFailWithoutExpandingTheValue() {
    ColumnType t = declared("numeric(10,2)");
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          assertThat(state(() -> t.coerce("1e2147483647"))).isEqualTo("22003");
          assertThat(state(() -> declared("numeric").coerce("1e2147483647"))).isEqualTo("22003");
          assertThat(state(() -> t.coerce(new BigDecimal("1e100000")))).isEqualTo("22003");
        });
  }

  @Test
  public void coerce_NegativeScaleAndScaleAbovePrecision_ShouldRoundAsPostgres() {
    ColumnType hundreds = declared("numeric(5,-2)");
    BigDecimal v = (BigDecimal) hundreds.coerce(12345);
    assertThat(v).isEqualTo(new BigDecimal("12300"));
    assertThat(v.scale()).isZero(); // never 1.23E+4
    assertThat(Evaluator.text(hundreds.coerce(1234567))).isEqualTo("1234600");
    assertThat(state(() -> hundreds.coerce(12345678))).isEqualTo("22003");
    ColumnType tiny = declared("numeric(2,5)");
    assertThat(tiny.coerce("0.00012")).isEqualTo(new BigDecimal("0.00012"));
    assertThat(state(() -> tiny.coerce("0.001"))).isEqualTo("22003");
  }

  @Test
  public void coerce_UnboundedNumeric_ShouldKeepValueScaleAndSpecials() {
    ColumnType t = declared("numeric");
    assertThat((BigDecimal) t.coerce("1.10")).isEqualTo(new BigDecimal("1.10")); // scale kept
    assertThat(t.coerce(" 0012.5 ")).isEqualTo(new BigDecimal("12.5"));
    assertThat(t.coerce("1e3")).isEqualTo(new BigDecimal("1000"));
    assertThat(t.coerce("nan")).isEqualTo(Double.NaN);
    assertThat(t.coerce(" Infinity ")).isEqualTo(Double.POSITIVE_INFINITY);
    assertThat(t.coerce("+inf")).isEqualTo(Double.POSITIVE_INFINITY);
    assertThat(t.coerce("-infinity")).isEqualTo(Double.NEGATIVE_INFINITY);
    ColumnType wide = declared("numeric(30,10)");
    assertThat(wide.coerce("1.00000000005")).isEqualTo(new BigDecimal("1.0000000001"));
    assertThat(wide.coerce("NaN")).isEqualTo(Double.NaN); // PostgreSQL keeps NaN with a typmod
    assertThat(state(() -> wide.coerce("Infinity"))).isEqualTo("22003");
  }

  @Test
  public void coerce_VarcharAndChar_ShouldCutOnlySpacesAndCountCharacters() {
    ColumnType v3 = declared("varchar(3)");
    assertThat(v3.coerce("ab")).isEqualTo("ab");
    assertThat(v3.coerce("ab   ")).isEqualTo("ab "); // spaces beyond n are cut, as PostgreSQL does
    assertThat(v3.coerce("日本語")).isEqualTo("日本語");
    assertThat(state(() -> v3.coerce("abcd"))).isEqualTo("22001");
    assertThat(state(() -> v3.coerce("日本語x"))).isEqualTo("22001");
    assertThatThrownBy(() -> v3.coerce("abcd"))
        .hasMessage("value too long for type character varying(3)");
    assertThat(state(() -> v3.coerce("a\0b"))).isEqualTo("22021");
    ColumnType c3 = declared("char(3)");
    assertThat(c3.coerce("ab")).isEqualTo("ab");
    assertThat(c3.coerce("ab      ")).isEqualTo("ab"); // stored without trailing spaces
    assertThat(c3.coerce("a😀")).isEqualTo("a😀"); // two characters, three UTF-16 units
    assertThat(state(() -> c3.coerce("a😀bc"))).isEqualTo("22001");
    assertThatThrownBy(() -> c3.coerce("abcd")).hasMessage("value too long for type character(3)");
    assertThat(declared("char").coerce("a")).isEqualTo("a");
    assertThat(state(() -> declared("char").coerce("ab"))).isEqualTo("22001");
  }

  @Test
  public void coerce_Smallint_ShouldRoundAndCheckTheRange() {
    ColumnType t = declared("smallint");
    assertThat(t.coerce(32767L)).isEqualTo(32767);
    assertThat(t.coerce(new BigDecimal("2.5"))).isEqualTo(3);
    assertThat(t.coerce(" 42 ")).isEqualTo(42);
    assertThat(state(() -> t.coerce(32768L))).isEqualTo("22003");
    assertThat(state(() -> t.coerce(-32769L))).isEqualTo("22003");
  }

  // ---- exact conversion ----

  @Test
  public void exact_ScaledNumeric_ShouldStoreUnscaledOrSayWhyNot() {
    ColumnType t = declared("numeric(10,2)");
    assertThat(t.exact("a", new BigDecimal("19.9"))).isEqualTo(BigIntColumn.of("a", 1990));
    assertThat(t.exact("a", 19.9)).isEqualTo(BigIntColumn.of("a", 1990));
    assertThat(t.exact("a", "-0.01")).isEqualTo(BigIntColumn.of("a", -1));
    assertThat(t.exact("a", 0L)).isEqualTo(BigIntColumn.of("a", 0));
    assertThat(t.exact("a", null)).isEqualTo(BigIntColumn.ofNull("a"));
    assertThatThrownBy(() -> t.exact("a", new BigDecimal("1.005")))
        .isInstanceOf(PgException.NotRepresentable.class);
    assertThatThrownBy(() -> t.exact("a", new BigDecimal("1e9")))
        .isInstanceOfSatisfying(PgException.OutOfRange.class, e -> assertThat(e.above()).isTrue());
    assertThatThrownBy(() -> t.exact("a", new BigDecimal("-1e9")))
        .isInstanceOfSatisfying(PgException.OutOfRange.class, e -> assertThat(e.above()).isFalse());
    assertThatThrownBy(() -> t.exact("a", "NaN")).isInstanceOf(PgException.NotRepresentable.class);
    assertThatThrownBy(() -> t.exact("a", "-Infinity"))
        .isInstanceOfSatisfying(PgException.OutOfRange.class, e -> assertThat(e.above()).isFalse());
    ColumnType hundreds = declared("numeric(5,-2)");
    assertThat(hundreds.exact("r", 12300)).isEqualTo(BigIntColumn.of("r", 123));
    assertThat(hundreds.exact("r", 0)).isEqualTo(BigIntColumn.of("r", 0));
    assertThatThrownBy(() -> hundreds.exact("r", 12345))
        .isInstanceOf(PgException.NotRepresentable.class);
  }

  @Test
  public void exact_TextNumeric_ShouldStoreCanonicalText() {
    assertThat(declared("numeric").exact("t", new BigDecimal("1.10")))
        .isEqualTo(TextColumn.of("t", "1.10"));
    assertThat(declared("numeric").exact("t", "1e3")).isEqualTo(TextColumn.of("t", "1000"));
    assertThat(declared("numeric").exact("t", "-inf")).isEqualTo(TextColumn.of("t", "-Infinity"));
    assertThat(declared("numeric(30,10)").exact("w", new BigDecimal("1.5")))
        .isEqualTo(TextColumn.of("w", "1.5000000000"));
    assertThat(declared("numeric(30,10)").exact("w", "NaN")).isEqualTo(TextColumn.of("w", "NaN"));
    assertThatThrownBy(() -> declared("numeric(30,10)").exact("w", new BigDecimal("1e-11")))
        .isInstanceOf(PgException.NotRepresentable.class);
  }

  @Test
  public void exact_TextKindsAndSmallint_ShouldNeverPadTruncateOrRound() {
    ColumnType v5 = declared("varchar(5)");
    assertThat(v5.exact("v", "ab   ")).isEqualTo(TextColumn.of("v", "ab   ")); // significant
    assertThatThrownBy(() -> v5.exact("v", "abcdefgh"))
        .isInstanceOf(PgException.NotRepresentable.class);
    ColumnType c3 = declared("char(3)");
    assertThat(c3.exact("c", "ab    ")).isEqualTo(TextColumn.of("c", "ab")); // insignificant
    assertThatThrownBy(() -> c3.exact("c", "abcd"))
        .isInstanceOf(PgException.NotRepresentable.class);
    ColumnType s = declared("smallint");
    assertThat(s.exact("s", 7L)).isEqualTo(IntColumn.of("s", 7));
    assertThat(s.exact("s", new BigDecimal("7.0"))).isEqualTo(IntColumn.of("s", 7));
    assertThatThrownBy(() -> s.exact("s", 40000L))
        .isInstanceOfSatisfying(PgException.OutOfRange.class, e -> assertThat(e.above()).isTrue());
    assertThatThrownBy(() -> s.exact("s", new BigDecimal("1.5")))
        .isInstanceOf(PgException.NotRepresentable.class);
  }

  @Test
  public void coerceThenExact_ShouldAlwaysSucceedForWhatCoerceAccepts() {
    Object[][] cases = {
      {"numeric(10,2)", "19.905"},
      {"numeric(5,-2)", 12345},
      {"numeric(2,5)", "0.00012"},
      {"numeric", "1.10"},
      {"numeric", "nan"},
      {"numeric(30,10)", "123.45678901234"},
      {"varchar(3)", "ab   "},
      {"char(3)", "ab      "},
      {"smallint", new BigDecimal("2.5")},
    };
    for (Object[] c : cases) {
      ColumnType t = declared((String) c[0]);
      Column<?> stored = t.exact("x", t.coerce(c[1]));
      // and what was stored reads back as the coerced value
      assertThat(t.decode(stored, "t")).as(c[0] + " " + c[1]).isEqualTo(t.coerce(c[1]));
    }
  }

  // ---- decoding ----

  @Test
  public void decode_ValidStoredValues_ShouldBeTheDeclaredType() {
    ColumnType money = declared("numeric(10,2)");
    BigDecimal d = (BigDecimal) money.decode(BigIntColumn.of("a", 1991), "t");
    assertThat(d).isEqualTo(new BigDecimal("19.91"));
    assertThat(d.toPlainString()).isEqualTo("19.91");
    assertThat(String.valueOf(money.decode(BigIntColumn.of("a", 1990), "t")))
        .isEqualTo("19.90"); // the scale is kept
    assertThat(money.decode(BigIntColumn.ofNull("a"), "t")).isNull();
    assertThat(declared("numeric(5,-2)").decode(BigIntColumn.of("r", 123), "t"))
        .isEqualTo(new BigDecimal("12300"));
    ColumnType t = declared("numeric");
    assertThat(t.decode(TextColumn.of("x", "1.10"), "t")).isEqualTo(new BigDecimal("1.10"));
    assertThat(t.decode(TextColumn.of("x", "NaN"), "t")).isEqualTo(Double.NaN);
    assertThat(t.decode(TextColumn.of("x", "-Infinity"), "t")).isEqualTo(Double.NEGATIVE_INFINITY);
    assertThat(declared("numeric(30,10)").decode(TextColumn.of("w", "1.5"), "t"))
        .isEqualTo(new BigDecimal("1.5000000000"));
    assertThat(declared("char(3)").decode(TextColumn.of("c", "ab "), "t")).isEqualTo("ab");
    assertThat(declared("varchar(3)").decode(TextColumn.of("v", "ab "), "t")).isEqualTo("ab ");
    assertThat(declared("smallint").decode(IntColumn.of("s", -32768), "t")).isEqualTo(-32768);
    assertThat(ColumnType.of(DataType.INT).decode(IntColumn.of("id", 5), "t")).isEqualTo(5);
  }

  @Test
  public void decode_ValuesBreakingTheContract_ShouldFailWithXx001WithoutTheValue() {
    Object[][] cases = {
      {declared("numeric(10,2)"), BigIntColumn.of("a", 10_000_000_000L)},
      {declared("numeric"), TextColumn.of("x", "abc")},
      {declared("numeric"), TextColumn.of("x", "1e3")},
      {declared("numeric(30,10)"), TextColumn.of("w", "1.00000000001")},
      {declared("numeric(30,10)"), TextColumn.of("w", "Infinity")},
      {declared("varchar(3)"), TextColumn.of("v", "abcd")},
      {declared("char(3)"), TextColumn.of("c", "abcd")},
      {declared("smallint"), IntColumn.of("s", 40000)},
      {declared("jsonb"), TextColumn.of("j", "{bad")},
    };
    for (Object[] c : cases) {
      ColumnType t = (ColumnType) c[0];
      Column<?> stored = (Column<?>) c[1];
      assertThatThrownBy(() -> t.decode(stored, "orders"))
          .as(stored.toString())
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo("XX001"))
          .hasMessageContaining("column \"" + stored.getName() + "\"")
          .hasMessageContaining("table \"orders\"")
          .hasMessageNotContaining(String.valueOf(stored.getValueAsObject()));
    }
  }

  @Test
  public void applyTypmod_CastsAndWritesShareTheRounding() {
    assertThat(Evaluator.cast("numeric(5,-2)", 12345L)).isEqualTo(new BigDecimal("12300"));
    assertThat(Evaluator.text(Evaluator.cast("numeric(5,-2)", 12345L))).isEqualTo("12300");
    assertThat(Evaluator.cast("numeric(10,2)", "19.905")).isEqualTo(new BigDecimal("19.91"));
    assertThat(state(() -> Evaluator.cast("numeric(3,0)", 12345L))).isEqualTo("22003");
  }

  @Test
  public void exact_QuotedSmallint_ShouldBeReadAsSmallintInput() {
    // WHERE s = '1.5' is smallint input in PostgreSQL (22P02), unlike the numeric literal 1.5
    ColumnType s = declared("smallint");
    assertThat(s.exact("s", "7")).isEqualTo(IntColumn.of("s", 7));
    assertThat(state(() -> s.exact("s", "1.5"))).isEqualTo("22P02");
    assertThat(state(() -> s.exact("s", "NaN"))).isEqualTo("22P02");
    assertThat(state(() -> s.exact("s", "40000"))).isEqualTo("22003");
  }

  @Test
  public void comparableWhenStored_ShouldBeFalseOnlyForTextStoredNumerics() {
    assertThat(declared("numeric(10,2)").comparableWhenStored()).isTrue();
    assertThat(declared("varchar(5)").comparableWhenStored()).isTrue();
    assertThat(declared("char(3)").comparableWhenStored()).isTrue();
    assertThat(declared("smallint").comparableWhenStored()).isTrue();
    assertThat(declared("integer").comparableWhenStored()).isTrue();
    assertThat(declared("numeric").comparableWhenStored()).isFalse(); // "10" < "9" as text
    assertThat(declared("numeric(30,10)").comparableWhenStored()).isFalse();
    assertThat(
            ColumnType.fromRow("NUMERIC", null, null, null, "ZZ", DataType.TEXT)
                .comparableWhenStored())
        .isFalse();
  }

  @Test
  public void json_ShouldKeepItsTextAndJsonbBeNormalised() {
    String written = "{\"z\" : [1 ,2], \"a\": 1, \"a\": 1.50}";
    ColumnType json = declared("json");
    ColumnType jsonb = declared("jsonb");
    assertThat(json.exact("j", json.coerce(written))).isEqualTo(TextColumn.of("j", written));
    assertThat(jsonb.exact("b", jsonb.coerce(written)))
        .isEqualTo(TextColumn.of("b", "{\"a\": 1.50, \"z\": [1, 2]}"));
    assertThat(state(() -> json.coerce("{bad"))).isEqualTo("22P02");
    // read back as the declared type: json keeps its text, jsonb reads unnormalised text as jsonb
    assertThat(String.valueOf(json.decode(TextColumn.of("j", written), "t"))).isEqualTo(written);
    assertThat(String.valueOf(jsonb.decode(TextColumn.of("b", "{\"z\":1, \"a\" : 2}"), "t")))
        .isEqualTo("{\"a\": 2, \"z\": 1}");
    assertThat(json.oid()).isEqualTo(114);
    assertThat(jsonb.oid()).isEqualTo(3802);
    assertThat(jsonb.comparableWhenStored()).isFalse();
    assertThat(state(() -> jsonb.checkKeyable("b", 18))).isEqualTo("0A000");
  }

  @Test
  public void decode_SmallestLong_ShouldNotPassTheBoundCheck() {
    assertThatThrownBy(
            () -> declared("numeric(18,0)").decode(BigIntColumn.of("a", Long.MIN_VALUE), "t"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("XX001"));
    assertThat(
            declared("numeric(18,0)").decode(BigIntColumn.of("a", 999_999_999_999_999_999L), "t"))
        .isEqualTo(new BigDecimal("999999999999999999"));
  }

  @Test
  public void cast_NumericModifiersPostgresRefuses_ShouldFailWith22023() {
    assertThat(state(() -> Evaluator.cast("numeric(0)", "1"))).isEqualTo("22023");
    assertThat(state(() -> Evaluator.cast("numeric(5,-5000)", 1L))).isEqualTo("22023");
    assertThat(state(() -> Evaluator.cast("numeric(99999999999)", 1L))).isEqualTo("22023");
    assertThat(Evaluator.cast("numeric(1000,0)", 1L)).isEqualTo(new BigDecimal("1"));
  }
}
