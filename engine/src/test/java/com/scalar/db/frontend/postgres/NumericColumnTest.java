package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.ScanAll;
import com.scalar.db.api.Selection;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * numeric(10,2) columns stored as scaled bigints: written rounded and scaled, read back exact, and
 * described to clients as numeric(10,2), whatever path the value takes.
 */
public class NumericColumnTest {
  private static final String NS = "ns";
  private static final int NUMERIC_10_2 = (10 << 16 | 2) + 4; // PostgreSQL's typmod

  /** Table m as ScalarDB stores it: k int, p numeric(10,2), a numeric(10,2), key (k, p). */
  private static final TableMetadata STORED =
      TableMetadata.newBuilder()
          .addColumn("k", DataType.INT)
          .addColumn("p__pg_n10_2", DataType.BIGINT)
          .addColumn("a__pg_n10_2", DataType.BIGINT)
          .addPartitionKey("k")
          .addClusteringKey("p__pg_n10_2")
          .build();

  private DistributedTransaction transaction;
  private QueryParser parser;
  private final List<Result> table = new ArrayList<>();
  private final List<Operation> executed = new ArrayList<>();

  private static Result row(int k, long p, long a) {
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("k", IntColumn.of("k", k));
    columns.put("p__pg_n10_2", BigIntColumn.of("p__pg_n10_2", p));
    columns.put("a__pg_n10_2", BigIntColumn.of("a__pg_n10_2", a));
    return new ResultImpl(columns, STORED);
  }

  private static boolean keyMatches(Result r, Key key) {
    for (Column<?> c : key.getColumns()) {
      if (!String.valueOf(r.getAsObject(c.getName()))
          .equals(String.valueOf(c.getValueAsObject()))) {
        return false;
      }
    }
    return true;
  }

  @BeforeEach
  public void setUp() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata(NS, "m")).thenReturn(STORED);
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton(NS));
    when(admin.getNamespaceTableNames(NS)).thenReturn(Collections.singleton("m"));
    parser = new QueryParser(admin, NS);
    transaction = mock(DistributedTransaction.class);
    table.add(row(1, 100, 1990)); // p 1.00, a 19.90
    table.add(row(1, 250, 5)); // p 2.50, a 0.05
    Map<String, Column<?>> nullA = new LinkedHashMap<>();
    nullA.put("k", IntColumn.of("k", 2));
    nullA.put("p__pg_n10_2", BigIntColumn.of("p__pg_n10_2", 0));
    nullA.put("a__pg_n10_2", BigIntColumn.ofNull("a__pg_n10_2"));
    table.add(new ResultImpl(nullA, STORED)); // k 2, p 0.00, a NULL
    when(transaction.get(any()))
        .thenAnswer(
            invocation -> {
              Get get = invocation.getArgument(0);
              executed.add(get);
              return table.stream()
                  .filter(r -> keyMatches(r, get.getPartitionKey()))
                  .filter(
                      r ->
                          !get.getClusteringKey().isPresent()
                              || keyMatches(r, get.getClusteringKey().get()))
                  .findFirst();
            });
    when(transaction.scan(any()))
        .thenAnswer(
            invocation -> {
              Scan scan = invocation.getArgument(0);
              executed.add(scan);
              return scan instanceof ScanAll
                  ? new ArrayList<>(table)
                  : table.stream()
                      .filter(r -> keyMatches(r, scan.getPartitionKey()))
                      .collect(Collectors.toList());
            });
    TestScanners.stub(transaction);
    doAnswer(
            invocation -> {
              List<Mutation> mutations = invocation.getArgument(0);
              executed.addAll(mutations);
              return null;
            })
        .when(transaction)
        .mutate(anyList());
  }

  private List<Mutation> mutations() {
    return executed.stream()
        .filter(op -> op instanceof Mutation)
        .map(op -> (Mutation) op)
        .collect(Collectors.toList());
  }

  private static Throwable failure(ThrowingRunnable r) {
    try {
      r.run();
    } catch (Exception e) {
      return e;
    }
    throw new AssertionError("expected a failure");
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  public void insert_ShouldRoundHalfAwayFromZeroAndStoreScaled() throws Exception {
    // 19.905 rounds to 19.91; a float8 1.005 is 1.005 as numeric (15 digits), so 1.01
    parser.parse("INSERT INTO m (k, p, a) VALUES (2, 19.905, '1.005'::float8)").open(transaction);
    Insert insert = (Insert) mutations().get(0);
    assertThat(insert.getClusteringKey().get().getColumns())
        .containsExactly(BigIntColumn.of("p__pg_n10_2", 1991));
    assertThat(insert.getColumns().get("a__pg_n10_2").getBigIntValue()).isEqualTo(101);
  }

  @Test
  public void insert_TooLarge_ShouldFailWithPostgresDetail() {
    Throwable e =
        failure(
            () ->
                parser.parse("INSERT INTO m (k, p, a) VALUES (2, 1, 123456789)").open(transaction));
    assertThat(PostgresServer.sqlState(e)).isEqualTo("22003");
    assertThat(((PgException) e).detail())
        .isEqualTo(
            "A field with precision 10, scale 2 must round to an absolute value less than 10^8.");
  }

  @Test
  public void select_ShouldReturnExactValuesDescribedAsNumeric() throws Exception {
    QueryParser.Plan plan =
        parser.parse("SELECT p, a, a * 2 AS twice FROM m WHERE k = 1 ORDER BY p");
    assertThat(plan.execute(transaction))
        .extracting(r -> r.get("a"))
        .containsExactly(new BigDecimal("19.90"), new BigDecimal("0.05"));
    assertThat(plan.outputOids()).containsExactly(1700, 1700, 1700);
    assertThat(plan.outputTypmods()).containsExactly(NUMERIC_10_2, NUMERIC_10_2, -1);
    // through a subquery as well
    QueryParser.Plan derived = parser.parse("SELECT s.a FROM (SELECT a FROM m) s");
    assertThat(derived.outputOids()).containsExactly(1700);
    assertThat(derived.outputTypmods()).containsExactly(NUMERIC_10_2);
  }

  @Test
  public void where_ShouldPushScaledBoundsDown() throws Exception {
    // a > 1.005 holds for exactly the stored values from 1.01 up: a__pg_n10_2 >= 101
    parser.parse("SELECT k FROM m WHERE k = 1 AND a > 1.005").execute(transaction);
    assertThat(executed)
        .singleElement()
        .satisfies(
            op ->
                assertThat(((Selection) op).getConjunctions().iterator().next().getConditions())
                    .containsExactly(
                        ConditionBuilder.column("a__pg_n10_2").isGreaterThanOrEqualToBigInt(101)));
    // a numeric(10,2) column never equals 1.005
    assertThat(parser.parse("SELECT k FROM m WHERE k = 1 AND a = 1.005").execute(transaction))
        .isEmpty();
  }

  @Test
  public void deleteByUnrepresentableKey_ShouldDeleteNothing() throws Exception {
    parser.parse("DELETE FROM m WHERE k = 1 AND p = 1.005").execute(transaction);
    assertThat(mutations()).isEmpty();
  }

  @Test
  public void updateReturning_ShouldWriteScaledAndReturnNumeric() throws Exception {
    QueryParser.Plan plan =
        parser.parse("UPDATE m SET a = a * 100 WHERE k = 1 AND p = 1 RETURNING a");
    assertThat(plan.execute(transaction))
        .extracting(r -> r.get("a"))
        .containsExactly(new BigDecimal("1990.00"));
    assertThat(((Update) mutations().get(0)).getColumns().get("a__pg_n10_2").getBigIntValue())
        .isEqualTo(199000);
    assertThat(plan.outputOids()).containsExactly(1700);
    assertThat(plan.outputTypmods()).containsExactly(NUMERIC_10_2);
  }

  @Test
  public void catalog_ShouldShowTheDeclaredType() throws Exception {
    assertThat(
            parser
                .parse(
                    "SELECT format_type(atttypid, atttypmod) AS t FROM pg_attribute"
                        + " WHERE attrelid = 'm'::regclass AND attnum > 0 ORDER BY attnum")
                .execute(transaction))
        .extracting(r -> r.get("t"))
        .containsExactly("integer", "numeric(10,2)", "numeric(10,2)");
    assertThat(
            parser
                .parse(
                    "SELECT data_type, numeric_precision, numeric_scale"
                        + " FROM information_schema.columns WHERE column_name = 'a'")
                .execute(transaction))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.get("data_type")).isEqualTo("numeric");
              assertThat(r.get("numeric_precision")).isEqualTo(10L);
              assertThat(r.get("numeric_scale")).isEqualTo(2L);
            });
  }

  @Test
  public void floatComparison_ShouldConvertTheColumnToDoubleAsPostgres() {
    ColumnType n10_2 = ColumnType.numeric(10, 2, ColumnType.MAX_SCALED_PRECISION);
    // 0.30 is 0.3 as a double, which 0.1 + 0.2 is not
    assertThat(n10_2.scaledCondition("a", ConditionalExpression.Operator.EQ, 0.1 + 0.2)).isNull();
    assertThat(n10_2.scaledCondition("a", ConditionalExpression.Operator.EQ, 0.3))
        .isEqualTo(ConditionBuilder.column("a").isEqualToBigInt(30));
    assertThat(n10_2.scaledCondition("a", ConditionalExpression.Operator.GT, 0.1 + 0.2))
        .isEqualTo(ConditionBuilder.column("a").isGreaterThanOrEqualToBigInt(31));
    assertThat(n10_2.scaledCondition("a", ConditionalExpression.Operator.LTE, 0.1 + 0.2))
        .isEqualTo(ConditionBuilder.column("a").isLessThanOrEqualToBigInt(30));
    assertThat(n10_2.scaledCondition("a", ConditionalExpression.Operator.LT, 1e300)).isNotNull();
    assertThat(n10_2.scaledCondition("a", ConditionalExpression.Operator.GT, 1e300)).isNull();
    // the in-memory comparison agrees
    assertThat(Evaluator.compare(new BigDecimal("0.30"), 0.1 + 0.2)).isNotZero();
    assertThat(Evaluator.compare(new BigDecimal("0.30"), 0.3)).isZero();
    // beyond 2^53 several stored integers share one double: refused rather than half pushed
    ColumnType n18 = ColumnType.numeric(18, 0, ColumnType.MAX_SCALED_PRECISION);
    assertThat(
            PostgresServer.sqlState(
                failure(() -> n18.scaledCondition("a", ConditionalExpression.Operator.EQ, 1e17))))
        .isEqualTo("0A000");
  }

  @Test
  public void keyedDeleteRebound_ToValuesNoRowHolds_ShouldDeleteNothing() throws Exception {
    QueryParser.Plan plan =
        parser.parse(
            "DELETE FROM m WHERE k = $1 AND p = $2 AND a = $3", Arrays.asList(1L, "1", "19.90"));
    assertThat(plan.isCacheable()).isTrue();
    plan.execute(transaction);
    assertThat(mutations()).hasSize(1);
    executed.clear();
    for (List<Object> values :
        Arrays.asList(
            Arrays.<Object>asList(1L, "1", "1.005"), // a numeric(10,2) never holds 1.005
            Arrays.<Object>asList(1L, "1.005", "19.90"), // nor does its key
            Arrays.<Object>asList(5000000000L, "1", "19.90"))) { // nor an int 5000000000
      plan.rebind(values);
      plan.execute(transaction);
      assertThat(mutations()).as(values.toString()).isEmpty();
      assertThat(plan.commandTag(0)).isEqualTo("DELETE 0");
    }
  }

  @Test
  public void union_ShouldKeepAModifierOnlyWhenEveryBranchHasIt() throws Exception {
    assertThat(parser.parse("SELECT a FROM m UNION ALL SELECT p FROM m").outputTypmods())
        .containsExactly(NUMERIC_10_2);
    assertThat(parser.parse("SELECT a FROM m UNION ALL SELECT 1.5").outputTypmods())
        .containsExactly(-1);
  }

  @Test
  public void caseOverANullNumericColumn_ShouldStillBeNumeric() throws Exception {
    // PostgreSQL types the CASE numeric by its branches, whatever the row holds
    assertThat(
            parser
                .parse(
                    "SELECT ((CASE WHEN a IS NULL THEN 3 ELSE a END) / 2)::text AS h FROM m"
                        + " WHERE k = 2")
                .execute(transaction))
        .extracting(r -> r.get("h"))
        .containsExactly("1.5000000000000000");
  }
}
