package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  public void error_ShouldBeReportedAsInternalErrorAndConnectionStayUsable() throws Exception {
    when(manager.begin()).thenThrow(new AssertionError("boom"));
    try (Connection connection = connect("");
        Statement statement = connection.createStatement()) {
      statement.execute("BEGIN"); // the ScalarDB transaction starts with the first statement
      assertThatThrownBy(() -> statement.executeQuery("SELECT id FROM t"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("boom")
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
          .hasMessageContaining("Current transaction is aborted")
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
    assertThat(PostgresServer.binary(1700, numeric(2, 0, 0, 4, 1234, 5678))).isEqualTo("1234.5678");
    // -0.05: one group (0500) at weight -1, negative, scale 2
    assertThat(PostgresServer.binary(1700, numeric(1, -1, 0x4000, 2, 500))).isEqualTo("-0.05");
    // 12000000: one group (1200) at weight 1, scale 0
    assertThat(PostgresServer.binary(1700, numeric(1, 1, 0, 0, 1200))).isEqualTo("12000000");
    assertThat(PostgresServer.binary(1700, numeric(0, 0, 0, 2))).isEqualTo("0.00");
    assertThat(PostgresServer.binary(1700, numeric(0, 0, 0xC000, 0))).isEqualTo("NaN");
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
    byte[] raw = PostgresServer.binary(2950, uuid);
    assertThat(raw).hasSize(16);
    assertThat(PostgresServer.binary(2950, raw)).isEqualTo(uuid);
    byte[] jsonb = PostgresServer.binary(3802, Json.cast("{\"b\":1,\"a\":2}", true));
    assertThat(jsonb[0]).isEqualTo((byte) 1);
    assertThat(PostgresServer.binary(3802, jsonb)).isEqualTo("{\"a\": 2, \"b\": 1}");
  }

  @Test
  public void binary_ShouldDecodeArrayParametersToArrayText() {
    // int4[] {1,NULL,3}: 1 dimension, has nulls, element oid 23, 3 elements from index 1
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(20 + 8 + 4 + 8);
    b.putInt(1).putInt(1).putInt(23).putInt(3).putInt(1);
    b.putInt(4).putInt(1).putInt(-1).putInt(4).putInt(3);
    assertThat(PostgresServer.binary(1007, b.array())).isEqualTo("{1,NULL,3}");
    // text[] {"a b",c}
    byte[] ab = "a b".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.nio.ByteBuffer t = java.nio.ByteBuffer.allocate(20 + 4 + ab.length + 4 + 1);
    t.putInt(1).putInt(0).putInt(25).putInt(2).putInt(1);
    t.putInt(ab.length).put(ab).putInt(1).put((byte) 'c');
    assertThat(PostgresServer.binary(1009, t.array())).isEqualTo("{\"a b\",c}");
    assertThat(PostgresServer.typed("{1,2}", 1007)).isEqualTo("{1,2}");
  }

  @Test
  public void binary_ShouldEncodeNumericLikePostgres() {
    assertThat(PostgresServer.binary(1700, new java.math.BigDecimal("1234.5678")))
        .isEqualTo(numeric(2, 0, 0, 4, 1234, 5678));
    assertThat(PostgresServer.binary(1700, new java.math.BigDecimal("-0.05")))
        .isEqualTo(numeric(1, -1, 0x4000, 2, 500));
    assertThat(PostgresServer.binary(1700, new java.math.BigDecimal("12000000")))
        .isEqualTo(numeric(1, 1, 0, 0, 1200));
    assertThat(PostgresServer.binary(1700, new java.math.BigDecimal("2500.0")))
        .isEqualTo(numeric(1, 0, 0, 1, 2500));
    assertThat(PostgresServer.binary(1700, new java.math.BigDecimal("0.00")))
        .isEqualTo(numeric(0, 0, 0, 2));
    // decoding what was encoded gives the text back
    for (String s : new String[] {"3.5000000000000000", "-180414.25", "0.0001", "1000"}) {
      assertThat(
              PostgresServer.binary(1700, PostgresServer.binary(1700, new java.math.BigDecimal(s))))
          .isEqualTo(s);
    }
    assertThat(PostgresServer.typed("1.5", 0)).isEqualTo(new java.math.BigDecimal("1.5"));
    assertThat(PostgresServer.typed("1.5", 701)).isEqualTo(1.5);
    assertThat(PostgresServer.typed("NaN", 1700)).isEqualTo(Double.NaN);
  }

  @Test
  public void binary_ShouldEncodeLikePostgres() {
    assertThat(PostgresServer.binary(23, 7)).containsExactly(0, 0, 0, 7);
    assertThat(PostgresServer.binary(20, 1L)).containsExactly(0, 0, 0, 0, 0, 0, 0, 1);
    assertThat(PostgresServer.binary(16, true)).containsExactly(1);
    assertThat(PostgresServer.binary(701, 1.5)).containsExactly(0x3f, 0xf8, 0, 0, 0, 0, 0, 0);
    assertThat(PostgresServer.binary(1082, java.time.LocalDate.of(2000, 1, 3)))
        .containsExactly(0, 0, 0, 2);
    assertThat(PostgresServer.binary(1114, java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 1)))
        .containsExactly(0, 0, 0, 0, 0, 0x0f, 0x42, 0x40);
    assertThat(PostgresServer.binary(25, "ab")).containsExactly('a', 'b');
    // time: microseconds since midnight; timestamptz: microseconds since 2000-01-01 UTC; bytea: raw
    assertThat(PostgresServer.binary(1083, java.time.LocalTime.of(0, 0, 1)))
        .containsExactly(0, 0, 0, 0, 0, 0x0f, 0x42, 0x40);
    assertThat(PostgresServer.binary(1184, java.time.Instant.parse("2000-01-01T00:00:01Z")))
        .containsExactly(0, 0, 0, 0, 0, 0x0f, 0x42, 0x40);
    assertThat(PostgresServer.binary(17, java.nio.ByteBuffer.wrap(new byte[] {(byte) 0xde, 1})))
        .containsExactly(0xde, 1);
    assertThat(PostgresServer.binary(17, (Object) new byte[] {7})).containsExactly(7);
  }

  @Test
  public void text_Floats_ShouldUsePostgresFormat() {
    assertThat(PostgresServer.text(2500.0)).isEqualTo("2500");
    assertThat(PostgresServer.text(0.1)).isEqualTo("0.1");
    assertThat(PostgresServer.text(-0.0)).isEqualTo("-0");
    assertThat(PostgresServer.text(1e14)).isEqualTo("100000000000000");
    assertThat(PostgresServer.text(1e15)).isEqualTo("1e+15");
    assertThat(PostgresServer.text(1.5e-5)).isEqualTo("1.5e-05");
    assertThat(PostgresServer.text(0.0001)).isEqualTo("0.0001");
    assertThat(PostgresServer.text(Double.NaN)).isEqualTo("NaN");
    assertThat(PostgresServer.text(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    assertThat(PostgresServer.text(100000f)).isEqualTo("100000");
    assertThat(PostgresServer.text(1e6f)).isEqualTo("1e+06");
    assertThat(PostgresServer.text(0.1f)).isEqualTo("0.1");
  }

  @Test
  public void errors_ShouldCarryPostgresSqlStates() throws Exception {
    doThrow(
            new CrudConflictException(
                "DB-CORE-20013: The record being prepared already exists", null, "tx"))
        .when(manager)
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

  private Authenticator.Identity stubAuthenticate(String user, String secret)
      throws Authenticator.AuthenticationException {
    if (!user.equals("alice") || !secret.equals("secret")) {
      throw new Authenticator.AuthenticationException("not alice");
    }
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
    when(manager.scan(any()))
        .thenAnswer(
            invocation -> {
              insideScope();
              return Arrays.asList(row(1, "a"), row(2, "b"));
            });
    when(manager.get(any()))
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
    doAnswer(
            invocation -> {
              insideScope();
              return null;
            })
        .when(admin)
        .createTable(any(), any(), any(), anyBoolean());
  }

  private Connection connectAs(String user, String password) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", user);
    properties.setProperty("password", password);
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
      // a lookup join reads its inner side from the pool: those threads enter the identity too
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
    assertThat(lastIdentity.threads)
        .anyMatch(thread -> thread.startsWith("postgres-frontend-"))
        .anyMatch(thread -> thread.equals("lookup"));
    for (int i = 0; i < 100 && !lastIdentity.closed; i++) {
      Thread.sleep(20); // the server closes the identity after the client has gone
    }
    assertThat(lastIdentity.closed).isTrue();
  }

  @Test
  public void auth_TokenInvalidMidSession_ShouldReport28000AndCloseTheSession() throws Exception {
    allowPlaintext(); // the tests have no TLS; with an authenticator it is otherwise required
    server.setAuthenticator(this::stubAuthenticate);
    when(manager.scan(any()))
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
    when(manager.scan(any()))
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
    byte[] interval = PostgresServer.binary(1186, Interval.parse("1 mon 2 days 00:00:03"));
    java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(interval);
    assertThat(b.getLong()).isEqualTo(3_000_000L); // microseconds, days, months
    assertThat(b.getInt()).isEqualTo(2);
    assertThat(b.getInt()).isEqualTo(1);
    assertThat(PostgresServer.binary(26, 1259L)).containsExactly(0, 0, 4, (byte) 0xEB);
    // name[]: an array header then the element bytes, not the text {a,b}
    assertThat(PostgresServer.binary(1003, Arrays.asList("a", "b")).length).isEqualTo(20 + 2 * 5);
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
