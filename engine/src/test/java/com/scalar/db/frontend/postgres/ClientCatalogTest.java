package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Replays the catalog queries real clients send (src/test/resources/clients, captured by
 * difftest/catalog) against the schema they were captured with, and checks each answer against the
 * fixture's {@code -- expect:} line: a row count, {@code rows} for at least one, {@code same} for
 * PostgreSQL 16's rows exactly (compared by digest, as difftest/catalog/replay.py computes it), or
 * {@code ERROR <sqlstate>}. A catalog answer must be the truth or an error (todo 057).
 */
public class ClientCatalogTest {
  private static final Pattern QUOTED_OID = Pattern.compile("'(\\d{5,})'");
  private static final HexFormat HEX = HexFormat.of();

  private DistributedTransactionAdmin admin;
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
    admin = mock(DistributedTransactionAdmin.class);
    // difftest/catalog/schema.sql: items in the connected namespace audit, orders in sales. The
    // column types are what ScalarDB stores without the frontend's metadata tables (NUMERIC(10,2)
    // as
    // BIGINT, SMALLINT as INT, VARCHAR(20) as TEXT), so answers about types are written against
    // that.
    when(admin.getNamespaceNames())
        .thenReturn(new LinkedHashSet<>(Arrays.asList("audit", "sales")));
    when(admin.getNamespaceTableNames("audit")).thenReturn(Collections.singleton("items"));
    when(admin.getNamespaceTableNames("sales")).thenReturn(Collections.singleton("orders"));
    when(admin.namespaceExists("audit")).thenReturn(true);
    when(admin.namespaceExists("sales")).thenReturn(true);
    when(admin.tableExists("audit", "items")).thenReturn(true);
    when(admin.tableExists("sales", "orders")).thenReturn(true);
    when(admin.getTableMetadata("audit", "items"))
        .thenReturn(
            TableMetadata.newBuilder()
                .addColumn("id", DataType.INT)
                .addColumn("name", DataType.TEXT)
                .addColumn("price", DataType.BIGINT)
                .addColumn("qty", DataType.INT)
                .addColumn("note", DataType.TEXT)
                .addPartitionKey("id")
                .addSecondaryIndex("name")
                .build());
    when(admin.getTableMetadata("sales", "orders"))
        .thenReturn(
            TableMetadata.newBuilder()
                .addColumn("id", DataType.INT)
                .addColumn("line", DataType.INT)
                .addColumn("item_id", DataType.INT)
                .addColumn("amount", DataType.DOUBLE)
                .addPartitionKey("id")
                .addClusteringKey("line", Scan.Ordering.Order.ASC)
                .build());
    server = new PostgresServer(manager, admin, 0);
    server.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    server.close();
  }

  /** One captured query: its label, PostgreSQL 16's answer, the expected answer, and its text. */
  private static final class Query {
    String label = "";
    String postgres = "";
    String expect;
    String sql;
  }

  private static List<Query> fixture(String name, Map<String, String> oids) throws Exception {
    String text;
    try (java.io.InputStream in =
        ClientCatalogTest.class.getResourceAsStream("/clients/" + name + ".sql")) {
      text = new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Query> queries = new ArrayList<>();
    for (String chunk : text.split("\n-----\n")) {
      Query q = new Query();
      StringBuilder sql = new StringBuilder();
      for (String line : chunk.split("\n", -1)) {
        if (line.startsWith("-- oids: ")) {
          for (String pair : line.substring("-- oids: ".length()).trim().split(" ")) {
            String[] kv = pair.split("=");
            oids.put(kv[1], kv[0]); // captured oid -> relation name
          }
        } else if (line.startsWith("-- [")) {
          q.label = line.substring(4, line.length() - 1);
        } else if (line.startsWith("-- postgres16: ")) {
          q.postgres = line.substring("-- postgres16: ".length());
        } else if (line.startsWith("-- expect: ")) {
          q.expect = line.substring("-- expect: ".length());
        } else if (!line.startsWith("-- ")) {
          sql.append(line).append('\n');
        }
      }
      q.sql = sql.toString().trim();
      if (!q.sql.isEmpty()) {
        queries.add(q);
      }
    }
    return queries;
  }

  /**
   * The answer as the fixtures write it: the row count and a digest of the rows' text (sorted, tab
   * separated, NULL as \N), or ERROR and the SQLSTATE.
   */
  private static String answer(Statement statement, String sql) throws Exception {
    try (ResultSet rs = statement.executeQuery(sql)) {
      int columns = rs.getMetaData().getColumnCount();
      List<String> rows = new ArrayList<>();
      while (rs.next()) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= columns; i++) {
          values.add(rs.getString(i) == null ? "\\N" : rs.getString(i));
        }
        rows.add(String.join("\t", values));
      }
      Collections.sort(rows);
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
      return rows.size() + " " + HEX.formatHex(hash).substring(0, 12);
    } catch (SQLException e) {
      return "ERROR " + e.getSQLState() + " " + String.valueOf(e.getMessage()).split("\n")[0];
    }
  }

  private static boolean matches(Query q, String actual) {
    String count = actual.split(" ")[0];
    return switch (q.expect) {
      case "same" -> actual.equals(q.postgres);
      case "rows" -> !count.equals("ERROR") && !count.equals("0");
      default ->
          q.expect.startsWith("ERROR ")
              ? actual.startsWith(q.expect + " ")
              : count.equals(q.expect);
    };
  }

  @ParameterizedTest
  @ValueSource(strings = {"psql18", "psql14", "pgjdbc", "sqlalchemy", "asyncpg", "django"})
  public void clientCatalogQueries_ShouldMatchExpected(String client) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("user", "test");
    // text results, as the clients that sent these queries read them
    properties.setProperty("preferQueryMode", "simple");
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + server.getPort() + "/audit", properties);
        Statement statement = connection.createStatement()) {
      Map<String, String> captured = new HashMap<>();
      List<Query> queries = fixture(client, captured);
      assertThat(queries).isNotEmpty();
      Map<String, String> here = new HashMap<>(); // relation name -> this server's oid
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT c.oid, n.nspname, c.relname FROM pg_catalog.pg_class c"
                  + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace")) {
        while (rs.next()) {
          here.put(rs.getString(2) + "." + rs.getString(3), rs.getString(1));
        }
      }
      // every relation the queries name must exist here, or its OID would match nothing (or
      // another)
      assertThat(here).containsKeys(captured.values().toArray(new String[0]));
      List<String> mismatches = new ArrayList<>();
      List<String> unexpected = new ArrayList<>();
      for (Query q : queries) {
        Matcher m = QUOTED_OID.matcher(q.sql);
        StringBuffer sql = new StringBuffer();
        while (m.find()) {
          // only the relation OIDs in the oids line; others are the capturing server's system OIDs
          String relation = captured.get(m.group(1));
          m.appendReplacement(
              sql, "'" + (relation == null ? m.group(1) : here.get(relation)) + "'");
        }
        m.appendTail(sql);
        String actual = answer(statement, sql.toString());
        String where =
            client + " [" + q.label + "] postgres16=" + q.postgres + " frontend=" + actual;
        if (q.expect == null) {
          unexpected.add(where);
        } else if (!matches(q, actual)) {
          mismatches.add(where + " expect=" + q.expect + "\n" + q.sql);
        }
      }
      unexpected.forEach(System.out::println);
      assertThat(mismatches).isEmpty();
    }
  }
}
