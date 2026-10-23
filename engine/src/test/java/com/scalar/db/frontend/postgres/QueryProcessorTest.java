package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.exception.transaction.CommitConflictException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
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
    when(manager.beginReadOnly()).thenReturn(transaction); // an auto-commit read
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
    verify(transaction)
        .scan(Scan.newBuilder().namespace("ns").table("t").all().projections("id").build());
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
  public void autoCommitWrite_CommitFailed_ShouldRollBackOnlyWhenTheOutcomeIsKnown()
      throws Exception {
    // a write that reads first runs in a transaction of its own (a key alone is a one-shot mutate)
    doThrow(new UnknownTransactionStatusException("unknown", "tx1")).when(transaction).commit();
    assertThatThrownBy(() -> processor.execute("DELETE FROM t WHERE id > 0"))
        .isInstanceOf(UnknownTransactionStatusException.class);
    verify(transaction, never()).rollback(); // the commit may have landed

    doThrow(new CommitConflictException("conflict", "tx2")).when(transaction).commit();
    assertThatThrownBy(() -> processor.execute("DELETE FROM t WHERE id > 0"))
        .isInstanceOf(CommitConflictException.class);
    verify(transaction).rollback();
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

  private static Result row(int id) {
    return new ResultImpl(Collections.singletonMap("id", IntColumn.of("id", id)), METADATA);
  }

  @Test
  public void autoCommitRead_ShouldRunInOneTransactionOfItsOwn() throws Exception {
    // every read of a statement outside BEGIN, a join's lookups included, in one transaction, so
    // the isolation level covers the statement as a whole
    when(transaction.scan(any())).thenReturn(Arrays.asList(row(1), row(2)));
    when(transaction.get(any()))
        .thenAnswer(
            invocation ->
                java.util.Optional.of(
                    row(((Get) invocation.getArgument(0)).getPartitionKey().getIntValue(0))));
    assertThat(processor.execute("SELECT a.id FROM t a JOIN t b ON b.id = a.id")).hasSize(2);
    verify(manager).beginReadOnly();
    verify(transaction).commit();
    verify(manager, never()).scan(any());
    verify(manager, never()).get(any());

    // a statement that reads no table begins no transaction
    processor.execute("SELECT 1");
    verify(manager).beginReadOnly();

    // a failed commit (SERIALIZABLE's validation) fails the statement
    doThrow(new CommitConflictException("conflict", "tx")).when(transaction).commit();
    assertThatThrownBy(() -> processor.execute("SELECT id FROM t"))
        .isInstanceOf(CommitConflictException.class);

    // rows the client stops reading early roll the transaction back
    clearInvocations(transaction);
    processor.open("SELECT id FROM t").close();
    verify(transaction).rollback();
    verify(transaction, never()).commit();
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
    // as in PostgreSQL, a SET after SET LOCAL outlasts the transaction, and ROLLBACK undoes SET
    processor.execute("BEGIN");
    processor.execute("SET LOCAL application_name = 'local'");
    processor.execute("SET application_name = 'kept'");
    processor.execute("COMMIT");
    assertThat(processor.execute("SHOW application_name"))
        .containsExactly(Collections.singletonMap("application_name", "kept"));
    processor.execute("BEGIN");
    processor.execute("SET application_name = 'undone'");
    processor.execute("ROLLBACK");
    assertThat(processor.execute("SHOW application_name"))
        .containsExactly(Collections.singletonMap("application_name", "kept"));
    processor.execute("RESET application_name");
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
    // real is single precision: a cast rounds to it, an unknown literal next to it is real, and
    // real op real stays real
    String[][] reals = {
      {"4567890123456789::int8::float4", "4.56789e+15"},
      {"'7.8'::real * '-10'", "-78"},
      {"'0.1'::real + '0.2'::real", "0.3"},
      {"-'0.1'::real", "-0.1"},
      {"'7.8'::real * 2", "15.600000381469727"},
      {"'324.78'::real > '324.78'", "false"}
    };
    for (String[] r : reals) {
      assertThat(processor.execute("SELECT (" + r[0] + ")::text AS v"))
          .containsExactly(Collections.singletonMap("v", r[1]));
    }
    // numeric ^ and sqrt are exact in PostgreSQL: refused rather than answered in double precision
    for (String sql :
        Arrays.asList("SELECT 1.2 ^ 345", "SELECT power(2.0, 3)", "SELECT sqrt(2.0)")) {
      assertThatThrownBy(() -> processor.execute(sql))
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo("0A000"));
    }
    assertThat(processor.execute("SELECT (2 ^ 10)::text AS v"))
        .containsExactly(Collections.singletonMap("v", "1024"));
    assertThatThrownBy(() -> processor.execute("SELECT '2147483647'::float4::int4"))
        .hasMessageContaining("integer out of range"); // 2147483648 as a real
    // a time with an offset is refused, not read as a timestamptz
    for (String sql :
        Arrays.asList(
            "SELECT TIME WITH TIME ZONE '2020-05-26 13:30:25-04'",
            "SELECT '13:30:25-04'::timetz")) {
      assertThatThrownBy(() -> processor.execute(sql))
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo("0A000"));
    }
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

    // a cache too long to wait out is announced instead
    processor.setDdlSettleMillis(0);
    processor.setDdlNotice("cached");
    assertThat(processor.open("DROP TABLE t2").notices()).containsExactly("cached");
    assertThat(processor.open("CREATE TABLE t3 (k INT PRIMARY KEY)").notices()).isEmpty();
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
  public void execute_DataModifyingWith_ShouldWriteFirstInTheSameTransaction() throws Exception {
    List<Map<String, Object>> rows =
        processor.execute(
            "WITH i AS (INSERT INTO t (id) VALUES (7) RETURNING id) SELECT id * 2 AS d FROM i");

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("d", 14);
    verify(transaction).mutate(any());
    verify(transaction).commit();
    // the modified table read elsewhere would show PostgreSQL's snapshot, not the write: refused
    assertThatThrownBy(
            () ->
                processor.execute(
                    "WITH i AS (INSERT INTO t (id) VALUES (8) RETURNING id) SELECT * FROM t"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("0A000"));
    verify(transaction).mutate(any()); // still only the first statement's write
    // a read-only transaction refuses a write whatever the statement's first word
    processor.execute("START TRANSACTION READ ONLY");
    assertThatThrownBy(
            () ->
                processor.execute(
                    "WITH i AS (INSERT INTO t (id) VALUES (9) RETURNING id) SELECT id FROM i"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("25006"));
    processor.execute("ROLLBACK");
    processor.execute("START TRANSACTION READ ONLY");
    assertThatThrownBy(
            () ->
                processor.execute("WITH s AS (SELECT 9 AS id) INSERT INTO t (id) SELECT id FROM s"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("25006"));
    processor.execute("ROLLBACK");
  }

  @Test
  public void execute_RollbackToSavepoint_ShouldUndoSettingsAndCharacteristics() throws Exception {
    processor.execute("BEGIN");
    processor.execute("SAVEPOINT x");
    processor.execute("SET TRANSACTION READ ONLY");
    processor.execute("SET search_path = nosuch");
    processor.execute("SET timezone = '04:30'"); // POSIX, unsigned: west of UTC
    assertThat(processor.execute("SHOW timezone").get(0).values()).containsExactly("04:30");
    processor.execute("ROLLBACK TO SAVEPOINT x");
    assertThat(processor.execute("SHOW transaction_read_only").get(0).values())
        .containsExactly("off");
    assertThat(processor.execute("SHOW search_path").get(0).values())
        .containsExactly("\"$user\", public");
    processor.execute("ROLLBACK");
    assertThat(QueryProcessor.zoneOf("04:30"))
        .isEqualTo(java.time.ZoneOffset.ofHoursMinutes(-4, -30));
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
    assertThat(processor.execute("SHOW ALL"))
        .noneMatch(row -> "is_superuser".equals(row.get("name")));
    // pg_settings, SHOW and current_setting read one table, with the session's values
    processor.setClientDefault("DateStyle", "ISO, DMY");
    processor.execute("SET statement_timeout = '5s'"); // pg_settings has it in its unit, ms
    assertThat(processor.execute("SHOW statement_timeout"))
        .containsExactly(Collections.singletonMap("statement_timeout", "5s"));
    assertThat(
            processor.execute(
                "SELECT name, setting, source, reset_val FROM pg_settings WHERE name IN"
                    + " ('DateStyle', 'statement_timeout', 'TimeZone', 'server_version_num')"))
        .containsExactlyInAnyOrder(
            Map.of(
                "name",
                "DateStyle",
                "setting",
                "ISO, DMY",
                "source",
                "client",
                "reset_val",
                "ISO, DMY"),
            Map.of(
                "name",
                "server_version_num",
                "setting",
                "160000",
                "source",
                "default",
                "reset_val",
                "160000"),
            Map.of(
                "name",
                "statement_timeout",
                "setting",
                "5000",
                "source",
                "session",
                "reset_val",
                "0"),
            Map.of(
                "name",
                "TimeZone",
                "setting",
                "Asia/Tokyo",
                "source",
                "session",
                "reset_val",
                "UTC"));
    // pgAdmin reads the function behind the pg_settings view
    assertThat(
            processor.execute(
                "SELECT s.setting FROM pg_catalog.pg_show_all_settings() s"
                    + " WHERE s.name = 'statement_timeout'"))
        .containsExactly(Collections.singletonMap("setting", "5000"));
    assertThat(processor.execute("SELECT current_setting('server_version_num') AS v"))
        .containsExactly(Collections.singletonMap("v", "160000"));
    for (String sql :
        new String[] {
          "SET server_version = '1'", "SET server_version TO DEFAULT", "RESET server_version"
        }) {
      assertThatThrownBy(() -> processor.execute(sql))
          .hasMessageContaining("parameter \"server_version\" cannot be changed");
    }
    assertThatThrownBy(() -> processor.execute("SHOW nosuch"))
        .hasMessageContaining("unrecognized configuration parameter");
    // COMMIT AND CHAIN begins the next block with the same characteristics; outside one, 25P01
    processor.execute("BEGIN READ ONLY");
    processor.execute("COMMIT AND CHAIN");
    assertThat(processor.inTransaction()).isTrue();
    assertThatThrownBy(() -> processor.execute("DELETE FROM t WHERE id = 1"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("25006"));
    processor.execute("ROLLBACK");
    assertThatThrownBy(() -> processor.execute("COMMIT AND CHAIN"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("25P01"));
    // a read-only default makes a single statement's transaction read-only too: no write, no DDL
    processor.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
    for (String sql : new String[] {"DELETE FROM t WHERE id = 1", "DROP TABLE t", "TRUNCATE t"}) {
      assertThatThrownBy(() -> processor.execute(sql))
          .as(sql)
          .isInstanceOfSatisfying(
              PgException.class, e -> assertThat(e.sqlState()).isEqualTo("25006"));
    }
    processor.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
    // DateStyle is stored as PostgreSQL stores it, which clients parse: style and order
    processor.execute("SET DateStyle = ymd");
    assertThat(processor.execute("SHOW DateStyle"))
        .containsExactly(Collections.singletonMap("DateStyle", "ISO, YMD"));
    processor.execute("SET DateStyle = ISO");
    assertThat(processor.execute("SHOW DateStyle"))
        .containsExactly(Collections.singletonMap("DateStyle", "ISO, YMD"));
    assertThatThrownBy(() -> processor.execute("SET DateStyle = German"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("0A000"));
    assertThatThrownBy(() -> processor.execute("SET DateStyle = 'ISO, YMD, DMY'"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22023"));
    // IntervalStyle: one of PostgreSQL's four, and sql_standard's leading minus negates every field
    assertThatThrownBy(() -> processor.execute("SET IntervalStyle = bogus"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22023"));
    processor.execute("SET IntervalStyle = SQL_Standard");
    assertThat(processor.execute("SELECT interval '-1 2:03:04' AS i, interval '-1 +2:03:04' AS j"))
        .extracting(row -> row.get("i").toString(), row -> row.get("j").toString())
        .containsExactly(tuple("-1 days -02:03:04", "-1 days +02:03:04"));
    assertThat(processor.execute("SHOW IntervalStyle"))
        .containsExactly(Collections.singletonMap("IntervalStyle", "sql_standard"));
    processor.execute("RESET IntervalStyle");
    processor.execute("SET bytea_output = escape");
    assertThat(processor.execute("SELECT E'\\\\xDeAd5c41'::bytea::text AS b"))
        .containsExactly(Collections.singletonMap("b", "\\336\\255\\\\A"));
    assertThatThrownBy(() -> processor.execute("SET bytea_output = iso_8601"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22023"));
    processor.execute("RESET bytea_output");
    // extra_float_digits <= 0 writes DBL_DIG (FLT_DIG) plus it digits, as printf's %g
    processor.execute("SET extra_float_digits = 0");
    assertThat(
            processor.execute(
                "SELECT (1 / 3::float8)::text AS d, (2::float4 / 3::float4)::text AS r"))
        .containsExactly(map2("d", "0.333333333333333", "r", "0.666667"));
    processor.execute("RESET extra_float_digits");
    assertThatThrownBy(() -> processor.execute("SET extra_float_digits = 4"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("22023"));
    // the transaction's own settings: shown as they stand, and not reset
    processor.execute("BEGIN READ ONLY DEFERRABLE");
    assertThat(processor.execute("SHOW transaction_read_only"))
        .containsExactly(Collections.singletonMap("transaction_read_only", "on"));
    assertThat(processor.execute("SHOW transaction_deferrable"))
        .containsExactly(Collections.singletonMap("transaction_deferrable", "on"));
    processor.execute("ROLLBACK");
    assertThat(processor.execute("SHOW transaction_read_only"))
        .containsExactly(Collections.singletonMap("transaction_read_only", "off"));
    assertThatThrownBy(() -> processor.execute("RESET transaction_isolation"))
        .isInstanceOfSatisfying(
            PgException.class, e -> assertThat(e.sqlState()).isEqualTo("0A000"));
    // as PostgreSQL: a name it does not know fails, unless it is a custom dotted one
    for (String sql :
        new String[] {
          "SET nosuch = 1",
          "SET nosuch TO DEFAULT",
          "RESET nosuch",
          "SELECT set_config('nosuch', '1', false)"
        }) {
      assertThatThrownBy(() -> processor.execute(sql))
          .hasMessageContaining("unrecognized configuration parameter \"nosuch\"");
    }
    assertThatThrownBy(() -> processor.setClientDefault("nosuch", "1"))
        .hasMessageContaining("unrecognized configuration parameter \"nosuch\"");
    processor.execute("SET myapp.x = 1");
    processor.setClientDefault("extra_float_digits", "3");
    processor.execute("SET lock_timeout = 5000");
    assertThat(processor.execute("SHOW lock_timeout"))
        .containsExactly(Collections.singletonMap("lock_timeout", "5s"));
    assertThat(processor.execute("SHOW myapp.x"))
        .containsExactly(Collections.singletonMap("myapp.x", "1"));
    // SHOW ALL lists what pg_settings does, in its order, and no custom setting
    assertThat(processor.execute("SHOW ALL"))
        .extracting(row -> row.get("name"))
        .containsExactlyElementsOf(
            processor.execute("SELECT name FROM pg_settings").stream()
                .map(row -> row.get("name"))
                .collect(java.util.stream.Collectors.toList()));
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
    when(transaction.scan(any())).thenReturn(Collections.emptyList());
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

  @Test
  public void plan_AfterAnotherProcessesDdl_ShouldSeeTheNewColumns() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    TableMetadata renamed =
        TableMetadata.newBuilder().addColumn("key", DataType.INT).addPartitionKey("key").build();
    when(admin.getTableMetadata("ns", "t")).thenReturn(METADATA);
    when(transaction.scan(any())).thenReturn(Collections.emptyList());
    QueryProcessor a = new QueryProcessor(manager, admin, "ns");
    a.execute("SELECT id FROM t");
    when(admin.getTableMetadata("ns", "t")).thenReturn(renamed); // RENAME COLUMN in another process
    Thread.sleep(1100); // the session checks once a second
    a.execute("SELECT 1"); // the check
    assertThatThrownBy(() -> a.execute("SELECT id FROM t"))
        .hasMessageContaining("Unknown column: t.id");
    a.execute("SELECT key FROM t");

    // the sessions of a process share one read per table and interval
    QueryProcessor b = new QueryProcessor(manager, admin, "ns");
    a.execute("SELECT key FROM t");
    b.execute("SELECT key FROM t");
    Thread.sleep(1100);
    clearInvocations(admin);
    a.execute("SELECT 1");
    b.execute("SELECT 1");
    verify(admin, times(1)).getTableMetadata("ns", "t");
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

  @Test
  public void pgAdminQueries_ShouldBeAnswered() throws Exception {
    // a parenthesized join on the right of a join: its tables' columns, qualified or not; twice,
    // as the second run plans the cached statement again
    for (int run = 0; run < 2; run++) {
      assertThat(
              processor.execute(
                  "SELECT x.relname, c.relkind, nspname FROM pg_class x LEFT JOIN"
                      + " (pg_namespace n JOIN pg_class c ON c.relnamespace = n.oid)"
                      + " ON x.oid = c.oid WHERE x.relname = 'pg_class'"))
          .containsExactly(map("relname", "pg_class", "relkind", "r", "nspname", "pg_catalog"));
      // a whole-row reference, as the dashboard's charts, polled, write row_to_json(t)
      assertThat(
              processor
                  .execute("SELECT row_to_json(t) AS j FROM (SELECT 1 AS a, 'x' AS b) t")
                  .stream()
                  .map(row -> String.valueOf(row.get("j"))))
          .containsExactly("{\"a\":1,\"b\":\"x\"}");
    }
    assertThatThrownBy(
            () ->
                processor.execute(
                    "SELECT oid FROM pg_class x JOIN"
                        + " (pg_namespace n JOIN pg_class c ON c.relnamespace = n.oid) ON true"))
        .hasMessageContaining("Ambiguous column: oid");
    // SELECT * over a subquery whose join gives two columns one name
    assertThat(
            processor.execute(
                "SELECT * FROM (SELECT * FROM pg_namespace n JOIN pg_class c"
                    + " ON c.relnamespace = n.oid) j WHERE j.relname = 'pg_class'"))
        .hasSize(1);
    // = ANY ((SELECT ...)::oid[]), the primary key lookup of View Data
    assertThat(processor.execute("SELECT 1 AS one WHERE 1 = ANY ((SELECT '{1,2}'::int[])::int[])"))
        .containsExactly(Collections.singletonMap("one", 1L));
    assertThat(QueryParser.castsInAny("SELECT 'ANY ((x)::t)' WHERE a = ANY ((SELECT b)::oid[])"))
        .isEqualTo("SELECT 'ANY ((x)::t)' WHERE a = ANY (CAST((SELECT b) AS oid[]))");
    // name truncates to 63 bytes, at a character boundary
    assertThat(processor.execute("SELECT repeat('\u00e9', 40)::name AS n"))
        .containsExactly(Collections.singletonMap("n", "\u00e9".repeat(31)));
    // in one pass: a long text is cut as fast as a short one
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThat(processor.execute("SELECT repeat('\uD83D\uDE00', 1000000)::name AS n"))
                .containsExactly(Collections.singletonMap("n", "\uD83D\uDE00".repeat(15))));
    assertThat(
            processor.execute(
                "SELECT has_schema_privilege('pg_catalog', 'USAGE') AS u,"
                    + " has_schema_privilege(11, 'CREATE') AS c, has_schema_privilege(1, 'USAGE') AS n"))
        .containsExactly(map("u", true, "c", true, "n", null));
    // aclexplode: one record per privilege, PUBLIC as grantee 0, read as (d).field
    assertThat(
            processor.execute(
                "SELECT (d).grantee, (d).privilege_type, (d).is_grantable FROM (SELECT"
                    + " aclexplode('{=r/scalardb,scalardb=a*/scalardb}'::aclitem[]) AS d) a"))
        .containsExactly(
            map("grantee", 0L, "privilege_type", "SELECT", "is_grantable", false),
            map("grantee", 10L, "privilege_type", "INSERT", "is_grantable", true));
  }

  private static Map<String, Object> map2(String k1, Object v1, String k2, Object v2) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }
}
