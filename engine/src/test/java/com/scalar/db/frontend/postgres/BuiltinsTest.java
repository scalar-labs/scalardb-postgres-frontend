package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Builtins' lists claim only what the frontend does: each row's call (builtins-calls.txt, which
 * difftest/catalog/builtins.py ran on PostgreSQL 16 and the frontend) runs and is described with
 * PostgreSQL's result type, in text and in binary; and every name the evaluator dispatches is
 * listed.
 */
public class BuiltinsTest {
  private PostgresServer server;

  @BeforeEach
  public void setUp() throws Exception {
    DistributedTransactionManager manager = mock(DistributedTransactionManager.class);
    DistributedTransaction transaction = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(transaction);
    TestScanners.stub(transaction);
    TestScanners.stub(manager);
    when(manager.scan(any())).thenReturn(Collections.emptyList());
    when(transaction.scan(any())).thenReturn(Collections.emptyList());
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton("audit"));
    when(admin.getNamespaceTableNames("audit")).thenReturn(Collections.emptySet());
    when(admin.namespaceExists("audit")).thenReturn(true);
    server = new PostgresServer(manager, admin, 0);
    server.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"simple", "extended"})
  public void calls_ShouldRunWithPostgresResultTypes(String mode) throws Exception {
    Properties properties = new Properties();
    // extended: server-prepared at once, with the results pgjdbc reads in binary
    properties.setProperty(
        mode.equals("simple") ? "preferQueryMode" : "prepareThreshold",
        mode.equals("simple") ? "simple" : "-1");
    List<String> wrong = new ArrayList<>();
    try (Connection connection = connect(properties);
        Statement statement = connection.createStatement()) {
      for (String line : calls()) {
        String[] f = line.split("\t", 3); // id, result type, call
        String expected = typeName(statement, f[1]);
        try (ResultSet rs = statement.executeQuery(f[2])) {
          while (rs.next()) {
            rs.getObject(1); // decodes every value in the described type
          }
          String actual = rs.getMetaData().getColumnTypeName(1);
          if (!expected.equals(actual)) {
            wrong.add(f[2] + ": " + actual + ", not " + expected);
          }
        } catch (SQLException e) {
          wrong.add(f[2] + ": " + e.getMessage());
        }
      }
    }
    assertThat(wrong).isEmpty();
  }

  @Test
  public void dispatchedNames_ShouldBeListed() {
    List<String> names = new ArrayList<>(Evaluator.AGGREGATES);
    names.addAll(Windows.RANKING);
    names.addAll(Evaluator.TEXT_FUNCTIONS);
    names.addAll(Evaluator.NUMERIC_FUNCTIONS);
    names.removeAll(Evaluator.SYNTAX);
    assertThat(names).allMatch(n -> !Builtins.overloads(n).isEmpty(), "listed in builtins.txt");
  }

  @Test
  public void jdbcFunctionMetadata_ShouldListWhatIsImplemented() throws Exception {
    Properties properties = new Properties();
    try (Connection connection = connect(properties)) {
      java.sql.DatabaseMetaData meta = connection.getMetaData();
      // upper(text), not PostgreSQL's range forms; its columns are the result and the argument
      assertThat(count(meta.getFunctions(null, "pg_catalog", "upper"))).isEqualTo(1);
      assertThat(count(meta.getFunctionColumns(null, "pg_catalog", "upper", null))).isEqualTo(2);
      assertThat(count(meta.getProcedureColumns(null, "pg_catalog", "upper", null))).isEqualTo(2);
      assertThat(count(meta.getFunctions(null, "pg_catalog", "%")))
          .isEqualTo(
              (int) Builtins.PROCS.stream().filter(r -> "f".equals(r.get("prokind"))).count());
    }
  }

  @Test
  public void arrayConcatenation_ShouldTakeTheCommonType() throws Exception {
    // anycompatible: the common type of all the arguments, not the first one's
    try (Connection connection = connect(new Properties());
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT ARRAY[1] || 2.5, ARRAY[1] || ARRAY[2.5], 2.5 || ARRAY[1],"
                    + " ARRAY[1] || 2::bigint, ARRAY[1] || 2.5::float8, ARRAY[1] || 2,"
                    + " ARRAY[ARRAY[1]]")) {
      List<String> types = new ArrayList<>();
      for (int i = 1; i <= 7; i++) {
        types.add(rs.getMetaData().getColumnTypeName(i));
      }
      assertThat(types)
          .containsExactly(
              "_numeric", "_numeric", "_numeric", "_int8", "_float8", "_int4", "_int4");
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(2)).isEqualTo("{1,2.5}");
    }
  }

  @Test
  public void pgTypeof_ShouldNameTheDeclaredType() throws Exception {
    Properties properties = new Properties();
    try (Connection connection = connect(properties);
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT pg_typeof(1), pg_typeof(1.5), pg_typeof(ARRAY[1, 2]),"
                    + " format_type(1007, NULL), '_text'::regtype")) {
      assertThat(rs.next()).isTrue();
      assertThat(
              List.of(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5)))
          .containsExactly("integer", "numeric", "integer[]", "integer[]", "text[]");
      // a quoted literal is of unknown type, which the frontend does not name
      assertThatThrownBy(() -> statement.executeQuery("SELECT pg_typeof('a')"))
          .hasMessageContaining("Unsupported function");
    }
  }

  @Test
  public void resolution_ShouldFollowPostgres() throws Exception {
    try (Connection connection = connect(new Properties());
        Statement statement = connection.createStatement()) {
      // pg_typeof is typed while running, so a repeat and an ORDER BY copy of it agree
      for (int i = 0; i < 2; i++) {
        try (ResultSet rs =
            statement.executeQuery("SELECT DISTINCT pg_typeof(1) ORDER BY pg_typeof(1)")) {
          assertThat(rs.getMetaData().getColumnLabel(1)).isEqualTo("pg_typeof");
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString(1)).isEqualTo("integer");
        }
      }
      // a tie goes to PostgreSQL's preferred type: ceil(int) is ceil(float8)
      try (ResultSet rs = statement.executeQuery("SELECT ceil(2), floor(2), round(2)")) {
        for (int i = 1; i <= 3; i++) {
          assertThat(rs.getMetaData().getColumnTypeName(i)).isEqualTo("float8");
        }
      }
      // an ambiguous name prints qualified; pg_type's I/O functions are named, others fail
      try (ResultSet rs = statement.executeQuery("SELECT 1394::regproc, 'array_in'::regproc")) {
        assertThat(rs.next()).isTrue();
        assertThat(List.of(rs.getString(1), rs.getString(2)))
            .containsExactly("pg_catalog.abs", "array_in");
      }
      assertThatThrownBy(() -> statement.executeQuery("SELECT 'nosuch'::regproc"))
          .hasMessageContaining("function \"nosuch\" does not exist");
      // multi-dimensional arrays are rectangular, as PostgreSQL's
      for (String ragged :
          new String[] {
            "SELECT ARRAY[ARRAY[1, 2], ARRAY[3]]",
            "SELECT array_agg(x) FROM (VALUES (ARRAY[1, 2]), (ARRAY[3])) v(x)"
          }) {
        assertThatThrownBy(() -> statement.executeQuery(ragged))
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("2202E"));
      }
    }
  }

  private static int count(ResultSet rs) throws SQLException {
    try (rs) {
      int n = 0;
      while (rs.next()) {
        n++;
      }
      return n;
    }
  }

  @Test
  public void sizePretty_ShouldRoundAsPostgres() {
    // SELECT pg_size_pretty(x) on PostgreSQL 16
    long[] sizes = {0, 10239, 10240, 20971519, -10240, 123456789012L, 20000L << 40};
    String[] pretty = {"0 bytes", "10239 bytes", "10 kB", "20 MB", "-10 kB", "115 GB", "20 PB"};
    for (int i = 0; i < sizes.length; i++) {
      assertThat(Evaluator.sizePretty(sizes[i])).isEqualTo(pretty[i]);
    }
  }

  private Connection connect(Properties properties) throws SQLException {
    properties.setProperty("user", "test");
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:" + server.getPort() + "/audit", properties);
  }

  private static String typeName(Statement statement, String oid) throws SQLException {
    try (ResultSet rs =
        statement.executeQuery("SELECT typname FROM pg_catalog.pg_type WHERE oid = " + oid)) {
      return rs.next() ? rs.getString(1) : "type " + oid;
    }
  }

  private static List<String> calls() throws Exception {
    try (java.io.InputStream in = BuiltinsTest.class.getResourceAsStream("/builtins-calls.txt")) {
      List<String> calls = new ArrayList<>();
      for (String line :
          new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)
              .split("\n")) {
        if (!line.startsWith("#") && !line.isEmpty()) {
          calls.add(line);
        }
      }
      return calls;
    }
  }
}
