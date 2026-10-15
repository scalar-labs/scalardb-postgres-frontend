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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The static helpers the SQL-level tests reach only indirectly. */
public class HelpersTest {

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
        Arguments.of(new RuntimeException("DB-CORE-10026: bad escape"), "22025"),
        Arguments.of(new RuntimeException("DB-CORE-10211: read only"), "25006"),
        Arguments.of(new RuntimeException("DB-CORE-10020: bad key"), "22023"),
        Arguments.of(new RuntimeException("DB-CORE-10017: bad value"), "22023"),
        Arguments.of(new RuntimeException("DB-CORE-10052: table exists"), "42P07"),
        Arguments.of(new RuntimeException("DB-CORE-10053: namespace not empty"), "2BP01"),
        Arguments.of(
            new QueryParser.UniqueViolationException(new RuntimeException("dup")), "23505"),
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
        "90|00:01:30"
      })
  public void intervalParse_ShouldReadPostgreSqlInputForms(String input, String output) {
    assertThat(Interval.parse(input).toString()).isEqualTo(output);
  }

  @Test
  public void json_ShouldFollowJsonbSemantics() {
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
  public void stringEscapes_ShouldReadAsPostgreSqlDoes() {
    assertThat(QueryParser.escapeStringQuotes("SELECT E'it\\'s', 'a\\'"))
        .isEqualTo("SELECT E'it''s', 'a\\'");
    assertThat(QueryParser.unescape("a\\tb\\n\\x41\\101\\u00e9\\\\")).isEqualTo("a\tb\nAAé\\");
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
  }
}
