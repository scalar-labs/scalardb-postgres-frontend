package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.GetWithIndex;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.ScanAll;
import com.scalar.db.api.ScanWithIndex;
import com.scalar.db.api.Selection;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.api.Upsert;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.DoubleColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Columns whose declared type is recorded in their ScalarDB name: every operation that reaches
 * ScalarDB carries storage names, and everything a client sees carries client names. The table
 * stores its text partition key, clustering key and indexed column as varchar(n) columns, whose
 * values pass through unchanged, so only the names are under test here.
 */
public class ColumnMapTest {
  private static final String NS = "ns";

  /** Table e as ScalarDB stores it. */
  private static final TableMetadata STORED =
      TableMetadata.newBuilder()
          .addColumn("k__pg_v10", DataType.TEXT)
          .addColumn("c__pg_v10", DataType.TEXT)
          .addColumn("name__pg_v20", DataType.TEXT)
          .addColumn("n", DataType.INT)
          .addPartitionKey("k__pg_v10")
          .addClusteringKey("c__pg_v10")
          .addSecondaryIndex("name__pg_v20")
          .build();

  private DistributedTransactionAdmin admin;
  private DistributedTransaction transaction;
  private QueryParser parser;
  private final List<Result> table = new ArrayList<>();
  private final List<Operation> executed = new ArrayList<>();

  private static Result row(String k, String c, String name, int n) {
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("k__pg_v10", TextColumn.of("k__pg_v10", k));
    columns.put("c__pg_v10", TextColumn.of("c__pg_v10", c));
    columns.put("name__pg_v20", TextColumn.of("name__pg_v20", name));
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

  private static String text(Result r, String column) {
    return String.valueOf(r.getAsObject(column));
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
    admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata(NS, "e")).thenReturn(STORED);
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton(NS));
    when(admin.getNamespaceTableNames(NS)).thenReturn(Collections.singleton("e"));
    parser = new QueryParser(admin, NS);
    transaction = mock(DistributedTransaction.class);
    table.addAll(
        Arrays.asList(row("a", "x", "b", 1), row("a", "y", "c", 2), row("z", "x", "b", 3)));
    // The in-memory table answers reads by key; every operation is recorded
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
              if (scan instanceof ScanAll) {
                return new ArrayList<>(table);
              }
              return table.stream()
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

  /** Every column name an operation sends to ScalarDB. */
  private static Set<String> names(Operation op) {
    Set<String> names = new LinkedHashSet<>();
    if (!(op instanceof ScanAll)) {
      op.getPartitionKey().getColumns().forEach(c -> names.add(c.getName()));
    }
    op.getClusteringKey().ifPresent(k -> k.getColumns().forEach(c -> names.add(c.getName())));
    if (op instanceof Selection) {
      Selection s = (Selection) op;
      names.addAll(s.getProjections());
      for (Selection.Conjunction conjunction : s.getConjunctions()) {
        conjunction.getConditions().forEach(e -> names.add(e.getColumn().getName()));
      }
    }
    if (op instanceof Scan) {
      Scan s = (Scan) op;
      s.getStartClusteringKey().ifPresent(k -> k.getColumns().forEach(c -> names.add(c.getName())));
      s.getEndClusteringKey().ifPresent(k -> k.getColumns().forEach(c -> names.add(c.getName())));
      s.getOrderings().forEach(o -> names.add(o.getColumnName()));
    }
    if (op instanceof Put) {
      names.addAll(((Put) op).getColumns().keySet());
    } else if (op instanceof Insert) {
      names.addAll(((Insert) op).getColumns().keySet());
    } else if (op instanceof Upsert) {
      names.addAll(((Upsert) op).getColumns().keySet());
    } else if (op instanceof Update) {
      names.addAll(((Update) op).getColumns().keySet());
    }
    if (op instanceof Put || op instanceof Update || op instanceof Delete) {
      ((Mutation) op) // Insert and Upsert have no condition
          .getCondition()
          .ifPresent(c -> c.getExpressions().forEach(e -> names.add(e.getColumn().getName())));
    }
    return names;
  }

  /** Every operation run so far named only ScalarDB's columns, and at least one ran. */
  private void assertStorageNamesOnly() {
    assertThat(executed).isNotEmpty();
    for (Operation op : executed) {
      assertThat(STORED.getColumnNames()).as(op.toString()).containsAll(names(op));
    }
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

  // ---- the map itself ----

  @Test
  public void clientView_ShouldNameKeysIndexesAndColumnsAsTheClientDoes() {
    ColumnMap map = ColumnMap.of(NS, "e", STORED);
    TableMetadata view = map.clientView();
    assertThat(view.getColumnNames()).containsExactly("k", "c", "name", "n");
    assertThat(view.getPartitionKeyNames()).containsExactly("k");
    assertThat(view.getClusteringKeyNames()).containsExactly("c");
    assertThat(view.getSecondaryIndexNames()).containsExactly("name");
    assertThat(view.getColumnDataType("name")).isEqualTo(DataType.TEXT);
    assertThat(map.renames()).isTrue();
    assertThat(map.storageName("name")).isEqualTo("name__pg_v20");
    assertThat(map.clientName("name__pg_v20")).isEqualTo("name");
    assertThat(map.type("k").sqlName()).isEqualTo("character varying(10)");
    assertThat(state(() -> map.storageName("name__pg_v20"))).isEqualTo("42703");
  }

  @Test
  public void tableWithoutEncodedNames_ShouldBePassedThroughUntouched() {
    TableMetadata plain =
        TableMetadata.newBuilder().addColumn("id", DataType.INT).addPartitionKey("id").build();
    ColumnMap map = ColumnMap.of(NS, "p", plain);
    assertThat(map.renames()).isFalse();
    assertThat(map.clientView()).isSameAs(plain);
    Get get = Get.newBuilder().namespace(NS).table("p").partitionKey(Key.ofInt("id", 1)).build();
    assertThat(map.toStorage(get)).isSameAs(get);
  }

  @Test
  public void toStorage_EveryOperationKind_ShouldRenameEveryColumn() {
    ColumnMap map = ColumnMap.of(NS, "e", STORED);
    Key k = Key.ofText("k", "a");
    Key c = Key.ofText("c", "x");
    List<Operation> client =
        Arrays.asList(
            Get.newBuilder()
                .namespace(NS)
                .table("e")
                .partitionKey(k)
                .clusteringKey(c)
                .projections("name", "n")
                .where(ConditionBuilder.column("name").isLikeText("b%"))
                .build(),
            Get.newBuilder()
                .namespace(NS)
                .table("e")
                .indexKey(Key.ofText("name", "b"))
                .projections("k")
                .build(),
            Scan.newBuilder()
                .namespace(NS)
                .table("e")
                .partitionKey(k)
                .start(c, false)
                .end(Key.ofText("c", "z"))
                .ordering(Scan.Ordering.desc("c"))
                .projections("c")
                .where(ConditionBuilder.column("name").isEqualToText("b"))
                .or(ConditionBuilder.column("n").isGreaterThanInt(1))
                .limit(3)
                .build(),
            Scan.newBuilder()
                .namespace(NS)
                .table("e")
                .all()
                .where(ConditionBuilder.column("k").isNotEqualToText("q"))
                .build(),
            Scan.newBuilder().namespace(NS).table("e").indexKey(Key.ofText("name", "b")).build(),
            Put.newBuilder()
                .namespace(NS)
                .table("e")
                .partitionKey(k)
                .clusteringKey(c)
                .textValue("name", "q")
                .intValue("n", 7)
                .condition(
                    ConditionBuilder.putIf(ConditionBuilder.column("name").isEqualToText("b"))
                        .build())
                .build(),
            Insert.newBuilder()
                .namespace(NS)
                .table("e")
                .partitionKey(k)
                .clusteringKey(c)
                .textValue("name", "q")
                .build(),
            Upsert.newBuilder()
                .namespace(NS)
                .table("e")
                .partitionKey(k)
                .clusteringKey(c)
                .textValue("name", "q")
                .build(),
            Update.newBuilder()
                .namespace(NS)
                .table("e")
                .partitionKey(k)
                .clusteringKey(c)
                .textValue("name", "q")
                .condition(
                    ConditionBuilder.updateIf(ConditionBuilder.column("n").isEqualToInt(1))
                        .and(ConditionBuilder.column("name").isNotNullText())
                        .build())
                .build(),
            Delete.newBuilder()
                .namespace(NS)
                .table("e")
                .partitionKey(k)
                .clusteringKey(c)
                .condition(
                    ConditionBuilder.deleteIf(ConditionBuilder.column("name").isEqualToText("b"))
                        .build())
                .build());
    for (Operation op : client) {
      Operation stored = map.toStorage(op);
      assertThat(stored.getClass()).isEqualTo(op.getClass());
      assertThat(STORED.getColumnNames()).as(op.toString()).containsAll(names(stored));
      assertThat(names(stored)).as(op.toString()).hasSameSizeAs(names(op));
    }
    // and nothing else changes: the same values, operators, orders, inclusivity, limit
    Scan scan = (Scan) map.toStorage(client.get(2));
    assertThat(scan.getStartClusteringKey()).contains(Key.ofText("c__pg_v10", "x"));
    assertThat(scan.getStartInclusive()).isFalse();
    assertThat(scan.getEndInclusive()).isTrue();
    assertThat(scan.getOrderings()).containsExactly(Scan.Ordering.desc("c__pg_v10"));
    assertThat(scan.getLimit()).isEqualTo(3);
    assertThat(scan.getConjunctions()).hasSize(2);
    assertThat(map.toStorage(client.get(1))).isInstanceOf(GetWithIndex.class);
    assertThat(map.toStorage(client.get(4))).isInstanceOf(ScanWithIndex.class);
    assertThat(((Put) map.toStorage(client.get(5))).getCondition().get().getExpressions())
        .containsExactly(ConditionBuilder.column("name__pg_v20").isEqualToText("b"));
  }

  @Test
  public void namesTheMapCannotHonour_ShouldBeShownRawAndNeverWritten() {
    TableMetadata stored =
        TableMetadata.newBuilder()
            .addColumn("id", DataType.INT)
            .addColumn("amount", DataType.DOUBLE)
            .addColumn("amount__pg_v10", DataType.TEXT) // the same client name, once decoded
            .addColumn("note__pg_zz", DataType.TEXT) // a code this frontend does not know
            .addColumn("code__pg_c3", DataType.TEXT) // a code whose values are not wired yet
            .addColumn("tag__pg_v5", DataType.TEXT)
            .addPartitionKey("id")
            .build();
    ColumnMap map = ColumnMap.of(NS, "d", stored);
    assertThat(map.clientView().getColumnNames())
        .containsExactly("id", "amount", "amount__pg_v10", "note__pg_zz", "code__pg_c3", "tag");
    for (Column<?> write :
        Arrays.<Column<?>>asList(
            DoubleColumn.of("amount", 1.0),
            TextColumn.of("amount__pg_v10", "x"),
            TextColumn.of("note__pg_zz", "x"),
            TextColumn.of("code__pg_c3", "ab "))) {
      Put put =
          Put.newBuilder()
              .namespace(NS)
              .table("d")
              .partitionKey(Key.ofInt("id", 1))
              .value(write)
              .build();
      assertThat(state(() -> map.toStorage(put))).as(write.getName()).isEqualTo("0A000");
    }
    // a raw column reads its stored value as is: the trailing space is not trimmed
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("id", IntColumn.of("id", 1));
    columns.put("code__pg_c3", TextColumn.of("code__pg_c3", "ab "));
    Result result = new ResultImpl(columns, stored);
    assertThat(map.value("code__pg_c3", result, result.getColumns())).isEqualTo("ab ");
    // a delete writes no value, and is allowed; the translated column is written
    Delete delete =
        Delete.newBuilder().namespace(NS).table("d").partitionKey(Key.ofInt("id", 1)).build();
    assertThat(map.toStorage(delete)).isEqualTo(delete);
    Put tag =
        Put.newBuilder()
            .namespace(NS)
            .table("d")
            .partitionKey(Key.ofInt("id", 1))
            .textValue("tag", "ab")
            .build();
    assertThat(map.toStorage(tag).getColumns()).containsKey("tag__pg_v5");
  }

  // ---- statements end to end ----

  @Test
  public void select_ByKeyByIndexAndScan_ShouldReadStorageNamesAndReturnClientNames()
      throws Exception {
    assertThat(parser.parse("SELECT * FROM e WHERE k = 'a' AND c = 'x'").execute(transaction))
        .containsExactly(map("k", "a", "c", "x", "name", "b", "n", 1));
    assertThat(
            parser
                .parse("SELECT name, n FROM e WHERE k = 'a' ORDER BY c DESC")
                .execute(transaction))
        .containsExactlyInAnyOrder(map("name", "c", "n", 2), map("name", "b", "n", 1));
    // the in-memory stub ignores orderings: what matters is the one ScalarDB was asked for
    assertThat(executed)
        .filteredOn(op -> op instanceof Scan && !((Scan) op).getOrderings().isEmpty())
        .singleElement()
        .satisfies(
            op ->
                assertThat(((Scan) op).getOrderings())
                    .containsExactly(Scan.Ordering.desc("c__pg_v10")));
    assertThat(parser.parse("SELECT k, n FROM e WHERE name = 'c'").execute(transaction))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r).containsOnlyKeys("k", "n"));
    assertThat(parser.parse("SELECT count(*) AS c FROM e").execute(transaction))
        .containsExactly(map("c", 3L));
    assertStorageNamesOnly();
    assertThat(executed).anyMatch(op -> op instanceof ScanWithIndex);
  }

  @Test
  public void join_LookupsAndPartitionGroups_ShouldMatchRowsByStoredKeys() throws Exception {
    // b is read by key per outer row; the lookups that share partition a can become one scan
    List<Map<String, Object>> rows =
        parser
            .parse(
                "SELECT a.n AS an, b.name AS bn FROM e a JOIN e b ON b.k = a.k AND b.c = a.c"
                    + " ORDER BY a.n")
            .execute(transaction);
    assertThat(rows)
        .containsExactly(map("an", 1, "bn", "b"), map("an", 2, "bn", "c"), map("an", 3, "bn", "b"));
    assertStorageNamesOnly();
    // the two lookups into partition a were one Scan, an OR of their stored clustering keys
    assertThat(executed)
        .filteredOn(
            op ->
                op instanceof Scan
                    && !(op instanceof ScanAll)
                    && ((Scan) op).getConjunctions().size() == 2)
        .singleElement()
        .satisfies(
            op ->
                assertThat((Object) op.getPartitionKey()).isEqualTo(Key.ofText("k__pg_v10", "a")));
  }

  @Test
  public void writes_ShouldSendStorageNames() throws Exception {
    parser.parse("INSERT INTO e (k, c, name, n) VALUES ('q', 'r', 's', 9)").open(transaction);
    parser
        .parse("UPDATE e SET name = 'u' WHERE k = 'a' AND c = 'x' AND name = 'b'")
        .open(transaction);
    parser.parse("DELETE FROM e WHERE k = 'a' AND c = 'y'").open(transaction);
    assertThat(parser.parse("DELETE FROM e WHERE name = 'b'").executeWrite(transaction, 10))
        .isEqualTo(2);
    assertStorageNamesOnly();
    assertThat(executed).filteredOn(op -> op instanceof Mutation).hasSize(5);
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
                assertThat(((Insert) op).getColumns().get("name__pg_v20").getTextValue())
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
                        ConditionBuilder.column("name__pg_v20").isEqualToText("x".repeat(21))));
  }

  @Test
  public void insertOnConflictWithReturning_ShouldReadStoredRowAndReturnClientNames()
      throws Exception {
    List<Map<String, Object>> returned =
        parser
            .parse(
                "INSERT INTO e (k, c, name, n) VALUES ('a', 'x', 'new', 5), ('q', 'q', 'w', 1)"
                    + " ON CONFLICT (k, c) DO UPDATE SET n = e.n + EXCLUDED.n"
                    + " RETURNING k, name, n")
            .execute(transaction);
    assertThat(returned)
        .containsExactly(map("k", "a", "name", "b", "n", 6), map("k", "q", "name", "w", "n", 1));
    assertStorageNamesOnly();
  }

  @Test
  public void explainAndCatalog_ShouldShowClientNames() throws Exception {
    String explain =
        String.join("\n", parser.explain(parser.parse("SELECT name FROM e WHERE k = 'a'")));
    assertThat(explain).contains("partitionKey={k=").doesNotContain("__pg_");
    assertThat(
            parser
                .parse(
                    "SELECT column_name, data_type FROM information_schema.columns"
                        + " WHERE table_name = 'e' ORDER BY ordinal_position")
                .execute(transaction))
        .extracting(r -> r.get("column_name"))
        .containsExactly("k", "c", "name", "n");
    assertThat(
            parser
                .parse("SELECT indexname FROM pg_indexes WHERE tablename = 'e' ORDER BY 1")
                .execute(transaction))
        .extracting(r -> String.valueOf(r.get("indexname")))
        .allSatisfy(n -> assertThat(n).doesNotContain("__pg_"))
        .contains("e_name_idx");
  }

  @Test
  public void storageNamesInSql_ShouldBeUnknownColumns() throws Exception {
    assertThat(state(() -> parser.parse("SELECT \"name__pg_v20\" FROM e"))).isEqualTo("42703");
    assertThat(
            state(
                () ->
                    parser
                        .parse("INSERT INTO e (k, c, \"name__pg_v20\") VALUES ('q', 'r', 's')")
                        .open(transaction)))
        .isEqualTo("42703");
    assertThat(
            state(
                () ->
                    parser
                        .parse("UPDATE e SET \"name__pg_v20\" = 'x' WHERE k = 'a' AND c = 'x'")
                        .open(transaction)))
        .isEqualTo("42703");
    assertThat(
            state(
                () ->
                    parser.parse("SELECT n FROM e WHERE \"k__pg_v10\" = 'a'").execute(transaction)))
        .isEqualTo("42703");
    assertThat(
            state(
                () ->
                    Optional.ofNullable(parser.parse("CREATE INDEX i ON e (\"n__pg_s\")").getDdl())
                        .get()
                        .run()))
        .isEqualTo("42703");
  }

  @Test
  public void ddl_ShouldMapNamesAndRefuseWhatPhase2CannotDo() throws Exception {
    // CREATE INDEX on a client column indexes the stored column
    Optional.ofNullable(parser.parse("CREATE INDEX i ON e (k)").getDdl()).get().run();
    org.mockito.Mockito.verify(admin).createIndex(NS, "e", "k__pg_v10", false);
    // a new column may not carry the marker, which would read back as a declared type
    assertThat(state(() -> parser.parse("ALTER TABLE e ADD COLUMN \"x__pg_s\" INT")))
        .isEqualTo("42602");
    assertThat(state(() -> parser.parse("CREATE TABLE f (id INT PRIMARY KEY, \"y__pg_v3\" TEXT)")))
        .isEqualTo("42602");
    // renaming a column whose type is in its name is refused until it can keep the suffix
    assertThat(
            state(
                () ->
                    Optional.ofNullable(
                            parser.parse("ALTER TABLE e RENAME COLUMN name TO nm").getDdl())
                        .get()
                        .run()))
        .isEqualTo("0A000");
    assertThat(
            state(
                () ->
                    Optional.ofNullable(
                            parser
                                .parse("ALTER TABLE e RENAME COLUMN \"name__pg_v20\" TO name")
                                .getDdl())
                        .get()
                        .run()))
        .isEqualTo("0A000"); // nor by its ScalarDB name, which would lose the type
    // a new name may not be one a column has as clients see it
    assertThat(
            state(
                () ->
                    Optional.ofNullable(parser.parse("ALTER TABLE e ADD COLUMN Name INT").getDdl())
                        .get()
                        .run()))
        .isEqualTo("42701");
    assertThat(
            state(
                () ->
                    Optional.ofNullable(parser.parse("ALTER TABLE e RENAME COLUMN n TO k").getDdl())
                        .get()
                        .run()))
        .isEqualTo("42701");
    Optional.ofNullable(parser.parse("ALTER TABLE e RENAME COLUMN n TO m").getDdl()).get().run();
    org.mockito.Mockito.verify(admin).renameColumn(NS, "e", "n", "m");
  }
}
