package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.exception.transaction.CommitConflictException;
import com.scalar.db.exception.transaction.CrudConflictException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The static helpers the SQL-level tests reach only indirectly. */
public class HelpersTest {
  @Test
  public void inetText_ShouldPrintAddressesAsPostgreSqlInetDoes() throws Exception {
    assertThat(Catalog.inetText(java.net.InetAddress.getByName("::1"))).isEqualTo("::1");
    assertThat(Catalog.inetText(java.net.InetAddress.getByName("127.0.0.1")))
        .isEqualTo("127.0.0.1");
    assertThat(Catalog.inetText(java.net.InetAddress.getByName("2001:db8:0:0:1:0:0:1")))
        .isEqualTo("2001:db8::1:0:0:1"); // the first of two equal runs
    assertThat(Catalog.inetText(java.net.InetAddress.getByName("1:0:2:3:4:5:6:7")))
        .isEqualTo("1:0:2:3:4:5:6:7"); // a single 0 group stays
    assertThat(Catalog.inetText(java.net.InetAddress.getByName("fe80::"))).isEqualTo("fe80::");
  }

  @ParameterizedTest
  @CsvSource({
    "2024-01-02T03:04:05, 2024-01-02 03:04:05",
    "2024-01-02T03:04:05.120, 2024-01-02 03:04:05.12",
    "0099-12-31T23:59:59.000001, 0099-12-31 23:59:59.000001",
    "-0044-03-15T00:00:00.5, 0045-03-15 00:00:00.5 BC", // the year -44 is 45 BC
    "+12345-01-01T00:00:00, 12345-01-01 00:00:00"
  })
  public void timestampText_ShouldPadAsFormatDid(String value, String text) {
    java.time.LocalDateTime t = java.time.LocalDateTime.parse(value);
    assertThat(Evaluator.timestampText(t)).isEqualTo(text);
    assertThat(Evaluator.text(t.toLocalTime()))
        .isEqualTo(text.substring(text.indexOf(' ') + 1).replace(" BC", ""));
    assertThat(Evaluator.hex(new byte[] {0, 15, -1})).isEqualTo("000fff");
    assertThat(Evaluator.offsetText(java.time.ZoneOffset.ofHoursMinutes(-5, -30)))
        .isEqualTo("-05:30");
    assertThat(Evaluator.offsetText(java.time.ZoneOffset.ofHours(9))).isEqualTo("+09");
  }

  @Test
  public void integerInput_ShouldReadIntegersAsPostgreSql16Does() {
    assertThat(Evaluator.integerInput(" -0x1F ")).isEqualTo(-31);
    assertThat(Evaluator.integerInput("0o_17")).isEqualTo(15);
    assertThat(Evaluator.integerInput("+0B101")).isEqualTo(5);
    assertThat(Evaluator.integerInput("1_000")).isEqualTo(1000);
    for (String bad : Arrays.asList("1__0", "_1", "1_", "0x", "0x_", "1.0", "1e3", "00x1", "")) {
      assertThat(Evaluator.integerInput(bad)).as(bad).isNull();
    }
  }

  static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(new StackOverflowError(), "54001"),
        Arguments.of(new OutOfMemoryError(), "XX000"),
        Arguments.of(new PgException.Argument("42883", "no such function"), "42883"),
        Arguments.of(new RuntimeException("DB-AUTH-10005: bad password"), "28P01"),
        Arguments.of(new RuntimeException("DB-AUTH-10006: bad token"), "28000"),
        Arguments.of(new RuntimeException("DB-AUTH-10000: user exists"), "42710"),
        Arguments.of(new RuntimeException("DB-AUTH-10021: role exists"), "42710"),
        Arguments.of(new RuntimeException("DB-AUTH-10001: no user"), "42704"),
        Arguments.of(new RuntimeException("DB-AUTH-10022: no role"), "42704"),
        Arguments.of(new RuntimeException("DB-AUTH-10003: no namespace"), "3F000"),
        Arguments.of(new RuntimeException("DB-AUTH-10004: no table"), "42P01"),
        Arguments.of(new RuntimeException("DB-AUTH-10023: role cycle"), "0LP01"),
        Arguments.of(new RuntimeException("DB-AUTH-10013: own superuser"), "55006"),
        Arguments.of(new RuntimeException("DB-AUTH-10014: drop yourself"), "55006"),
        Arguments.of(new RuntimeException("DB-AUTH-30000: Cluster failed"), "XX000"),
        Arguments.of(new RuntimeException("DB-AUTH-10010: not allowed"), "42501"),
        Arguments.of(new RuntimeException("DB-ABAC-10037: data tag in condition"), "0A000"),
        Arguments.of(new RuntimeException("DB-ABAC-10042: data tag in ordering"), "0A000"),
        Arguments.of(new RuntimeException("DB-ABAC-10030: invalid data tag"), "22023"),
        Arguments.of(new RuntimeException("DB-ABAC-10031: no level in data tag"), "22023"),
        Arguments.of(new RuntimeException("DB-ABAC-10032: invalid user tag"), "22023"),
        Arguments.of(new RuntimeException("DB-ABAC-10033: no level in user tag"), "22023"),
        Arguments.of(new RuntimeException("DB-ABAC-10039: invalid token"), "28000"),
        Arguments.of(new RuntimeException("DB-ABAC-10036: data tag not allowed"), "42501"),
        Arguments.of(new RuntimeException("DB-ABAC-30004: Cluster failed"), "XX000"),
        Arguments.of(new RuntimeException("DB-ENCRYPTION-10000: encrypted key"), "0A000"),
        Arguments.of(new RuntimeException("DB-ENCRYPTION-30008: decrypting failed"), "XX000"),
        Arguments.of(new UnsupportedOperationException("DB-CORE-10143: no encryption"), "0A000"),
        Arguments.of(new UnsupportedOperationException("DB-CORE-10152: no ABAC"), "0A000"),
        Arguments.of(new IllegalArgumentException("DB-CORE-10309: LIKE under ICU"), "0A000"),
        Arguments.of(new RuntimeException("DB-CORE-10026: bad escape"), "22025"),
        Arguments.of(new RuntimeException("DB-CORE-10211: read only"), "25006"),
        Arguments.of(new RuntimeException("DB-CORE-10020: bad key"), "22023"),
        Arguments.of(new RuntimeException("DB-CORE-10017: bad value"), "22023"),
        Arguments.of(new RuntimeException("DB-CORE-10052: table exists"), "42P07"),
        Arguments.of(new RuntimeException("DB-CORE-10053: namespace not empty"), "2BP01"),
        Arguments.of(new Plan.UniqueViolationException(new RuntimeException("dup")), "23505"),
        Arguments.of(new QueryProcessor.AbortedTransactionException(), "25P02"),
        Arguments.of(new CrudConflictException("conflict", "tx"), "40001"),
        Arguments.of(new CommitConflictException("conflict", "tx"), "40001"),
        Arguments.of(new UnknownTransactionStatusException("unknown", "tx"), "40003"),
        Arguments.of(new ArithmeticException("/ by zero"), "22012"),
        Arguments.of(new ArithmeticException("Overflow"), "22003"),
        Arguments.of(new DateTimeException("bad date"), "22007"),
        Arguments.of(new NumberFormatException("abc"), "22P02"),
        Arguments.of(
            new IllegalStateException("create failed", new SQLException("exists", "42P07")),
            "42P07"),
        Arguments.of(
            new IllegalStateException("vendor", new SQLException("other", "HY000")), "XX000"),
        Arguments.of(new IllegalArgumentException("Unsupported statement"), "42601"),
        Arguments.of(new IllegalStateException("metadata read failed"), "XX000"));
  }

  @ParameterizedTest
  @MethodSource("failures")
  public void sqlState_ShouldMapEachFailure(Throwable failure, String expected) {
    assertThat(PostgresServer.sqlState(failure)).isEqualTo(expected);
  }

  @Test
  public void split_ShouldSplitOnSemicolonsOutsideQuotesAndComments() {
    assertThat(PostgresServer.split("SELECT 1; SELECT ';' AS \"a;b\"; -- c;\n"))
        .containsExactly("SELECT 1", "SELECT ';' AS \"a;b\"");
    assertThat(PostgresServer.split("SELECT $$x;y$$; /* ; */ ;")).containsExactly("SELECT $$x;y$$");
    assertThat(PostgresServer.split("-- only a comment")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "1 year 2 mons 3 days 04:05:06.5|1 year 2 mons 3 days 04:05:06.5",
        "1.5 days|1 day 12:00:00",
        "@ 2 hours ago|-02:00:00",
        "P1Y2M3DT4H5M6S|1 year 2 mons 3 days 04:05:06",
        "90|00:01:30",
        "1 2:03:04|1 day 02:03:04",
        "-1 +02:03|-1 days +02:03:00"
      })
  public void intervalParse_ShouldReadPostgreSqlInputForms(String input, String output) {
    assertThat(Interval.parse(input).toString()).isEqualTo(output);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "0|iso_8601|PT0S",
        "-1 days +02:03:04|iso_8601|P-1DT2H3M4S",
        "-0.7 seconds|iso_8601|PT-0.7S",
        "0|postgres_verbose|@ 0",
        "-1 days +02:00:00|postgres_verbose|@ 1 day -2 hours ago",
        "0.7 seconds|postgres_verbose|@ 0.7 secs",
        "-1 second|postgres_verbose|@ 1 sec ago",
        "0|sql_standard|0",
        "1 year 2 mons|sql_standard|1-2",
        "-1 days -02:03:04.5|sql_standard|-1 2:03:04.5",
        "-1 days +02:03:04|sql_standard|+0-0 -1 +2:03:04",
        "1 mon 1 sec|sql_standard|+0-1 +0 +0:00:01"
      })
  public void intervalText_ShouldWriteEachIntervalStyle(String input, String style, String output) {
    assertThat(Interval.parse(input).text(style)).isEqualTo(output);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "1 day 1 day|22007",
        "1 hour 02:00:00|22007",
        "1 2|22007",
        "1-2|22007", // years and months in PostgreSQL, not read here
        "''|22007",
        "2562047789 hours|22015",
        "2147483648 days|22015"
      })
  public void intervalParse_ShouldRefuseWhatItCannotRead(String input, String state) {
    assertThatThrownBy(() -> Interval.parse(input))
        .isInstanceOfSatisfying(PgException.class, e -> assertThat(e.sqlState()).isEqualTo(state));
  }

  @Test
  public void json_ShouldFollowJsonbSemantics() {
    assertThatThrownBy(() -> Json.cast("true, false", false))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22P02"));
    assertThatThrownBy(() -> Json.cast("\"\\u0000\"", true))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22P05"));
    assertThat(QueryParser.parseArrayText("{a\\,b,\\NULL}")).containsExactly("a,b", "NULL");
    for (String bad : new String[] {"{}}", "{\"a}"}) {
      assertThatThrownBy(() -> QueryParser.parseArrayText(bad))
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22P02"));
    }
    assertThatThrownBy(() -> QueryParser.normalize("SELECT ARRAY[]"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("42P18"));
    assertThat(Json.contains(Json.parse("{\"a\":1,\"b\":[1,2]}"), Json.parse("{\"b\":[2]}")))
        .isTrue();
    assertThat(Json.contains(Json.parse("[1,2]"), Json.parse("[3]"))).isFalse();
    assertThat(Json.same(Json.parse("1.0"), Json.parse("1"))).isTrue();
    assertThat(Json.delete(Json.parse("{\"a\":1,\"b\":2}"), "a").toString()).isEqualTo("{\"b\":2}");
    assertThat(Json.delete(Json.parse("[\"a\",\"b\",\"a\"]"), "a").toString()).isEqualTo("[\"b\"]");
    assertThatThrownBy(() -> Json.delete(Json.parse("{\"a\":1}"), 0L))
        .hasMessageContaining("integer index");
    assertThat(Json.path(Json.parse("{\"a\":[10,{\"b\":7}]}"), Arrays.asList("a", "1", "b")))
        .hasToString("7");
    assertThat(Json.path(Json.parse("{\"a\":1}"), Collections.singletonList("x"))).isNull();
  }

  @Test
  public void bytea_ShouldReadHexAndEscapeFormats() {
    assertThat(Evaluator.hex(QueryParser.bytea("\\x de AD"))).isEqualTo("dead");
    assertThat(Evaluator.hex(QueryParser.bytea("DeAd"))).isEqualTo("44654164"); // escape format
    assertThat(Evaluator.hex(QueryParser.bytea("a\\\\b\\101\\000"))).isEqualTo("615c624100");
    assertThat(Evaluator.hex(QueryParser.bytea("\u00e9"))).isEqualTo("c3a9");
    for (String bad : new String[] {"\\xdea", "\\xgg", "a\\b", "a\\400"}) {
      assertThatThrownBy(() -> QueryParser.bytea(bad)).as(bad).isInstanceOf(PgException.class);
    }
  }

  @Test
  public void stringAggOfBytea_ShouldJoinTheBytes() {
    java.nio.ByteBuffer ff = java.nio.ByteBuffer.wrap(new byte[] {(byte) 0xff});
    java.nio.ByteBuffer aa = java.nio.ByteBuffer.wrap(new byte[] {(byte) 0xaa});
    net.sf.jsqlparser.expression.Function f = new net.sf.jsqlparser.expression.Function();
    Object joined =
        Evaluator.aggregateValues(
            "STRING_AGG",
            new java.util.ArrayList<>(List.of(ff, aa)),
            java.nio.ByteBuffer.wrap(new byte[] {(byte) 0xee}),
            f);
    assertThat(Evaluator.text(joined)).isEqualTo("\\xffeeaa");
    // an untyped '' delimiter is bytea input, and no delimiter joins them directly
    assertThat(Evaluator.text(Evaluator.aggregateValues("STRING_AGG", List.of(ff, aa), "", f)))
        .isEqualTo("\\xffaa");
    assertThat(Evaluator.text(Evaluator.aggregateValues("STRING_AGG", List.of(ff, aa), null, f)))
        .isEqualTo("\\xffaa");
  }

  @Test
  public void instant_ShouldResolveDstLikePostgreSql() {
    java.time.ZoneId ny = java.time.ZoneId.of("America/New_York");
    // 01:30 occurs twice when DST ends: the later, standard-time offset (-05)
    assertThat(Evaluator.instant(java.time.LocalDateTime.parse("2023-11-05T01:30:00"), ny))
        .isEqualTo(java.time.Instant.parse("2023-11-05T06:30:00Z"));
    // 02:30 does not occur when DST starts: moved forward by the gap, to 03:30 -04
    assertThat(Evaluator.instant(java.time.LocalDateTime.parse("2023-03-12T02:30:00"), ny))
        .isEqualTo(java.time.Instant.parse("2023-03-12T07:30:00Z"));
  }

  @Test
  public void time_ShouldRoundToMicrosecondsAsPostgreSqlDoes() {
    // half to even; a value rounding past the last microsecond is 24:00:00
    assertThat(Evaluator.text(Evaluator.time("12:00:00.1234565"))).isEqualTo("12:00:00.123456");
    assertThat(Evaluator.text(Evaluator.time("12:00:00.1234567"))).isEqualTo("12:00:00.123457");
    assertThat(Evaluator.text(Evaluator.time("12:00:00.0000005"))).isEqualTo("12:00:00");
    assertThat(Evaluator.text(Evaluator.time("23:59:59.9999999"))).isEqualTo("24:00:00");
    assertThat(Evaluator.text(Evaluator.time(" 24:00 "))).isEqualTo("24:00:00");
    assertThat(Evaluator.time("24:00:00")).isAfter(Evaluator.time("23:59:59.999999"));
  }

  @Test
  public void rounded_ShouldRoundAsPostgreSqlCasts() {
    // a float rounds half to even and converts exactly; a numeric rounds half away from zero
    assertThat(Evaluator.rounded(-2.5d)).isEqualTo(-2);
    assertThat(Evaluator.rounded(0.5d)).isZero();
    assertThat(Evaluator.rounded(1.5f)).isEqualTo(2);
    assertThat(Evaluator.rounded(9223372036854773760d)).isEqualTo(9223372036854773760L);
    assertThat(Evaluator.rounded(new java.math.BigDecimal("2.5"))).isEqualTo(3);
    assertThat(Evaluator.rounded(new java.math.BigDecimal("-2.5"))).isEqualTo(-3);
    assertThatThrownBy(() -> Evaluator.rounded(Double.NaN)).isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> Evaluator.rounded(9.3e18)).isInstanceOf(ArithmeticException.class);
  }

  @Test
  public void jsonEquality_ShouldFollowPostgreSql() throws Exception {
    // jsonb: equal by value, so one DISTINCT or GROUP BY member; json: no equality at all
    Json.Value one = Json.cast("[1, {\"a\": 1}]", true);
    Json.Value same = Json.cast("[1.0, {\"a\":1.00}]", true);
    assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(Evaluator.compare(one, same)).isZero();
    Json.Value json = Json.cast("[1]", false);
    assertThatThrownBy(() -> Evaluator.compare(json, json))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("42883"));
    assertThatThrownBy(() -> Operators.normalize(json))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("42883"));
    // a trailing escape: SIMILAR TO drops it ('ab\\' is ab), LIKE refuses it (22025)
    assertThat(Evaluator.similarPattern("ab\\", "\\").matcher("ab").matches()).isTrue();
    assertThat(Evaluator.similarPattern("ab\\", "\\").matcher("ab\\").matches()).isFalse();
    assertThatThrownBy(() -> Evaluator.likePattern("ab#", "#"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22025"));
    // AT TIME ZONE reads a POSIX spec west of UTC, as PostgreSQL does; abbreviations are refused
    assertThat(Evaluator.atZone("+02")).isEqualTo(java.time.ZoneOffset.ofHours(-2));
    assertThat(Evaluator.atZone("UTC+3")).isEqualTo(java.time.ZoneOffset.ofHours(-3));
    assertThat(Evaluator.atZone("-05:30")).isEqualTo(java.time.ZoneOffset.ofHoursMinutes(5, 30));
    assertThat(Evaluator.atZone("asia/tokyo")).isEqualTo(java.time.ZoneId.of("Asia/Tokyo"));
    for (String refused : new String[] {"PST", "IST", "CET", "Japan", "5:60"}) {
      assertThatThrownBy(() -> Evaluator.atZone(refused))
          .as(refused)
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo("0A000"));
    }
    // integer literals: int4, int8, numeric beyond, with minus signs folded into the constant
    for (String[] c :
        new String[][] {
          {"2147483648", "20"}, {"-2147483648", "23"}, {"-(2147483648)", "23"},
          {"-(-2147483648)", "20"}, {"9223372036854775808", "1700"}, {"-9223372036854775808", "20"}
        }) {
      assertThat(
              QueryParser.inferOid(
                  net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(c[0]),
                  java.util.Collections.emptyList()))
          .as(c[0])
          .isEqualTo(Integer.parseInt(c[1]));
    }
    // join, GROUP BY and set-operation keys widen real to float8 as PostgreSQL compares them
    assertThat(Operators.normalize(0.16519462f)).isNotEqualTo(Operators.normalize(0.16519462));
    assertThat(Operators.normalize(0.5f)).isEqualTo(Operators.normalize(0.5));
    assertThat(Operators.normalize(2f)).isEqualTo(Operators.normalize(2L));
  }

  @Test
  public void timestamps_ShouldBeStoredExactlyOrRefused() {
    // ScalarDB keeps milliseconds: a finer value is refused, never rounded or cut
    assertThatThrownBy(
            () ->
                QueryParser.parseColumn(
                    com.scalar.db.io.DataType.TIMESTAMP, "c", "2024-01-01 00:00:00.123456"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("0A000"));
    assertThat(
            QueryParser.parseColumn(
                    com.scalar.db.io.DataType.TIMESTAMP, "c", "2024-01-01 00:00:00.123")
                .getTimestampValue())
        .isEqualTo(java.time.LocalDateTime.parse("2024-01-01T00:00:00.123"));
    // timestamp(p) up to 3 is recorded, and rounds to p as PostgreSQL does
    ColumnType t3 = ColumnType.declared("timestamp(3)", 18);
    assertThat(t3.encoded()).isTrue();
    assertThat(t3.coerce("2024-01-01 00:00:00.9996"))
        .isEqualTo(java.time.LocalDateTime.parse("2024-01-01T00:00:01"));
    assertThatThrownBy(() -> t3.exact("c", "2024-01-01 00:00:00.9996"))
        .isInstanceOf(PgException.NotRepresentable.class); // a predicate: compared in memory
    assertThat(ColumnType.declared("timestamptz", 18).encoded()).isFalse();
    assertThat(Evaluator.clock().getNano() % 1_000_000).isZero();
  }

  @Test
  public void floats_ShouldReadAndWriteAsPostgreSqlDoes() {
    // Ryu's shortest digits, never a midpoint between two doubles: 1e23 is one
    assertThat(Evaluator.text(1e23)).isEqualTo("9.999999999999999e+22");
    assertThat(Evaluator.text(Double.MIN_VALUE)).isEqualTo("5e-324");
    assertThat(Evaluator.text(0.1f)).isEqualTo("0.1");
    assertThat(Evaluator.floatIn(" -Inf ")).isEqualTo(Double.NEGATIVE_INFINITY);
    for (String[] bad :
        new String[][] {{"10e400", "22003"}, {"1e-400", "22003"}, {"1.5f", "22P02"}}) {
      assertThatThrownBy(() -> Evaluator.floatIn(bad[0]))
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo(bad[1]));
    }
  }

  @Test
  public void stringEscapes_ShouldReadAsPostgreSqlDoes() {
    assertThat(QueryParser.escapeStringQuotes("SELECT E'it\\'s', 'a\\'"))
        .isEqualTo("SELECT E'it''s', 'a\\'");
    assertThat(QueryParser.unescape("a\\tb\\n\\x41\\101\\u00e9\\\\")).isEqualTo("a\tb\nAAé\\");
    // escapes make bytes, valid UTF-8 together; a surrogate pair makes one character
    assertThat(QueryParser.unescape("\\xc3\\xa9\\uD83D\\uDE00")).isEqualTo("é\uD83D\uDE00");
    for (String[] bad :
        new String[][] {
          {"\\xDE", "22021"}, {"\\x00", "22021"}, {"\\u061", "22025"}, {"\\udb99", "42601"}
        }) {
      assertThatThrownBy(() -> QueryParser.unescape(bad[0]))
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo(bad[1]));
    }
    // no table inheritance: ONLY t and t* are t; a multiplication is left alone
    assertThat(
            QueryParser.normalize("SELECT * FROM ONLY t, t* JOIN ONLY u ON true WHERE a * b > 1"))
        .isEqualTo("SELECT * FROM t, t JOIN u ON true WHERE a * b > 1");
    assertThat(QueryParser.normalize("SELECT a, b * c FROM t")).isEqualTo("SELECT a, b * c FROM t");
    assertThat(QueryParser.normalize("SELECT x, idx.*, y FROM t"))
        .isEqualTo("SELECT x, idx.*, y FROM t");
    // adjacent literals join only across a newline, as PostgreSQL's lexer does
    assertThat(QueryParser.normalize("SELECT 'a'\n-- c\n'b'")).isEqualTo("SELECT 'a'\n-- c\n'b'");
    assertThatThrownBy(() -> QueryParser.normalize("SELECT 'a' 'b'"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("42601"));
  }

  @Test
  public void normalize_ShouldRewriteSyntaxButNotData() {
    // the rewrites apply to the SQL, never inside a literal, quoted name, dollar quote or comment
    assertThat(
            QueryParser.normalize(
                "SELECT 'x AS order', 1 AS order, $$a NATURAL INNER JOIN b$$ /* AS user */"
                    + " FROM a NATURAL INNER JOIN b WHERE 2 OPERATOR(pg_catalog.+) 3 > \"AS user\""))
        .isEqualTo(
            "SELECT 'x AS order', 1 AS \"order\", $$a NATURAL INNER JOIN b$$ /* AS user */"
                + " FROM a NATURAL JOIN b WHERE 2 + 3 > \"AS user\"");
  }

  @Test
  public void parenthesisedJsonOperands_ShouldNotReadAsLambdas() {
    // a parenthesised operand of -> is wrapped; a call, ->>, a literal and a comment are not
    assertThat(
            QueryParser.parenthesisedJsonOperands(
                "SELECT (j -> 'a') -> 'b', f(j) -> 'c', (j)->>'d', '(x) -> y' /* (z) -> w */"
                    + " WHERE x AND ((j -> 'a')) -> 'b' = 1"))
        .isEqualTo(
            "SELECT COALESCE((j -> 'a')) -> 'b', f(j) -> 'c', (j)->>'d', '(x) -> y'"
                + " /* (z) -> w */ WHERE x AND COALESCE(((j -> 'a'))) -> 'b' = 1");
  }

  @Test
  public void similarPattern_ShouldMatchTheWholeString() {
    assertThat(Evaluator.similarPattern("%(b|d)%", "").matcher("abc").matches()).isTrue();
    assertThat(Evaluator.similarPattern("a_c", "").matcher("abcd").matches()).isFalse();
    assertThat(Evaluator.similarPattern("a#%", "#").matcher("a%").matches()).isTrue();
    assertThat(Evaluator.similarPattern("a.c", "").matcher("abc").matches()).isFalse();
  }

  @Test
  public void compare_ShouldConvertTextToTheOtherType() {
    assertThat(Evaluator.compare(10L, "9")).isPositive();
    assertThat(Evaluator.compare(new BigDecimal("1.50"), 1.5)).isZero();
    assertThat(Evaluator.compare("abc", "abd")).isNegative();
    // code point order, as ScalarDB's BINARY collation and UTF-8 bytes: U+1F600 sorts after U+FFFF
    assertThat(Evaluator.compare("\uD83D\uDE00", "\uFFFF")).isPositive();
  }

  @Test
  public void toDecimal_Float_ShouldUsePostgresSignificantDigits() {
    // PostgreSQL's float8 and float4 to numeric: 15 and 6 significant digits
    assertThat(Evaluator.toDecimal(0.1 + 0.2)).isEqualTo(new BigDecimal("0.3"));
    assertThat(Evaluator.toDecimal(1.005)).isEqualTo(new BigDecimal("1.005"));
    assertThat(Evaluator.toDecimal(1.2345678901234567))
        .isEqualTo(new BigDecimal("1.23456789012346"));
    assertThat(Evaluator.toDecimal(1e20)).isEqualTo(new BigDecimal("100000000000000000000"));
    assertThat(Evaluator.toDecimal(123456789.123)).isEqualTo(new BigDecimal("123456789.123"));
    assertThat(Evaluator.toDecimal(0.1f)).isEqualTo(new BigDecimal("0.1"));
    assertThat(Evaluator.toDecimal(7L)).isEqualTo(BigDecimal.valueOf(7));
  }

  @Test
  public void compare_IntegersOfDifferentWidths_ShouldCompareByValue() {
    assertThat(Evaluator.compare(1, 1L)).isZero();
    assertThat(Evaluator.compare(Long.MAX_VALUE, Integer.MAX_VALUE)).isPositive();
    assertThat(Evaluator.compare(-1L, 0)).isNegative();
  }
}
