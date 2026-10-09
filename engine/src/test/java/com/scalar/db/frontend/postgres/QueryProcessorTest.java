package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class QueryProcessorTest {
  private static final TableMetadata METADATA =
      TableMetadata.newBuilder().addColumn("id", DataType.INT).addPartitionKey("id").build();

  private DistributedTransactionManager manager;
  private DistributedTransaction transaction;
  private QueryProcessor processor;

  @BeforeEach
  public void setUp() throws Exception {
    manager = mock(DistributedTransactionManager.class);
    transaction = mock(DistributedTransaction.class);
    when(manager.begin()).thenReturn(transaction);
    TestScanners.stub(transaction);
    TestScanners.stub(manager);
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata("ns", "t")).thenReturn(METADATA);
    processor = new QueryProcessor(manager, admin, "ns");
  }

  @Test
  public void execute_BeginSelectCommit_ShouldRunOnOneTransaction() throws Exception {
    List<Result> results =
        Collections.singletonList(
            new ResultImpl(Collections.singletonMap("id", IntColumn.of("id", 1)), METADATA));
    when(transaction.scan(any())).thenReturn(results);

    processor.execute("BEGIN");
    assertThat(processor.inTransaction()).isTrue();
    List<Map<String, Object>> actual = processor.execute("SELECT * FROM t;");
    assertThat(processor.lastCommandTag()).isEqualTo("SELECT 1");
    processor.execute("commit");

    assertThat(actual).containsExactly(Collections.singletonMap("id", 1));
    assertThat(processor.inTransaction()).isFalse();
    verify(transaction).scan(Scan.newBuilder().namespace("ns").table("t").all().build());
    verify(transaction).commit();
    verify(manager, never()).scan(any());
    // outside a transaction COMMIT and ROLLBACK only warn, as in PostgreSQL
    processor.execute("COMMIT");
    processor.execute("ROLLBACK");
    assertThat(processor.lastCommandTag()).isEqualTo("ROLLBACK");
    verify(transaction).commit();
    verify(transaction, never()).rollback();
  }

  @Test
  public void execute_WithoutBegin_ShouldAutoCommitOnManager() throws Exception {
    assertThat(processor.execute("DELETE FROM t WHERE id = 1")).isEmpty();
    assertThat(processor.lastCommandTag()).isEqualTo("DELETE 1");

    verify(manager)
        .mutate(
            Collections.singletonList(
                Delete.newBuilder()
                    .namespace("ns")
                    .table("t")
                    .partitionKey(Key.ofInt("id", 1))
                    .condition(ConditionBuilder.deleteIfExists())
                    .build()));
    verify(manager, never()).begin();
  }

  @Test
  public void describe_ShouldReturnOutputColumnsWithoutExecuting() throws Exception {
    assertThat(processor.describe("SELECT id, id + 1 AS next FROM t"))
        .containsExactly(
            java.util.AbstractMap.SimpleEntry.class.cast(
                new java.util.AbstractMap.SimpleEntry<>("id", DataType.INT)),
            new java.util.AbstractMap.SimpleEntry<>("next", null));
    assertThat(processor.describe("SET extra_float_digits = 3")).isEmpty();
    assertThat(processor.execute("SET extra_float_digits = 3")).isEmpty();
    assertThat(processor.lastCommandTag()).isEqualTo("SET");
    verify(manager, never()).scan(any());
  }

  @Test
  public void begin_WithIsolationLevelAndReadOnly_ShouldShapeTheScalarDbTransaction()
      throws Exception {
    // a stronger level than the engine's is refused, a weaker or equal one is reported
    assertThatThrownBy(() -> processor.execute("BEGIN ISOLATION LEVEL SERIALIZABLE"))
        .hasMessageContaining("not available");
    processor.execute("BEGIN ISOLATION LEVEL READ COMMITTED");
    assertThat(processor.execute("SHOW transaction_isolation"))
        .containsExactly(Collections.singletonMap("transaction_isolation", "read committed"));
    verify(manager, never()).begin(); // the ScalarDB transaction starts with the first statement
    processor.execute("COMMIT");
    verify(transaction, never()).commit();

    // SET TRANSACTION READ ONLY before any query makes the transaction read-only
    DistributedTransaction readOnly = mock(DistributedTransaction.class);
    TestScanners.stub(readOnly);
    when(manager.beginReadOnly()).thenReturn(readOnly);
    processor.execute("BEGIN");
    processor.execute("SET TRANSACTION READ ONLY");
    processor.execute("SELECT id FROM t");
    verify(manager).beginReadOnly();
    assertThatThrownBy(() -> processor.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED"))
        .hasMessageContaining("before any query");
    processor.execute("ROLLBACK");
    verify(readOnly).rollback();
    assertThatThrownBy(
            () ->
                processor.execute(
                    "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE"))
        .hasMessageContaining("not available");
  }

  @Test
  public void set_LocalAndTimeZone_ShouldScopeAndValidate() throws Exception {
    processor.execute("BEGIN");
    processor.execute("SET LOCAL application_name = 'inside'");
    assertThat(processor.execute("SHOW application_name"))
        .containsExactly(Collections.singletonMap("application_name", "inside"));
    processor.execute("COMMIT");
    assertThat(processor.execute("SHOW application_name"))
        .containsExactly(Collections.singletonMap("application_name", ""));
    // outside a transaction block SET LOCAL has no effect and warns, as in PostgreSQL
    assertThat(processor.open("SET LOCAL application_name = 'outside'").notices())
        .containsExactly(
            QueryProcessor.WARNING + "SET LOCAL can only be used in transaction blocks");
    assertThat(processor.execute("SHOW application_name"))
        .containsExactly(Collections.singletonMap("application_name", ""));
    assertThat(processor.open("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").notices())
        .containsExactly(
            QueryProcessor.WARNING + "SET TRANSACTION can only be used in transaction blocks");
    assertThatThrownBy(() -> processor.execute("SET TIME ZONE 'Mars/Olympus'"))
        .hasMessageContaining("invalid value for parameter \"TimeZone\"");
    processor.execute("SET TIME ZONE 'Asia/Tokyo'");
    assertThat(processor.execute("SELECT '2024-01-01 00:00:00+00'::timestamptz::text AS t"))
        .containsExactly(Collections.singletonMap("t", "2024-01-01 09:00:00+09"));
    processor.execute("RESET TIME ZONE");
    assertThat(processor.execute("SELECT '2024-01-01 00:00:00+00'::timestamptz::text AS t"))
        .containsExactly(Collections.singletonMap("t", "2024-01-01 00:00:00+00"));
    // the other spellings PostgreSQL accepts: an interval (Sequelize), hours, and a POSIX spec
    // whose sign counts west of UTC
    processor.execute("SET TIME ZONE INTERVAL '+09:00' HOUR TO MINUTE");
    assertThat(processor.execute("SELECT '2024-01-01 00:00:00+00'::timestamptz::text AS t"))
        .containsExactly(Collections.singletonMap("t", "2024-01-01 09:00:00+09"));
    processor.execute("SET TIME ZONE -3.5");
    assertThat(processor.execute("SELECT '2024-01-01 00:00:00+00'::timestamptz::text AS t"))
        .containsExactly(Collections.singletonMap("t", "2023-12-31 20:30:00-03:30"));
    processor.execute("SET TIME ZONE '+05:30'");
    assertThat(processor.execute("SELECT '2024-01-01 00:00:00+00'::timestamptz::text AS t"))
        .containsExactly(Collections.singletonMap("t", "2023-12-31 18:30:00-05:30"));
    processor.execute("SET TIME ZONE 'UTC+9'");
    assertThat(processor.execute("SELECT '2024-01-01 00:00:00+00'::timestamptz::text AS t"))
        .containsExactly(Collections.singletonMap("t", "2023-12-31 15:00:00-09"));
    processor.execute("RESET TIME ZONE");
  }

  @Test
  public void execute_Ddl_ShouldRunOutsideTransactionsOnly() throws Exception {
    assertThat(processor.execute("CREATE TABLE t2 (id int PRIMARY KEY, v text)")).isEmpty();
    assertThat(processor.lastCommandTag()).isEqualTo("CREATE TABLE");
    assertThat(processor.describe("DROP TABLE t2")).isEmpty();

    // inside a transaction the DDL runs at once (ScalarDB DDL is not transactional), as the
    // migration tools that wrap DDL in BEGIN/COMMIT expect
    processor.execute("BEGIN");
    assertThat(processor.execute("DROP TABLE t2")).isEmpty();
    assertThat(processor.lastCommandTag()).isEqualTo("DROP TABLE");
    processor.execute("ROLLBACK");

    // DDL on an existing table is answered only once the engine's metadata cache has expired
    processor.setDdlSettleMillis(30);
    long start = System.nanoTime();
    processor.execute("DROP TABLE t2");
    assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(30);
  }

  @Test
  public void execute_WriteByScanWithoutBegin_ShouldUseOneTransactionAndHonorTheCap()
      throws Exception {
    when(transaction.scan(any()))
        .thenReturn(
            Collections.singletonList(
                new ResultImpl(Collections.singletonMap("id", IntColumn.of("id", 1)), METADATA)));

    assertThat(processor.execute("DELETE FROM t WHERE id > 0")).isEmpty();
    assertThat(processor.lastCommandTag()).isEqualTo("DELETE 1");
    verify(manager).begin();
    verify(transaction)
        .mutate(
            Collections.singletonList(
                Delete.newBuilder()
                    .namespace("ns")
                    .table("t")
                    .partitionKey(Key.ofInt("id", 1))
                    .build()));
    verify(transaction).commit();

    when(transaction.scan(any())).thenReturn(Arrays.asList(idRow(1), idRow(2)));
    processor.execute("SET scalardb.max_rows_per_write = '1'"); // quoted, as psql users write it
    assertThatThrownBy(() -> processor.execute("DELETE FROM t WHERE id > 0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max_rows_per_write");
    verify(transaction).rollback();
  }

  @Test
  public void execute_SessionStatementPaddedWithWhitespace_ShouldNotBacktrack() {
    // SET, SHOW, RESET and SAVEPOINT are matched by patterns that used to backtrack cubically
    String pad = " ".repeat(20_000) + "x";
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          processor.execute("SET application_name = 'a'" + pad);
          assertThatThrownBy(() -> processor.execute("SHOW no_such_setting" + pad))
              .isInstanceOf(IllegalArgumentException.class);
          processor.execute("RESET application_name" + pad);
          processor.execute("BEGIN");
          processor.execute("SAVEPOINT s" + pad);
          processor.execute("ROLLBACK");
        });
  }

  @Test
  public void execute_MaxRowsPerWriteSetting_ShouldBeShownValidatedAndReset() throws Exception {
    when(transaction.scan(any())).thenReturn(Arrays.asList(idRow(1), idRow(2)));
    assertThat(processor.execute("SHOW scalardb.max_rows_per_write"))
        .containsExactly(Collections.singletonMap("scalardb.max_rows_per_write", "10000"));

    for (String bad : new String[] {"-1", "'abc'", "1.5", "99999999999"}) {
      assertThatThrownBy(() -> processor.execute("SET scalardb.max_rows_per_write = " + bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("invalid value for parameter");
    }

    // 0 means no limit
    processor.execute("SET scalardb.max_rows_per_write TO 0");
    processor.execute("DELETE FROM t WHERE id > 0");
    assertThat(processor.lastCommandTag()).isEqualTo("DELETE 2");

    processor.execute("SET scalardb.max_rows_per_write = 5");
    assertThat(processor.execute("SHOW scalardb.max_rows_per_write"))
        .containsExactly(Collections.singletonMap("scalardb.max_rows_per_write", "5"));
    processor.execute("RESET ALL");
    assertThat(processor.execute("SHOW scalardb.max_rows_per_write"))
        .containsExactly(Collections.singletonMap("scalardb.max_rows_per_write", "10000"));
    processor.execute("SET scalardb.max_rows_per_write = 1");
    processor.execute("DISCARD ALL");
    assertThat(processor.execute("SHOW scalardb.max_rows_per_write"))
        .containsExactly(Collections.singletonMap("scalardb.max_rows_per_write", "10000"));
  }

  private static ResultImpl idRow(int id) {
    return new ResultImpl(Collections.singletonMap("id", IntColumn.of("id", id)), METADATA);
  }

  @Test
  public void execute_FailureInsideTransaction_ShouldAbortItUntilRollback() throws Exception {
    processor.execute("BEGIN");
    processor.execute("SELECT id FROM t"); // starts the ScalarDB transaction
    assertThatThrownBy(() -> processor.execute("SELECT nope FROM t"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(processor.isAborted()).isTrue();
    assertThatThrownBy(() -> processor.execute("SELECT * FROM t"))
        .isInstanceOf(QueryProcessor.AbortedTransactionException.class);
    assertThatThrownBy(() -> processor.describe("SELECT * FROM t"))
        .isInstanceOf(QueryProcessor.AbortedTransactionException.class);

    processor.execute("COMMIT");
    assertThat(processor.lastCommandTag()).isEqualTo("ROLLBACK");
    verify(transaction).rollback();
    verify(transaction, never()).commit();
    assertThat(processor.inTransaction()).isFalse();
    assertThat(processor.isAborted()).isFalse();

    // outside a transaction a failure has nothing to abort
    assertThatThrownBy(() -> processor.execute("SELECT nope FROM t"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(processor.isAborted()).isFalse();
  }

  @Test
  public void execute_ExplainAnalyzeOfWrite_ShouldWriteAndReportActualCounts() throws Exception {
    when(transaction.scan(any()))
        .thenReturn(
            Collections.singletonList(
                new ResultImpl(Collections.singletonMap("id", IntColumn.of("id", 1)), METADATA)));

    List<Map<String, Object>> report =
        processor.execute("EXPLAIN ANALYZE DELETE FROM t WHERE id > 0");

    assertThat(report)
        .extracting(r -> (String) r.get("QUERY PLAN"))
        .satisfiesExactly(
            l -> assertThat(l).isEqualTo("Delete ns.t: one ScalarDB Delete per row read"),
            l -> assertThat(l).isEqualTo("  -> Project [id] (actual rows=1)"),
            l ->
                assertThat(l)
                    .isEqualTo(
                        "    -> ScalarDB ScanAll ns.t conditions=[id > 0] projections=[id] AS t"
                            + " (actual rows=1 ScalarDB reads=1)"),
            l -> assertThat(l).matches("Execution Time: \\d+\\.\\d{3} ms"));
    assertThat(processor.lastCommandTag()).isEqualTo("EXPLAIN");
    verify(transaction).mutate(any());
    verify(transaction).commit();
  }

  @Test
  public void execute_InsertReturning_ShouldReturnRowsAndCountThem() throws Exception {
    List<Map<String, Object>> rows =
        processor.execute("INSERT INTO t (id) VALUES (7), (8) RETURNING id, id * 2 AS d");

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("id", 7).containsEntry("d", 14);
    assertThat(processor.lastCommandTag()).isEqualTo("INSERT 0 2");
    verify(transaction).mutate(any());
    verify(transaction).commit();
  }

  @Test
  public void execute_BeginReadOnly_ShouldStartAReadOnlyTransaction() throws Exception {
    DistributedTransaction readOnly = mock(DistributedTransaction.class);
    when(manager.beginReadOnly()).thenReturn(readOnly);
    TestScanners.stub(readOnly);
    when(readOnly.scan(any())).thenReturn(Collections.emptyList());

    processor.execute("START TRANSACTION READ ONLY");
    processor.execute("SELECT * FROM t");
    processor.execute("COMMIT");

    verify(manager).beginReadOnly();
    verify(manager, never()).begin();
    verify(readOnly).commit();
  }

  @Test
  public void open_SameStatementAgain_ShouldReuseThePlanWithNewValues() throws Exception {
    when(transaction.get(any())).thenReturn(java.util.Optional.empty());
    processor.execute("BEGIN");
    try (QueryProcessor.Rows rows =
        processor.open("SELECT id FROM t WHERE id = $1", Collections.singletonList(1L))) {
      assertThat(rows.hasNext()).isFalse();
    }
    try (QueryProcessor.Rows rows =
        processor.open("SELECT id FROM t WHERE id = $1", Collections.singletonList(2L))) {
      assertThat(rows.hasNext()).isFalse();
    }

    assertThat(processor.cachedPlans()).isEqualTo(1);
    verify(transaction)
        .get(
            com.scalar.db.api.Get.newBuilder()
                .namespace("ns")
                .table("t")
                .partitionKey(Key.ofInt("id", 1))
                .projections("id")
                .build());
    verify(transaction)
        .get(
            com.scalar.db.api.Get.newBuilder()
                .namespace("ns")
                .table("t")
                .partitionKey(Key.ofInt("id", 2))
                .projections("id")
                .build());
  }

  @Test
  public void execute_ShowAndSet_ShouldReportSessionSettings() throws Exception {
    assertThat(processor.execute("SHOW search_path"))
        .containsExactly(Collections.singletonMap("search_path", "\"$user\", public"));
    assertThat(processor.execute("SHOW TRANSACTION ISOLATION LEVEL"))
        .containsExactly(Collections.singletonMap("transaction_isolation", "read committed"));
    processor.execute("SET application_name = 'app'");
    assertThat(processor.lastCommandTag()).isEqualTo("SET");
    assertThat(processor.execute("SHOW application_name"))
        .containsExactly(Collections.singletonMap("application_name", "app"));
    processor.execute("SET TIME ZONE 'Asia/Tokyo'");
    assertThat(processor.execute("show timezone"))
        .containsExactly(Collections.singletonMap("TimeZone", "Asia/Tokyo"));
    processor.execute("RESET application_name");
    assertThat(processor.execute("SHOW application_name"))
        .containsExactly(Collections.singletonMap("application_name", ""));
    assertThat(processor.execute("SHOW ALL"))
        .anyMatch(row -> "server_version".equals(row.get("name")));
    assertThat(processor.describe("SHOW server_version")).containsOnlyKeys("server_version");
    assertThatThrownBy(() -> processor.execute("SHOW nosuch"))
        .hasMessageContaining("unrecognized configuration parameter");
  }

  @Test
  public void execute_Savepoints_ShouldBeHonoredWhileNothingWasWritten() throws Exception {
    when(transaction.scan(any())).thenReturn(Collections.emptyList());
    assertThatThrownBy(() -> processor.execute("SAVEPOINT s"))
        .hasMessageContaining("transaction blocks");
    processor.execute("BEGIN");
    processor.execute("SAVEPOINT s");
    processor.execute("SELECT * FROM t");
    assertThatThrownBy(() -> processor.execute("SELECT * FROM nosuchtable"))
        .isInstanceOf(RuntimeException.class); // fails while planning, inside the savepoint
    assertThatThrownBy(() -> processor.execute("SELECT * FROM t"))
        .isInstanceOf(QueryProcessor.AbortedTransactionException.class);
    // only reads since the savepoint: rolling back to it recovers the transaction
    processor.execute("ROLLBACK TO SAVEPOINT s");
    assertThat(processor.execute("SELECT * FROM t")).isEmpty();
    processor.execute("RELEASE SAVEPOINT s");
    processor.execute("SAVEPOINT w");
    processor.execute("DELETE FROM t WHERE id = 1");
    assertThatThrownBy(() -> processor.execute("ROLLBACK TO w"))
        .hasMessageContaining("after writes");
    processor.execute("ROLLBACK");
    assertThat(processor.inTransaction()).isFalse();
  }

  @Test
  public void execute_RollbackToOuterSavepointAfterWrite_ShouldBeRefused() throws Exception {
    processor.execute("BEGIN");
    processor.execute("SAVEPOINT a");
    processor.execute("DELETE FROM t WHERE id = 1");
    processor.execute("SAVEPOINT b");
    // b saw no writes, a did: only b can be rolled back to
    processor.execute("ROLLBACK TO SAVEPOINT b");
    processor.execute("ROLLBACK TO SAVEPOINT b"); // the savepoint stays after a rollback to it
    assertThatThrownBy(() -> processor.execute("ROLLBACK TO SAVEPOINT a"))
        .hasMessageContaining("after writes");
    assertThatThrownBy(() -> processor.execute("ROLLBACK TO SAVEPOINT nosuch"))
        .hasMessageContaining("does not exist");
    processor.execute("ROLLBACK");
  }

  @Test
  public void plan_AfterAnotherSessionsDdl_ShouldReadTheTableAgain() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata("ns", "t")).thenReturn(METADATA);
    when(manager.scan(any())).thenReturn(Collections.emptyList());
    QueryProcessor a = new QueryProcessor(manager, admin, "ns");
    QueryProcessor b = new QueryProcessor(manager, admin, "ns");
    a.execute("SELECT * FROM t");
    a.execute("SELECT * FROM t");
    verify(admin, org.mockito.Mockito.times(1)).getTableMetadata("ns", "t"); // cached
    b.execute("CREATE INDEX t_id ON t (id)"); // DDL in another session
    org.mockito.Mockito.clearInvocations(admin);
    a.execute("SELECT * FROM t"); // a re-reads the table instead of its cached metadata
    verify(admin).getTableMetadata("ns", "t");
  }

  private static Map<String, Object> map(Object... namesAndValues) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      m.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return m;
  }

  @Test
  public void stringFunctions_ShouldCountCharactersAsPostgres() throws Exception {
    assertThat(
            processor.execute(
                "SELECT substring('hello' from 0 for 3) AS a, substring('hello', -1, 3) AS b,"
                    + " left('abc', -1) AS c, right('abc', -1) AS d, strpos('\uD83D\uDE00a', 'a') AS e,"
                    + " left('\uD83D\uDE00ab', 1) AS f"))
        .containsExactly(
            map("a", "he", "b", "h", "c", "ab", "d", "bc", "e", 2L, "f", "\uD83D\uDE00"));
    assertThatThrownBy(() -> processor.execute("SELECT substring('hello', 2, -1)"))
        .hasMessageContaining("negative substring length");
  }

  @Test
  public void regex_ShouldUnderstandPosixClassesAndStopRunawayMatches() throws Exception {
    assertThat(processor.execute("SELECT 'abc' ~ '^[[:alpha:]]+$' AS a, 'a b' ~ '\\yb' AS b"))
        .containsExactly(map("a", true, "b", true));
  }
}
