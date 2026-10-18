package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.ScanWithIndex;
import com.scalar.db.api.Selection;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Columns whose declared type is recorded in the frontend's metadata table: what a table's map
 * makes of the rows, and how DDL keeps the rows in step with the table. Table e stores its text
 * partition key, clustering key and indexed column as varchar(n) columns, whose values pass through
 * unchanged.
 */
public class ColumnMapTest {
  private static final String NS = "ns";

  /** Table e as ScalarDB stores it. */
  private static final TableMetadata STORED =
      TableMetadata.newBuilder()
          .addColumn("k", DataType.TEXT)
          .addColumn("c", DataType.TEXT)
          .addColumn("name", DataType.TEXT)
          .addColumn("n", DataType.INT)
          .addPartitionKey("k")
          .addClusteringKey("c")
          .addSecondaryIndex("name")
          .build();

  private DistributedTransactionAdmin admin;
  private MemoryTypeStore types;
  private DistributedTransaction transaction;
  private QueryParser parser;
  private List<Result> table;
  private List<Operation> executed;

  private static Result row(String k, String c, String name, int n) {
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("k", TextColumn.of("k", k));
    columns.put("c", TextColumn.of("c", c));
    columns.put("name", TextColumn.of("name", name));
    columns.put("n", IntColumn.of("n", n));
    return new ResultImpl(columns, STORED);
  }

  private static Map<String, Object> map(Object... namesAndValues) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      m.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return m;
  }

  private static ColumnType declared(String type) {
    return ColumnType.declared(type, ColumnType.MAX_SCALED_PRECISION);
  }

  @BeforeEach
  public void setUp() throws Exception {
    admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata(NS, "e")).thenReturn(STORED);
    when(admin.tableExists(NS, "e")).thenReturn(true);
    when(admin.getNamespaceNames())
        .thenReturn(new java.util.HashSet<>(Arrays.asList(NS, TypeStore.NAMESPACE)));
    when(admin.getNamespaceTableNames(NS)).thenReturn(Collections.singleton("e"));
    types = new MemoryTypeStore(admin);
    types.seed(NS, "e", "k", declared("varchar(10)"));
    types.seed(NS, "e", "c", declared("varchar(10)"));
    types.seed(NS, "e", "name", declared("varchar(20)"));
    parser = new QueryParser(admin, NS);
    parser.setTypeStore(types.store);
    transaction = mock(DistributedTransaction.class);
    MemoryTable memory = new MemoryTable(STORED, transaction);
    table = memory.rows;
    executed = memory.executed;
    table.addAll(
        Arrays.asList(row("a", "x", "b", 1), row("a", "y", "c", 2), row("z", "x", "b", 3)));
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

  /** Plans and runs a DDL statement. */
  private void ddl(String sql) throws Exception {
    Optional.ofNullable(parser.parse(sql).getDdl()).get().run();
  }

  // ---- the map itself ----

  @Test
  public void map_ShouldCarryTheRecordedTypesUnderTheStoredNames() throws Exception {
    ColumnMap map = ColumnMap.of(NS, "e", STORED, types.store.read(NS, "e", STORED));
    assertThat(map.metadata()).isSameAs(STORED);
    assertThat(map.decodes()).isTrue();
    assertThat(map.type("k").sqlName()).isEqualTo("character varying(10)");
    assertThat(map.type("name").sqlName()).isEqualTo("character varying(20)");
    assertThat(map.type("n").kind).isEqualTo(ColumnType.Kind.NATIVE);
    assertThat(state(() -> map.type("nosuch"))).isEqualTo("42703");
  }

  @Test
  public void tableWithoutRecordedTypes_ShouldBePassedThroughUntouched() {
    TableMetadata plain =
        TableMetadata.newBuilder().addColumn("id", DataType.INT).addPartitionKey("id").build();
    ColumnMap map = ColumnMap.of(NS, "p", plain, Collections.emptyMap());
    assertThat(map.decodes()).isFalse();
    Get get = Get.newBuilder().namespace(NS).table("p").partitionKey(Key.ofInt("id", 1)).build();
    assertThat(map.checked(get)).isSameAs(get);
  }

  @Test
  public void rowsThatDoNotApply_ShouldBeIgnored() throws Exception {
    // a row for a column the table no longer has, and one for another ScalarDB type
    types.seedRaw(NS, "e", "gone", "SMALLINT", "INT", "INT");
    types.seedRaw(NS, "e", "n", "NUMERIC", "SCALED_BIGINT", "BIGINT");
    ColumnMap map = ColumnMap.of(NS, "e", STORED, types.store.read(NS, "e", STORED));
    assertThat(map.type("n").kind).isEqualTo(ColumnType.Kind.NATIVE);
    assertThat(map.type("k").kind).isEqualTo(ColumnType.Kind.VARCHAR);
  }

  @Test
  public void recordedTypesItCannotRead_ShouldBeShownAsStoredAndNeverWritten() throws Exception {
    TableMetadata stored =
        TableMetadata.newBuilder()
            .addColumn("id", DataType.INT)
            .addColumn("note", DataType.TEXT)
            .addColumn("tag", DataType.TEXT)
            .addPartitionKey("id")
            .build();
    types.seedRaw(NS, "d", "note", "NUMERIC", "PACKED_DECIMAL", "TEXT"); // a newer encoding
    types.seed(NS, "d", "tag", declared("varchar(5)"));
    ColumnMap map = ColumnMap.of(NS, "d", stored, types.store.read(NS, "d", stored));
    assertThat(map.type("note").sqlName()).isEqualTo("text");
    Put note =
        Put.newBuilder()
            .namespace(NS)
            .table("d")
            .partitionKey(Key.ofInt("id", 1))
            .textValue("note", "x")
            .build();
    assertThat(state(() -> map.checked(note))).isEqualTo("0A000");
    // its stored value is read as is, not validated against an encoding it does not know
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("id", IntColumn.of("id", 1));
    columns.put("note", TextColumn.of("note", "ab "));
    Result result = new ResultImpl(columns, stored);
    assertThat(map.value("note", result, result.getColumns())).isEqualTo("ab ");
    // a delete writes no value, and is allowed; the readable column is written
    Delete delete =
        Delete.newBuilder().namespace(NS).table("d").partitionKey(Key.ofInt("id", 1)).build();
    assertThat(map.checked(delete)).isSameAs(delete);
    Put tag =
        Put.newBuilder()
            .namespace(NS)
            .table("d")
            .partitionKey(Key.ofInt("id", 1))
            .textValue("tag", "ab")
            .build();
    assertThat(map.checked(tag)).isSameAs(tag);
    // nor can it be renamed: its type could not follow it
    when(admin.getTableMetadata(NS, "d")).thenReturn(stored);
    assertThat(state(() -> ddl("ALTER TABLE d RENAME COLUMN note TO memo"))).isEqualTo("0A000");
  }

  // ---- statements end to end ----

  @Test
  public void select_ByKeyByIndexAndScan_ShouldReturnTheStoredValues() throws Exception {
    assertThat(parser.parse("SELECT * FROM e WHERE k = 'a' AND c = 'x'").execute(transaction))
        .containsExactly(map("k", "a", "c", "x", "name", "b", "n", 1));
    assertThat(parser.parse("SELECT k, n FROM e WHERE name = 'c'").execute(transaction))
        .containsExactly(map("k", "a", "n", 2));
    assertThat(parser.parse("SELECT count(*) AS c FROM e").execute(transaction))
        .containsExactly(map("c", 3L));
    assertThat(executed).anyMatch(op -> op instanceof ScanWithIndex);
  }

  @Test
  public void writes_ShouldBeReadBack() throws Exception {
    write("INSERT INTO e (k, c, name, n) VALUES ('q', 'r', 'w', 5)");
    assertThat(write("UPDATE e SET n = n + 1 WHERE k = 'q' AND c = 'r'")).isEqualTo(1);
    assertThat(parser.parse("SELECT name, n FROM e WHERE k = 'q'").execute(transaction))
        .containsExactly(map("name", "w", "n", 6));
    write(
        "INSERT INTO e (k, c, name, n) VALUES ('q', 'r', 'v', 0)"
            + " ON CONFLICT (k, c) DO UPDATE SET n = e.n + 10");
    assertThat(parser.parse("SELECT name, n FROM e WHERE k = 'q'").execute(transaction))
        .containsExactly(map("name", "w", "n", 16));
    assertThat(write("DELETE FROM e WHERE k = 'q'")).isEqualTo(1);
    assertThat(parser.parse("SELECT count(*) AS c FROM e").execute(transaction))
        .containsExactly(map("c", 3L));
  }

  /**
   * Runs an INSERT, UPDATE or DELETE as a session does; the rows written, -1 for a plain insert.
   */
  private int write(String sql) throws Exception {
    Plan plan = parser.parse(sql);
    if (plan.getWrite() != null) {
      return plan.executeWrite(transaction, 100);
    }
    plan.open(transaction);
    return -1;
  }

  @Test
  public void writes_ShouldFollowTheVarcharWriteRules() throws Exception {
    // spaces beyond 20 are cut, as PostgreSQL does; anything else beyond 20 is 22001
    parser
        .parse("INSERT INTO e (k, c, name, n) VALUES ('q', 'r', 'b" + " ".repeat(30) + "', 1)")
        .open(transaction);
    assertThat(executed)
        .filteredOn(op -> op instanceof Mutation)
        .singleElement()
        .satisfies(
            op ->
                assertThat(((Insert) op).getColumns().get("name").getTextValue())
                    .isEqualTo("b" + " ".repeat(19)));
    assertThat(
            state(
                () ->
                    parser
                        .parse(
                            "INSERT INTO e (k, c, name, n) VALUES ('q', 's', '"
                                + "x".repeat(21)
                                + "', 1)")
                        .open(transaction)))
        .isEqualTo("22001");
    // a predicate is not a write: a longer value is compared as text, and matches nothing
    executed.clear();
    parser
        .parse("SELECT n FROM e WHERE k = 'a' AND name = '" + "x".repeat(21) + "'")
        .execute(transaction);
    assertThat(executed)
        .singleElement()
        .satisfies(
            op ->
                assertThat(((Selection) op).getConjunctions().iterator().next().getConditions())
                    .containsExactly(
                        ConditionBuilder.column("name").isEqualToText("x".repeat(21))));
  }

  @Test
  public void catalog_ShouldShowDeclaredTypesAndHideTheMetadataNamespace() throws Exception {
    assertThat(
            parser
                .parse(
                    "SELECT column_name, data_type, character_maximum_length"
                        + " FROM information_schema.columns"
                        + " WHERE table_name = 'e' ORDER BY ordinal_position")
                .execute(transaction))
        .containsExactly(
            map(
                "column_name",
                "k",
                "data_type",
                "character varying",
                "character_maximum_length",
                10L),
            map(
                "column_name",
                "c",
                "data_type",
                "character varying",
                "character_maximum_length",
                10L),
            map(
                "column_name",
                "name",
                "data_type",
                "character varying",
                "character_maximum_length",
                20L),
            map("column_name", "n", "data_type", "integer", "character_maximum_length", null));
    assertThat(
            parser
                .parse("SELECT schema_name FROM information_schema.schemata")
                .execute(transaction))
        .extracting(r -> String.valueOf(r.get("schema_name")))
        .doesNotContain(TypeStore.NAMESPACE);
  }

  // ---- DDL ----

  @Test
  public void createTable_ShouldRecordTypesFirstAndReplaceLeftoverRows() throws Exception {
    types.seedRaw(NS, "f", "old", "SMALLINT", "INT", "INT"); // an earlier table f's
    List<String> atCreate = new ArrayList<>();
    doAnswer(
            i -> {
              atCreate.addAll(types.describe());
              return null;
            })
        .when(admin)
        .createTable(eq(NS), eq("f"), any(), anyBoolean());
    ddl("CREATE TABLE f (id INT PRIMARY KEY, amount NUMERIC(10,2), code CHAR(3), note TEXT)");
    List<String> rows =
        Arrays.asList(
            "ns.f/amount NUMERIC 10 2 - SCALED_BIGINT BIGINT",
            "ns.f/code CHAR - - 3 TRIMMED_TEXT TEXT");
    assertThat(atCreate).containsAll(rows).noneMatch(r -> r.startsWith("ns.f/old"));
    assertThat(types.describe()).containsAll(rows);
    org.mockito.ArgumentCaptor<TableMetadata> created =
        org.mockito.ArgumentCaptor.forClass(TableMetadata.class);
    verify(admin).createTable(eq(NS), eq("f"), created.capture(), eq(false));
    assertThat(created.getValue().getColumnNames()).containsExactly("id", "amount", "code", "note");
    assertThat(created.getValue().getColumnDataType("amount")).isEqualTo(DataType.BIGINT);
  }

  @Test
  public void createTable_WhenItFails_ShouldTakeBackItsRows() throws Exception {
    doThrow(new ExecutionException("no CREATE privilege"))
        .when(admin)
        .createTable(eq(NS), eq("f"), any(), anyBoolean());
    assertThat(state(() -> ddl("CREATE TABLE f (id INT PRIMARY KEY, amount NUMERIC(10,2))")))
        .isNotNull();
    assertThat(types.describe()).noneMatch(r -> r.startsWith("ns.f/"));
  }

  @Test
  public void createTableIfNotExists_LosingARace_ShouldPutBackTheWinnersRows() throws Exception {
    // another session created f with amount numeric(12,4) between the check and the create
    types.seed(NS, "f", "amount", declared("numeric(12,4)"));
    when(admin.tableExists(NS, "f")).thenReturn(false, true);
    doThrow(new ExecutionException("already exists"))
        .when(admin)
        .createTable(eq(NS), eq("f"), any(), anyBoolean());
    ddl("CREATE TABLE IF NOT EXISTS f (id INT PRIMARY KEY, amount NUMERIC(10,2))");
    assertThat(types.describe())
        .filteredOn(r -> r.startsWith("ns.f/"))
        .containsExactly("ns.f/amount NUMERIC 12 4 - SCALED_BIGINT BIGINT");
  }

  @Test
  public void createTable_OverAnExistingTable_ShouldLeaveItsRowsAlone() throws Exception {
    ddl("CREATE TABLE IF NOT EXISTS e (k INT PRIMARY KEY, amount NUMERIC(10,2))");
    verify(admin).createTable(eq(NS), eq("e"), any(), eq(true));
    assertThat(types.describe()).noneMatch(r -> r.startsWith("ns.e/amount"));
  }

  @Test
  public void declaredTypes_WithoutTheMetadataTable_ShouldFailWith55000() throws Exception {
    DistributedTransactionAdmin bare = mock(DistributedTransactionAdmin.class);
    QueryParser p = new QueryParser(bare, NS); // TypeStore.NONE
    Optional.ofNullable(p.parse("CREATE TABLE t (id INT PRIMARY KEY, s TEXT)").getDdl())
        .get()
        .run();
    verify(bare).createTable(eq(NS), eq("t"), any(), eq(false));
    for (String type :
        new String[] {"NUMERIC(10,2)", "DECIMAL", "VARCHAR(5)", "CHAR(2)", "SMALLINT"}) {
      assertThat(
              state(
                  () ->
                      Optional.ofNullable(
                              p.parse("CREATE TABLE u (id INT PRIMARY KEY, v " + type + ")")
                                  .getDdl())
                          .get()
                          .run()))
          .as(type)
          .isEqualTo("55000");
    }
  }

  @Test
  public void addColumn_ShouldRecordItsTypeAndTakeItBackOnFailure() throws Exception {
    ddl("ALTER TABLE e ADD COLUMN amount NUMERIC(10,2)");
    verify(admin).addNewColumnToTable(NS, "e", "amount", DataType.BIGINT);
    assertThat(types.describe()).contains("ns.e/amount NUMERIC 10 2 - SCALED_BIGINT BIGINT");
    doThrow(new ExecutionException("refused"))
        .when(admin)
        .addNewColumnToTable(NS, "e", "qty", DataType.INT);
    assertThat(state(() -> ddl("ALTER TABLE e ADD COLUMN qty SMALLINT"))).isNotNull();
    assertThat(types.describe()).noneMatch(r -> r.startsWith("ns.e/qty"));
  }

  @Test
  public void renameColumn_ShouldMoveTheRowWithTheColumn() throws Exception {
    ddl("ALTER TABLE e RENAME COLUMN name TO nm");
    verify(admin).renameColumn(NS, "e", "name", "nm");
    assertThat(types.describe())
        .contains("ns.e/nm VARCHAR - - 20 TEXT TEXT")
        .noneMatch(r -> r.startsWith("ns.e/name "));
    // a column without a recorded type keeps none, and a leftover row under its new name goes
    types.seedRaw(NS, "e", "m", "SMALLINT", "INT", "INT");
    ddl("ALTER TABLE e RENAME COLUMN n TO m");
    verify(admin).renameColumn(NS, "e", "n", "m");
    assertThat(types.describe()).noneMatch(r -> r.startsWith("ns.e/m "));
  }

  @Test
  public void renameColumn_NamesTakenOrMissing_ShouldFailAsPostgres() throws Exception {
    assertThat(state(() -> ddl("ALTER TABLE e RENAME COLUMN name TO k"))).isEqualTo("42701");
    assertThat(state(() -> ddl("ALTER TABLE e RENAME COLUMN name TO name"))).isEqualTo("42701");
    assertThat(state(() -> ddl("ALTER TABLE e RENAME COLUMN nosuch TO k"))).isEqualTo("42703");
    assertThat(state(() -> ddl("ALTER TABLE e ADD COLUMN Name INT"))).isEqualTo("42701");
  }

  @Test
  public void dropTable_ShouldDeleteItsRowsAfterTheTable() throws Exception {
    List<String> atDrop = new ArrayList<>();
    doAnswer(
            i -> {
              atDrop.addAll(types.describe());
              return null;
            })
        .when(admin)
        .dropTable(NS, "e", false);
    ddl("DROP TABLE e");
    assertThat(atDrop).anyMatch(r -> r.startsWith("ns.e/"));
    assertThat(types.describe()).noneMatch(r -> r.startsWith("ns.e/"));
  }

  @Test
  public void dropTable_ShouldKeepRowsAnotherSessionWroteMeanwhile() throws Exception {
    // between the drop and the cleanup, another session creates e again with its own types
    doAnswer(
            i -> {
              types.seed(NS, "e", "name", declared("numeric(10,2)"), "other");
              return null;
            })
        .when(admin)
        .dropTable(NS, "e", false);
    ddl("DROP TABLE e");
    assertThat(types.describe()).containsExactly("ns.e/name NUMERIC 10 2 - SCALED_BIGINT BIGINT");
  }

  @Test
  public void renameColumn_ShouldKeepARowWrittenUnderTheOldNameMeanwhile() throws Exception {
    doAnswer(
            i -> {
              types.seed(NS, "e", "name", declared("smallint"), "other"); // ADD COLUMN name
              return null;
            })
        .when(admin)
        .renameColumn(NS, "e", "name", "nm");
    ddl("ALTER TABLE e RENAME COLUMN name TO nm");
    assertThat(types.describe())
        .contains("ns.e/nm VARCHAR - - 20 TEXT TEXT", "ns.e/name SMALLINT - - - INT INT");
  }

  @Test
  public void renameColumn_OnlyTheCase_ShouldMoveTheRow() throws Exception {
    ddl("ALTER TABLE e RENAME COLUMN name TO \"Name\"");
    verify(admin).renameColumn(NS, "e", "name", "Name");
    assertThat(types.describe())
        .contains("ns.e/Name VARCHAR - - 20 TEXT TEXT")
        .noneMatch(r -> r.startsWith("ns.e/name "));
  }

  @Test
  public void dropSchemaCascade_ShouldDeleteTheRowsOfEachTable() throws Exception {
    when(admin.namespaceExists(NS)).thenReturn(true);
    ddl("DROP SCHEMA ns CASCADE");
    verify(admin).dropTable(NS, "e", true);
    assertThat(types.describe()).noneMatch(r -> r.startsWith("ns.e/"));
  }

  @Test
  public void cleanupFailure_AfterTheDdlTookEffect_ShouldNotFailTheStatement() throws Exception {
    doAnswer(
            i -> {
              types.failing = true;
              return null;
            })
        .when(admin)
        .dropTable(NS, "e", false);
    ddl("DROP TABLE e"); // no exception: the table is gone, only inert rows are left
    verify(admin).dropTable(NS, "e", false);
  }

  @Test
  public void undoFailure_ShouldReportTheDdlsOwnError() throws Exception {
    doAnswer(
            i -> {
              types.failing = true;
              throw new ExecutionException("no CREATE privilege");
            })
        .when(admin)
        .createTable(eq(NS), eq("f"), any(), anyBoolean());
    try {
      ddl("CREATE TABLE f (id INT PRIMARY KEY, amount NUMERIC(10,2))");
      throw new AssertionError("expected a failure");
    } catch (ExecutionException e) {
      assertThat(e).hasMessage("no CREATE privilege");
      assertThat(e.getSuppressed()).hasSize(1);
    }
  }

  @Test
  public void createIndex_ShouldIndexTheColumnByItsName() throws Exception {
    ddl("CREATE INDEX i ON e (k)");
    verify(admin).createIndex(NS, "e", "k", false);
  }

  @Test
  public void setupCommand_ShouldCreateTheTableAndReportOnlyTheTag() throws Exception {
    Plan plan = parser.parse("create frontend metadata tables if not exists");
    assertThat(plan.getOutputColumns()).isEmpty(); // no authentication: no properties to print
    Optional.ofNullable(plan.getDdl()).get().run();
    verify(admin).createTable(TypeStore.NAMESPACE, TypeStore.TABLE, TypeStore.METADATA, true);
    assertThat(plan.commandTag(0)).isEqualTo("CREATE FRONTEND METADATA TABLES");
  }

  @Test
  public void metadataNamespace_ShouldBeRefusedToUserDdl() {
    String ns = TypeStore.NAMESPACE;
    for (String sql :
        new String[] {
          "CREATE TABLE " + ns + ".t (id INT PRIMARY KEY)",
          "DROP TABLE " + ns + "." + TypeStore.TABLE,
          "ALTER TABLE " + ns + "." + TypeStore.TABLE + " ADD COLUMN x INT",
          "CREATE SCHEMA " + ns,
          "DROP SCHEMA " + ns + " CASCADE",
          "INSERT INTO " + ns + "." + TypeStore.TABLE + " (full_table_name) VALUES ('x')",
          "DELETE FROM " + ns + "." + TypeStore.TABLE,
        }) {
      assertThat(state(() -> parser.parse(sql))).as(sql).isEqualTo("42501");
    }
  }

  @Test
  public void dottedTableOrSchemaNames_ShouldBeRefused() {
    // "a.b".c and a."b.c" would be the same namespace.table in the metadata tables
    assertThat(state(() -> parser.parse("CREATE TABLE ns.\"b.c\" (id INT PRIMARY KEY)")))
        .isEqualTo("42602");
    assertThat(state(() -> parser.parse("CREATE SCHEMA \"a.b\""))).isNotNull();
  }
}
