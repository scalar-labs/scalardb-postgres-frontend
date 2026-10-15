package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Result;
import com.scalar.db.api.Selection;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.TextColumn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * char(n), varchar(n) and smallint columns, their declarations recorded in their ScalarDB names:
 * written with PostgreSQL's length and range rules, char stored without its padding, and each
 * described to clients as its declared type.
 */
public class CharacterColumnTest {
  private static final String NS = "ns";

  /** Table w as ScalarDB stores it: k int primary key, c char(5), v varchar(7), s smallint. */
  private static final TableMetadata STORED =
      TableMetadata.newBuilder()
          .addColumn("k", DataType.INT)
          .addColumn("c", DataType.TEXT)
          .addColumn("v", DataType.TEXT)
          .addColumn("s", DataType.INT)
          .addPartitionKey("k")
          .build();

  private DistributedTransactionAdmin admin;
  private DistributedTransaction transaction;
  private QueryParser parser;
  private final List<Result> table = new ArrayList<>();
  private final List<Operation> executed = new ArrayList<>();

  private static Result row(int k, String c, String v, int s) {
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    columns.put("k", IntColumn.of("k", k));
    columns.put("c", TextColumn.of("c", c));
    columns.put("v", TextColumn.of("v", v));
    columns.put("s", IntColumn.of("s", s));
    return new ResultImpl(columns, STORED);
  }

  @BeforeEach
  public void setUp() throws Exception {
    admin = mock(DistributedTransactionAdmin.class);
    when(admin.getTableMetadata(NS, "w")).thenReturn(STORED);
    when(admin.getNamespaceNames()).thenReturn(Collections.singleton(NS));
    when(admin.getNamespaceTableNames(NS)).thenReturn(Collections.singleton("w"));
    parser = new QueryParser(admin, NS);
    MemoryTypeStore types = new MemoryTypeStore(admin);
    types.seed(NS, "w", "c", ColumnType.declared("char(5)", ColumnType.MAX_SCALED_PRECISION));
    types.seed(NS, "w", "v", ColumnType.declared("varchar(7)", ColumnType.MAX_SCALED_PRECISION));
    types.seed(NS, "w", "s", ColumnType.declared("smallint", ColumnType.MAX_SCALED_PRECISION));
    parser.setTypeStore(types.store);
    transaction = mock(DistributedTransaction.class);
    table.add(row(1, "ab", "xyz", 7));
    table.add(row(2, "abc", "x", -32768));
    when(transaction.scan(any()))
        .thenAnswer(
            invocation -> {
              executed.add(invocation.getArgument(0));
              return new ArrayList<>(table);
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
  public void createTable_ShouldRecordTheDeclarations() throws Exception {
    QueryParser.Plan plan =
        parser.parse(
            "CREATE TABLE q (id INT PRIMARY KEY, c CHAR(5), b CHARACTER, v VARCHAR(7),"
                + " s SMALLINT, t VARCHAR)");
    assertThat(plan.notices).isEmpty();
    plan.getDdl().run();
    org.mockito.ArgumentCaptor<TableMetadata> metadata =
        org.mockito.ArgumentCaptor.forClass(TableMetadata.class);
    verify(admin).createTable(eq(NS), eq("q"), metadata.capture(), eq(false));
    assertThat(metadata.getValue().getColumnNames()).containsExactly("id", "c", "b", "v", "s", "t");
  }

  @Test
  public void insert_ShouldFollowPostgresLengthAndRangeRules() throws Exception {
    parser.parse("INSERT INTO w VALUES (3, 'ab   ', 'abc    ', 12)").open(transaction);
    Insert insert = (Insert) executed.get(0);
    // a char is stored without its padding; spaces beyond a varchar's length are cut
    assertThat(insert.getColumns().get("c").getTextValue()).isEqualTo("ab");
    assertThat(insert.getColumns().get("v").getTextValue()).isEqualTo("abc    ");
    assertThat(
            state(
                () -> parser.parse("INSERT INTO w VALUES (4, 'abcdef', 'x', 1)").open(transaction)))
        .isEqualTo("22001");
    assertThat(
            state(
                () ->
                    parser.parse("INSERT INTO w VALUES (4, 'a', 'abcdefgh', 1)").open(transaction)))
        .isEqualTo("22001");
    assertThat(
            state(
                () -> parser.parse("INSERT INTO w VALUES (4, 'a', 'x', 40000)").open(transaction)))
        .isEqualTo("22003");
  }

  @Test
  public void select_ShouldDescribeTheDeclaredTypes() throws Exception {
    QueryParser.Plan plan = parser.parse("SELECT c, v, s FROM w");
    assertThat(plan.execute(transaction)).extracting(r -> r.get("c")).containsExactly("ab", "abc");
    assertThat(plan.outputOids()).containsExactly(1042, 1043, 21);
    assertThat(plan.outputTypmods()).containsExactly(9, 11, -1);
    // padded to n characters when sent, as PostgreSQL returns a char(n)
    assertThat(PostgresServer.padded("ab", 5)).isEqualTo("ab   ");
  }

  @Test
  public void where_ShouldPushStrippedCharsAndKeepOutOfRangeInMemory() throws Exception {
    // trailing spaces never matter in a char: c = 'ab   ' is c = 'ab' as stored
    // (the stubbed scan ignores pushed conditions: the condition itself is checked)
    parser.parse("SELECT k FROM w WHERE c = 'ab   '").execute(transaction);
    assertThat(executed)
        .singleElement()
        .satisfies(
            op ->
                assertThat(((Selection) op).getConjunctions().iterator().next().getConditions())
                    .containsExactly(ConditionBuilder.column("c").isEqualToText("ab")));
    // no smallint holds 40000: nothing matches, and nothing fails
    assertThat(parser.parse("SELECT k FROM w WHERE s = 40000").execute(transaction)).isEmpty();
    // LIKE on a char is not pushed: the stored value has no padding
    executed.clear();
    parser.parse("SELECT k FROM w WHERE c LIKE 'a%'").execute(transaction);
    assertThat(((Selection) executed.get(0)).getConjunctions()).isEmpty();
  }

  @Test
  public void catalog_ShouldShowTheDeclaredTypes() throws Exception {
    assertThat(
            parser
                .parse(
                    "SELECT format_type(atttypid, atttypmod) AS t FROM pg_attribute"
                        + " WHERE attrelid = 'w'::regclass AND attnum > 0 ORDER BY attnum")
                .execute(transaction))
        .extracting(r -> r.get("t"))
        .containsExactly("integer", "character(5)", "character varying(7)", "smallint");
    assertThat(
            parser
                .parse(
                    "SELECT character_maximum_length AS n FROM information_schema.columns"
                        + " WHERE table_name = 'w' AND column_name IN ('c', 'v')"
                        + " ORDER BY ordinal_position")
                .execute(transaction))
        .extracting(r -> r.get("n"))
        .containsExactly(5L, 7L);
  }

  @Test
  public void charInMemory_ShouldFollowBpcharRules() throws Exception {
    // as PostgreSQL: a quoted value's trailing spaces never count; LIKE sees the padded value
    assertThat(
            parser
                .parse(
                    "SELECT k, c IN ('ab   ', 'zz') AS i, c LIKE 'ab' AS l, c LIKE 'ab   ' AS p,"
                        + " c LIKE 'ab%' AS q FROM w")
                .execute(transaction))
        .containsExactly(
            map("k", 1, "i", true, "l", false, "p", true, "q", true),
            map("k", 2, "i", false, "l", false, "p", false, "q", true));
  }

  @Test
  public void charComparisons_ShouldIgnoreTrailingSpacesAsBpchar() throws Exception {
    // every comparison of char with char, varchar, a quoted value or a parameter; not with text
    assertThat(
            parser
                .parse(
                    "SELECT k, c BETWEEN 'ab ' AND 'ab ' AS b, c IS NOT DISTINCT FROM 'ab ' AS d,"
                        + " CASE c WHEN 'ab ' THEN 1 ELSE 0 END AS w, 'ab ' IN (c) AS i,"
                        + " c = $1 AS p, c = 'ab '::text AS t FROM w",
                    Collections.singletonList("ab "))
                .execute(transaction))
        .containsExactly(
            map("k", 1, "b", true, "d", true, "w", 1L, "i", true, "p", true, "t", false),
            map("k", 2, "b", false, "d", false, "w", 0L, "i", false, "p", false, "t", false));
    // the same through a subquery, LIKE seeing the padded value there too
    assertThat(
            parser
                .parse(
                    "SELECT k, d.c = 'ab ' AS e, d.c LIKE 'ab' AS l, d.c LIKE 'ab   ' AS p"
                        + " FROM (SELECT k, c FROM w) d")
                .execute(transaction))
        .containsExactly(
            map("k", 1, "e", true, "l", false, "p", true),
            map("k", 2, "e", false, "l", false, "p", false));
  }

  @Test
  public void quotedSmallint_ShouldBeSmallintInput() {
    assertThatThrownBy(() -> parser.parse("SELECT k FROM w WHERE s = '40000'").execute(transaction))
        .hasMessage("value \"40000\" is out of range for type smallint");
    assertThatThrownBy(() -> parser.parse("SELECT k FROM w WHERE s < '1.5'").execute(transaction))
        .hasMessage("invalid input syntax for type smallint: \"1.5\"");
    // through a subquery the in-memory comparison refuses it, though naming numeric
    assertThat(
            state(
                () ->
                    parser
                        .parse("SELECT * FROM (SELECT k, s FROM w) d WHERE d.s = 'abc'")
                        .execute(transaction)))
        .isEqualTo("22P02");
  }

  @Test
  public void padFunctions_ShouldWorkAsInPostgres() throws Exception {
    assertThat(
            parser
                .parse(
                    "SELECT rpad('ab', 5) || '|' AS r, lpad('7', 3, '0') AS l,"
                        + " rpad('abcdef', 3) AS t, lpad('x', 4, 'ab') AS f")
                .execute(transaction))
        .containsExactly(map("r", "ab   |", "l", "007", "t", "abc", "f", "abax"));
    assertThatThrownBy(() -> parser.parse("SELECT rpad('x', 10000001) AS r").execute(transaction))
        .hasMessage("requested length too large");
    assertThatThrownBy(() -> parser.parse("SELECT lpad('a', 2.9) AS r").execute(transaction))
        .hasMessage("function lpad(text, numeric) does not exist");
    assertThatThrownBy(() -> parser.parse("SELECT rpad('a', 5000000000) AS r").execute(transaction))
        .hasMessage("function rpad(text, bigint) does not exist");
  }

  private static Map<String, Object> map(Object... namesAndValues) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      m.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return m;
  }
}
