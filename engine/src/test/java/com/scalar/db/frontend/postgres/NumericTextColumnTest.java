package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Result;
import com.scalar.db.api.Selection;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * numeric without a precision, and numeric(p,s) with more digits than a bigint holds, stored as the
 * exact decimal text: written as PostgreSQL keeps them, compared and ordered as numbers in memory,
 * never pushed down, and refused as a key or index.
 */
public class NumericTextColumnTest {
  private static final String NS = "ns";

  /** Table u as ScalarDB stores it: k int primary key, b numeric, c numeric(30,10). */
  private static final TableMetadata STORED =
      TableMetadata.newBuilder()
          .addColumn("k", DataType.INT)
          .addColumn("b", DataType.TEXT)
          .addColumn("c", DataType.TEXT)
          .addPartitionKey("k")
          .build();

  private DistributedTransactionAdmin admin;
  private DistributedTransaction transaction;
  private QueryParser parser;
  private List<Result> table;
  private List<Operation> executed;

  private static Result row(int k, String b, String c) {
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("k", IntColumn.of("k", k));
    columns.put("b", TextColumn.of("b", b));
    columns.put("c", TextColumn.of("c", c));
    return new ResultImpl(columns, STORED);
  }

  @BeforeEach
  public void setUp() throws Exception {
    admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata(NS, "u")).thenReturn(STORED);
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton(NS));
    when(admin.getNamespaceTableNames(NS)).thenReturn(Collections.singleton("u"));
    parser = new QueryParser(admin, NS);
    MemoryTypeStore types = new MemoryTypeStore(admin);
    types.seed(NS, "u", "b", ColumnType.declared("numeric", ColumnType.MAX_SCALED_PRECISION));
    types.seed(
        NS, "u", "c", ColumnType.declared("numeric(30,10)", ColumnType.MAX_SCALED_PRECISION));
    parser.setTypeStore(types.store);
    transaction = mock(DistributedTransaction.class);
    MemoryTable memory = new MemoryTable(STORED, transaction);
    table = memory.rows;
    executed = memory.executed;
    // as text, "10" sorts before "9" and "1.10" differs from "1.1"
    table.add(row(1, "9", "1.0000000000"));
    table.add(row(2, "10", "2.5000000000"));
    table.add(row(3, "1.10", "12345678901234567890.1234567890"));
    table.add(row(4, "NaN", "0.0000000000"));
  }

  private static String state(ThrowingRunnable r) {
    try {
      r.run();
    } catch (Exception e) {
      return PostgresServer.sqlState(e);
    }
    throw new AssertionError("expected a failure");
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  public void createTable_ShouldStoreAsTextAndRefuseKeys() throws Exception {
    Plan plan = parser.parse("CREATE TABLE q (id INT PRIMARY KEY, a NUMERIC, b DECIMAL(30,10))");
    assertThat(plan.notices).noneMatch(n -> n.startsWith("NUMERIC"));
    plan.getDdl().run();
    org.mockito.ArgumentCaptor<TableMetadata> metadata =
        org.mockito.ArgumentCaptor.forClass(TableMetadata.class);
    verify(admin).createTable(eq(NS), eq("q"), metadata.capture(), eq(false));
    assertThat(metadata.getValue().getColumnDataType("a")).isEqualTo(DataType.TEXT);
    assertThat(metadata.getValue().getColumnDataType("b")).isEqualTo(DataType.TEXT);
    // as text, ScalarDB would order and match the key wrongly
    assertThat(state(() -> parser.parse("CREATE TABLE q (a NUMERIC PRIMARY KEY)")))
        .isEqualTo("0A000");
    assertThat(
            state(
                () ->
                    parser.parse("CREATE TABLE q (id INT, a NUMERIC(19,2), PRIMARY KEY (id, a))")))
        .isEqualTo("0A000");
    assertThat(state(() -> parser.parse("CREATE INDEX ON u (b)").getDdl().run()))
        .isEqualTo("0A000");
  }

  @Test
  public void insert_ShouldStoreTheExactDecimalAsPostgresKeepsIt() throws Exception {
    parser
        .parse("INSERT INTO u VALUES (5, 1.50, 10), (6, 'nan', '-0.00000000005')")
        .open(transaction);
    List<Insert> inserts =
        executed.stream()
            .filter(op -> op instanceof Insert)
            .map(op -> (Insert) op)
            .collect(Collectors.toList());
    assertThat(inserts).hasSize(2);
    // numeric keeps the scale it was written with; numeric(30,10) has ten fraction digits
    assertThat(inserts.get(0).getColumns().get("b").getTextValue()).isEqualTo("1.50");
    assertThat(inserts.get(0).getColumns().get("c").getTextValue()).isEqualTo("10.0000000000");
    assertThat(inserts.get(1).getColumns().get("b").getTextValue()).isEqualTo("NaN");
    assertThat(inserts.get(1).getColumns().get("c").getTextValue()).isEqualTo("-0.0000000001");
    assertThat(state(() -> parser.parse("INSERT INTO u VALUES (7, 1, 1e20)").open(transaction)))
        .isEqualTo("22003");
    assertThat(
            state(() -> parser.parse("INSERT INTO u VALUES (7, '1e-16384', 1)").open(transaction)))
        .isEqualTo("22003");
  }

  @Test
  public void select_ShouldCompareAndOrderAsNumbersInMemory() throws Exception {
    Plan plan = parser.parse("SELECT k, b, c FROM u WHERE b > 5 ORDER BY b");
    // NaN is above every number, as in PostgreSQL
    assertThat(plan.execute(transaction)).extracting(r -> r.get("k")).containsExactly(1, 2, 4);
    assertThat(executed)
        .singleElement()
        .satisfies(op -> assertThat(((Selection) op).getConjunctions()).isEmpty());
    assertThat(plan.outputOids()).containsExactly(23, 1700, 1700);
    assertThat(plan.outputTypmods()).containsExactly(-1, -1, (30 << 16 | 10) + 4);
    assertThat(parser.parse("SELECT b FROM u WHERE b = 1.1 AND b = '1.1'").execute(transaction))
        .extracting(r -> r.get("b"))
        .containsExactly(new BigDecimal("1.10"));
    // 1.1 and 1.10 are one group, as they are equal
    assertThat(
            parser
                .parse("SELECT count(*) AS n FROM u WHERE b IN (1.1, 1.100)")
                .execute(transaction))
        .extracting(r -> r.get("n"))
        .containsExactly(1L);
  }

  @Test
  public void like_ShouldBeRefusedAsInPostgres() {
    assertThat(state(() -> parser.parse("SELECT k FROM u WHERE b LIKE '1%'"))).isEqualTo("42883");
    assertThatThrownBy(() -> parser.parse("SELECT k FROM u WHERE c NOT LIKE '1%'"))
        .hasMessage("operator does not exist: numeric !~~ unknown");
    assertThatThrownBy(() -> parser.parse("SELECT k FROM u WHERE b LIKE '1%'::varchar"))
        .hasMessage("operator does not exist: numeric ~~ character varying");
    // through a subquery or CTE as well
    assertThat(
            state(
                () ->
                    parser.parse("WITH x AS (SELECT b FROM u) SELECT b FROM x WHERE b LIKE '1%'")))
        .isEqualTo("42883");
    assertThat(state(() -> parser.parse("SELECT s.c FROM (SELECT c FROM u) s WHERE s.c LIKE '1%'")))
        .isEqualTo("42883");
  }

  @Test
  public void likeOnSessionFunctions_ShouldStillRun() throws Exception {
    parser.setUser("admin");
    assertThat(parser.parse("SELECT k FROM u WHERE current_user LIKE 'adm%'").execute(transaction))
        .isNotEmpty();
  }

  @Test
  public void catalog_ShouldShowTheDeclaredType() throws Exception {
    assertThat(
            parser
                .parse(
                    "SELECT format_type(atttypid, atttypmod) AS t FROM pg_attribute"
                        + " WHERE attrelid = 'u'::regclass AND attnum > 0 ORDER BY attnum")
                .execute(transaction))
        .extracting(r -> r.get("t"))
        .containsExactly("integer", "numeric", "numeric(30,10)");
    assertThat(
            parser
                .parse(
                    "SELECT column_name, numeric_precision FROM information_schema.columns"
                        + " WHERE table_name = 'u' AND column_name <> 'k' ORDER BY ordinal_position")
                .execute(transaction))
        .extracting(r -> r.get("numeric_precision"))
        .containsExactly(null, 30L);
  }
}
