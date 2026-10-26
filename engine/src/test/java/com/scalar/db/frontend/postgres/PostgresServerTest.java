package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.AuthAdmin;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.exception.transaction.CrudConflictException;
import com.scalar.db.exception.transaction.CrudException;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import com.scalar.db.util.ThrowableSupplier;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives the wire-protocol server with the PostgreSQL JDBC driver as a client. */
public class PostgresServerTest {
  private static final TableMetadata METADATA =
      TableMetadata.newBuilder()
          .addColumn("id", DataType.INT)
          .addColumn("name", DataType.TEXT)
          .addPartitionKey("id")
          .build();

  private DistributedTransactionManager manager;
  private DistributedTransaction transaction;
  private DistributedTransactionAdmin admin;
  private PostgresServer server;

  private static Result row(int id, String name) {
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("id", IntColumn.of("id", id));
    columns.put("name", TextColumn.of("name", name));
    return new ResultImpl(columns, METADATA);
  }

  @BeforeEach
  public void setUp() throws Exception {
    manager = mock(DistributedTransactionManager.class);
    transaction = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(transaction);
    when(manager.beginReadOnly()).thenReturn(transaction); // an auto-commit read
    TestScanners.stub(transaction);
    TestScanners.stub(manager);
    when(manager.scan(any())).thenReturn(Arrays.asList(row(1, "a"), row(2, "b")));
    when(transaction.scan(any())).thenReturn(Arrays.asList(row(1, "a"), row(2, "b")));
    admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata("ns", "t")).thenReturn(METADATA);
    server = new PostgresServer(manager, admin, 0);
    server.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.close();
  }

  private Connection connect(String options) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", "test"); // the server does not authenticate
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:" + server.getPort() + "/ns" + options, properties);
  }

  @Test
  public void extendedProtocol_PreparedStatementWithParameters_ShouldReturnRows() throws Exception {
    try (Connection connection = connect("");
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id, UPPER(name) AS u FROM t WHERE id >= ? ORDER BY id DESC")) {
      statement.setInt(1, 1);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
        assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("id");
        assertThat(rs.getMetaData().getColumnName(2)).isEqualTo("u");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(2);
        assertThat(rs.getString(2)).isEqualTo("B");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("id")).isEqualTo(1);
        assertThat(rs.getString("u")).isEqualTo("A");
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  public void describe_UntypedParameters_ShouldTakeTheTypesTheirPlacesGive() throws Exception {
    // asyncpg declares no parameter types and introspects whatever Describe reports; 0 sent it
    // round its own introspection query forever
    String[][] cases = {
      {"SELECT id FROM t WHERE id = ? AND name = ?", "int4", "text"},
      {"SELECT ?::bigint", "int8"},
      {"SELECT id FROM t WHERE id = ANY(?) AND name = ANY(?)", "_int4", "_text"},
      {"SELECT id FROM t WHERE id > ? + 1 LIMIT ? OFFSET ?", "int4", "int8", "int8"},
      {"SELECT id FROM t WHERE name LIKE ? OR name = 'a' || ?", "text", "text"},
      {"SELECT id FROM t WHERE id IN (?, ?)", "int4", "int4"},
      {"INSERT INTO t (id, name) VALUES (?, ?)", "int4", "text"},
      {"UPDATE t SET name = ? WHERE id = ?", "text", "int4"},
      {"SELECT t.oid FROM pg_catalog.pg_type t WHERE t.oid = any(?::oid[])", "_oid", null},
    };
    try (Connection connection = connect("")) {
      for (String[] c : cases) {
        try (PreparedStatement statement = connection.prepareStatement(c[0])) {
          java.sql.ParameterMetaData meta = statement.getParameterMetaData();
          for (int i = 1; i < c.length && c[i] != null; i++) {
            assertThat(meta.getParameterTypeName(i)).as(c[0] + " $" + i).isEqualTo(c[i]);
          }
        }
      }
    }
  }

  @Test
  public void simpleProtocol_SelectAndMultiRowInsert_ShouldWork() throws Exception {
    try (Connection connection = connect("?preferQueryMode=simple");
        Statement statement = connection.createStatement()) {
      try (ResultSet rs = statement.executeQuery("SELECT * FROM t WHERE name = 'a' OR id = 2")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("id")).isEqualTo(1);
        assertThat(rs.getString("name")).isEqualTo("a");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("id")).isEqualTo(2);
        assertThat(rs.next()).isFalse();
      }
      assertThat(statement.executeUpdate("INSERT INTO t (id, name) VALUES (3, 'c'), (4, 'd')"))
          .isEqualTo(2);
      verify(manager)
          .mutate(
              Arrays.asList(
                  Insert.newBuilder()
                      .namespace("ns")
                      .table("t")
                      .partitionKey(Key.ofInt("id", 3))
                      .textValue("name", "c")
                      .build(),
                  Insert.newBuilder()
                      .namespace("ns")
                      .table("t")
                      .partitionKey(Key.ofInt("id", 4))
                      .textValue("name", "d")
                      .build()));
    }
  }

  @Test
  public void transaction_CommitThroughJdbc_ShouldUseOneScalarDbTransaction() throws Exception {
    try (Connection connection = connect("")) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        assertThat(statement.executeUpdate("DELETE FROM t WHERE id = 1")).isEqualTo(1);
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS n FROM t")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getLong("n")).isEqualTo(2);
        }
      }
      connection.commit();
    }
    verify(transaction)
        .mutate(
            Collections.singletonList(
                Delete.newBuilder()
                    .namespace("ns")
                    .table("t")
                    .partitionKey(Key.ofInt("id", 1))
                    .condition(ConditionBuilder.deleteIfExists())
                    .build()));
    verify(transaction).commit();
  }

  @Test
  public void error_ShouldBeReportedAndConnectionStayUsable() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeQuery("SELECT nope FROM t"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("Unknown column");
      try (ResultSet rs = statement.executeQuery("SELECT 1 AS one")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("one")).isEqualTo(1);
      }
    }
  }

  @Test
  public void error_ScalarDbRefusal_ShouldKeepItsMessage() throws Exception {
    doThrow(
            new UnsupportedOperationException(
                "DB-CORE-10143: The encryption feature is not enabled."))
        .when(admin)
        .createTable(any(), any(), any(), anyBoolean());
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "CREATE TABLE e (id INT PRIMARY KEY, s TEXT) WITH (encrypted = 's')"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("The encryption feature is not enabled")
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("0A000");
    }
  }

  @Test
  public void error_ShouldBeReportedAsInternalErrorAndConnectionStayUsable() throws Exception {
    when(manager.begin()).thenThrow(new AssertionError("boom"));
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      statement.execute("BEGIN"); // the ScalarDB transaction starts with the first statement
      assertThatThrownBy(() -> statement.executeQuery("SELECT id FROM t"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("internal error") // the JVM's own text stays in the log
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("boom"))
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("XX000");
      statement.execute("ROLLBACK");
      try (ResultSet rs = statement.executeQuery("SELECT 1 AS one")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("one")).isEqualTo(1);
      }
    }
  }

  @Test
  public void failedStatementInTransaction_ShouldAbortItUntilRollback() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      assertThatThrownBy(() -> statement.execute("SELECT nope FROM t"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.execute("SELECT id FROM t"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("current transaction is aborted")
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("25P02");
      assertThat(connection.unwrap(org.postgresql.jdbc.PgConnection.class).getTransactionState())
          .isEqualTo(org.postgresql.core.TransactionState.FAILED);
      connection.rollback();
      assertThat(connection.unwrap(org.postgresql.jdbc.PgConnection.class).getTransactionState())
          .isEqualTo(org.postgresql.core.TransactionState.IDLE);
      try (ResultSet rs = statement.executeQuery("SELECT id FROM t")) {
        assertThat(rs.next()).isTrue();
      }
      connection.commit();
    }
  }

  @Test
  public void insertReturning_ShouldComeBackAsAResultSet() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery("INSERT INTO t (id, name) VALUES (3, 'c') RETURNING id, name")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt("id")).isEqualTo(3);
      assertThat(rs.getString("name")).isEqualTo("c");
      assertThat(rs.next()).isFalse();
    }
  }

  @Test
  public void typed_ShouldGiveParametersTheValueOfTheirLiteral() {
    assertThat(PostgresServer.typed("5", 0)).isEqualTo(5L);
    // a decimal or exponent literal is numeric, as in PostgreSQL; 1.5e3 has no fraction digits
    assertThat(Evaluator.typedValue(PostgresServer.typed("-1.5e3", 0)))
        .isEqualTo(new java.math.BigDecimal("-1500"));
    assertThat(Evaluator.typedValue(PostgresServer.typed("true", 0))).isEqualTo(true);
    // undeclared text that does not print back as itself keeps its text for text contexts
    assertThat(PostgresServer.typed("007", 0))
        .isInstanceOfSatisfying(
            Evaluator.Untyped.class,
            u -> {
              assertThat(u.text).isEqualTo("007");
              assertThat(u.guess).isEqualTo(7L);
            });
    assertThat(PostgresServer.typed("007", 23)).isEqualTo(7L); // declared int4: a number
    assertThat(PostgresServer.typed("x", 0)).isEqualTo("x");
    assertThat(PostgresServer.typed("7", 1043)).isEqualTo("7"); // declared varchar
    assertThat(PostgresServer.typed("t", 16)).isEqualTo(true);
    assertThat(PostgresServer.typed(null, 23)).isNull();
  }

  @Test
  public void extendedProtocol_ServerPreparedStatement_ShouldReturnBinaryResults()
      throws Exception {
    // pgjdbc asks for binary results once a statement is server-prepared and its types are known
    try (Connection connection = connect("?prepareThreshold=1");
        PreparedStatement statement =
            connection.prepareStatement("SELECT id, name, id * 1.5 AS d FROM t WHERE id >= ?")) {
      for (int round = 0; round < 3; round++) {
        statement.setInt(1, 2);
        // the stubbed scan returns both rows whatever the condition
        try (ResultSet rs = statement.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt("id")).isEqualTo(1);
          assertThat(rs.getString("name")).isEqualTo("a");
          assertThat(rs.getDouble("d")).isEqualTo(1.5);
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt("id")).isEqualTo(2);
          assertThat(rs.getString("name")).isEqualTo("b");
          assertThat(rs.getDouble("d")).isEqualTo(3.0);
          assertThat(rs.next()).isFalse();
        }
      }
    }
  }

  @Test
  public void binary_ShouldDecodeNumericParameters() {
    // 1234.5678: two base-10000 groups, weight 0, positive, scale 4
    assertThat(PostgresServer.decodeBinary(1700, numeric(2, 0, 0, 4, 1234, 5678)))
        .isEqualTo("1234.5678");
    // -0.05: one group (0500) at weight -1, negative, scale 2
    assertThat(PostgresServer.decodeBinary(1700, numeric(1, -1, 0x4000, 2, 500)))
        .isEqualTo("-0.05");
    // 12000000: one group (1200) at weight 1, scale 0
    assertThat(PostgresServer.decodeBinary(1700, numeric(1, 1, 0, 0, 1200))).isEqualTo("12000000");
    assertThat(PostgresServer.decodeBinary(1700, numeric(0, 0, 0, 2))).isEqualTo("0.00");
    assertThat(PostgresServer.decodeBinary(1700, numeric(0, 0, 0xC000, 0))).isEqualTo("NaN");
  }

  private static byte[] numeric(int groups, int weight, int sign, int scale, int... digits) {
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + 2 * digits.length);
    b.putShort((short) groups).putShort((short) weight).putShort((short) sign);
    b.putShort((short) scale);
    for (int d : digits) {
      b.putShort((short) d);
    }
    return b.array();
  }

  @Test
  public void createTable_WithConstraints_ShouldWarnTheClient() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE w (id INT, v INT NOT NULL, PRIMARY KEY (id))");
      java.sql.SQLWarning warning = statement.getWarnings();
      assertThat(warning == null ? null : warning.getMessage())
          .contains("NOT NULL on column \"v\"");
    }
  }

  @Test
  public void binary_ShouldRoundTripUuidAndJsonb() {
    String uuid = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11";
    byte[] raw = PostgresServer.encodeBinary(2950, uuid);
    assertThat(raw).hasSize(16);
    assertThat(PostgresServer.decodeBinary(2950, raw)).isEqualTo(uuid);
    byte[] jsonb = PostgresServer.encodeBinary(3802, Json.cast("{\"b\":1,\"a\":2}", true));
    assertThat(jsonb[0]).isEqualTo((byte) 1);
    assertThat(PostgresServer.decodeBinary(3802, jsonb)).isEqualTo("{\"a\": 2, \"b\": 1}");
  }

  @Test
  public void binary_ShouldDecodeArrayParametersToArrayText() {
    // int4[] {1,NULL,3}: 1 dimension, has nulls, element oid 23, 3 elements from index 1
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(20 + 8 + 4 + 8);
    b.putInt(1).putInt(1).putInt(23).putInt(3).putInt(1);
    b.putInt(4).putInt(1).putInt(-1).putInt(4).putInt(3);
    assertThat(PostgresServer.decodeBinary(1007, b.array())).isEqualTo("{1,NULL,3}");
    // text[] {"a b",c}
    byte[] ab = "a b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.nio.ByteBuffer t = java.nio.ByteBuffer.allocate(20 + 4 + ab.length + 4 + 1);
    t.putInt(1).putInt(0).putInt(25).putInt(2).putInt(1);
    t.putInt(ab.length).put(ab).putInt(1).put((byte) 'c');
    assertThat(PostgresServer.decodeBinary(1009, t.array())).isEqualTo("{\"a b\",c}");
    assertThat(PostgresServer.typed("{1,2}", 1007)).isEqualTo("{1,2}");
  }

  @Test
  public void binary_ShouldEncodeNumericLikePostgres() {
    assertThat(PostgresServer.encodeBinary(1700, new java.math.BigDecimal("1234.5678")))
        .isEqualTo(numeric(2, 0, 0, 4, 1234, 5678));
    assertThat(PostgresServer.encodeBinary(1700, new java.math.BigDecimal("-0.05")))
        .isEqualTo(numeric(1, -1, 0x4000, 2, 500));
    assertThat(PostgresServer.encodeBinary(1700, new java.math.BigDecimal("12000000")))
        .isEqualTo(numeric(1, 1, 0, 0, 1200));
    assertThat(PostgresServer.encodeBinary(1700, new java.math.BigDecimal("2500.0")))
        .isEqualTo(numeric(1, 0, 0, 1, 2500));
    assertThat(PostgresServer.encodeBinary(1700, new java.math.BigDecimal("0.00")))
        .isEqualTo(numeric(0, 0, 0, 2));
    // decoding what was encoded gives the text back
    for (String s : new String[] {"3.5000000000000000", "-180414.25", "0.0001", "1000"}) {
      assertThat(
              PostgresServer.decodeBinary(
                  1700, PostgresServer.encodeBinary(1700, new java.math.BigDecimal(s))))
          .isEqualTo(s);
    }
    assertThat(PostgresServer.typed("1.5", 0)).isEqualTo(new java.math.BigDecimal("1.5"));
    assertThat(PostgresServer.typed("1.5", 701)).isEqualTo(1.5);
    assertThat(PostgresServer.typed("NaN", 1700)).isEqualTo(Double.NaN);
  }

  @Test
  public void binary_ShouldEncodeLikePostgres() {
    assertThat(PostgresServer.encodeBinary(23, 7)).containsExactly(0, 0, 0, 7);
    assertThat(PostgresServer.encodeBinary(20, 1L)).containsExactly(0, 0, 0, 0, 0, 0, 0, 1);
    assertThat(PostgresServer.encodeBinary(16, true)).containsExactly(1);
    assertThat(PostgresServer.encodeBinary(701, 1.5)).containsExactly(0x3f, 0xf8, 0, 0, 0, 0, 0, 0);
    assertThat(PostgresServer.encodeBinary(1082, java.time.LocalDate.of(2000, 1, 3)))
        .containsExactly(0, 0, 0, 2);
    assertThat(PostgresServer.encodeBinary(1114, java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 1)))
        .containsExactly(0, 0, 0, 0, 0, 0x0f, 0x42, 0x40);
    assertThat(PostgresServer.encodeBinary(25, "ab")).containsExactly('a', 'b');
    // time: microseconds since midnight; timestamptz: microseconds since 2000-01-01 UTC; bytea: raw
    assertThat(PostgresServer.encodeBinary(1083, java.time.LocalTime.of(0, 0, 1)))
        .containsExactly(0, 0, 0, 0, 0, 0x0f, 0x42, 0x40);
    assertThat(PostgresServer.encodeBinary(1184, java.time.Instant.parse("2000-01-01T00:00:01Z")))
        .containsExactly(0, 0, 0, 0, 0, 0x0f, 0x42, 0x40);
    assertThat(
            PostgresServer.encodeBinary(17, java.nio.ByteBuffer.wrap(new byte[] {(byte) 0xde, 1})))
        .containsExactly(0xde, 1);
    assertThat(PostgresServer.encodeBinary(17, new byte[] {7})).containsExactly(7);
  }

  @Test
  public void text_Floats_ShouldUsePostgresFormat() {
    assertThat(PostgresServer.wireText(2500.0)).isEqualTo("2500");
    assertThat(PostgresServer.wireText(0.1)).isEqualTo("0.1");
    assertThat(PostgresServer.wireText(-0.0)).isEqualTo("-0");
    assertThat(PostgresServer.wireText(1e14)).isEqualTo("100000000000000");
    assertThat(PostgresServer.wireText(1e15)).isEqualTo("1e+15");
    assertThat(PostgresServer.wireText(1.5e-5)).isEqualTo("1.5e-05");
    assertThat(PostgresServer.wireText(0.0001)).isEqualTo("0.0001");
    assertThat(PostgresServer.wireText(Double.NaN)).isEqualTo("NaN");
    assertThat(PostgresServer.wireText(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    assertThat(PostgresServer.wireText(100000f)).isEqualTo("100000");
    assertThat(PostgresServer.wireText(1e6f)).isEqualTo("1e+06");
    assertThat(PostgresServer.wireText(0.1f)).isEqualTo("0.1");
  }

  @Test
  public void errors_ShouldCarryPostgresSqlStates() throws Exception {
    doThrow(
            new CrudConflictException(
                "DB-CORE-20013: The record being prepared already exists", null, "tx"))
        .when(manager)
        .mutate(any());
    // a write over pgjdbc's extended protocol runs in an implicit transaction
    doThrow(
            new CrudConflictException(
                "DB-CORE-20013: The record being prepared already exists", null, "tx"))
        .when(transaction)
        .mutate(any());
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      assertSqlState(statement, "SELECT 1 / 0", "22012");
      assertSqlState(statement, "SELECT 2147483647 + 1", "22003");
      assertSqlState(statement, "SELECT nosuch FROM t", "42703");
      assertSqlState(statement, "SELECT * FROM nosuch", "42P01");
      assertSqlState(statement, "SELECT CAST('x' AS INT)", "22P02");
      assertSqlState(statement, "SELECT (SELECT id FROM t), 1", "21000");
      assertSqlState(statement, "SELECT id FROM t LIMIT -1", "2201W");
      assertSqlState(statement, "SELECT (SELECT id, name FROM t)", "42601");
      // a quoted literal takes the type of the number next to it; a text column does not
      assertSqlState(statement, "SELECT '1.5' * 2", "22P02");
      assertSqlState(statement, "SELECT '2147483647' + 1", "22003");
      assertSqlState(statement, "SELECT name + 1 FROM t", "42883");
      try (ResultSet rs = statement.executeQuery("SELECT '5' + 1, abs('-3'), mod('7', 3)")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("6");
        assertThat(rs.getString(2)).isEqualTo("3");
        assertThat(rs.getString(3)).isEqualTo("1");
      }
      assertSqlState(statement, "SELECT id FROM t WHERE count(*) > 1", "42803");
      assertSqlState(statement, "SELECT id, count(*) FROM t GROUP BY name", "42803");
      assertSqlState(statement, "SELECT id FROM t ORDER BY 2", "42P10");
      assertSqlState(statement, "CREATE TABLE d (id INT, id INT, PRIMARY KEY (id))", "42701");
      assertSqlState(statement, "SELECT id FROM t OFFSET -1", "2201X");
      assertSqlState(statement, "SELECT CAST(1e308 AS DOUBLE PRECISION) * 10", "22003");
      assertSqlState(statement, "INSERT INTO t VALUES (2, E'a\\000b')", "22021");
      assertSqlState(statement, "SELECT id FROM t WHERE name = E'a\\000b'", "22021");
      assertSqlState(statement, "CREATE TABLE d (id INT, v CHAR(0), PRIMARY KEY (id))", "22023");
      assertSqlState(
          statement,
          "CREATE TABLE d (id INT, " + "c".repeat(57) + " INT, PRIMARY KEY (id))",
          "42622");
      assertSqlState(statement, "INSERT INTO t VALUES (1, 'a')", "23505");
      // The same ScalarDB error on an upsert is a write-write conflict the client should retry
      assertSqlState(
          statement,
          "INSERT INTO t VALUES (1, 'a') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
          "40001");
    }
  }

  @Test
  public void select_NaN_ShouldCompareAsPostgres() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT CAST('NaN' AS DOUBLE PRECISION) > CAST('Infinity' AS DOUBLE PRECISION),"
                    + " CAST('NaN' AS DOUBLE PRECISION) = CAST('NaN' AS DOUBLE PRECISION)")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean(1)).isTrue();
      assertThat(rs.getBoolean(2)).isTrue();
    }
  }

  private static void assertSqlState(Statement statement, String sql, String sqlState) {
    assertThatThrownBy(() -> statement.execute(sql))
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).as(sql).isEqualTo(sqlState));
  }

  @Test
  public void rowDescription_ShouldUsePostgresColumnNames() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT a.id + 1, UPPER(a.name), a.*, b.id FROM t a JOIN t b ON a.id = b.id")) {
      java.sql.ResultSetMetaData md = rs.getMetaData();
      assertThat(md.getColumnCount()).isEqualTo(5);
      assertThat(md.getColumnName(1)).isEqualTo("?column?");
      assertThat(md.getColumnName(2)).isEqualTo("upper");
      assertThat(md.getColumnName(3)).isEqualTo("id");
      assertThat(md.getColumnName(4)).isEqualTo("name");
      assertThat(md.getColumnName(5)).isEqualTo("id");
    }
    try (Connection connection = connect("");
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT s.count FROM (SELECT COUNT(*) FROM t) s")) {
      assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("count");
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong("count")).isEqualTo(2);
    }
  }

  // ---- authentication: a stub authenticator, with every ScalarDB call checked for its scope

  /** Set by the stub identity while a call runs inside it, on whichever thread. */
  private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

  private static final class StubIdentity implements Authenticator.Identity {
    final List<String> threads = new CopyOnWriteArrayList<>();
    volatile boolean closed;

    @Override
    public String user() {
      return "alice";
    }

    @Override
    public boolean superuser() {
      return false;
    }

    @Override
    public <R, T extends Throwable> R run(ThrowableSupplier<R, T> call) throws T {
      if (INSIDE.get()) {
        throw new IllegalStateException("nested scope on " + Thread.currentThread().getName());
      }
      threads.add(Thread.currentThread().getName());
      INSIDE.set(true);
      try {
        return call.get();
      } finally {
        INSIDE.set(false);
      }
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private StubIdentity lastIdentity;

  private void allowPlaintext() {
    Properties properties = new Properties();
    properties.setProperty("scalar.db.postgres.ssl.require", "false");
    server.configure(properties);
  }

  /** A JWT-shaped secret: the header {"alg":"RS256","typ":"JWT"}, a payload and a signature. */
  private static final String JWT =
      "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhbGljZSJ9.c2ln";

  private volatile Authenticator.Secret lastKind;

  private Authenticator.Identity stubAuthenticate(
      String user, String secret, Authenticator.Secret kind)
      throws Authenticator.AuthenticationException {
    if (!user.equals("alice") || !(secret.equals("secret") || secret.equals(JWT))) {
      throw new Authenticator.AuthenticationException("not alice");
    }
    lastKind = kind;
    lastIdentity = new StubIdentity();
    return lastIdentity;
  }

  private static void insideScope() {
    if (!INSIDE.get()) {
      throw new IllegalStateException(
          "ScalarDB called outside the session's identity on " + Thread.currentThread().getName());
    }
  }

  /** Every ScalarDB call fails unless it happens inside the identity, on whichever thread. */
  private void requireScope() throws Exception {
    when(transaction.scan(any()))
        .thenAnswer(
            invocation -> {
              insideScope();
              return Arrays.asList(row(1, "a"), row(2, "b"));
            });
    when(transaction.get(any()))
        .thenAnswer(
            invocation -> {
              insideScope();
              return Optional.of(row(1, "a"));
            });
    when(manager.begin())
        .thenAnswer(
            invocation -> {
              insideScope();
              return transaction;
            });
    when(manager.beginReadOnly())
        .thenAnswer(
            invocation -> {
              insideScope();
              return transaction;
            });
    doAnswer(
            invocation -> {
              insideScope();
              return null;
            })
        .when(admin)
        .createTable(any(), any(), any(), anyBoolean());
  }

  private Connection connectAs(String user, String password) throws SQLException {
    return connectAs(user, password, "");
  }

  /** Connects with pgjdbc's {@code options}, sent in the startup message as libpq's PGOPTIONS. */
  private Connection connectAs(String user, String password, String options) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", user);
    properties.setProperty("password", password);
    if (!options.isEmpty()) {
      properties.setProperty("options", options);
    }
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:" + server.getPort() + "/ns", properties);
  }

  @Test
  public void auth_WrongPassword_ShouldBeRefusedWith28P01() {
    server.loginFailureDelayMillis = 0;
    allowPlaintext(); // the tests have no TLS; with an authenticator it is otherwise required
    server.setAuthenticator(this::stubAuthenticate);
    assertThatThrownBy(() -> connectAs("alice", "nope"))
        .isInstanceOfSatisfying(
            SQLException.class,
            e -> {
              assertThat(e.getSQLState()).isEqualTo("28P01");
              assertThat(e.getMessage()).contains("alice").doesNotContain("nope");
            });
  }

  @Test
  public void auth_Auto_ShouldTellATokenFromAPasswordByItsShape() throws Exception {
    allowPlaintext();
    server.setAuthenticator(this::stubAuthenticate);
    try (Connection ignored = connectAs("alice", "secret")) {
      assertThat(lastKind).isEqualTo(Authenticator.Secret.PASSWORD);
    }
    try (Connection ignored = connectAs("alice", JWT)) {
      assertThat(lastKind).isEqualTo(Authenticator.Secret.OIDC_TOKEN);
    }
  }

  @Test
  public void auth_StartupOption_ShouldStateTheKindUnlessTheConfigurationForcesOne()
      throws Exception {
    server.loginFailureDelayMillis = 0;
    allowPlaintext();
    server.setAuthenticator(this::stubAuthenticate);
    // the client says what it sends, whatever the shape; the option is the login's, not a setting
    try (Connection c = connectAs("alice", "secret", "-c scalardb.auth=oidc_jwt");
        Statement statement = c.createStatement()) {
      assertThat(lastKind).isEqualTo(Authenticator.Secret.OIDC_TOKEN);
      assertThatThrownBy(() -> statement.executeQuery("SHOW scalardb.auth"))
          .isInstanceOf(SQLException.class);
    }
    lastKind = null;
    try (Connection ignored = connectAs("alice", JWT, "-c scalardb.auth=userpass")) {
      assertThat(lastKind).isEqualTo(Authenticator.Secret.PASSWORD);
    }
    lastKind = null;
    assertThatThrownBy(() -> connectAs("alice", "secret", "-c scalardb.auth=ldap"))
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28000"));
    assertThat(lastKind).isNull(); // refused before the secret was checked
    // the configuration allows passwords only: a token-shaped secret is checked as a password, and
    // a client asking for a token is refused
    Properties properties = new Properties();
    properties.setProperty("scalar.db.postgres.auth", "userpass");
    server.configure(properties);
    try (Connection ignored = connectAs("alice", JWT)) {
      assertThat(lastKind).isEqualTo(Authenticator.Secret.PASSWORD);
    }
    lastKind = null;
    assertThatThrownBy(() -> connectAs("alice", "secret", "-c scalardb.auth=oidc_jwt"))
        .isInstanceOfSatisfying(
            SQLException.class,
            e -> {
              assertThat(e.getSQLState()).isEqualTo("28000");
              assertThat(e.getMessage())
                  .contains("an OIDC access token is not allowed")
                  .doesNotContain("scalar.db.postgres.auth");
            });
    assertThat(lastKind).isNull();
  }

  @Test
  public void secretOf_ShouldTakeOnlyAWellFormedJwtAsAToken() {
    assertThat(Authenticator.Secret.of(JWT)).isEqualTo(Authenticator.Secret.OIDC_TOKEN);
    for (String password :
        new String[] {
          "secret",
          "",
          "a.b.c",
          "pa.ss.word",
          "eyJhbGciOiJSUzI1NiJ9.x",
          JWT + ".more",
          "e30.e30.e30",
          "{\"alg\":\"x\"}.b.c",
          "YWxn.YWxn.YWxn",
          JWT + "=",
          "x." + JWT
        }) {
      assertThat(Authenticator.Secret.of(password))
          .as(password)
          .isEqualTo(Authenticator.Secret.PASSWORD);
    }
  }

  @Test
  public void auth_EveryScalarDbCall_ShouldRunInsideTheIdentity() throws Exception {
    allowPlaintext(); // the tests have no TLS; with an authenticator it is otherwise required
    server.setAuthenticator(this::stubAuthenticate);
    requireScope();
    try (Connection connection = connectAs("alice", "secret");
        Statement statement = connection.createStatement()) {
      try (ResultSet rs = statement.executeQuery("SELECT current_user")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("alice");
      }
      // the startup parameters report the identity
      org.postgresql.PGConnection pg = connection.unwrap(org.postgresql.PGConnection.class);
      assertThat(pg.getParameterStatus("is_superuser")).isEqualTo("off");
      assertThat(pg.getParameterStatus("session_authorization")).isEqualTo("alice");
      // a one-shot read, a transaction, DDL, and the extended protocol's Parse and Describe
      try (ResultSet rs = statement.executeQuery("SELECT id FROM t")) {
        assertThat(rs.next()).isTrue();
      }
      statement.execute("BEGIN");
      statement.executeQuery("SELECT id FROM t WHERE id = 1").close();
      statement.execute("COMMIT");
      statement.execute("CREATE TABLE u (id INT, PRIMARY KEY (id))");
      try (PreparedStatement prepared =
          connection.prepareStatement("SELECT id FROM t WHERE id >= ?")) {
        prepared.setInt(1, 1);
        try (ResultSet rs = prepared.executeQuery()) {
          assertThat(rs.next()).isTrue();
        }
      }
      // a lookup join reads its inner side in the statement's transaction, inside the identity
      try (ResultSet rs =
          statement.executeQuery("SELECT a.id, b.name FROM t a JOIN t b ON b.id = a.id")) {
        int rows = 0;
        while (rs.next()) {
          rows++;
        }
        assertThat(rows).isEqualTo(2);
      }
      assertSqlState(statement, "SET ROLE bob", "0A000");
      assertSqlState(statement, "SET SESSION AUTHORIZATION bob", "0A000");
    }
    assertThat(lastIdentity.threads).allMatch(thread -> thread.startsWith("postgres-frontend-"));
    for (int i = 0; i < 100 && !lastIdentity.closed; i++) {
      Thread.sleep(20); // the server closes the identity after the client has gone
    }
    assertThat(lastIdentity.closed).isTrue();
  }

  @Test
  public void auth_TokenInvalidMidSession_ShouldReport28000AndCloseTheSession() throws Exception {
    allowPlaintext(); // the tests have no TLS; with an authenticator it is otherwise required
    server.setAuthenticator(this::stubAuthenticate);
    when(transaction.scan(any()))
        .thenThrow(new CrudException("DB-AUTH-10006: Access denied: Invalid auth token", "tx"));
    try (Connection connection = connectAs("alice", "secret");
        Statement statement = connection.createStatement()) {
      assertSqlState(statement, "SELECT id FROM t", "28000");
      assertThatThrownBy(() -> statement.execute("SELECT 1")).isInstanceOf(SQLException.class);
    }
  }

  @Test
  public void auth_MissingPrivilege_ShouldReport42501AndKeepTheSession() throws Exception {
    allowPlaintext(); // the tests have no TLS; with an authenticator it is otherwise required
    server.setAuthenticator(this::stubAuthenticate);
    when(transaction.scan(any()))
        .thenThrow(
            new CrudException(
                "DB-AUTH-10012: Access denied: You need the READ privilege on the table ns.t",
                "tx"));
    try (Connection connection = connectAs("alice", "secret");
        Statement statement = connection.createStatement()) {
      assertSqlState(statement, "SELECT id FROM t", "42501");
      try (ResultSet rs = statement.executeQuery("SELECT 1")) {
        assertThat(rs.next()).isTrue();
      }
    }
  }

  @Test
  public void pgStatActivity_ShouldListTheSessionsByTheirBackendPids() throws Exception {
    int other;
    try (Connection connection = connect("?ApplicationName=watcher");
        Statement statement = connection.createStatement()) {
      int pid = connection.unwrap(org.postgresql.PGConnection.class).getBackendPID();
      try (ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(pid).isPositive();
      }
      String columns =
          "SELECT application_name, state, query, xact_start IS NOT NULL AS x, usename"
              + " FROM pg_stat_activity WHERE pid = ";
      try (Connection idle = connect("");
          Statement idleStatement = idle.createStatement()) {
        other = idle.unwrap(org.postgresql.PGConnection.class).getBackendPID();
        idle.setAutoCommit(false);
        idleStatement.executeQuery("SELECT id FROM t").close();
        // this session runs its own query; the other waits in its transaction after its last one
        try (ResultSet rs = statement.executeQuery(columns + pid)) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString(1)).isEqualTo("watcher");
          assertThat(rs.getString(2)).isEqualTo("active");
          assertThat(rs.getString(3)).isEqualTo(columns + pid);
          assertThat(rs.getBoolean(4)).isTrue();
          assertThat(rs.getString(5)).isEqualTo("test");
        }
        try (ResultSet rs = statement.executeQuery(columns + other)) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString(1)).isEqualTo("PostgreSQL JDBC Driver");
          assertThat(rs.getString(2)).isEqualTo("idle in transaction");
          assertThat(rs.getString(3)).isEqualTo("SELECT id FROM t");
          assertThat(rs.getBoolean(4)).isTrue();
        }
        idle.rollback();
        try (ResultSet rs = statement.executeQuery(columns + other)) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString(2)).isEqualTo("idle");
          assertThat(rs.getBoolean(4)).isFalse();
        }
      }
      // a closed session leaves the list, once its connection thread has ended
      for (int i = 0; i < 50 && Catalog.ACTIVITY.containsKey(other); i++) {
        Thread.sleep(100);
      }
      try (ResultSet rs =
          statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid = " + other)) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }

  @Test
  public void pgStatActivity_AnotherUsersSession_ShouldShowOnlyWhoAndWhere() throws Exception {
    allowPlaintext(); // the tests have no TLS; with an authenticator it is otherwise required
    server.setAuthenticator(this::stubAuthenticate);
    // bob's session, as PostgresServer registers one; alice is no superuser
    Catalog.Activity bob =
        new Catalog.Activity(
            Integer.MAX_VALUE, 0, "ns", "bob", new java.net.InetSocketAddress("127.0.0.1", 5432));
    bob.start("SELECT secret FROM t", false);
    Catalog.ACTIVITY.put(bob.pid, bob);
    try (Connection connection = connectAs("alice", "secret");
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT usename, datname, client_addr, state, query, backend_type"
                    + " FROM pg_stat_activity WHERE pid = "
                    + bob.pid)) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("bob");
      assertThat(rs.getString(2)).isEqualTo("ns");
      assertThat(rs.getString(3)).isNull();
      assertThat(rs.getString(4)).isNull();
      assertThat(rs.getString(5)).isEqualTo("<insufficient privilege>");
      assertThat(rs.getString(6)).isNull();
    } finally {
      Catalog.ACTIVITY.remove(bob.pid);
    }
  }

  // ---- CREATE USER, GRANT and the like, onto AuthAdmin

  private Connection connectForDcl() throws Exception {
    allowPlaintext(); // the tests have no TLS; with an authenticator it is otherwise required
    server.setAuthenticator(this::stubAuthenticate);
    // bob is a user, readers and admins are roles, nobody else exists
    when(admin.getUser(any())).thenReturn(Optional.empty());
    when(admin.getRole(any())).thenReturn(Optional.empty());
    when(admin.getUser("bob")).thenReturn(Optional.of(mock(AuthAdmin.User.class)));
    when(admin.getRole("readers")).thenReturn(Optional.of(mock(AuthAdmin.Role.class)));
    when(admin.getRole("admins")).thenReturn(Optional.of(mock(AuthAdmin.Role.class)));
    return connectAs("alice", "secret");
  }

  /** The statement fails with {@code sqlState}, and its message never holds {@code secret}. */
  private static void assertRefused(
      Statement statement, String sql, String sqlState, String secret) {
    assertThatThrownBy(() -> statement.execute(sql))
        .isInstanceOfSatisfying(
            SQLException.class,
            e -> {
              assertThat(e.getSQLState()).as(sql).isEqualTo(sqlState);
              assertThat(e.getMessage()).as(sql).doesNotContain(secret);
            });
  }

  @Test
  public void dcl_WithoutAuthentication_ShouldBeUnsupported() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      assertSqlState(statement, "CREATE USER bob", "0A000");
      assertSqlState(statement, "GRANT SELECT ON t TO bob", "0A000");
    }
  }

  @Test
  public void dcl_Users_ShouldMapOntoAuthAdmin() throws Exception {
    try (Connection connection = connectForDcl();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE USER Carol WITH PASSWORD 'pw'");
      verify(admin).createUser("carol", "pw");
      statement.execute("CREATE ROLE \"Ops\"");
      verify(admin).createRole("Ops");
      statement.execute("CREATE ROLE dave LOGIN SUPERUSER ENCRYPTED PASSWORD 'it''s'");
      verify(admin).createUser("dave", "it's", AuthAdmin.UserOption.SUPERUSER);
      statement.execute("ALTER USER bob PASSWORD NULL");
      verify(admin).alterUser("bob", "");
      statement.execute("ALTER ROLE CURRENT_USER WITH PASSWORD 'new' NOSUPERUSER");
      verify(admin).alterUser("alice", "new", AuthAdmin.UserOption.NO_SUPERUSER);
      statement.execute("DROP ROLE IF EXISTS bob, readers, ghost;");
      verify(admin).dropUser("bob");
      verify(admin).dropRole("readers");
      assertThat(statement.getWarnings().getMessage())
          .isEqualTo("role \"ghost\" does not exist, skipping");
      assertSqlState(statement, "DROP USER ghost", "42704");

      assertSqlState(statement, "CREATE ROLE r PASSWORD 'x'", "0A000");
      assertSqlState(statement, "CREATE USER x CREATEDB", "0A000");
      assertSqlState(statement, "ALTER USER bob RENAME TO robert", "0A000");
      // psql's \\password sends a verifier hashed on the client: Cluster needs the password
      assertRefused(
          statement,
          "ALTER USER bob PASSWORD 'SCRAM-SHA-256$4096:c2FsdA==$a:b'",
          "0A000",
          "c2FsdA");
      assertRefused(
          statement,
          "ALTER USER bob PASSWORD 'md5" + "0123456789abcdef0123456789abcdef'",
          "0A000",
          "0123456789abcdef");
      // no message repeats a password, whatever is wrong with the statement
      assertRefused(statement, "CREATE USER x PASSWORD 'topsecret' 'other'", "42601", "other");
      assertRefused(statement, "CREATE USER x PASSWORD 'topsecret' PASSWORD", "42601", "topsecret");
      assertRefused(statement, "EXPLAIN CREATE USER x PASSWORD 'topsecret'", "0A000", "topsecret");
      // a leading comment, nested or not, must not send the statement past Dcl to JSQLParser,
      // whose errors quote it
      assertRefused(
          statement,
          "/* a /* nested */ comment */ -- and a line\n CREATE USER x PASSWORD 'topsecret' 'other'",
          "42601",
          "topsecret");
      statement.execute("/* setup */ CREATE USER h PASSWORD 'pw2'");
      verify(admin).createUser("h", "pw2");

      // Cluster's errors, by code
      doThrow(new IllegalArgumentException("DB-AUTH-10000: The user already exists. Username: e"))
          .when(admin)
          .createUser("e", null);
      assertSqlState(statement, "CREATE USER e", "42710");
      doThrow(new com.scalar.db.exception.storage.ExecutionException("DB-AUTH-30002: failed"))
          .when(admin)
          .createUser("f", null);
      assertSqlState(statement, "CREATE USER f", "XX000");
      doThrow(
              new com.scalar.db.exception.storage.ExecutionException(
                  "DB-AUTH-10009: Access denied: You must be a superuser to execute this"
                      + " operation"))
          .when(admin)
          .createUser("g", null);
      assertSqlState(statement, "CREATE USER g", "42501");
    }
  }

  @Test
  public void dcl_Privileges_ShouldFollowScalarDbSql() throws Exception {
    try (Connection connection = connectForDcl();
        Statement statement = connection.createStatement()) {
      when(admin.getPrivileges("bob", "ns", "t")).thenReturn(EnumSet.of(AuthAdmin.Privilege.READ));
      when(admin.getRolePrivileges(any(), any(), any()))
          .thenReturn(EnumSet.noneOf(AuthAdmin.Privilege.class));

      statement.execute("GRANT SELECT, INSERT, UPDATE ON t TO bob WITH GRANT OPTION");
      verify(admin)
          .grant(
              "bob",
              "ns",
              "t",
              AuthAdmin.Privilege.READ,
              AuthAdmin.Privilege.WRITE,
              AuthAdmin.Privilege.GRANT);
      // DELETE needs SELECT: bob has it, the role does not
      statement.execute("GRANT DELETE ON TABLE ns.t TO bob");
      verify(admin).grant("bob", "ns", "t", AuthAdmin.Privilege.READ, AuthAdmin.Privilege.DELETE);
      assertSqlState(statement, "GRANT DELETE ON t TO readers", "0LP01");
      // INSERT and UPDATE are both WRITE
      assertSqlState(statement, "GRANT INSERT ON t TO bob", "0LP01");
      assertSqlState(statement, "REVOKE UPDATE ON t FROM bob", "0LP01");

      statement.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO readers");
      verify(admin)
          .grantPrivilegeToRole(
              "readers",
              "ns",
              AuthAdmin.Privilege.READ,
              AuthAdmin.Privilege.WRITE,
              AuthAdmin.Privilege.DELETE,
              AuthAdmin.Privilege.CREATE,
              AuthAdmin.Privilege.DROP,
              AuthAdmin.Privilege.TRUNCATE,
              AuthAdmin.Privilege.ALTER);
      statement.execute("GRANT USAGE, CREATE ON SCHEMA ns TO bob");
      verify(admin).grant("bob", "ns", AuthAdmin.Privilege.CREATE);
      assertThat(statement.getWarnings().getMessage()).contains("USAGE");
      assertSqlState(statement, "GRANT SELECT ON SCHEMA ns TO bob", "0LP01");

      statement.execute("REVOKE SELECT ON t FROM bob");
      verify(admin).revoke("bob", "ns", "t", AuthAdmin.Privilege.READ);
      statement.execute("REVOKE GRANT OPTION FOR SELECT ON t FROM bob RESTRICT");
      verify(admin).revoke("bob", "ns", "t", AuthAdmin.Privilege.GRANT);
      statement.execute("REVOKE DROP, ALTER ON t FROM readers");
      verify(admin)
          .revokePrivilegeFromRole(
              "readers", "ns", "t", AuthAdmin.Privilege.DROP, AuthAdmin.Privilege.ALTER);

      assertSqlState(statement, "GRANT SELECT ON t TO PUBLIC", "0A000");
      assertSqlState(statement, "GRANT SELECT (id) ON t TO bob", "0A000");
      assertSqlState(statement, "GRANT SELECT ON SEQUENCE s TO bob", "0A000");
      assertSqlState(statement, "GRANT REFERENCES ON t TO bob", "0A000");
      assertSqlState(statement, "REVOKE SELECT ON t FROM bob CASCADE", "0A000");
      assertSqlState(statement, "GRANT SELECT ON missing TO bob", "42P01");
      assertSqlState(statement, "GRANT SELECT ON t TO ghost", "42704");
    }
  }

  @Test
  public void dcl_RoleMembership_ShouldMapOntoAuthAdmin() throws Exception {
    try (Connection connection = connectForDcl();
        Statement statement = connection.createStatement()) {
      statement.execute("GRANT readers TO bob WITH ADMIN OPTION");
      verify(admin).grantRoleToUser("bob", "readers", true);
      statement.execute("GRANT readers TO admins");
      verify(admin).grantRoleToRole("admins", "readers", false);
      statement.execute("REVOKE ADMIN OPTION FOR readers FROM bob");
      verify(admin).revokeAdminOptionFromUser("bob", "readers");
      statement.execute("REVOKE readers FROM admins");
      verify(admin).revokeRoleFromRole("admins", "readers");
      assertSqlState(statement, "REVOKE ADMIN OPTION FOR SELECT ON t FROM bob", "0A000");
      assertSqlState(statement, "REVOKE GRANT OPTION FOR readers FROM bob", "0A000");
    }
  }

  /** The rows of one of psql's catalog queries, saved verbatim under src/test/resources/psql. */
  private List<String> psql(Statement statement, String name) throws Exception {
    String sql;
    try (java.io.InputStream in = getClass().getResourceAsStream("/psql/" + name + ".sql")) {
      sql = new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
    }
    List<String> rows = new ArrayList<>();
    try (ResultSet rs = statement.executeQuery(sql)) {
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        List<String> row = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
          row.add(rs.getString(i));
        }
        rows.add(String.join("|", row));
      }
    }
    return rows;
  }

  @Test
  public void psqlMetaCommands_ShouldAnswerAsPostgreSqlDoes() throws Exception {
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton("ns"));
    when(admin.getNamespaceTableNames("ns")).thenReturn(Collections.singleton("t"));
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      List<String> failures = new ArrayList<>();
      for (String name :
          Arrays.asList(
              "databases",
              "schemas",
              "comments",
              "access_methods",
              "domains",
              "collations",
              "tablespaces",
              "publications")) {
        try {
          System.out.println(name + ": " + psql(statement, name));
        } catch (SQLException e) {
          failures.add(name + ": " + e.getMessage());
        }
      }
      assertThat(failures).isEmpty();
    }
  }

  @Test
  public void jdbcMetadata_ShouldDescribeTablesAsPostgreSqlDoes() throws Exception {
    // what JDBC-based GUI tools and BI clients browse with: a key in index order, not table order
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton("ns"));
    when(admin.getNamespaceTableNames("ns")).thenReturn(Collections.singleton("u"));
    when(admin.getTableMetadata("ns", "u"))
        .thenReturn(
            TableMetadata.newBuilder()
                .addColumn("a", DataType.TEXT)
                .addColumn("k", DataType.INT)
                .addColumn("c", DataType.BIGINT)
                .addPartitionKey("k")
                .addClusteringKey("c", com.scalar.db.api.Scan.Ordering.Order.DESC)
                .addSecondaryIndex("a")
                .build());
    try (Connection connection = connect("")) {
      java.sql.DatabaseMetaData m = connection.getMetaData();
      assertThat(rows(m.getTables(null, "public", "%", new String[] {"TABLE"}), "TABLE_NAME"))
          .containsExactly("u");
      assertThat(
              rows(m.getColumns(null, "public", "u", "%"), "COLUMN_NAME", "TYPE_NAME", "NULLABLE"))
          .containsExactly("a|text|1", "k|int4|0", "c|int8|0");
      assertThat(rows(m.getPrimaryKeys(null, "public", "u"), "COLUMN_NAME", "KEY_SEQ", "PK_NAME"))
          .containsExactly("k|1|u_pkey", "c|2|u_pkey");
      assertThat(
              rows(
                  m.getIndexInfo(null, "public", "u", false, true),
                  "INDEX_NAME",
                  "ORDINAL_POSITION",
                  "COLUMN_NAME",
                  "ASC_OR_DESC"))
          .containsExactly("u_pkey|1|k|A", "u_pkey|2|c|D", "u_a_idx|1|a|A");
      assertThat(rows(m.getBestRowIdentifier(null, "public", "u", 1, true), "COLUMN_NAME"))
          .containsExactly("k", "c");
      assertThat(rows(m.getImportedKeys(null, "public", "u"), "FK_NAME")).isEmpty();
      assertThat(rows(m.getCatalogs(), "TABLE_CAT")).containsExactly("ns");
      assertThat(rows(m.getSchemas(), "TABLE_SCHEM"))
          .containsExactly("information_schema", "pg_catalog", "public");
      assertThat(m.getSQLKeywords()).contains("analyze").doesNotContain("select");
      assertThat(rows(m.getTypeInfo(), "TYPE_NAME")).contains("int4", "text", "_int4");
      // DBeaver reads index columns by the type name of pg_index.indkey, which pgjdbc looks up
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT indkey AS keys FROM pg_catalog.pg_index")) {
        assertThat(rs.getMetaData().getColumnTypeName(1)).isEqualTo("int2vector");
      }
    }
  }

  @Test
  public void dbeaverCatalogQueries_ShouldAllRun() throws Exception {
    // what DBeaver's PostgreSQL driver sends on connecting and browsing a table, from its source
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton("ns"));
    when(admin.getNamespaceTableNames("ns")).thenReturn(Collections.singleton("t"));
    String sql;
    try (java.io.InputStream in = getClass().getResourceAsStream("/dbeaver/catalog.sql")) {
      sql = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      List<String> failures = new ArrayList<>();
      List<Integer> rows = new ArrayList<>();
      for (String query : sql.split("\n-----\n")) {
        try (ResultSet rs = statement.executeQuery(query)) {
          int n = 0;
          while (rs.next()) {
            n++;
          }
          rows.add(n);
        } catch (SQLException e) {
          failures.add(e.getMessage() + "\n" + query);
        }
      }
      assertThat(failures).isEmpty();
      // the navigator's table list, its size statistics (table and index), the table's columns
      // and its primary key
      assertThat(rows.subList(13, 17)).containsExactly(1, 2, 2, 1);
    }
  }

  /** The named columns of each row, joined with {@code |}. */
  private static List<String> rows(ResultSet rs, String... columns) throws SQLException {
    List<String> out = new ArrayList<>();
    try (ResultSet r = rs) {
      while (r.next()) {
        List<String> values = new ArrayList<>();
        for (String c : columns) {
          values.add(r.getString(c));
        }
        out.add(String.join("|", values));
      }
    }
    return out;
  }

  @Test
  public void du_ShouldListClusterUsersAndRoles() throws Exception {
    AuthAdmin.User bob = mock(AuthAdmin.User.class);
    when(bob.getName()).thenReturn("bob");
    when(bob.isSuperuser()).thenReturn(true);
    AuthAdmin.User carol = mock(AuthAdmin.User.class);
    when(carol.getName()).thenReturn("carol");
    AuthAdmin.GrantedRoleRef readers = mock(AuthAdmin.GrantedRoleRef.class);
    when(readers.getName()).thenReturn("readers");
    AuthAdmin.Role leads = mock(AuthAdmin.Role.class);
    when(leads.getName()).thenReturn("leads");
    when(leads.getGrantedRoles()).thenReturn(Collections.singletonList(readers));
    AuthAdmin.Role readersRole = mock(AuthAdmin.Role.class);
    when(readersRole.getName()).thenReturn("readers");
    AuthAdmin.RoleForUser carolLeads = mock(AuthAdmin.RoleForUser.class);
    when(carolLeads.getName()).thenReturn("leads");
    when(carolLeads.hasAdminOptionOnUser()).thenReturn(true);
    when(admin.getUsers()).thenReturn(Arrays.asList(bob, carol));
    when(admin.getRoles()).thenReturn(Arrays.asList(leads, readersRole));
    when(admin.getRolesForUser("carol")).thenReturn(Collections.singletonList(carolLeads));
    try (Connection connection = connectForDcl();
        Statement statement = connection.createStatement()) {
      // psql 18's \du and \drg, verbatim
      List<String> roles = new ArrayList<>();
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT r.rolname, r.rolsuper, r.rolinherit,\n"
                  + "  r.rolcreaterole, r.rolcreatedb, r.rolcanlogin,\n"
                  + "  r.rolconnlimit, r.rolvaliduntil\n"
                  + ", r.rolreplication\n"
                  + ", r.rolbypassrls\n"
                  + "FROM pg_catalog.pg_roles r\n"
                  + "WHERE r.rolname !~ '^pg_'\n"
                  + "ORDER BY 1;")) {
        while (rs.next()) {
          roles.add(rs.getString(1) + " " + rs.getBoolean(2) + " " + rs.getBoolean(6));
        }
      }
      assertThat(roles)
          .containsExactly(
              "bob true true",
              "carol false true",
              "leads false false",
              "readers false false",
              "scalardb true false"); // the tables' owner, which nobody logs in as
      List<String> grants = new ArrayList<>();
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT m.rolname AS \"Role name\", r.rolname AS \"Member of\",\n"
                  + "  pg_catalog.concat_ws(', ',\n"
                  + "    CASE WHEN pam.admin_option THEN 'ADMIN' END,\n"
                  + "    CASE WHEN pam.inherit_option THEN 'INHERIT' END,\n"
                  + "    CASE WHEN pam.set_option THEN 'SET' END\n"
                  + "  ) AS \"Options\",\n"
                  + "  g.rolname AS \"Grantor\"\n"
                  + "FROM pg_catalog.pg_roles m\n"
                  + "     JOIN pg_catalog.pg_auth_members pam ON (pam.member = m.oid)\n"
                  + "     LEFT JOIN pg_catalog.pg_roles r ON (pam.roleid = r.oid)\n"
                  + "     LEFT JOIN pg_catalog.pg_roles g ON (pam.grantor = g.oid)\n"
                  + "WHERE m.rolname !~ '^pg_'\n"
                  + "ORDER BY 1, 2, 4;\n")) {
        while (rs.next()) {
          grants.add(
              rs.getString(1)
                  + " "
                  + rs.getString(2)
                  + " "
                  + rs.getString(3)
                  + " "
                  + rs.getString(4));
        }
      }
      assertThat(grants)
          .containsExactly(
              "carol leads ADMIN, INHERIT, SET null", "leads readers INHERIT, SET null");
    }
  }

  /** psql 18's {@code \dp t}, verbatim. */
  private static final String DP =
      "SELECT n.nspname as \"Schema\",\n"
          + "  c.relname as \"Name\",\n"
          + "  CASE c.relkind WHEN 'r' THEN 'table' WHEN 'v' THEN 'view' WHEN 'm' THEN 'materialized view' WHEN 'S' THEN 'sequence' WHEN 'f' THEN 'foreign table' WHEN 'p' THEN 'partitioned table' END as \"Type\",\n"
          + "  CASE WHEN pg_catalog.array_length(c.relacl, 1) = 0 THEN '(none)' ELSE pg_catalog.array_to_string(c.relacl, E'\\n') END AS \"Access privileges\",\n"
          + "  pg_catalog.array_to_string(ARRAY(\n"
          + "    SELECT attname || E':\\n  ' || pg_catalog.array_to_string(attacl, E'\\n  ')\n"
          + "    FROM pg_catalog.pg_attribute a\n"
          + "    WHERE attrelid = c.oid AND NOT attisdropped AND attacl IS NOT NULL\n"
          + "  ), E'\\n') AS \"Column privileges\",\n"
          + "  pg_catalog.array_to_string(ARRAY(\n"
          + "    SELECT polname\n"
          + "    || CASE WHEN NOT polpermissive THEN\n"
          + "       E' (RESTRICTIVE)'\n"
          + "       ELSE '' END\n"
          + "    || CASE WHEN polcmd != '*' THEN\n"
          + "           E' (' || polcmd::pg_catalog.text || E'):'\n"
          + "       ELSE E':'\n"
          + "       END\n"
          + "    || CASE WHEN polqual IS NOT NULL THEN\n"
          + "           E'\\n  (u): ' || pg_catalog.pg_get_expr(polqual, polrelid)\n"
          + "       ELSE E''\n"
          + "       END\n"
          + "    || CASE WHEN polwithcheck IS NOT NULL THEN\n"
          + "           E'\\n  (c): ' || pg_catalog.pg_get_expr(polwithcheck, polrelid)\n"
          + "       ELSE E''\n"
          + "       END    || CASE WHEN polroles <> '{0}' THEN\n"
          + "           E'\\n  to: ' || pg_catalog.array_to_string(\n"
          + "               ARRAY(\n"
          + "                   SELECT rolname\n"
          + "                   FROM pg_catalog.pg_roles\n"
          + "                   WHERE oid = ANY (polroles)\n"
          + "                   ORDER BY 1\n"
          + "               ), E', ')\n"
          + "       ELSE E''\n"
          + "       END\n"
          + "    FROM pg_catalog.pg_policy pol\n"
          + "    WHERE polrelid = c.oid), E'\\n')\n"
          + "    AS \"Policies\"\n"
          + "FROM pg_catalog.pg_class c\n"
          + "     LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace\n"
          + "WHERE c.relkind IN ('r','v','m','S','f','p')\n"
          + "  AND c.relname OPERATOR(pg_catalog.~) '^(t)$' COLLATE pg_catalog.default\n"
          + "  AND pg_catalog.pg_table_is_visible(c.oid)\n"
          + "ORDER BY 1, 2;\n";

  @Test
  public void dp_ShouldListClusterGrants() throws Exception {
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton("ns"));
    when(admin.getNamespaceTableNames("ns")).thenReturn(Collections.singleton("t"));
    AuthAdmin.User root = mock(AuthAdmin.User.class);
    when(root.getName()).thenReturn("root");
    when(root.isSuperuser()).thenReturn(true);
    AuthAdmin.User bob = mock(AuthAdmin.User.class);
    when(bob.getName()).thenReturn("bob");
    AuthAdmin.Role readers = mock(AuthAdmin.Role.class);
    when(readers.getName()).thenReturn("readers");
    when(admin.getUsers()).thenReturn(Arrays.asList(root, bob));
    when(admin.getRoles()).thenReturn(Collections.singletonList(readers));
    when(admin.getPrivileges("bob", "ns")).thenReturn(EnumSet.of(AuthAdmin.Privilege.READ));
    when(admin.getPrivileges("bob", "ns", "t"))
        .thenReturn(EnumSet.of(AuthAdmin.Privilege.WRITE, AuthAdmin.Privilege.GRANT));
    when(admin.getRolePrivileges("readers", "ns", "t"))
        .thenReturn(EnumSet.of(AuthAdmin.Privilege.READ, AuthAdmin.Privilege.DELETE));
    when(admin.getRolePrivileges("readers", "ns"))
        .thenReturn(EnumSet.of(AuthAdmin.Privilege.CREATE));
    try (Connection connection = connectForDcl();
        Statement statement = connection.createStatement()) {
      try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM pg_class")) {
        assertThat(rs.next()).isTrue();
      }
      verify(admin, never()).getPrivileges(any(), any(), any()); // only \dp pays for the grants
      try (ResultSet rs = statement.executeQuery(DP)) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(2)).isEqualTo("t");
        // the owner first, as PostgreSQL lists it
        assertThat(rs.getString(4))
            .isEqualTo("scalardb=arwdD/scalardb\nbob=a*r*w*/scalardb\nreaders=rd/scalardb");
        assertThat(rs.next()).isFalse();
      }
      // \dn+: who may create tables in the schema
      assertThat(psql(statement, "schemas"))
          .containsExactly("public|scalardb|readers=C/scalardb|null");
      // has_table_privilege reads the same grants: any listed privilege, a superuser holds all
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT has_table_privilege('readers', 't', 'select'),"
                  + " has_table_privilege('readers', 't', 'INSERT, TRUNCATE'),"
                  + " has_table_privilege('bob', 't'::regclass, 'INSERT WITH GRANT OPTION'),"
                  + " has_table_privilege('readers', 't', 'DELETE WITH GRANT OPTION'),"
                  + " has_table_privilege('root', 'public.t', 'TRUNCATE')")) {
        assertThat(rs.next()).isTrue();
        assertThat(
                List.of(
                    rs.getBoolean(1),
                    rs.getBoolean(2),
                    rs.getBoolean(3),
                    rs.getBoolean(4),
                    rs.getBoolean(5)))
            .containsExactly(true, false, true, false, true);
      }
      assertThatThrownBy(
              () -> statement.executeQuery("SELECT has_table_privilege('bob', 't', 'READ')"))
          .hasMessageContaining("unrecognized privilege type: \"READ\"");
      assertThatThrownBy(
              () -> statement.executeQuery("SELECT has_table_privilege('nobody', 't', 'SELECT')"))
          .hasMessageContaining("role \"nobody\" does not exist");
    }
  }

  @Test
  public void hasSchemaPrivilege_ShouldReadOnlySchemaGrants() throws Exception {
    // pgAdmin asks it of every schema it lists: Cluster's grants on each schema, not on each table
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton("ns"));
    when(admin.getNamespaceTableNames("ns")).thenReturn(Collections.singleton("t"));
    AuthAdmin.User bob = mock(AuthAdmin.User.class);
    when(bob.getName()).thenReturn("bob");
    AuthAdmin.Role readers = mock(AuthAdmin.Role.class);
    when(readers.getName()).thenReturn("readers");
    when(admin.getUsers()).thenReturn(Collections.singletonList(bob));
    when(admin.getRoles()).thenReturn(Collections.singletonList(readers));
    when(admin.getPrivileges("bob", "ns")).thenReturn(EnumSet.of(AuthAdmin.Privilege.READ));
    when(admin.getRolePrivileges("readers", "ns"))
        .thenReturn(EnumSet.of(AuthAdmin.Privilege.CREATE));
    try (Connection connection = connectForDcl();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT has_schema_privilege('readers', 'public', 'CREATE'),"
                    + " has_schema_privilege('bob', 'public', 'CREATE'),"
                    + " has_schema_privilege('bob', 'public', 'USAGE')")) {
      assertThat(rs.next()).isTrue();
      assertThat(List.of(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)))
          .containsExactly(true, false, true);
    }
    verify(admin, never()).getPrivileges(any(), any(), any());
    verify(admin, never()).getRolePrivileges(any(), any(), any());
  }

  // ---- TLS: the test certificate under src/test/resources/tls

  private static String resource(String name) {
    return java.util.Objects.requireNonNull(PostgresServerTest.class.getResource(name)).getPath();
  }

  private static Properties tlsProperties() {
    Properties properties = new Properties();
    properties.setProperty("scalar.db.postgres.ssl.cert", resource("/tls/server.crt"));
    properties.setProperty("scalar.db.postgres.ssl.key", resource("/tls/server.key"));
    return properties;
  }

  @Test
  public void tls_SslRequest_ShouldUpgradeTheConnection() throws Exception {
    server.configure(tlsProperties());
    Properties properties = new Properties();
    properties.setProperty("user", "test");
    properties.setProperty("ssl", "true");
    properties.setProperty("sslmode", "require");
    properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + server.getPort() + "/ns", properties);
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT id FROM t")) {
      assertThat(rs.next()).isTrue();
    }
  }

  @Test
  public void tls_Required_ShouldRefuseAPlaintextConnection() {
    Properties properties = tlsProperties();
    properties.setProperty("scalar.db.postgres.ssl.require", "true");
    server.configure(properties);
    assertThatThrownBy(() -> connect("?sslmode=disable"))
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28000"));
  }

  @Test
  public void tls_Pkcs1Key_ShouldBeRefusedWithTheConversionHint() throws Exception {
    java.nio.file.Path key = java.nio.file.Files.createTempFile("key", ".pem");
    java.nio.file.Files.writeString(
        key, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
    Properties properties = tlsProperties();
    properties.setProperty("scalar.db.postgres.ssl.key", key.toString());
    assertThatThrownBy(() -> server.configure(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pkcs8");
  }

  @Test
  public void describe_ShowAfterAnotherStatement_ShouldDescribeTheShow() throws Exception {
    try (Connection connection = connect("?prepareThreshold=1")) {
      try (PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM t");
          ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
      }
      // pgjdbc reads the isolation level with SHOW through the extended protocol
      assertThat(connection.getTransactionIsolation())
          .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      try (PreparedStatement statement = connection.prepareStatement("SHOW TimeZone");
          ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1))
            .isEqualTo(java.util.TimeZone.getDefault().getID()); // pgjdbc sets it
      }
    }
  }

  @Test
  public void deallocate_ShouldDropPreparedStatements() throws Exception {
    try (Connection connection = connect("?prepareThreshold=1")) {
      // pgjdbc prepares this server-side, sees the DEALLOCATE ALL tag, and prepares it again
      try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM t")) {
        for (int i = 0; i < 2; i++) {
          try (ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
          }
          try (Statement deallocate = connection.createStatement()) {
            deallocate.execute("DEALLOCATE ALL");
          }
        }
      }
      try (Statement statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.execute("DEALLOCATE PREPARE nosuch"))
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("26000"));
      }
    }
  }

  // ---- malformed sizes over a raw socket

  private static void startup(java.io.DataOutputStream out) throws java.io.IOException {
    byte[] params =
        "user\0test\0database\0ns\0\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.writeInt(8 + params.length);
    out.writeInt(196608);
    out.write(params);
    out.flush();
  }

  /** Reads messages until one of type {@code until}; returns the SQLSTATE of an error met first. */
  private static String readUntil(java.io.DataInputStream in, char until)
      throws java.io.IOException {
    while (true) {
      int type = in.read();
      if (type < 0) {
        return "closed";
      }
      byte[] body = new byte[in.readInt() - 4];
      in.readFully(body);
      if (type == 'E') {
        String fields = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        int c = fields.indexOf("\0C");
        return fields.substring(c + 2, c + 7);
      }
      if (type == until) {
        return "";
      }
    }
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UNENCRYPTED_SOCKET",
      justification = "A test speaking the plaintext protocol to a local server")
  public void protocol_OversizedLengths_ShouldBeRefusedWithoutAllocating() throws Exception {
    // a startup packet longer than PostgreSQL allows
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      out.writeInt(Integer.MAX_VALUE);
      out.writeInt(196608);
      out.flush();
      assertThat(readUntil(in, 'Z')).isEqualTo("08P01");
    }
    // a message claiming 2 GB, after a good startup: FATAL, and the connection closes
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      out.writeByte('Q');
      out.writeInt(Integer.MAX_VALUE);
      out.flush();
      assertThat(readUntil(in, 'Z')).isEqualTo("08P01");
      assertThat(in.read()).isEqualTo(-1);
    }
    // a Bind whose parameter claims more bytes than the message has: an error, the session goes on
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      byte[] parse = "\0SELECT $1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      out.writeByte('P');
      out.writeInt(4 + parse.length + 2);
      out.write(parse);
      out.writeShort(0);
      java.io.ByteArrayOutputStream bind = new java.io.ByteArrayOutputStream();
      java.io.DataOutputStream b = new java.io.DataOutputStream(bind);
      b.writeBytes("\0\0"); // unnamed portal and statement
      b.writeShort(0); // no parameter formats
      b.writeShort(1); // one parameter
      b.writeInt(1_000_000_000); // claimed length, far beyond the message
      b.writeShort(0); // no result formats
      out.writeByte('B');
      out.writeInt(4 + bind.size());
      out.write(bind.toByteArray());
      out.writeByte('S');
      out.writeInt(4);
      out.flush();
      assertThat(readUntil(in, 'Z')).isEqualTo("08P01");
      assertThat(readUntil(in, 'Z')).isEmpty(); // ReadyForQuery after Sync: still connected
    }
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UNENCRYPTED_SOCKET",
      justification = "A test speaking the plaintext protocol to a local server")
  public void protocol_MalformedMessages_ShouldBeRefused() throws Exception {
    // an unknown message type
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      socket.setSoTimeout(5000);
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      out.writeByte('!');
      out.writeInt(4);
      out.flush();
      assertThat(readUntil(in, 'Z')).isEqualTo("08P01");
      assertThat(in.read()).isEqualTo(-1); // FATAL: the connection closes
    }
    // a length shorter than the length field itself
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      socket.setSoTimeout(5000);
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      out.writeByte('Q');
      out.writeInt(3);
      out.flush();
      assertThat(readUntil(in, 'Z')).isEqualTo("08P01");
      assertThat(in.read()).isEqualTo(-1);
    }
    // a query string without its terminating zero byte
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      socket.setSoTimeout(5000);
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      out.writeByte('Q');
      out.writeInt(4 + 8);
      out.writeBytes("SELECT 1");
      out.flush();
      assertThat(readUntil(in, 'Z')).isEqualTo("08P01");
      assertThat(readUntil(in, 'Z')).isEmpty(); // an ERROR: the session goes on
    }
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UNENCRYPTED_SOCKET",
      justification = "A test speaking the plaintext protocol to a local server")
  public void protocol_ExecuteRowLimit_ShouldSuspendThePortal() throws Exception {
    when(transaction.scan(any())).thenReturn(Arrays.asList(row(1, "a"), row(2, "b"), row(3, "c")));
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out =
            new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(socket.getOutputStream()));
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      // The subquery's rows and the reader that fetches them belong to the plan, not to a run's
      // operators, so a run of the same plan that finishes first must not take them away
      parse(out, "s", "SELECT id FROM t WHERE id + 0 <= (SELECT count(*) FROM t)");
      assertThat(readUntil(in, 'Z')).isEmpty();
      bind(out, "p1");
      execute(out, "p1", 1); // one row, then suspended
      bind(out, "p2");
      execute(out, "p2", 0); // the same statement in full, while p1 is suspended
      execute(out, "p1", 0); // the rest of p1
      out.writeByte('S');
      out.writeInt(4);
      out.flush();
      StringBuilder types = new StringBuilder();
      int type;
      do {
        type = in.read();
        in.readFully(new byte[in.readInt() - 4]);
        types.append((char) type);
      } while (type != 'Z');
      // BindComplete, a row, PortalSuspended; p2 whole; p1's other two rows and CommandComplete
      assertThat(types.toString()).isEqualTo("2Ds" + "2DDDC" + "DDC" + "Z");
    }
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UNENCRYPTED_SOCKET",
      justification = "A test speaking the plaintext protocol to a local server")
  public void protocol_FailingCatalogQueries_ShouldFailEveryTimeAndLeaveTheSessionUsable()
      throws Exception {
    // no plan, cached or described, may carry an answer for a query that fails (todo 057)
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out =
            new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(socket.getOutputStream()));
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      socket.setSoTimeout(5000);
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      // an unknown catalog table fails when described (the frontend plans at Describe, not at
      // Parse), a setting that does not exist when it runs; both fail every time they run
      String[][] cases = {
        {"SELECT * FROM pg_catalog.pg_stat_replication", "42P01", "42P01"},
        {"SELECT current_setting('nosuch') AS v", "", "42704"}
      };
      for (String[] c : cases) {
        parse(out, "s", c[0]);
        assertThat(readUntil(in, 'Z')).isEmpty();
        byte[] describe = "Ss\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeByte('D');
        out.writeInt(4 + describe.length);
        out.write(describe);
        out.writeByte('S');
        out.writeInt(4);
        out.flush();
        assertThat(readUntil(in, 'Z')).isEqualTo(c[1]);
        if (!c[1].isEmpty()) {
          assertThat(readUntil(in, 'Z')).isEmpty();
        }
        for (int i = 0; i < 2; i++) {
          bind(out, "");
          execute(out, "", 0);
          out.writeByte('S');
          out.writeInt(4);
          out.flush();
          assertThat(readUntil(in, 'Z')).isEqualTo(c[2]);
          assertThat(readUntil(in, 'Z')).isEmpty();
        }
      }
      // no failed transaction is left behind: the next statement runs, and the session is idle
      byte[] query = "SELECT 1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      out.writeByte('Q');
      out.writeInt(4 + query.length);
      out.write(query);
      out.flush();
      assertThat(readUntil(in, 'C')).isEmpty();
      assertThat(in.read()).isEqualTo('Z');
      assertThat(in.readInt()).isEqualTo(5);
      assertThat(in.read()).isEqualTo('I');
    }
  }

  private static void bind(java.io.DataOutputStream out, String portal) throws java.io.IOException {
    byte[] names = (portal + "\0s\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.writeByte('B');
    out.writeInt(4 + names.length + 6);
    out.write(names);
    out.writeShort(0); // no parameter formats
    out.writeShort(0); // no parameters
    out.writeShort(0); // no result formats
  }

  private static void execute(java.io.DataOutputStream out, String portal, int maxRows)
      throws java.io.IOException {
    byte[] name = (portal + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.writeByte('E');
    out.writeInt(4 + name.length + 4);
    out.write(name);
    out.writeInt(maxRows);
  }

  @Test
  public void fetchSize_ShouldPageThroughTheRows() throws Exception {
    when(transaction.scan(any())).thenReturn(Arrays.asList(row(1, "a"), row(2, "b"), row(3, "c")));
    try (Connection connection = connect("")) {
      connection.setAutoCommit(false); // pgjdbc uses the row limit only in a transaction
      try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM t")) {
        statement.setFetchSize(1);
        List<Integer> ids = new java.util.ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) {
            ids.add(rs.getInt(1));
          }
        }
        assertThat(ids).containsExactly(1, 2, 3);
      }
      connection.commit();
    }
  }

  private static void parse(java.io.DataOutputStream out, String name, String sql)
      throws java.io.IOException {
    byte[] body = (name + "\0" + sql + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.writeByte('P');
    out.writeInt(4 + body.length + 2);
    out.write(body);
    out.writeShort(0);
    out.writeByte('S');
    out.writeInt(4);
    out.flush();
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UNENCRYPTED_SOCKET",
      justification = "A test speaking the plaintext protocol to a local server")
  public void protocol_PreparedBytes_ShouldBeCappedPerSession() throws Exception {
    String big = "SELECT 1" + " ".repeat(40 << 20); // 40 MiB: two of them pass the 64 MiB cap
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out =
            new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(socket.getOutputStream()));
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      startup(out);
      assertThat(readUntil(in, 'Z')).isEmpty();
      parse(out, "a", big);
      assertThat(readUntil(in, 'Z')).isEmpty();
      parse(out, "a", big); // replacing a statement frees what it held
      assertThat(readUntil(in, 'Z')).isEmpty();
      parse(out, "b", big);
      assertThat(readUntil(in, 'Z')).isEqualTo("53000");
      assertThat(readUntil(in, 'Z')).isEmpty();
      out.writeByte('C'); // closing one makes room again
      out.writeInt(4 + 3);
      out.writeBytes("Sa\0");
      parse(out, "b", big);
      assertThat(readUntil(in, 'Z')).isEmpty();
    }
  }

  @Test
  public void simpleQuery_SeveralWrites_ShouldRunInOneTransaction() throws Exception {
    // one Query message, as psql -c sends it; pgjdbc's default mode splits it into extended ones
    try (Connection connection = connect("?preferQueryMode=simple");
        Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO t VALUES (5, 'x'); INSERT INTO t VALUES (6, 'y')");
    }
    verify(manager).begin(); // both inserts in one transaction, committed once
    verify(transaction).commit();
  }

  @Test
  public void simpleQuery_SecondWriteFails_ShouldRollBackTheFirst() throws Exception {
    try (Connection connection = connect("?preferQueryMode=simple");
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.execute("INSERT INTO t VALUES (5, 'x'); INSERT INTO nosuch VALUES (1)"))
          .isInstanceOf(SQLException.class);
    }
    verify(manager).begin();
    verify(transaction).rollback();
    verify(transaction, org.mockito.Mockito.never()).commit();
  }

  @Test
  public void extendedBatch_ShouldRunInOneTransaction() throws Exception {
    // pgjdbc sends a batch as Parse/Bind/Execute messages and one Sync: one implicit transaction
    try (Connection connection = connect("");
        PreparedStatement insert = connection.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
      for (int id = 5; id <= 7; id++) {
        insert.setInt(1, id);
        insert.setString(2, "x");
        insert.addBatch();
      }
      insert.executeBatch();
    }
    verify(manager).begin();
    verify(transaction).commit();
  }

  @Test
  public void extendedBatch_FailingWrite_ShouldRollBackTheOthers() throws Exception {
    org.mockito.Mockito.doNothing() // the first insert succeeds, the second hits a key
        .doThrow(
            new CrudConflictException(
                "DB-CORE-20013: The record being prepared already exists", null, "tx"))
        .when(transaction)
        .mutate(any());
    try (Connection connection = connect("");
        PreparedStatement insert = connection.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
      insert.setInt(1, 5);
      insert.setString(2, "x");
      insert.addBatch();
      insert.setInt(1, 1); // the mock's existing key
      insert.setString(2, "y");
      insert.addBatch();
      assertThatThrownBy(insert::executeBatch).isInstanceOf(SQLException.class);
      // the session is usable after the failed batch, outside any transaction
      try (Statement statement = connection.createStatement()) {
        statement.execute("SELECT 1");
      }
    }
    verify(transaction).rollback();
    verify(transaction, org.mockito.Mockito.never()).commit();
  }

  @Test
  public void commitConflict_AfterPlainInserts_ShouldBeAUniqueViolation() throws Exception {
    // ScalarDB finds an INSERT's existing key only at commit, as a conflict
    doThrow(
            new com.scalar.db.exception.transaction.CommitConflictException(
                "DB-CORE-20013: A conflict occurred. Details: Mutations: [{Type: Put, Table:"
                    + " ns.t, Condition: Optional[PutIfNotExists{}]}]",
                null,
                "tx"))
        .when(transaction)
        .commit();
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("INSERT INTO t VALUES (9, 'x')"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("23505");
                assertThat(e.getMessage()).contains("\"t_pkey\"");
              });
      // after an UPDATE the same conflict is a write-write conflict, to retry
      assertSqlState(statement, "UPDATE t SET name = 'y' WHERE id = 1", "40001");
    }
  }

  @Test
  public void statement_TimeoutCancelAndRowBudget_ShouldStopIt() throws Exception {
    String big =
        "SELECT count(*) FROM generate_series(1, 1000) a, generate_series(1, 1000) b,"
            + " generate_series(1, 1000) c";
    QueryParser.setMaxRowsInMemory(Integer.MAX_VALUE); // so the timeout and the cancel come first
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      statement.execute("SET statement_timeout = '200ms'");
      long start = System.nanoTime();
      assertSqlState(statement, big, "57014");
      assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
      statement.execute("SET statement_timeout = 0");
      // a CancelRequest on another connection, with this session's key, stops it too
      Thread canceller =
          new Thread(
              () -> {
                try {
                  Thread.sleep(200);
                  statement.cancel();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      canceller.start();
      start = System.nanoTime();
      assertSqlState(statement, big, "57014");
      assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
      canceller.join();
      // and a statement that would hold more rows than allowed fails instead of filling the heap
      QueryParser.setMaxRowsInMemory(1000);
      assertSqlState(
          statement,
          "SELECT count(*) FROM generate_series(1, 100) a, generate_series(1, 100) b",
          "54000");
      try (ResultSet rs = statement.executeQuery("SELECT 1")) {
        assertThat(rs.next()).isTrue(); // the session goes on
      }
    } finally {
      QueryParser.setMaxRowsInMemory(1_000_000);
    }
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UNENCRYPTED_SOCKET",
      justification = "A test speaking the plaintext protocol to a local server")
  public void hardening_CoordinatorDropWriteCeilingAndStartupLayout() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      // without Cluster's privileges to decide, only the operator may drop the coordinator tables
      assertSqlState(statement, "DROP COORDINATOR TABLES", "42501");
      // however it is spelled: the check sits where the drop is planned
      assertThatThrownBy(() -> statement.execute("/* c */ drop\n COORDINATOR  tables ;"))
          .isInstanceOf(SQLException.class);
      verify(admin, org.mockito.Mockito.never()).dropCoordinatorTables(anyBoolean());
      // is_superuser reports the login; a client cannot claim it
      assertSqlState(statement, "SET is_superuser = on", "55P02");
    }
    Properties properties = new Properties();
    properties.setProperty("scalar.db.postgres.max_rows_per_write_ceiling", "500");
    server.configure(properties);
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      assertSqlState(statement, "SET scalardb.max_rows_per_write = 0", "42501");
      assertSqlState(statement, "SET scalardb.max_rows_per_write = 501", "42501");
      statement.execute("SET scalardb.max_rows_per_write = 100");
      try (ResultSet rs = statement.executeQuery("SHOW scalardb.max_rows_per_write")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("100");
      }
      statement.execute("RESET scalardb.max_rows_per_write");
      try (ResultSet rs = statement.executeQuery("SHOW scalardb.max_rows_per_write")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("500"); // the default lowered to the ceiling
      }
      assertThatThrownBy(
              () ->
                  statement.execute("SELECT set_config('scalardb.max_rows_per_write', '0', false)"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
    // nor through the startup message: options are checked as SET checks them, FATAL if refused
    assertThatThrownBy(() -> connect("?options=-c%20scalardb.max_rows_per_write=0"))
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    // a startup packet whose last string has no terminator: FATAL 08P01, as PostgreSQL answers
    try (java.net.Socket socket = new java.net.Socket("localhost", server.getPort());
        java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
        java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream())) {
      out.writeInt(12);
      out.writeInt(196608);
      out.writeBytes("user");
      out.flush();
      assertThat(readUntil(in, 'Z')).isEqualTo("08P01");
    }
  }

  @Test
  public void statement_NowAndSettings_ShouldBehaveAsPostgres() throws Exception {
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT count(DISTINCT now()) FROM generate_series(1, 100000) a")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(1); // one now() per statement, not per row
      }
      // the driver hears about a changed setting, as pgjdbc relies on for TimeZone
      org.postgresql.PGConnection pg = connection.unwrap(org.postgresql.PGConnection.class);
      statement.execute("SET TIME ZONE 'Asia/Kolkata'");
      assertThat(pg.getParameterStatus("TimeZone")).isEqualTo("Asia/Kolkata");
      statement.execute("SET application_name = 'probe'");
      assertThat(pg.getParameterStatus("application_name")).isEqualTo("probe");
      assertSqlState(statement, "SET statement_timeout = 'soon'", "22023");
      assertSqlState(statement, "INSERT INTO t (id, id) VALUES (1, 2)", "42701");
      assertSqlState(statement, "UPDATE t SET name = 'a', name = 'b' WHERE id = 1", "42601");
      // current_setting and set_config read and write what SHOW and SET do
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT current_setting('TimeZone'), set_config('application_name', 'cfg', false),"
                  + " current_setting('no.such', true)")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("Asia/Kolkata");
        assertThat(rs.getString(2)).isEqualTo("cfg");
        assertThat(rs.getString(3)).isNull();
      }
      assertThat(pg.getParameterStatus("application_name")).isEqualTo("cfg");
      assertSqlState(statement, "SELECT current_setting('no_such')", "42704");
    }
  }

  @Test
  public void split_ShouldKnowCommentsQuotesAndDollarQuotes() {
    assertThat(PostgresServer.split("SELECT 1; -- don't\nSELECT 2;"))
        .containsExactly("SELECT 1", "-- don't\nSELECT 2");
    assertThat(PostgresServer.split("SELECT 1 -- a; b")).containsExactly("SELECT 1 -- a; b");
    assertThat(PostgresServer.split("SELECT 1 /* a; /* nested; */ b; */ ; SELECT 2"))
        .containsExactly("SELECT 1 /* a; /* nested; */ b; */", "SELECT 2");
    assertThat(PostgresServer.split("SELECT $f$ a; b $f$; SELECT E'it\\'s; ok'"))
        .containsExactly("SELECT $f$ a; b $f$", "SELECT E'it\\'s; ok'");
    assertThat(PostgresServer.split("-- nothing\n/* at all */")).isEmpty();
    assertThat(QueryParser.parameterCount("SELECT $1 -- isn't $2\n, '$3', $$ $4 $$, $2"))
        .isEqualTo(2);
  }

  @Test
  public void binary_ShouldEncodeIntervalOidAndEveryKnownArray() {
    byte[] interval = PostgresServer.encodeBinary(1186, Interval.parse("1 mon 2 days 00:00:03"));
    java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(interval);
    assertThat(b.getLong()).isEqualTo(3_000_000L); // microseconds, days, months
    assertThat(b.getInt()).isEqualTo(2);
    assertThat(b.getInt()).isEqualTo(1);
    assertThat(PostgresServer.encodeBinary(26, 1259L)).containsExactly(0, 0, 4, (byte) 0xEB);
    // name[]: an array header then the element bytes, not the text {a,b}
    assertThat(PostgresServer.encodeBinary(1003, Arrays.asList("a", "b")).length)
        .isEqualTo(20 + 2 * 5);
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UNENCRYPTED_SOCKET",
      justification = "A test speaking the plaintext protocol to a local server")
  public void lifecycle_CapTimeoutAndClose_ShouldBound() throws Exception {
    server.maxConnections = 1;
    server.startupTimeoutMillis = 300;
    try (Connection first = connect("")) {
      // over the cap: PostgreSQL's 53300
      assertThatThrownBy(() -> connect(""))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("53300"));
      first.createStatement().execute("SELECT 1");
    }
    Thread.sleep(200); // the first session's slot is free once its thread has ended
    // a client that never sends its startup packet is dropped after the timeout
    try (java.net.Socket idle = new java.net.Socket("localhost", server.getPort())) {
      idle.setSoTimeout(5000);
      assertThat(idle.getInputStream().read()).isEqualTo(-1);
    }
    // so is one trickling a byte at a time: the timeout covers the whole startup, not each read
    try (java.net.Socket slow = new java.net.Socket("localhost", server.getPort())) {
      long start = System.nanoTime();
      boolean closed = false;
      for (int i = 0; i < 40 && !closed; i++) {
        try {
          slow.getOutputStream().write(0);
          slow.getOutputStream().flush();
          Thread.sleep(100);
        } catch (java.io.IOException e) {
          closed = true;
        }
      }
      slow.setSoTimeout(5000);
      assertThat(closed || slow.getInputStream().read() == -1).isTrue();
      assertThat(System.nanoTime() - start).isLessThan(3_000_000_000L);
    }
    Thread.sleep(200);
    // close() ends open sessions, not only the listener
    Connection open = connect("");
    server.close();
    assertThatThrownBy(() -> open.createStatement().execute("SELECT 1"))
        .isInstanceOf(SQLException.class);
  }
}
