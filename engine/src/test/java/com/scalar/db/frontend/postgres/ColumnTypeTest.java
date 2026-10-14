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

/** The declared-type codec: names, write coercion, exact conversion and validated decoding. */
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
    "'NUMERIC(10,2)', NUMERIC_SCALED, n10_2",
    "'decimal(10, 2)', NUMERIC_SCALED, n10_2",
    "'numeric(10)', NUMERIC_SCALED, n10_0",
    "'numeric(18,4)', NUMERIC_SCALED, n18_4",
    "'numeric(19,4)', NUMERIC_TEXT, t19_4",
    "'numeric(30,10)', NUMERIC_TEXT, t30_10",
    "'numeric', NUMERIC_TEXT, t",
    "'DECIMAL', NUMERIC_TEXT, t",
    "'numeric(5,-2)', NUMERIC_SCALED, n5_m2",
    "'numeric(2,5)', NUMERIC_SCALED, n2_5",
    "'varchar(255)', VARCHAR, v255",
    "'character varying(20)', VARCHAR, v20",
    "'char(3)', CHAR, c3",
    "'character', CHAR, c1",
    "'CHAR', CHAR, c1",
    "'bpchar(4)', CHAR, c4",
    "'smallint', SMALLINT, s",
    "'int2', SMALLINT, s",
  })
  public void declared_ModifiedTypes_ShouldHaveTheirCode(
      String type, ColumnType.Kind kind, String code) {
    ColumnType t = declared(type);
    assertThat(t.kind).isEqualTo(kind);
    assertThat(t.code()).isEqualTo(code);
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

  @ParameterizedTest
  @CsvSource({
    "amount, 'numeric(10,2)', amount__pg_n10_2, BIGINT",
    "big, 'numeric(30,10)', big__pg_t30_10, TEXT",
    "total, 'numeric', total__pg_t, TEXT",
    "r, 'numeric(5,-2)', r__pg_n5_m2, BIGINT",
    "email, 'varchar(255)', email__pg_v255, TEXT",
    "code, 'char(3)', code__pg_c3, TEXT",
    "qty, 'smallint', qty__pg_s, INT",
    "id, 'integer', id, INT",
  })
  public void storageNameAndParse_ShouldRoundTrip(
      String client, String type, String storage, DataType storageType) {
    ColumnType t = declared(type);
    assertThat(t.storageName(client)).isEqualTo(storage);
    assertThat(t.storage).isEqualTo(storageType);
    ColumnType parsed = ColumnType.parse(storage, storageType);
    assertThat(parsed.kind).isEqualTo(t.kind);
    assertThat(parsed.precision).isEqualTo(t.precision);
    assertThat(parsed.scale).isEqualTo(t.scale);
    assertThat(parsed.length).isEqualTo(t.length);
    assertThat(parsed.clientName(storage)).isEqualTo(client);
  }

  @Test
  public void parse_SuffixesThisFrontendCannotRead_ShouldBeUnknownEncoding() {
    // a code that does not match the stored type, an unknown code, a code out of bounds
    for (Object[] c :
        new Object[][] {
          {"amount__pg_n10_2", DataType.TEXT},
          {"code__pg_c3", DataType.INT},
          {"x__pg_zz", DataType.TEXT},
          {"x__pg_n19_2", DataType.BIGINT},
          {"x__pg_n10_m1001", DataType.BIGINT},
          {"x__pg_v0", DataType.TEXT},
          {"x__pg_c99999999999", DataType.TEXT},
          {"__pg_s", DataType.INT},
        }) {
      ColumnType t = ColumnType.parse((String) c[0], (DataType) c[1]);
      assertThat(t.kind).as((String) c[0]).isEqualTo(ColumnType.Kind.UNKNOWN_ENCODING);
      assertThat(t.clientName((String) c[0])).isEqualTo(c[0]); // shown raw
    }
    assertThat(ColumnType.parse("amount", DataType.BIGINT).kind).isEqualTo(ColumnType.Kind.NATIVE);
  }

  @Test
  public void unknownEncoding_ShouldReadRawAndRefuseWrites() {
    ColumnType t = ColumnType.parse("x__pg_zz", DataType.TEXT);
    assertThat(t.decode(TextColumn.of("x__pg_zz", "raw"), "t")).isEqualTo("raw");
    assertThat(state(() -> t.coerce("a"))).isEqualTo("0A000");
    assertThat(state(() -> t.exact("x__pg_zz", "a"))).isEqualTo("0A000");
  }

  @Test
  public void storageName_ReservedMarkerOrTooLong_ShouldFail() {
    assertThat(state(() -> ColumnType.checkClientName("a__pg_b"))).isEqualTo("42602");
    assertThat(state(() -> declared("integer").storageName("x__pg_s"))).isEqualTo("42602");
    ColumnType money = declared("numeric(10,2)"); // suffix __pg_n10_2: 10 bytes of the 56
    assertThat(money.storageName("a".repeat(46))).hasSize(56);
    assertThatThrownBy(() -> money.storageName("a".repeat(47)))
        .hasMessageContaining("at most 46 bytes")
        .hasMessageContaining("numeric(10,2)");
    assertThat(state(() -> money.storageName("a".repeat(47)))).isEqualTo("42622");
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
      String name = t.storageName("x");
      Column<?> stored = t.exact(name, t.coerce(c[1]));
      // and what was stored reads back as the coerced value
      assertThat(t.decode(stored, "t")).as(c[0] + " " + c[1]).isEqualTo(t.coerce(c[1]));
    }
  }

  // ---- decoding ----

  @Test
  public void decode_ValidStoredValues_ShouldBeTheDeclaredType() {
    ColumnType money = declared("numeric(10,2)");
    BigDecimal d = (BigDecimal) money.decode(BigIntColumn.of("a__pg_n10_2", 1991), "t");
    assertThat(d).isEqualTo(new BigDecimal("19.91"));
    assertThat(d.toPlainString()).isEqualTo("19.91");
    assertThat(String.valueOf(money.decode(BigIntColumn.of("a__pg_n10_2", 1990), "t")))
        .isEqualTo("19.90"); // the scale is kept
    assertThat(money.decode(BigIntColumn.ofNull("a__pg_n10_2"), "t")).isNull();
    assertThat(declared("numeric(5,-2)").decode(BigIntColumn.of("r__pg_n5_m2", 123), "t"))
        .isEqualTo(new BigDecimal("12300"));
    ColumnType t = declared("numeric");
    assertThat(t.decode(TextColumn.of("x__pg_t", "1.10"), "t")).isEqualTo(new BigDecimal("1.10"));
    assertThat(t.decode(TextColumn.of("x__pg_t", "NaN"), "t")).isEqualTo(Double.NaN);
    assertThat(t.decode(TextColumn.of("x__pg_t", "-Infinity"), "t"))
        .isEqualTo(Double.NEGATIVE_INFINITY);
    assertThat(declared("numeric(30,10)").decode(TextColumn.of("w__pg_t30_10", "1.5"), "t"))
        .isEqualTo(new BigDecimal("1.5000000000"));
    assertThat(declared("char(3)").decode(TextColumn.of("c__pg_c3", "ab "), "t")).isEqualTo("ab");
    assertThat(declared("varchar(3)").decode(TextColumn.of("v__pg_v3", "ab "), "t"))
        .isEqualTo("ab ");
    assertThat(declared("smallint").decode(IntColumn.of("s__pg_s", -32768), "t")).isEqualTo(-32768);
    assertThat(ColumnType.of(DataType.INT).decode(IntColumn.of("id", 5), "t")).isEqualTo(5);
  }

  @Test
  public void decode_ValuesBreakingTheContract_ShouldFailWithXx001WithoutTheValue() {
    Object[][] cases = {
      {declared("numeric(10,2)"), BigIntColumn.of("a__pg_n10_2", 10_000_000_000L)},
      {declared("numeric"), TextColumn.of("x__pg_t", "abc")},
      {declared("numeric"), TextColumn.of("x__pg_t", "1e3")},
      {declared("numeric(30,10)"), TextColumn.of("w__pg_t30_10", "1.00000000001")},
      {declared("numeric(30,10)"), TextColumn.of("w__pg_t30_10", "Infinity")},
      {declared("varchar(3)"), TextColumn.of("v__pg_v3", "abcd")},
      {declared("char(3)"), TextColumn.of("c__pg_c3", "abcd")},
      {declared("smallint"), IntColumn.of("s__pg_s", 40000)},
    };
    for (Object[] c : cases) {
      ColumnType t = (ColumnType) c[0];
      Column<?> stored = (Column<?>) c[1];
      assertThatThrownBy(() -> t.decode(stored, "orders"))
          .as(stored.toString())
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo("XX001"))
          .hasMessageContaining("column \"" + t.clientName(stored.getName()) + "\"")
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
    assertThat(ColumnType.parse("x__pg_zz", DataType.TEXT).comparableWhenStored()).isFalse();
  }

  @Test
  public void decode_SmallestLong_ShouldNotPassTheBoundCheck() {
    assertThatThrownBy(
            () ->
                declared("numeric(18,0)")
                    .decode(BigIntColumn.of("a__pg_n18_0", Long.MIN_VALUE), "t"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("XX001"));
    assertThat(
            declared("numeric(18,0)")
                .decode(BigIntColumn.of("a__pg_n18_0", 999_999_999_999_999_999L), "t"))
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
