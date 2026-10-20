package com.scalar.db.frontend.postgres;

import com.scalar.db.api.AndConditionSet;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionSetBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DeleteBuilder;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.GetBuilder;
import com.scalar.db.api.Insert;
import com.scalar.db.api.InsertBuilder;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.ScanBuilder;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.api.UpdateBuilder;
import com.scalar.db.api.Upsert;
import com.scalar.db.api.UpsertBuilder;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.frontend.postgres.Plan.Cursor;
import com.scalar.db.frontend.postgres.Plan.Ddl;
import com.scalar.db.frontend.postgres.Plan.Effect;
import com.scalar.db.frontend.postgres.Plan.Returning;
import com.scalar.db.frontend.postgres.Plan.Source;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.BlobColumn;
import com.scalar.db.io.BooleanColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.DateColumn;
import com.scalar.db.io.DoubleColumn;
import com.scalar.db.io.FloatColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import com.scalar.db.io.TimeColumn;
import com.scalar.db.io.TimestampColumn;
import com.scalar.db.io.TimestampTZColumn;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.CollateExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.RowGetExpression;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ReturningClause;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.ConflictActionType;
import net.sf.jsqlparser.statement.insert.InsertConflictAction;
import net.sf.jsqlparser.statement.insert.InsertConflictTarget;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperation;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * Parses a PostgreSQL-style SQL statement (with JSQLParser) and plans it as ScalarDB operations
 * plus in-memory post-processing.
 *
 * <p>Everything ScalarDB can evaluate is pushed down. Each table in {@code FROM} becomes a {@code
 * Get} when its full primary key is bound with {@code =}, a partition {@code Scan} when its
 * partition key is bound (clustering-key ranges become {@code start}/{@code end}), an index {@code
 * Scan} when a secondary-index column is bound, and a {@code ScanAll} otherwise. Simple {@code AND}
 * conditions on one table ({@code column op literal}, {@code IS NULL}, {@code LIKE}) become
 * ScalarDB conditions; for a single table, {@code ORDER BY} on clustering keys and {@code LIMIT}
 * are pushed down when nothing else has to happen in memory. The rest runs in memory over the
 * returned rows: nested-loop {@code JOIN}/{@code LEFT JOIN}, subqueries (scalar, {@code IN}, {@code
 * EXISTS}, correlated, and in {@code FROM}), {@code OR}/{@code NOT}/{@code IN}/{@code BETWEEN},
 * arithmetic, scalar functions, {@code CASE}, {@code CAST}, aliases, {@code DISTINCT}, other {@code
 * ORDER BY}, {@code OFFSET}, and {@code COUNT}/{@code SUM}/{@code AVG}/{@code MIN}/{@code MAX} with
 * {@code GROUP BY}/{@code HAVING}. {@code INSERT} rows and {@code UPDATE}/{@code DELETE} on a full
 * primary key map to ScalarDB mutations, with extra {@code AND} conditions as mutation conditions.
 */
class QueryParser {
  /** Rows an UPDATE, DELETE or INSERT ... SELECT driven by a read may write, unless raised. */
  public static final int DEFAULT_MAX_ROWS_PER_WRITE = 10_000;

  // the most rows generate_series makes, in FROM or in the select list
  static final int MAX_SERIES_ROWS = DEFAULT_MAX_ROWS_PER_WRITE * 100;

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(QueryParser.class);

  private final DistributedTransactionAdmin admin;
  // ponytail: fixed per storage (18, or 15 for Cosmos DB, object storage and Cluster); a property
  // when a Cluster on JDBC needs scaled numeric(16..18)
  private volatile int maxScaledPrecision = ColumnType.MAX_SCALED_PRECISION;
  private final String defaultNamespace;
  private final Catalog catalog;
  // Per session; dropped after DDL by any session of this process (DDL_GENERATION, todo 011), and
  // when a cached table's ScalarDB metadata changed, as DDL from another process does (todo 054).
  // namespace.table -> the table's columns as clients see them; replaced, not cleared, after DDL,
  // so a plan keeps the maps it was made with (read from the lookup threads too)
  private Map<String, ColumnMap> columnMaps = new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong DDL_GENERATION =
      new java.util.concurrent.atomic.AtomicLong();
  private long seenGeneration = DDL_GENERATION.get();
  private long checkedNanos = System.nanoTime();
  // How often a table's metadata is compared for DDL from another process: ScalarDB's own cache
  // expiry (at least 1 s), since the engine does not see such DDL sooner either; 0 for never, as a
  // negative expiry never refreshes the engine's cache
  private volatile long checkIntervalNanos = 1_000_000_000L;
  // namespace.table -> its metadata as last read, shared by the sessions of this process, so a
  // table is read at most once per interval however many sessions use it
  private static final Map<String, Checked> CHECKED =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final class Checked {
    final long nanos;
    @Nullable final TableMetadata metadata; // null when the read failed

    Checked(long nanos, @Nullable TableMetadata metadata) {
      this.nanos = nanos;
      this.metadata = metadata;
    }
  }

  public QueryParser(DistributedTransactionAdmin admin, String defaultNamespace) {
    this.admin = admin;
    this.defaultNamespace = defaultNamespace;
    this.catalog = new Catalog(admin, defaultNamespace);
  }

  void setCheckIntervalNanos(long nanos) {
    checkIntervalNanos = nanos;
  }

  void setMaxScaledPrecision(int digits) {
    maxScaledPrecision = digits;
  }

  // the declared types of columns; none until the server sets the deployment's store
  private volatile TypeStore typeStore = TypeStore.NONE;

  void setTypeStore(TypeStore store) {
    typeStore = store;
    catalog.typeStore = store;
  }

  /** Forgets cached table metadata, after DDL, in every session of this process. */
  public void invalidateMetadata() {
    DDL_GENERATION.incrementAndGet();
  }

  /**
   * True, once, after DDL in any session since this session last looked: its cached metadata is
   * dropped, and the caller drops plans made with it.
   */
  public boolean metadataChanged() {
    if (changedElsewhere()) {
      invalidateMetadata();
    }
    long now = DDL_GENERATION.get();
    if (now == seenGeneration) {
      return false;
    }
    seenGeneration = now;
    columnMaps = new java.util.concurrent.ConcurrentHashMap<>();
    return true;
  }

  /**
   * True when a table this session cached has other ScalarDB metadata now: DDL from another
   * process. ponytail: a metadata read per table and interval in each process; a cluster-wide
   * schema version would spare the reads
   */
  private boolean changedElsewhere() {
    long interval = checkIntervalNanos;
    if (interval <= 0 || System.nanoTime() - checkedNanos < interval) {
      return false;
    }
    checkedNanos = System.nanoTime();
    for (ColumnMap columns : columnMaps.values()) {
      Checked checked =
          CHECKED.compute(
              columns.namespace + "." + columns.table,
              (key, last) -> {
                long now = System.nanoTime();
                if (last != null && now - last.nanos < interval) {
                  return last;
                }
                try {
                  return new Checked(now, admin.getTableMetadata(columns.namespace, columns.table));
                } catch (ExecutionException e) {
                  return new Checked(now, null); // read afresh, and fail there if it still fails
                }
              });
      if (!columns.metadata().equals(checked.metadata)) {
        return true;
      }
    }
    return false;
  }

  // ---- rows ----

  /** A cursor over rows already in memory. */
  static Cursor listCursor(List<Map<String, Object>> rows) {
    Iterator<Map<String, Object>> iterator = rows.iterator();
    return new Cursor() {
      @Override
      @Nullable public Map<String, Object> next() {
        return iterator.hasNext() ? iterator.next() : null;
      }

      @Override
      public void close() {}
    };
  }

  /**
   * The most rows one sort, aggregate, window or join side may hold in memory: past it a statement
   * fails with 54000 rather than exhausting the heap every session shares ({@code
   * scalar.db.postgres.max_rows_in_memory}).
   */
  private static volatile int maxRowsInMemory = 1_000_000;

  static void setMaxRowsInMemory(int max) {
    maxRowsInMemory = max;
  }

  /** Pulls every remaining row without closing the cursor, up to {@link #maxRowsInMemory}. */
  static List<Map<String, Object>> drain(Cursor cursor) {
    // ponytail: a cap per collection, not per statement or per process; a statement holding
    // several such collections, or many sessions at once, can still use a multiple of it
    int max = maxRowsInMemory;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> row = cursor.next(); row != null; row = cursor.next()) {
      if (rows.size() >= max) {
        throw new PgException.Argument(
            "54000", // program_limit_exceeded
            "the statement would hold more than "
                + max
                + " rows in memory (scalar.db.postgres.max_rows_in_memory): narrow it with WHERE"
                + " or LIMIT");
      }
      rows.add(row);
    }
    return rows;
  }

  /** Pulls every row, then closes the cursor. */
  static List<Map<String, Object>> readAll(Cursor cursor) {
    try {
      return drain(cursor);
    } finally {
      cursor.close();
    }
  }

  /**
   * A row keyed as {@code qualifier.column}. Every column of the table is present: ScalarDB caches
   * table metadata (scalar.db.metadata.cache_expiration_time_secs, 60 seconds by default), so for a
   * while after ALTER TABLE a read may not return a new column, which is then null here.
   */
  static Map<String, Object> rowOf(
      String qualifier, List<String> columns, @Nullable ColumnMap map, Result result) {
    Map<String, Object> row = new LinkedHashMap<>();
    if (map == null || !map.decodes()) {
      for (String name : result.getContainedColumnNames()) {
        row.put(qualifier + "." + name, result.getAsObject(name));
      }
    } else {
      // stored values to the client's: amount = 1990 in a numeric(10,2) column is 19.90
      Map<String, Column<?>> stored = result.getColumns();
      for (String name : result.getContainedColumnNames()) {
        row.put(qualifier + "." + name, map.value(name, result, stored));
      }
    }
    for (String name : columns) {
      row.putIfAbsent(qualifier + "." + name, null);
    }
    return row;
  }

  static List<Map<String, Object>> rowsOf(
      String qualifier, List<String> columns, @Nullable ColumnMap map, List<Result> results) {
    List<Map<String, Object>> rows = new ArrayList<>(results.size());
    for (Result result : results) {
      rows.add(rowOf(qualifier, columns, map, result));
    }
    return rows;
  }

  // ---- EXPLAIN ----

  /** The operator tree, one operator per line: what runs in ScalarDB and what runs in memory. */
  public List<String> explain(Plan plan) {
    List<String> out = new ArrayList<>();
    explain(plan, out, 0, false);
    return out;
  }

  static void explain(Plan plan, List<String> out, int indent, boolean actual) {
    if (plan.write != null) {
      out.add(pad(indent) + plan.write.description);
      tree(plan.write.read.root(), out, indent + 2, false, actual);
      subplans(plan.write.read, out, indent + 2, actual);
      return;
    }
    if (plan.mutations() != null) {
      for (Mutation m : plan.mutations()) {
        out.add(pad(indent) + "ScalarDB " + m.getClass().getSimpleName() + " " + tableOf(m));
      }
      return;
    }
    tree(plan.root(), out, indent, true, actual);
    subplans(plan, out, indent, actual);
  }

  private static void subplans(Plan plan, List<String> out, int indent, boolean actual) {
    for (Map.Entry<Select, Plan> e : plan.subplans.entrySet()) {
      out.add(pad(indent) + "Subquery " + e.getKey() + ":");
      explain(e.getValue(), out, indent + 2, actual);
    }
  }

  private static void tree(
      Operator op, List<String> out, int indent, boolean root, boolean actual) {
    out.add(pad(indent) + (root ? "" : "-> ") + op.describe() + (actual ? stats(op) : ""));
    for (Operator child : op.children()) {
      tree(child, out, indent + 2, false, actual);
    }
    if (op instanceof Operators.Rekey) {
      subplans(((Operators.Rekey) op).derived, out, indent + 2, actual);
    }
    if (op instanceof Operators.SetOperation) {
      for (Plan member : ((Operators.SetOperation) op).members) {
        subplans(member, out, indent + 2, actual);
      }
    }
  }

  /** What EXPLAIN ANALYZE appends to an operator: total rows over all its loops, and its reads. */
  private static String stats(Operator op) {
    if (op.loops() == 0) {
      return " (never executed)";
    }
    StringBuilder sb = new StringBuilder(" (actual rows=").append(op.rows());
    if (op.loops() > 1) {
      sb.append(" loops=").append(op.loops());
    }
    if (op.reads() > 0) {
      sb.append(" ScalarDB reads=").append(op.reads());
    }
    return sb.append(')').toString();
  }

  private static String pad(int indent) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < indent; i++) {
      sb.append(' ');
    }
    return sb.toString();
  }

  static String conjunctionText(List<Expression> expressions) {
    StringBuilder sb = new StringBuilder();
    for (Expression e : expressions) {
      sb.append(sb.length() == 0 ? "" : " AND ").append(e);
    }
    return sb.toString();
  }

  private static String tableOf(Operation op) {
    return op.forNamespace().orElse("") + "." + op.forTable().orElse("");
  }

  /** A compact description of a ScalarDB read operation. */
  static String describe(Operation op) {
    StringBuilder b = new StringBuilder();
    if (op instanceof Get) {
      Get get = (Get) op;
      b.append("Get ")
          .append(tableOf(op))
          .append(" partitionKey=")
          .append(keyText(get.getPartitionKey()));
      get.getClusteringKey().ifPresent(k -> b.append(" clusteringKey=").append(keyText(k)));
      conditionsText(b, get.getConjunctions());
      projectionsText(b, get.getProjections());
    } else if (op instanceof Scan) {
      Scan scan = (Scan) op;
      if (scan instanceof com.scalar.db.api.ScanAll) {
        b.append("ScanAll ").append(tableOf(op));
      } else if (scan instanceof com.scalar.db.api.ScanWithIndex) {
        b.append("Scan ")
            .append(tableOf(op))
            .append(" indexKey=")
            .append(keyText(scan.getPartitionKey()));
      } else {
        b.append("Scan ")
            .append(tableOf(op))
            .append(" partitionKey=")
            .append(keyText(scan.getPartitionKey()));
        scan.getStartClusteringKey()
            .ifPresent(
                k ->
                    b.append(" start=")
                        .append(keyText(k))
                        .append(scan.getStartInclusive() ? " (inclusive)" : " (exclusive)"));
        scan.getEndClusteringKey()
            .ifPresent(
                k ->
                    b.append(" end=")
                        .append(keyText(k))
                        .append(scan.getEndInclusive() ? " (inclusive)" : " (exclusive)"));
      }
      conditionsText(b, scan.getConjunctions());
      if (!scan.getOrderings().isEmpty()) {
        b.append(" ordering=[");
        for (Scan.Ordering o : scan.getOrderings()) {
          b.append(b.charAt(b.length() - 1) == '[' ? "" : ", ")
              .append(o.getColumnName())
              .append(' ')
              .append(o.getOrder());
        }
        b.append(']');
      }
      if (scan.getLimit() > 0) {
        b.append(" limit=").append(scan.getLimit());
      }
      projectionsText(b, scan.getProjections());
    } else {
      b.append(op.getClass().getSimpleName()).append(' ').append(tableOf(op));
    }
    return b.toString();
  }

  /** Describes a keyed lookup: which key columns are bound to outer expressions or literals. */
  static String describeLookup(Source s) {
    LogicalPlan.Source b = s.lookup();
    TableMetadata metadata = b.table().metadata;
    Set<String> bound = new LinkedHashSet<>(s.lookupParams().keySet());
    for (ConditionalExpression c : b.pushed) {
      if (c.getOperator() == ConditionalExpression.Operator.EQ) {
        bound.add(c.getColumn().getName());
      }
    }
    boolean partition = bound.containsAll(metadata.getPartitionKeyNames());
    boolean full = partition && bound.containsAll(metadata.getClusteringKeyNames());
    StringBuilder t = new StringBuilder(full ? "Get " : "Scan ");
    t.append(b.table().namespace).append('.').append(b.table().table);
    if (partition) {
      t.append(" partitionKey=").append(boundText(metadata.getPartitionKeyNames(), s));
      if (full && !metadata.getClusteringKeyNames().isEmpty()) {
        t.append(" clusteringKey=").append(boundText(metadata.getClusteringKeyNames(), s));
      }
    } else {
      for (String index : metadata.getSecondaryIndexNames()) {
        if (s.lookupParams().containsKey(index)) {
          t.append(" indexKey=").append(boundText(Collections.singleton(index), s));
          break;
        }
      }
    }
    // Pushed conditions other than the key columns above still apply to each lookup
    Set<String> keyColumns = new HashSet<>(metadata.getPartitionKeyNames());
    keyColumns.addAll(metadata.getClusteringKeyNames());
    StringBuilder conditions = new StringBuilder();
    for (ConditionalExpression c : b.pushed) {
      boolean shownAsKey =
          c.getOperator() == ConditionalExpression.Operator.EQ
              && (keyColumns.contains(c.getColumn().getName())
                  || t.indexOf("indexKey={" + c.getColumn().getName() + "=") >= 0);
      if (!shownAsKey) {
        conditions.append(conditions.length() == 0 ? "" : " AND ").append(conditionText(c));
      }
    }
    for (List<List<ConditionalExpression>> group : b.pushedOr) {
      StringBuilder alternatives = new StringBuilder();
      for (List<ConditionalExpression> term : group) {
        StringBuilder all = new StringBuilder();
        for (ConditionalExpression c : term) {
          all.append(all.length() == 0 ? "" : " AND ").append(conditionText(c));
        }
        alternatives
            .append(alternatives.length() == 0 ? "" : " OR ")
            .append('(')
            .append(all)
            .append(')');
      }
      conditions.append(conditions.length() == 0 ? "" : " AND ").append(alternatives);
    }
    if (conditions.length() > 0) {
      t.append(" conditions=[").append(conditions).append(']');
    }
    t.append(" projections=").append(b.star ? "[all]" : new ArrayList<>(b.projections).toString());
    if (!b.pushedLate.isEmpty()) {
      t.append(" conditions=").append(conjunctionText(b.pushedLate));
    }
    if (!s.lookupOrderBy.isEmpty()) {
      t.append(" ordering=").append(s.lookupOrderBy);
    }
    if (s.lookupLimit >= 0) {
      t.append(" limit=").append(s.lookupLimit);
    }
    return t.toString();
  }

  private static String conditionText(ConditionalExpression c) {
    String value = valueText(c.getColumn());
    switch (c.getOperator()) {
      case EQ:
        return c.getColumn().getName() + " = " + value;
      case NE:
        return c.getColumn().getName() + " <> " + value;
      case GT:
        return c.getColumn().getName() + " > " + value;
      case GTE:
        return c.getColumn().getName() + " >= " + value;
      case LT:
        return c.getColumn().getName() + " < " + value;
      case LTE:
        return c.getColumn().getName() + " <= " + value;
      case IS_NULL:
        return c.getColumn().getName() + " IS NULL";
      case IS_NOT_NULL:
        return c.getColumn().getName() + " IS NOT NULL";
      case LIKE:
        return c.getColumn().getName() + " LIKE " + value;
      case NOT_LIKE:
        return c.getColumn().getName() + " NOT LIKE " + value;
      default:
        return c.getColumn().getName() + " " + c.getOperator();
    }
  }

  private static String boundText(Collection<String> columns, Source s) {
    StringBuilder sb = new StringBuilder("{");
    for (String column : columns) {
      sb.append(sb.length() == 1 ? "" : ", ").append(column).append('=');
      Expression param = s.lookupParams().get(column);
      if (param != null) {
        sb.append(param);
        continue;
      }
      for (ConditionalExpression c : s.lookup().pushed) {
        if (c.getOperator() == ConditionalExpression.Operator.EQ
            && c.getColumn().getName().equals(column)) {
          sb.append(valueText(c.getColumn()));
        }
      }
    }
    return sb.append('}').toString();
  }

  /** A ScalarDB column holding an in-memory value, converted to the column's type. */
  static Column<?> columnFromValue(Target t, String name, @Nullable Object value) {
    ColumnType declared = t.columns.type(existing(t.metadata, name));
    if (declared.convertsValues()) {
      return declared.exact(name, value); // a predicate's value: never rounded
    }
    if (value == null) {
      return columnFromText(t, name, null);
    }
    DataType type = t.metadata.getColumnDataType(existing(t.metadata, name));
    if (type == DataType.TIMESTAMP && value instanceof java.time.Instant) {
      // PostgreSQL compares a timestamp with a timestamptz in the session's zone
      value = LocalDateTime.ofInstant((java.time.Instant) value, Evaluator.zone());
    } else if (type == DataType.TIMESTAMPTZ && value instanceof LocalDateTime) {
      value = Evaluator.instant((LocalDateTime) value, Evaluator.zone());
    }
    String text;
    if (value instanceof java.nio.ByteBuffer) {
      java.nio.ByteBuffer b = ((java.nio.ByteBuffer) value).duplicate();
      byte[] bytes = new byte[b.remaining()];
      b.get(bytes);
      text = "\\x" + Evaluator.hex(bytes);
    } else {
      text =
          value instanceof BigDecimal
              ? ((BigDecimal) value).toPlainString()
              // an array keeps PostgreSQL's text form, {a,b}, not Java's [a, b]
              : value instanceof List ? Evaluator.text(value) : String.valueOf(value);
    }
    return columnFromText(t, name, text);
  }

  private static String keyText(Key key) {
    StringBuilder sb = new StringBuilder("{");
    for (Column<?> c : key.getColumns()) {
      sb.append(sb.length() == 1 ? "" : ", ").append(c.getName()).append('=').append(valueText(c));
    }
    return sb.append('}').toString();
  }

  private static String valueText(Column<?> c) {
    Object v = c.getValueAsObject();
    return v instanceof String ? "'" + v + "'" : String.valueOf(v);
  }

  /** Conditions in a stable order (ScalarDB keeps them in sets): AND within, OR between. */
  private static void conditionsText(
      StringBuilder b, Set<com.scalar.db.api.Selection.Conjunction> conjunctions) {
    if (conjunctions.isEmpty()) {
      return;
    }
    List<String> alternatives = new ArrayList<>();
    for (com.scalar.db.api.Selection.Conjunction conjunction : conjunctions) {
      List<String> conditions = new ArrayList<>();
      for (ConditionalExpression c : conjunction.getConditions()) {
        conditions.add(conditionText(c));
      }
      Collections.sort(conditions);
      alternatives.add(String.join(" AND ", conditions));
    }
    Collections.sort(alternatives);
    b.append(" conditions=[").append(String.join(" OR ", alternatives)).append(']');
  }

  private static void projectionsText(StringBuilder b, List<String> projections) {
    b.append(" projections=").append(projections.isEmpty() ? "[all]" : projections.toString());
  }

  /**
   * Parses a SQL statement into a plan.
   *
   * @param sql a SQL statement
   * @return the plan
   * @throws IllegalArgumentException if the SQL is invalid or unsupported
   * @throws ExecutionException if the table metadata cannot be retrieved
   */
  public Plan parse(String sql) throws ExecutionException {
    return parse(sql, Collections.emptyList());
  }

  /**
   * Plans a statement whose {@code $n} placeholders take {@code parameters} (1-based): null, a
   * Long, a Double, a Boolean or a String, each treated exactly like the corresponding literal. The
   * parsed statement is cached by its text, so a prepared statement is parsed once and only planned
   * per execution.
   */
  public Plan parse(String sql, List<Object> parameters) throws ExecutionException {
    catalog.invalidateIfStale(DDL_GENERATION.get());
    catalog.setAcls(ACLS.matcher(sql).find());
    ctes = Collections.emptyMap();
    int needed = parameterCount(sql);
    if (needed > parameters.size()) {
      throw new IllegalArgumentException("Missing value for parameter $" + needed);
    }
    BOUND.set(parameters);
    EMBEDDED.set(false);
    PARAMETER_OIDS.set(new HashMap<>());
    try {
      Plan plan = plan(sql);
      plan.cacheable = !EMBEDDED.get() && plan.reusable();
      plan.parameterOids = PARAMETER_OIDS.get();
      plan.useColumnMaps(columnMaps);
      return plan;
    } finally {
      BOUND.remove();
      PARAMETER_OIDS.remove();
    }
  }

  // The parameters of the statement being planned on this thread. Planning helpers that look at
  // literals are static (the optimizer shares them), so they read the values from here; every
  // Plan created meanwhile takes a snapshot for its execution, which may happen later.
  static final ThreadLocal<List<Object>> BOUND = ThreadLocal.withInitial(Collections::emptyList);

  // Set when a placeholder's value became part of the plan being made (a key, a LIMIT, a VALUES
  // row): such a plan serves this execution only, see Plan#isCacheable
  private static final ThreadLocal<Boolean> EMBEDDED = ThreadLocal.withInitial(() -> false);
  // the types of the statement's $n inferred while planning it (Plan.parameterOid)
  private static final ThreadLocal<Map<Integer, Integer>> PARAMETER_OIDS =
      ThreadLocal.withInitial(HashMap::new);

  /**
   * Records the type of each {@code $n} in {@code e} from where it stands, as PostgreSQL infers it:
   * a cast's type, or the type of what it is compared with, added to, or tested IN or BETWEEN.
   * Subqueries are left to their own binding, where their columns resolve.
   */
  private static void inferParameters(
      @Nullable Expression e, java.util.function.ToIntFunction<Expression> typeOf) {
    if (e == null) {
      return;
    }
    e.accept(
        new net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(CastExpression cast, S context) {
            note(cast.getLeftExpression(), cast);
            return super.visit(cast, context);
          }

          @Override
          protected <S> Void visitBinaryExpression(BinaryExpression b, S context) {
            // only where the operand types are the same in PostgreSQL: a comparison, LIKE, ||, and
            // arithmetic between numbers; not timestamp + $1 (an interval) or jsonb ? $1 (text)
            boolean sameType =
                b instanceof ComparisonOperator
                    || b instanceof LikeExpression
                    || b instanceof net.sf.jsqlparser.expression.operators.arithmetic.Concat;
            boolean arithmetic =
                b instanceof net.sf.jsqlparser.expression.operators.arithmetic.Addition
                    || b instanceof net.sf.jsqlparser.expression.operators.arithmetic.Subtraction
                    || b instanceof net.sf.jsqlparser.expression.operators.arithmetic.Multiplication
                    || b instanceof net.sf.jsqlparser.expression.operators.arithmetic.Division
                    || b instanceof net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
            if (sameType
                || (arithmetic && isNumericOid(typeOf.applyAsInt(b.getRightExpression())))) {
              note(b.getLeftExpression(), b.getRightExpression());
            }
            if (sameType
                || (arithmetic && isNumericOid(typeOf.applyAsInt(b.getLeftExpression())))) {
              note(b.getRightExpression(), b.getLeftExpression());
            }
            Function q = arrayQuantifier(b.getRightExpression());
            if (q != null && q.getParameters() != null && q.getParameters().size() == 1) {
              // x = ANY($1): an array of x's type, as pgx sends a Go slice
              Expression p = unwrap(q.getParameters().get(0));
              int element =
                  unwrap(b.getLeftExpression()) instanceof JdbcParameter
                      ? 0
                      : typeOf.applyAsInt(b.getLeftExpression());
              if (p instanceof JdbcParameter && element != 0) {
                PARAMETER_OIDS
                    .get()
                    .putIfAbsent(((JdbcParameter) p).getIndex(), (int) Catalog.arrayOid(element));
              }
            }
            return super.visitBinaryExpression(b, context);
          }

          @Override
          public <S> Void visit(InExpression in, S context) {
            if (in.getRightExpression() instanceof ExpressionList) {
              for (Object item : (ExpressionList<?>) in.getRightExpression()) {
                note(in.getLeftExpression(), (Expression) item);
                note((Expression) item, in.getLeftExpression());
              }
            }
            return super.visit(in, context);
          }

          @Override
          public <S> Void visit(
              net.sf.jsqlparser.expression.operators.relational.Between between, S context) {
            note(between.getLeftExpression(), between.getBetweenExpressionStart());
            note(between.getBetweenExpressionStart(), between.getLeftExpression());
            note(between.getBetweenExpressionEnd(), between.getLeftExpression());
            return super.visit(between, context);
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }

          private void note(Expression parameter, Expression typed) {
            Expression p = unwrap(parameter);
            if (p instanceof JdbcParameter && !(unwrap(typed) instanceof JdbcParameter)) {
              int oid = typeOf.applyAsInt(typed);
              if (oid != 0) {
                PARAMETER_OIDS.get().putIfAbsent(((JdbcParameter) p).getIndex(), oid);
              }
            }
          }
        });
  }

  private static boolean isNumericOid(int oid) {
    return oid == 20 || oid == 21 || oid == 23 || oid == 700 || oid == 701 || oid == 1700;
  }

  /** LIMIT $n, OFFSET $n: bigint, as in PostgreSQL. */
  private static void inferCount(@Nullable Expression e) {
    Expression p = e == null ? null : unwrap(e);
    if (p instanceof JdbcParameter) {
      PARAMETER_OIDS.get().putIfAbsent(((JdbcParameter) p).getIndex(), 20);
    }
  }

  /** As {@link #inferParameters}, where columns are those of the written table {@code t}. */
  private static void inferParameters(@Nullable Expression e, Target t) {
    inferParameters(
        e,
        x ->
            isColumn(x) && t.metadata.getColumnNames().contains(columnName(x))
                ? columnOid(t, columnName(x))
                : inferOid(x, Collections.emptyList()));
  }

  /** {@code column = $n} and {@code VALUES ($n)}: the placeholder takes the column's type. */
  private static void inferAssigned(Target t, String column, Expression value) {
    Expression p = unwrap(value);
    if (p instanceof JdbcParameter && t.metadata.getColumnNames().contains(column)) {
      PARAMETER_OIDS.get().putIfAbsent(((JdbcParameter) p).getIndex(), columnOid(t, column));
    }
    inferParameters(value, t);
  }

  /** The type OID a SELECT of column {@code name} of {@code t} reports. */
  static int columnOid(Target t, String name) {
    ColumnType declared = t.columns.type(name);
    return declared.encoded()
        ? (int) declared.oid()
        : (int) Catalog.typeOid(t.metadata.getColumnDataType(name));
  }

  /** The highest {@code $n} placeholder number in the statement, outside quotes. */
  static int parameterCount(String sql) {
    int max = 0;
    int i = 0;
    while (i < sql.length()) {
      int after = skipQuoted(sql, i);
      if (after != i) {
        i = after; // a string, quoted name, comment or dollar-quoted body: no placeholders
        continue;
      }
      char c = sql.charAt(i);
      if (c == '$' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1))) {
        int j = i + 1;
        while (j < sql.length() && Character.isDigit(sql.charAt(j))) {
          j++;
        }
        max = Math.max(max, Integer.parseInt(sql.substring(i + 1, j)));
        i = j;
        continue;
      }
      i++;
    }
    return max;
  }

  /**
   * If a string literal, quoted identifier, comment or dollar-quoted string starts at {@code i},
   * the index just past it (or the end of the text when it is not closed); else {@code i}. The one
   * lexer behind statement splitting and placeholder counting (todo 020): '' and "" double their
   * quote, E'' strings escape with a backslash, -- runs to the end of the line, comments nest, and
   * $tag$ ... $tag$ quotes anything ($1 is a placeholder, not a tag).
   */
  static int skipQuoted(String sql, int i) {
    int n = sql.length();
    char c = sql.charAt(i);
    if (c == '\'' || c == '"') {
      boolean escapes =
          c == '\''
              && i > 0
              && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
              && (i < 2
                  || !Character.isLetterOrDigit(sql.charAt(i - 2)) && sql.charAt(i - 2) != '_');
      int j = i + 1;
      while (j < n) {
        char d = sql.charAt(j);
        if (escapes && d == '\\') {
          j += 2;
          continue;
        }
        if (d == c) {
          if (j + 1 < n && sql.charAt(j + 1) == c) {
            j += 2; // a doubled quote inside
            continue;
          }
          return j + 1;
        }
        j++;
      }
      return n;
    }
    if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
      int end = sql.indexOf('\n', i);
      return end < 0 ? n : end + 1;
    }
    if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
      int depth = 1;
      int j = i + 2;
      while (j < n && depth > 0) {
        if (sql.startsWith("/*", j)) {
          depth++;
          j += 2;
        } else if (sql.startsWith("*/", j)) {
          depth--;
          j += 2;
        } else {
          j++;
        }
      }
      return j;
    }
    if (c == '$' && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)))) {
      int j = i + 1;
      while (j < n
          && (Character.isLetter(sql.charAt(j))
              || sql.charAt(j) == '_'
              || (j > i + 1 && Character.isDigit(sql.charAt(j))))) {
        j++;
      }
      if (j < n && sql.charAt(j) == '$') {
        String tag = sql.substring(i, j + 1);
        int end = sql.indexOf(tag, j + 1);
        return end < 0 ? n : end + tag.length();
      }
    }
    return i;
  }

  /** Records that {@code e} is evaluated while planning, so a placeholder in it is embedded. */
  static void embed(Expression e) {
    if (containsParameter(e)) {
      EMBEDDED.set(true);
    }
  }

  /**
   * An expression with no column reference, subquery, aggregate or window call: its value is the
   * same for every row, so a condition comparing a column with it can be pushed to ScalarDB once
   * the plan is opened, when {@code now()} and the placeholders have their values.
   */
  static boolean isConstant(Expression e) {
    return columnRefs(e).isEmpty()
        && subselects(e).isEmpty()
        && !hasAggregate(e)
        && windowCalls(e).isEmpty();
  }

  /**
   * {@code col = ANY(array)} as {@code col IN (...)} and {@code col <> ALL(array)} as {@code NOT
   * IN}, when the array's elements are known while planning: an ARRAY[...] constructor, an array
   * literal, or a bound parameter. The optimizer then reads keyed columns by lookup.
   */
  static Expression rewriteArrayAny(Expression c) {
    if (!(c instanceof EqualsTo) && !(c instanceof NotEqualsTo)) {
      return c;
    }
    net.sf.jsqlparser.expression.BinaryExpression cmp =
        (net.sf.jsqlparser.expression.BinaryExpression) c;
    Function q = arrayQuantifier(cmp.getRightExpression());
    if (q == null) {
      return c;
    }
    boolean all = q.getName().equalsIgnoreCase("ALL");
    if ((c instanceof EqualsTo) == all) {
      return c; // = ANY and <> ALL are memberships; the rest stays an in-memory quantifier
    }
    List<Expression> items = arrayItems(q.getParameters().get(0));
    if (items == null) {
      return c;
    }
    if (items.isEmpty()) {
      // nothing is in an empty array, and everything is not in it
      return new EqualsTo(new LongValue(1), new LongValue(all ? 1 : 0));
    }
    net.sf.jsqlparser.expression.operators.relational.InExpression in =
        new net.sf.jsqlparser.expression.operators.relational.InExpression(
            cmp.getLeftExpression(), new ExpressionList<>(items));
    in.setNot(all);
    return in;
  }

  /** ANY, SOME or ALL over a single argument that is not a subquery, as the parser gives it. */
  @Nullable static Function arrayQuantifier(Expression e) {
    if (!(e instanceof Function)) {
      return null;
    }
    Function f = (Function) e;
    String name = f.getName().toUpperCase(Locale.ROOT);
    boolean quantifier = name.equals("ANY") || name.equals("SOME") || name.equals("ALL");
    return quantifier && f.getParameters() != null && f.getParameters().size() == 1 ? f : null;
  }

  /** The array's elements as literals, or null when they are not known while planning. */
  @Nullable private static List<Expression> arrayItems(Expression a) {
    if (a instanceof net.sf.jsqlparser.expression.ArrayConstructor) {
      return new ArrayList<Expression>(
          ((net.sf.jsqlparser.expression.ArrayConstructor) a).getExpressions());
    }
    if (a instanceof CastExpression) {
      return arrayItems(((CastExpression) a).getLeftExpression());
    }
    Object value;
    if (a instanceof StringValue) {
      value = stringLiteral((StringValue) a);
    } else if (a instanceof JdbcParameter) {
      value = boundRaw((JdbcParameter) a);
      if (value instanceof Evaluator.Untyped) {
        value = ((Evaluator.Untyped) value).text;
      }
    } else {
      return null;
    }
    List<Expression> items = new ArrayList<>();
    if (value == null) {
      items.add(new NullValue()); // x = ANY(NULL) is unknown, so it matches nothing
      return items;
    }
    for (Object v :
        value instanceof List ? (List<?>) value : parseArrayText(String.valueOf(value))) {
      items.add(literalOf(v));
    }
    return items;
  }

  /** A value as the literal expression that evaluates back to it. */
  static Expression literalOf(@Nullable Object v) {
    if (v == null) {
      return new NullValue();
    }
    if (v instanceof Integer || v instanceof Long || v instanceof Short) {
      return new LongValue(((Number) v).longValue());
    }
    if (v instanceof Number) {
      return new DoubleValue(Evaluator.text(v));
    }
    if (v instanceof Boolean) {
      return new net.sf.jsqlparser.schema.Column((Boolean) v ? "TRUE" : "FALSE");
    }
    String s = Evaluator.text(v);
    return new StringValue("'" + s.replace("'", "''") + "'");
  }

  /**
   * PostgreSQL's array text, such as {@code {1,2}} or {@code {"a b",NULL}}: the elements as text
   * (null for NULL). Nested arrays are not supported.
   */
  static List<Object> parseArrayText(String text) {
    String s = text.trim();
    if (!s.startsWith("{") || !s.endsWith("}")) {
      throw new IllegalArgumentException("malformed array literal: \"" + text + "\"");
    }
    List<Object> out = new ArrayList<>();
    if (s.substring(1, s.length() - 1).isBlank()) {
      return out; // {} or { }
    }
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    boolean wasQuoted = false;
    boolean any = false;
    for (int i = 1; i < s.length() - 1; i++) {
      char c = s.charAt(i);
      if (quoted) {
        if (c == '\\' && i + 2 < s.length()) {
          cur.append(s.charAt(++i));
        } else if (c == '"') {
          quoted = false;
        } else {
          cur.append(c);
        }
        continue;
      }
      if (c == '\\' && i + 2 < s.length()) {
        cur.append(s.charAt(++i)); // an escaped character outside quotes: {a\,b} is one element
        wasQuoted = true;
      } else if (c == '}') {
        throw new PgException.Argument("22P02", "malformed array literal: \"" + text + "\"");
      } else if (c == '"') {
        quoted = true;
        wasQuoted = true;
      } else if (c == '{') {
        throw new PgException.Argument("0A000", "Nested arrays are not supported: " + text);
      } else if (c == ',') {
        out.add(arrayElement(cur, wasQuoted, text));
        cur.setLength(0);
        wasQuoted = false;
      } else {
        cur.append(c);
      }
      any = true;
    }
    if (quoted) {
      throw new PgException.Argument("22P02", "malformed array literal: \"" + text + "\"");
    }
    if (any) {
      out.add(arrayElement(cur, wasQuoted, text));
    }
    return out;
  }

  /** An element of array text: NULL unless quoted; an empty one unquoted is malformed ({a,,b}). */
  @Nullable private static String arrayElement(StringBuilder cur, boolean quoted, String text) {
    String e = cur.toString().trim();
    if (!quoted && e.isEmpty()) {
      throw new PgException.Argument("22P02", "malformed array literal: \"" + text + "\"");
    }
    return quoted || !e.equalsIgnoreCase("NULL") ? e : null;
  }

  static boolean containsParameter(Expression e) {
    boolean[] found = new boolean[1];
    e.accept(
        new net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(JdbcParameter parameter, S context) {
            found[0] = true;
            return null;
          }
        });
    return found[0];
  }

  /** The value bound to a placeholder of the statement being planned. */
  @Nullable private static Object bound(JdbcParameter p) {
    EMBEDDED.set(true);
    return Evaluator.typedValue(boundRaw(p));
  }

  /** As {@link #bound}, but an untyped parameter keeps both forms (see Evaluator.Untyped). */
  @Nullable private static Object boundRaw(JdbcParameter p) {
    EMBEDDED.set(true);
    List<Object> parameters = BOUND.get();
    int i = p.getIndex() - 1;
    if (i < 0 || i >= parameters.size()) {
      throw new IllegalArgumentException("Missing value for parameter $" + p.getIndex());
    }
    return parameters.get(i);
  }

  /** Evaluates a constant expression while planning; a placeholder in it is embedded. */
  @Nullable private static Object evalLiteral(Expression e) {
    embed(e);
    return Evaluator.eval(e, Collections.<Map<String, Object>>emptyList(), literalContext());
  }

  /** An evaluation context for expressions evaluated while planning (literals, VALUES rows). */
  static Evaluator.Context literalContext() {
    return new Evaluator.Context(null, Collections.emptyMap(), null, BOUND.get());
  }

  /** NULL, written as a literal or bound to a placeholder. */
  static boolean isNull(Expression e) {
    return e instanceof NullValue
        || (e instanceof JdbcParameter && bound((JdbcParameter) e) == null);
  }

  // Parsed statements by text, most recently used last; a prepared statement hits every time
  private final Map<String, Statement> statementCache =
      new LinkedHashMap<String, Statement>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Statement> eldest) {
          return size() > 256;
        }
      };

  private Statement parseStatement(String sql) {
    Statement statement = statementCache.get(sql);
    if (statement != null) {
      return statement;
    }
    try {
      // The parser is called directly: CCJSqlParserUtil.parse runs it on an executor with a
      // timeout, which more than doubles the cost of parsing a short statement
      // Complex parsing (more lookahead) is only tried when the simple grammar fails: it costs
      // an order of magnitude more on INSERT ... VALUES
      try {
        statement = parser(sql).withAllowComplexParsing(false).Statement();
      } catch (net.sf.jsqlparser.parser.ParseException e) {
        statement = parser(sql).withAllowComplexParsing(true).Statement();
      }
    } catch (net.sf.jsqlparser.parser.ParseException
        | net.sf.jsqlparser.parser.TokenMgrException e) {
      throw new IllegalArgumentException("Invalid SQL: " + sql, e);
    }
    statementCache.put(sql, statement);
    return statement;
  }

  /** Rewrites PostgreSQL syntax that JSQLParser does not accept. */
  static String normalize(String sql) {
    return parenthesisedJsonOperands(
        outsideLiterals(
            escapeStringQuotes(sql),
            code ->
                typedEmptyArrays(code)
                    // no table inheritance here, so FROM ONLY t and FROM t* are just t
                    .replaceAll(
                        "(?i)\\b(FROM|JOIN|UPDATE|INTO|USING|TABLE|,)(\\s+)ONLY\\s+(?=[A-Za-z_\"])",
                        "$1$2")
                    .replaceAll(
                        "(?i)(\\b(?:FROM|JOIN|,)\\s+[A-Za-z_]\\w*+(?:\\.[A-Za-z_]\\w*+)?)\\s*\\*(?=\\s*(?:,|\\)|$|\\b(?:AS|ON|USING|JOIN|INNER|LEFT|RIGHT|FULL|CROSS|NATURAL|WHERE|GROUP|HAVING|ORDER|LIMIT|OFFSET|FETCH|UNION|INTERSECT|EXCEPT|WINDOW|FOR)\\b))",
                        "$1")
                    .replaceAll("(?i)OPERATOR\\(pg_catalog\\.([^)]+)\\)", "$1")
                    // JSQLParser drops NATURAL when INNER follows it (a cross join, todo 070)
                    .replaceAll("(?i)\\bNATURAL\\s+INNER\\s+JOIN\\b", "NATURAL JOIN")
                    // a reserved word as a column alias, as Sequelize writes AS primary, AS unique
                    .replaceAll(
                        "(?i)\\bAS\\s+(primary|unique|order|group|user|limit|offset|default|check|references|constraint|desc|asc|key|index|exists|using|on|join|all|any|some|case|end|is|in|not|null|like|between|distinct|having|window|over)\\b(?!\")",
                        "AS \"$1\"")));
  }

  // ARRAY[] not cast where it is written
  private static final Pattern UNTYPED_EMPTY_ARRAY =
      Pattern.compile("(?i)(?<!CAST\\s{0,99}\\(\\s{0,99})\\bARRAY\\s*+\\[\\s*+\\](?!\\s*::)");

  /** The code, checked that an ARRAY[] has a type from a cast around it, as PostgreSQL needs. */
  private static String typedEmptyArrays(String code) {
    if (UNTYPED_EMPTY_ARRAY.matcher(code).find()) {
      throw new PgException.Argument("42P18", "cannot determine type of empty array");
    }
    return code;
  }

  /**
   * Applies {@code rewrite} to the SQL outside string literals, quoted identifiers, dollar quotes
   * and comments, which are copied as they are: a rewrite of the SQL's syntax must not change the
   * data it carries ('x AS order' stays as written). String literals have their quotes doubled by
   * then ({@link #escapeStringQuotes}).
   */
  static String outsideLiterals(String sql, java.util.function.UnaryOperator<String> rewrite) {
    StringBuilder out = new StringBuilder(sql.length());
    int code = 0; // start of the code not yet rewritten
    int literalEnd = -1; // the end of a '...' followed so far only by whitespace and comments
    boolean dollar = false; // that literal was dollar-quoted, which never continues
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      int end = -1;
      if (c == '\'' && literalEnd >= 0) {
        // as PostgreSQL's lexer: adjacent literals join only across a newline and -- comments
        String gap = sql.substring(literalEnd, i);
        if (dollar || gap.contains("/*") || gap.indexOf('\n') < 0 && gap.indexOf('\r') < 0) {
          throw new PgException.Argument("42601", "syntax error at or near \"'\"");
        }
      }
      if (c == '\'' || c == '"') {
        end = sql.indexOf(c, i + 1);
        while (end > 0 && end + 1 < sql.length() && sql.charAt(end + 1) == c) {
          end = sql.indexOf(c, end + 2); // a doubled quote
        }
        end = end < 0 ? sql.length() : end + 1;
      } else if (sql.startsWith("--", i)) {
        end = sql.indexOf('\n', i);
        end = end < 0 ? sql.length() : end;
      } else if (sql.startsWith("/*", i)) {
        end = sql.indexOf("*/", i + 2);
        end = end < 0 ? sql.length() : end + 2;
      } else if (c == '$') {
        Matcher tag = DOLLAR_TAG.matcher(sql).region(i, sql.length());
        if (tag.lookingAt() && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)))) {
          end = sql.indexOf(tag.group(), i + tag.group().length());
          end = end < 0 ? sql.length() : end + tag.group().length();
        }
      }
      if (end < 0) {
        literalEnd = isSpace(c) ? literalEnd : -1;
        i++;
        continue;
      }
      if (c == '\'' || c == '$') {
        literalEnd = end;
        dollar = c == '$';
      } else if (c == '"') {
        literalEnd = -1;
      }
      out.append(rewrite.apply(sql.substring(code, i))).append(sql, i, end);
      code = end;
      i = end;
    }
    return out.append(rewrite.apply(sql.substring(code))).toString();
  }

  private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_]\\w*)?\\$");

  // words before a parenthesis that make it an operand, not a function's argument list
  private static final Set<String> NOT_FUNCTIONS =
      Set.of(
          "AND",
          "OR",
          "NOT",
          "SELECT",
          "WHERE",
          "ON",
          "WHEN",
          "THEN",
          "ELSE",
          "BY",
          "HAVING",
          "RETURNING",
          "SET",
          "DISTINCT",
          "IS",
          "IN",
          "LIKE",
          "BETWEEN",
          "AS",
          "VALUES");

  /**
   * JSQLParser reads {@code (j -> 'a') -> 'b'} as a lambda, which PostgreSQL does not have: a
   * parenthesised operand of {@code ->} is written {@code COALESCE((...))}, the same value in a
   * form it parses as a JSON chain. Literals, quoted names and comments are skipped; the string
   * literals have their quotes doubled by then (see {@link #escapeStringQuotes}).
   */
  static String parenthesisedJsonOperands(String sql) {
    if (!sql.contains("->")) {
      return sql;
    }
    Deque<Integer> open = new ArrayDeque<>();
    Map<Integer, Integer> wrapped = new HashMap<>(); // ( index -> its ) index
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"') {
        int end = sql.indexOf(c, i + 1);
        while (end > 0 && end + 1 < sql.length() && sql.charAt(end + 1) == c) {
          end = sql.indexOf(c, end + 2); // a doubled quote
        }
        i = end < 0 ? sql.length() : end;
      } else if (sql.startsWith("--", i) || sql.startsWith("/*", i)) {
        int end = sql.charAt(i) == '-' ? sql.indexOf('\n', i) : sql.indexOf("*/", i);
        i = end < 0 ? sql.length() : end;
      } else if (c == '(') {
        open.push(i);
      } else if (c == ')' && !open.isEmpty()) {
        int start = open.pop();
        int next = i + 1;
        while (next < sql.length() && Character.isWhitespace(sql.charAt(next))) {
          next++;
        }
        if (sql.startsWith("->", next) && !sql.startsWith("->>", next) && !called(sql, start)) {
          wrapped.put(start, i);
        }
      }
    }
    if (wrapped.isEmpty()) {
      return sql;
    }
    Set<Integer> ends = new HashSet<>(wrapped.values());
    StringBuilder out = new StringBuilder(sql.length() + wrapped.size() * 12);
    for (int i = 0; i < sql.length(); i++) {
      if (wrapped.containsKey(i)) {
        out.append("COALESCE(");
      }
      out.append(sql.charAt(i));
      if (ends.contains(i)) {
        out.append(')');
      }
    }
    return out.toString();
  }

  /** Whether the parenthesis at {@code open} is a call's argument list: f(x), "f"(x), a[1](x). */
  private static boolean called(String sql, int open) {
    int end = open;
    while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
      end--;
    }
    int start = end;
    while (start > 0
        && (Character.isLetterOrDigit(sql.charAt(start - 1)) || sql.charAt(start - 1) == '_')) {
      start--;
    }
    if (start == end) {
      return end > 0 && (sql.charAt(end - 1) == '"' || sql.charAt(end - 1) == ']');
    }
    return !NOT_FUNCTIONS.contains(sql.substring(start, end).toUpperCase(Locale.ROOT));
  }

  /**
   * JSQLParser ends a string at any quote, even one after a backslash. Inside an {@code E'...'}
   * literal a backslash-quote becomes the doubled quote the parser understands; {@link
   * #stringLiteral} then reads it back as one quote. Other literals and comments are copied as is.
   */
  static String escapeStringQuotes(String sql) {
    if (!sql.contains("\\'")) {
      return sql;
    }
    StringBuilder out = new StringBuilder(sql.length());
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '-' && sql.startsWith("--", i)) {
        int end = sql.indexOf('\n', i);
        end = end < 0 ? sql.length() : end;
        out.append(sql, i, end);
        i = end;
        continue;
      }
      if (c == '/' && sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i + 2);
        end = end < 0 ? sql.length() : end + 2;
        out.append(sql, i, end);
        i = end;
        continue;
      }
      boolean escapeString =
          (c == 'E' || c == 'e')
              && i + 1 < sql.length()
              && sql.charAt(i + 1) == '\''
              && (i == 0
                  || !Character.isLetterOrDigit(sql.charAt(i - 1)) && sql.charAt(i - 1) != '_');
      if (c != '\'' && !escapeString) {
        out.append(c);
        i++;
        continue;
      }
      if (escapeString) {
        out.append(c);
        i++;
      }
      out.append('\'');
      i++;
      while (i < sql.length()) {
        char d = sql.charAt(i);
        if (escapeString && d == '\\' && i + 1 < sql.length()) {
          char n = sql.charAt(i + 1);
          out.append(n == '\'' ? "''" : "" + d + n);
          i += 2;
          continue;
        }
        if (d == '\'') {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
            out.append("''");
            i += 2;
            continue;
          }
          out.append(d);
          i++;
          break; // the literal ends
        }
        out.append(d);
        i++;
      }
    }
    return out.toString();
  }

  private static final Pattern CREATE_SCHEMA =
      Pattern.compile("(?is)CREATE\\s+SCHEMA\\s+(IF\\s+NOT\\s+EXISTS\\s+)?\"?(\\w+)\"?");

  private static net.sf.jsqlparser.parser.CCJSqlParser parser(String sql) {
    // the PostgreSQL dialect preset adds dollar-quoted strings ($$...$$, $tag$...$tag$)
    return new net.sf.jsqlparser.parser.CCJSqlParser(
            new net.sf.jsqlparser.parser.StringProvider(normalize(sql)))
        .withDialect(net.sf.jsqlparser.parser.AbstractJSqlParser.Dialect.POSTGRESQL);
  }

  /**
   * Strips one-element parentheses (JSQLParser parses {@code (e)} as a one-element parenthesized
   * list) and, after checking that it is byte order, a COLLATE clause.
   */
  static Expression unwrap(Expression e) {
    while (true) {
      if (e instanceof ParenthesedExpressionList
          && ((ParenthesedExpressionList<?>) e).size() == 1) {
        e = ((ParenthesedExpressionList<?>) e).get(0);
      } else if (e instanceof CollateExpression) {
        Evaluator.checkCollation(((CollateExpression) e).getCollate());
        e = ((CollateExpression) e).getLeftExpression();
        Expression collated = unwrap(e);
        if (collated instanceof ComparisonOperator
            || collated instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression
            || collated instanceof net.sf.jsqlparser.expression.operators.conditional.OrExpression
            || collated instanceof net.sf.jsqlparser.expression.NotExpression
            || collated instanceof IsNullExpression
            || collated instanceof InExpression
            || collated instanceof LikeExpression
            || collated
                instanceof net.sf.jsqlparser.expression.operators.relational.ExistsExpression
            || collated instanceof BooleanValue
            || collated instanceof LongValue
            || collated instanceof DoubleValue) {
          // only text has a collation: (a = b) COLLATE "C" is 42804 in PostgreSQL
          throw new PgException.Argument(
              "42804",
              "collations are not supported by type "
                  + (collated instanceof LongValue
                      ? "integer"
                      : collated instanceof DoubleValue ? "numeric" : "boolean"));
        }
      } else if (e instanceof RowGetExpression
          && ((RowGetExpression) e).getExpression() instanceof CollateExpression) {
        // JSQLParser reads COLLATE pg_catalog.default after ~ as (x COLLATE pg_catalog).default
        CollateExpression c = (CollateExpression) ((RowGetExpression) e).getExpression();
        Evaluator.checkCollation(c.getCollate() + "." + ((RowGetExpression) e).getColumnName());
        e = c.getLeftExpression();
      } else {
        return e;
      }
    }
  }

  private static final Pattern EXPLAIN =
      Pattern.compile("(?is)EXPLAIN\\s+((?:ANALYZE|\\(\\s*ANALYZE\\s*\\))\\s+)?(.+)");
  private static final Pattern VACUUM = Pattern.compile("(?is)VACUUM\\b.*");
  private volatile boolean coordinatorDropAllowed; // a Cluster superuser, or the operator's

  void setCoordinatorDropAllowed(boolean allowed) {
    coordinatorDropAllowed = allowed;
  }

  // Users, roles and privileges live in Cluster, behind an Authenticator: without one, AuthAdmin's
  // methods throw UnsupportedOperationException
  private volatile boolean userManagement;

  void setUserManagement(boolean enabled) {
    userManagement = enabled;
    catalog.clusterUsers = enabled;
    catalog.invalidate();
  }

  private static final Pattern ACLS =
      Pattern.compile("(?i)\\b(relacl|nspacl|has_table_privilege)\\b");

  private static final Pattern COORDINATOR_TABLES =
      Pattern.compile("(?is)(CREATE|DROP)\\s+COORDINATOR\\s+TABLES(\\s+IF\\s+(NOT\\s+)?EXISTS)?");
  private static final Pattern FRONTEND_TABLES =
      Pattern.compile("(?is)CREATE\\s+FRONTEND\\s+METADATA\\s+TABLES(\\s+IF\\s+NOT\\s+EXISTS)?");

  /**
   * The statement without surrounding whitespace and one final {@code ;}, which is what the
   * statement patterns match. Matching that tail inside a pattern instead ({@code \s*;?\s*$} after
   * a lazy group) backtracks cubically on a long run of whitespace, so a short statement padded
   * with a few thousand spaces could take minutes. Whitespace is what {@code \s} matches.
   */
  static String trimStatement(String sql) {
    int end = sql.length();
    while (end > 0 && isSpace(sql.charAt(end - 1))) {
      end--;
    }
    if (end > 0 && sql.charAt(end - 1) == ';') {
      end--;
      while (end > 0 && isSpace(sql.charAt(end - 1))) {
        end--;
      }
    }
    int start = 0;
    while (start < end && isSpace(sql.charAt(start))) {
      start++;
    }
    return sql.substring(start, end);
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
  }

  private Plan plan(String sql) throws ExecutionException {
    // Statements JSQLParser does not accept, matched on the trimmed text (see trimStatement)
    String trimmed = trimStatement(sql);
    if (Dcl.matches(trimmed)) {
      if (!userManagement) {
        throw new PgException.Argument(
            "0A000",
            "users, roles and privileges need ScalarDB Cluster with scalar.db.postgres.auth set");
      }
      return Dcl.plan(this, admin, trimmed);
    }
    Matcher schema = CREATE_SCHEMA.matcher(trimmed);
    if (schema.matches()) {
      boolean ifNotExists = schema.group(1) != null;
      String name = userNamespace(checkedName(namespace(schema.group(2)), false));
      return Plan.ddl(() -> admin.createNamespace(name, ifNotExists), "CREATE SCHEMA");
    }
    Matcher explain = EXPLAIN.matcher(trimmed);
    if (explain.matches()) {
      Plan inner = plan(explain.group(2));
      if (inner.ddl != null) {
        throw new PgException.Argument("0A000", "EXPLAIN is not supported for DDL");
      }
      if (explain.group(1) != null) {
        return Plan.analyze(inner);
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (String line : explain(inner)) {
        rows.add(Collections.singletonMap("QUERY PLAN", line));
      }
      return Plan.constant(Collections.singletonList("QUERY PLAN"), rows, "EXPLAIN");
    }
    if (VACUUM.matcher(trimmed).matches()) {
      return Plan.ddl(() -> {}, "VACUUM"); // nothing to vacuum; tools run it after bulk loads
    }
    if (FRONTEND_TABLES.matcher(trimmed).matches()) {
      // creates only what is missing, so IF NOT EXISTS changes nothing; with authentication the
      // rows hold the service user's new password, filled in when the statement runs
      if (!typeStore.authenticated()) {
        return Plan.ddl(typeStore::create, "CREATE FRONTEND METADATA TABLES");
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      Plan plan =
          Plan.constant(
              Arrays.asList("property", "value"), rows, "CREATE FRONTEND METADATA TABLES");
      plan.ddl =
          () -> {
            rows.clear();
            rows.addAll(typeStore.create());
          };
      return plan;
    }
    Matcher coordinator = COORDINATOR_TABLES.matcher(trimmed);
    if (coordinator.matches()) {
      boolean create = coordinator.group(1).equalsIgnoreCase("CREATE");
      if (!create && !coordinatorDropAllowed) {
        // Every application's transactions depend on these tables: without Cluster's privileges
        // to decide, only an operator's setting lets a client drop them. Checked here, where the
        // drop is planned, so no spelling of the statement gets past it
        throw new PgException.Argument(
            "42501",
            "permission denied: DROP COORDINATOR TABLES needs a Cluster superuser, or"
                + " scalar.db.postgres.allow_drop_coordinator_tables=true");
      }
      return Plan.ddl(
          () -> {
            if (create) {
              admin.createCoordinatorTables(true);
            } else {
              admin.dropCoordinatorTables(true);
            }
          },
          (create ? "CREATE" : "DROP") + " COORDINATOR TABLES");
    }
    Statement statement;
    statement = parseStatement(sql);
    if (statement instanceof CreateTable) {
      return createTable((CreateTable) statement);
    }
    if (statement instanceof CreateIndex) {
      return createIndex((CreateIndex) statement);
    }
    if (statement instanceof Drop) {
      return drop((Drop) statement);
    }
    if (statement instanceof Truncate) {
      Table table = ((Truncate) statement).getTable();
      String namespace = namespaceOf(table);
      String name = checkedCharacters(unquote(table.getName()));
      return Plan.ddl(() -> admin.truncateTable(namespace, name), "TRUNCATE TABLE");
    }
    if (statement instanceof Alter) {
      return alter((Alter) statement);
    }
    if (statement instanceof Select) {
      return select((Select) statement, null);
    }
    if (statement instanceof net.sf.jsqlparser.statement.insert.Insert) {
      net.sf.jsqlparser.statement.insert.Insert insert =
          (net.sf.jsqlparser.statement.insert.Insert) statement;
      if (insert.getColumns() != null) {
        Set<String> seen = new HashSet<>();
        for (Expression c : insert.getColumns()) {
          if (!seen.add(assignedColumn(c))) {
            throw new PgException.Argument(
                "42701", "column \"" + columnName(c) + "\" specified more than once");
          }
        }
      }
      if (insert.getConflictAction() != null
          && insert.getConflictAction().getUpdateSets() != null) {
        checkSingleAssignments(insert.getConflictAction().getUpdateSets());
        if (insert.getTable().getAlias() == null
            && unquote(insert.getTable().getName()).equalsIgnoreCase("excluded")
            && insert
                .getConflictAction()
                .toString()
                .toLowerCase(Locale.ROOT)
                .contains("excluded.")) {
          // the target and the proposed row are both "excluded"; PostgreSQL wants an alias
          throw new PgException.Argument("42P09", "table reference \"excluded\" is ambiguous");
        }
      }
      Map<String, Cte> saved = pushCtes(insert.getWithItemsList());
      try {
        inferWriteParameters(insert);
        // Literal rows without RETURNING map straight to Inserts, or to Upserts for the plain
        // "ON CONFLICT DO UPDATE SET c = EXCLUDED.c" form; anything else is driven by a read
        boolean direct =
            insert.getSelect() instanceof Values
                && insert.getReturningClause() == null
                && (insert.getConflictAction() == null || upsertable(insert));
        if (!direct) {
          return insertRows(insert);
        }
        Target t = target(insert.getTable());
        return Plan.deferred(ctx -> insert(insert, t, ctx));
      } finally {
        ctes = saved;
      }
    }
    if (statement instanceof net.sf.jsqlparser.statement.update.Update) {
      net.sf.jsqlparser.statement.update.Update update =
          (net.sf.jsqlparser.statement.update.Update) statement;
      checkSingleAssignments(update.getUpdateSets());
      Map<String, Cte> saved = pushCtes(update.getWithItemsList());
      try {
        Target written = target(update.getTable());
        for (UpdateSet set : update.getUpdateSets()) {
          for (int i = 0; i < set.getColumns().size(); i++) {
            inferAssigned(written, columnName(set.getColumn(i)), set.getValue(i));
          }
        }
        inferParameters(update.getWhere(), written);
        if (update.getFromItem() != null || !keyed(update) || update.getReturningClause() != null) {
          return updateByScan(update);
        }
        Target t = target(update.getTable());
        checkTextAgainstNumber(update.getWhere(), t); // WHERE name = 1 is 42883, as in a SELECT
        for (UpdateSet set : update.getUpdateSets()) {
          for (int i = 0; i < set.getColumns().size(); i++) {
            if (keyColumns(t).contains(columnName(set.getColumn(i)))) {
              throw new IllegalArgumentException(
                  "A primary key column cannot be updated: " + columnName(set.getColumn(i)));
            }
          }
        }
        return Plan.deferred(ctx -> listOf(update(update, t, ctx))).keyed("UPDATE");
      } finally {
        ctes = saved;
      }
    }
    if (statement instanceof net.sf.jsqlparser.statement.delete.Delete) {
      net.sf.jsqlparser.statement.delete.Delete delete =
          (net.sf.jsqlparser.statement.delete.Delete) statement;
      Map<String, Cte> saved = pushCtes(delete.getWithItemsList());
      try {
        inferParameters(delete.getWhere(), target(delete.getTable()));
        if (!usingItems(delete).isEmpty()
            || !keyed(delete)
            || delete.getReturningClause() != null) {
          return deleteByScan(delete);
        }
        Target t = target(delete.getTable());
        checkTextAgainstNumber(delete.getWhere(), t);
        return Plan.deferred(ctx -> listOf(delete(delete, t, ctx))).keyed("DELETE");
      } finally {
        ctes = saved;
      }
    }
    if (statement instanceof net.sf.jsqlparser.statement.analyze.Analyze) {
      return Plan.ddl(() -> {}, "ANALYZE"); // no planner statistics to collect
    }
    throw new PgException.Argument("0A000", "Unsupported statement: " + sql);
  }

  // ---- SELECT planning ----

  // ---- DDL ----

  private String namespaceOf(Table table) {
    return userNamespace(checkedCharacters(namespace(table.getSchemaName())));
  }

  /** Refuses DDL in the frontend's own namespace, which only its setup command manages. */
  private static String userNamespace(String namespace) {
    if (namespace.equals(TypeStore.NAMESPACE)) {
      throw new PgException.Argument(
          "42501",
          "permission denied: schema \""
              + namespace
              + "\" holds the frontend's metadata; CREATE FRONTEND METADATA TABLES manages it");
    }
    return namespace;
  }

  /** The user the client connected as, for {@code current_user}. */
  void setUser(String user) {
    catalog.user = user;
  }

  String sessionUser() {
    return catalog.user;
  }

  // PostgreSQL's search_path: where an unqualified table is looked up, and where DDL creates;
  // "$user" and public both mean the connected namespace
  private List<String> searchPath = Arrays.asList("$user", "public");

  /** Sets the session's search_path from its text, such as {@code "$user", public, orm}. */
  void setSearchPath(String value) {
    List<String> entries = new ArrayList<>();
    for (String entry : value.split(",")) {
      String e = entry.trim();
      if (!e.isEmpty()) {
        entries.add(e.startsWith("\"") ? unquote(e) : e.toLowerCase(Locale.ROOT));
      }
    }
    searchPath = entries; // SET search_path = '': no schema at all, only pg_catalog
    catalog.searchPath = searchNamespaces();
  }

  /** The namespaces search_path names, in order, without repeats. */
  private List<String> searchNamespaces() {
    List<String> out = new ArrayList<>();
    for (String entry : searchPath) {
      String namespace = entry.equals("$user") || entry.equals("public") ? defaultNamespace : entry;
      if (!out.contains(namespace)) {
        out.add(namespace);
      }
    }
    return out;
  }

  /**
   * The namespace a schema name means: {@code public} is the connected namespace, and none means
   * the first namespace of search_path (for DDL, the first one that exists).
   */
  String namespace(@Nullable String schema) {
    if (schema != null) {
      return unquote(schema).equals("public") ? defaultNamespace : unquote(schema);
    }
    for (String candidate : searchNamespaces()) {
      try {
        if (candidate.equals(defaultNamespace) || admin.namespaceExists(candidate)) {
          return candidate;
        }
      } catch (ExecutionException e) {
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalArgumentException("no schema has been selected to create in");
  }

  /**
   * CREATE TABLE: the first PRIMARY KEY column is the partition key and the rest are clustering
   * keys, unless {@code WITH (partition_key = 'a, b', clustering_key = 'c DESC, d')} says
   * otherwise. {@code WITH (encrypted = 'e, f')} encrypts those columns in ScalarDB Cluster.
   */
  private Plan createTable(CreateTable create) {
    if (create.getCreateOptionsStrings() != null
        && String.join(" ", create.getCreateOptionsStrings())
            .toUpperCase(Locale.ROOT)
            .matches(".*\\bTEMP(ORARY)?\\b.*")) {
      throw new PgException.Argument(
          "0A000", "Temporary tables are not supported: a table created here would be permanent");
    }
    String namespace = namespaceOf(create.getTable());
    String name = checkedName(unquote(create.getTable().getName()), false);
    if (create.getColumnDefinitions() == null) {
      throw new PgException.Argument(
          "0A000",
          "Unsupported CREATE TABLE form (a ScalarDB table needs explicit columns and a primary key): "
              + create);
    }
    if (create.getColumnDefinitions().size() > 1600) {
      throw new PgException.Argument("54011", "tables can have at most 1600 columns");
    }
    TableMetadata.Builder builder = TableMetadata.newBuilder();
    List<String> columns = new ArrayList<>();
    Map<String, ColumnType> declaredTypes = new LinkedHashMap<>();
    List<String> primaryKey = new ArrayList<>();
    List<String> notices = new ArrayList<>();
    for (ColumnDefinition definition : create.getColumnDefinitions()) {
      String column = checkedName(unquote(definition.getColumnName()), true);
      String specs =
          definition.getColumnSpecs() == null
              ? ""
              : String.join(" ", definition.getColumnSpecs()).toUpperCase(Locale.ROOT);
      boolean keyColumn = specs.contains("PRIMARY KEY");
      notices.addAll(
          unenforced(column, declaredText(definition.getColDataType()), specs, keyColumn));
      for (String earlier : columns) {
        if (earlier.equals(column)) {
          throw new PgException.Argument(
              "42701", "column \"" + column + "\" specified more than once"); // duplicate_column
        }
        if (earlier.equalsIgnoreCase(column)) {
          throw new PgException.Argument(
              "0A000",
              "Columns differing only in case are not supported: \""
                  + earlier
                  + "\" and \""
                  + column
                  + "\" (the JDBC backend reads columns by name, case-insensitively)");
        }
      }
      columns.add(column);
      ColumnType declared = recorded(declaredText(definition.getColDataType()));
      declaredTypes.put(column, declared);
      builder.addColumn(
          column,
          declared.encoded()
              ? declared.storage
              : columnType(declaredText(definition.getColDataType())));
      if (keyColumn) {
        primaryKey.add(column);
      }
    }
    if (create.getIndexes() != null) {
      for (Index index : create.getIndexes()) {
        if ("PRIMARY KEY".equalsIgnoreCase(index.getType())) {
          for (String column : index.getColumnsNames()) {
            primaryKey.add(unquote(column));
          }
        } else {
          notices.add("constraint " + index + " is not enforced by ScalarDB");
        }
      }
    }
    for (String column : primaryKey) {
      notices.removeIf(n -> n.startsWith("NOT NULL on column \"" + column + "\""));
    }
    if (create.getPartitioning() != null
        || create.getPartitionOf() != null
        || create.getTableOptionsStrings() != null
            && String.join(" ", create.getTableOptionsStrings())
                .toUpperCase(Locale.ROOT)
                .matches("(?s).*\\bPARTITION\\s+BY\\b.*")) {
      // a partitioned table routes rows to its partitions and rejects the rest; a plain table would
      // take every row
      throw new PgException.Argument(
          "0A000", "Unsupported: partitioned tables (PARTITION BY, PARTITION OF)");
    }
    Map<String, String> options = new HashMap<>();
    if (create.getTableOptionsStrings() != null) {
      Matcher m =
          Pattern.compile("(?i)(\\w+)\\s*=\\s*'([^']*)'")
              .matcher(String.join(" ", create.getTableOptionsStrings()));
      while (m.find()) {
        options.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
      }
    }
    List<String> partitionKey =
        options.containsKey("partition_key")
            ? Arrays.asList(options.get("partition_key").split("\\s*,\\s*", -1))
            : primaryKey.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.singletonList(primaryKey.get(0));
    List<String> clusteringKey =
        options.containsKey("clustering_key")
            ? Arrays.asList(options.get("clustering_key").split("\\s*,\\s*", -1))
            : primaryKey.isEmpty()
                ? Collections.<String>emptyList()
                : primaryKey.subList(1, primaryKey.size());
    if (partitionKey.isEmpty()) {
      throw new IllegalArgumentException(
          "CREATE TABLE needs a PRIMARY KEY or WITH (partition_key = '...'): " + create);
    }
    for (String column : partitionKey) {
      if (!columns.contains(column.trim())) {
        throw new IllegalArgumentException("Unknown partition key column: " + column);
      }
      java.util.Objects.requireNonNull(declaredTypes.get(column.trim()))
          .checkKeyable(column.trim(), maxScaledPrecision);
      builder.addPartitionKey(column.trim());
    }
    for (String spec : clusteringKey) {
      String[] parts = spec.trim().split("\\s+", -1);
      if (!columns.contains(parts[0])) {
        throw new IllegalArgumentException("Unknown clustering key column: " + parts[0]);
      }
      java.util.Objects.requireNonNull(declaredTypes.get(parts[0]))
          .checkKeyable(parts[0], maxScaledPrecision);
      boolean desc = parts.length > 1 && parts[1].equalsIgnoreCase("DESC");
      builder.addClusteringKey(parts[0], desc ? Scan.Ordering.Order.DESC : Scan.Ordering.Order.ASC);
    }
    TableMetadata metadata = builder.build();
    List<String> stored = new ArrayList<>(metadata.getPartitionKeyNames());
    stored.addAll(metadata.getClusteringKeyNames());
    columns.stream().filter(c -> !stored.contains(c)).forEach(stored::add);
    if (!stored.equals(columns)) {
      // ScalarDB keeps the key columns first, so SELECT *, INSERT without a column list and
      // attnum would see that order, not the declared one: a value would land in another column
      throw new PgException.Argument(
          "0A000",
          "Unsupported column order: declare the key columns first, in key order, as ScalarDB"
              + " stores them: ("
              + String.join(", ", stored)
              + ")");
    }
    if (options.containsKey("encrypted")) {
      // ScalarDB Cluster's encryption: the values are stored as ciphertext, so a key column, which
      // the storage looks rows up by, cannot be one
      TableMetadata.Builder encrypted = TableMetadata.newBuilder(metadata);
      for (String column : options.get("encrypted").split("\\s*,\\s*", -1)) {
        column = column.trim();
        if (!columns.contains(column)) {
          throw new PgException.Argument(
              "42703", "column \"" + column + "\" named in encrypted does not exist");
        }
        if (metadata.getPartitionKeyNames().contains(column)
            || metadata.getClusteringKeyNames().contains(column)) {
          throw new PgException.Argument(
              "0A000", "key column \"" + column + "\" cannot be encrypted");
        }
        encrypted.addColumn(column, metadata.getColumnDataType(column), true);
      }
      metadata = encrypted.build();
    }
    TableMetadata table = metadata;
    boolean ifNotExists = create.isIfNotExists();
    Map<String, ColumnType> recorded = new LinkedHashMap<>(declaredTypes);
    recorded.values().removeIf(t -> !t.encoded());
    Plan plan =
        Plan.ddl(
            () -> {
              if (admin.tableExists(namespace, name)) {
                admin.createTable(namespace, name, table, ifNotExists); // as ScalarDB answers
                return;
              }
              withTypes(
                  namespace,
                  name,
                  recorded,
                  null,
                  () -> admin.createTable(namespace, name, table, false),
                  () -> admin.tableExists(namespace, name),
                  ifNotExists);
            },
            "CREATE TABLE");
    plan.notices.addAll(notices);
    return plan;
  }

  /**
   * What a column definition asks for that ScalarDB cannot keep: constraints other than the key,
   * defaults, and type modifiers. Accepted with a NOTICE rather than refused, since every ORM
   * writes them, but never silently.
   */
  private static List<String> unenforced(
      String column, String declared, String specs, boolean keyColumn) {
    List<String> out = new ArrayList<>();
    String base = declared.toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "").trim();
    boolean withArgs = declared.contains("(");
    long[] args = typeArguments(declared);
    String col = "column \"" + column + "\"";
    Matcher collate = Pattern.compile("COLLATE\\s+(\\S+)").matcher(specs);
    if (collate.find()) {
      Evaluator.checkColumnCollation(collate.group(1));
      specs = collate.replaceFirst(""); // COLLATE "default" is not a DEFAULT
    }
    if (specs.contains("NOT NULL") && !keyColumn) {
      out.add("NOT NULL on " + col + " is not enforced by ScalarDB");
    }
    for (String constraint : new String[] {"UNIQUE", "CHECK", "REFERENCES"}) {
      if (specs.contains(constraint)) {
        out.add(constraint + " on " + col + " is not enforced by ScalarDB");
      }
    }
    if (specs.matches(".*GENERATED\\s+(ALWAYS|BY\\s+DEFAULT)\\s+AS\\s+IDENTITY.*")) {
      out.add(
          "identity on " + col + " does not generate values here: inserts must supply the column");
    } else if (specs.contains("DEFAULT") && !specs.matches(".*DEFAULT\\s+NULL(\\s.*|$)")) {
      // DEFAULT NULL is what an omitted column reads anyway; any other default would be lost
      throw new PgException.Argument(
          "0A000",
          "DEFAULT on " + col + " is not supported: the column would read NULL when it is omitted");
    }
    switch (base) {
      case "char":
      case "character":
      case "bpchar":
        checkLength("char", args);
        if (base.equals("bpchar") && !withArgs) {
          // char(n), character and char are kept as declared (ColumnType); bpchar has no length
          out.add(
              "CHAR on "
                  + col
                  + " is stored as text: no blank padding, so comparisons do not ignore trailing"
                  + " spaces");
        }
        break;
      case "varchar":
      case "character varying":
        checkLength("varchar", args); // varchar(n) is kept as declared (ColumnType)
        break;
      case "numeric":
      case "decimal":
      case "dec":
        checkNumericModifiers(args); // kept exactly (ColumnType)
        break;
      case "timestamp":
      case "timestamp without time zone":
      case "timestamptz":
      case "timestamp with time zone":
        if (args.length == 0 || args[0] > 3) {
          out.add(
              "TIMESTAMP on "
                  + col
                  + " is stored with millisecond precision (ScalarDB's): a finer value is"
                  + " refused; declare it TIMESTAMP(3) to have it rounded, as PostgreSQL rounds");
        }
        break;
      case "serial":
      case "bigserial":
      case "smallserial":
        out.add(
            "serial on " + col + " does not generate values here: inserts must supply the column");
        break;
      default:
        break;
    }
    return out;
  }

  /**
   * The ScalarDB type of a declared column: {@code float(p)} up to 24 is real, as in PostgreSQL.
   */
  static DataType columnType(String declared) {
    Matcher floatP = Pattern.compile("(?i)^\\s*float\\s*\\(\\s*(\\d+)\\s*\\)").matcher(declared);
    if (floatP.find() && Integer.parseInt(floatP.group(1)) <= 24) {
      return DataType.FLOAT;
    }
    return dataType(declared);
  }

  // Index names given at CREATE INDEX, namespace.name -> {table, column}, for this process's life
  private static final Map<String, String[]> INDEX_NAMES =
      new java.util.concurrent.ConcurrentHashMap<>();

  private Plan createIndex(CreateIndex create) {
    String namespace = namespaceOf(create.getTable());
    String name = checkedCharacters(unquote(create.getTable().getName()));
    List<String> columns = create.getIndex().getColumnsNames();
    if (columns.size() != 1) {
      throw new IllegalArgumentException("ScalarDB indexes cover one column: " + create);
    }
    if (!columns.get(0).matches("\"?[\\w$ ]+\"?")) {
      throw new PgException.Argument(
          "0A000", "Expression indexes are not supported: " + columns.get(0));
    }
    String column = checkedCharacters(unquote(columns.get(0)));
    String generated = name + "_" + column + "_idx";
    String given =
        create.getIndex().getName() == null
            ? null
            : checkedCharacters(unquote(create.getIndex().getName()));
    Plan plan =
        Plan.ddl(
            () -> {
              ColumnMap map = columnMap(namespace, name);
              if (map != null && map.metadata().getColumnNames().contains(column)) {
                map.type(column).checkKeyable(column, maxScaledPrecision);
              }
              admin.createIndex(namespace, name, column, false);
              if (given != null && !given.equals(generated)) {
                INDEX_NAMES.put(namespace + "." + given, new String[] {name, column});
              }
            },
            "CREATE INDEX");
    if (given != null && !given.equals(generated)) {
      // ScalarDB has no index names (core-todo item 6): DROP INDEX by this name works only until
      // the frontend restarts (todo 015)
      plan.notices.add(
          "index \""
              + given
              + "\" is named "
              + generated
              + " in ScalarDB: DROP INDEX "
              + given
              + " works until the frontend restarts, DROP INDEX "
              + generated
              + " always");
    }
    if ("UNIQUE".equalsIgnoreCase(create.getIndex().getType())) {
      plan.notices.add(
          "UNIQUE on index "
              + unquote(create.getIndex().getName())
              + " is not enforced by ScalarDB");
    }
    if (create.getWhere() != null) {
      plan.notices.add(
          "the WHERE of index "
              + unquote(create.getIndex().getName())
              + " is ignored: the index covers every row");
    }
    return plan;
  }

  private Plan drop(Drop drop) {
    String type = drop.getType().toUpperCase(Locale.ROOT);
    Table target = drop.getName();
    boolean ifExists = drop.isIfExists();
    switch (type) {
      case "TABLE":
        {
          String namespace = namespaceOf(target);
          String name = checkedCharacters(unquote(target.getName()));
          return Plan.ddl(
              () -> {
                Map<String, String> rows = typeStore.tokens(namespace, name);
                admin.dropTable(namespace, name, ifExists);
                afterDdl(() -> typeStore.deleteUnchanged(namespace, name, rows));
              },
              "DROP TABLE");
        }
      case "SCHEMA":
        {
          String namespace = userNamespace(checkedCharacters(unquote(target.getName())));
          if (namespace.equals("public")) {
            throw new IllegalArgumentException(
                "cannot drop schema public: it is the connected database's namespace; drop it by name");
          }
          boolean cascade =
              drop.getParameters() != null
                  && String.join(" ", drop.getParameters())
                      .toUpperCase(Locale.ROOT)
                      .contains("CASCADE");
          return Plan.ddl(
              () -> {
                if (cascade && admin.namespaceExists(namespace)) {
                  for (String table : new TreeSet<>(admin.getNamespaceTableNames(namespace))) {
                    Map<String, String> rows = typeStore.tokens(namespace, table);
                    admin.dropTable(namespace, table, true);
                    afterDdl(() -> typeStore.deleteUnchanged(namespace, table, rows));
                  }
                }
                admin.dropNamespace(namespace, ifExists);
              },
              "DROP SCHEMA");
        }
      case "INDEX":
        {
          // Indexes are named <table>_<column>_idx in the catalog; find the one meant
          String namespace = namespaceOf(target);
          String indexName = unquote(target.getName());
          return Plan.ddl(
              () -> {
                String[] named = INDEX_NAMES.remove(namespace + "." + indexName);
                if (named != null && admin.indexExists(namespace, named[0], named[1])) {
                  admin.dropIndex(namespace, named[0], named[1]); // the name given at CREATE INDEX
                  return;
                }
                for (String table : admin.getNamespaceTableNames(namespace)) {
                  TableMetadata metadata = admin.getTableMetadata(namespace, table);
                  if (metadata == null) {
                    continue;
                  }
                  for (String column : metadata.getSecondaryIndexNames()) {
                    if (indexName.equals(table + "_" + column + "_idx")) {
                      admin.dropIndex(namespace, table, column);
                      return;
                    }
                  }
                }
                if (!ifExists) {
                  throw new PgException.Argument(
                      "42704", "index \"" + indexName + "\" does not exist");
                }
              },
              "DROP INDEX");
        }
      default:
        throw new PgException.Argument("0A000", "Unsupported statement: " + drop);
    }
  }

  private Plan alter(Alter alter) {
    String namespace = namespaceOf(alter.getTable());
    String name = checkedCharacters(unquote(alter.getTable().getName()));
    List<Ddl> steps = new ArrayList<>();
    List<String> notices = new ArrayList<>();
    for (AlterExpression expression : alter.getAlterExpressions()) {
      if (expression.getOperation() == AlterOperation.ADD
          && expression.getColDataTypeList() != null) {
        for (AlterExpression.ColumnDataType c : expression.getColDataTypeList()) {
          String column = checkedName(unquote(c.getColumnName()), true);
          // the same rules as CREATE TABLE: a non-NULL DEFAULT is refused (existing rows would read
          // NULL), other constraints and type modifiers are announced (todo 014)
          String specs =
              c.getColumnSpecs() == null
                  ? ""
                  : String.join(" ", c.getColumnSpecs()).toUpperCase(Locale.ROOT);
          notices.addAll(unenforced(column, declaredText(c.getColDataType()), specs, false));
          ColumnType declared = recorded(declaredText(c.getColDataType()));
          DataType type =
              declared.encoded() ? declared.storage : columnType(declaredText(c.getColDataType()));
          Map<String, ColumnType> rows =
              declared.encoded()
                  ? Collections.singletonMap(column, declared)
                  : Collections.<String, ColumnType>emptyMap();
          steps.add(
              () -> {
                checkNewColumn(freshColumnMap(namespace, name), column, null);
                withTypes(
                    namespace,
                    name,
                    rows,
                    Collections.singleton(column),
                    () -> admin.addNewColumnToTable(namespace, name, column, type),
                    () -> {
                      TableMetadata now = admin.getTableMetadata(namespace, name);
                      return now != null && now.getColumnNames().contains(column);
                    },
                    false);
              });
        }
      } else if (expression.getOperation() == AlterOperation.RENAME
          && expression.getColOldName() != null) {
        String oldName = checkedCharacters(unquote(expression.getColOldName()));
        String newName = checkedName(unquote(expression.getColumnName()), true);
        steps.add(
            () -> {
              ColumnMap columns = freshColumnMap(namespace, name);
              Map<String, ColumnType> rows = Collections.emptyMap();
              if (columns != null) {
                if (!columns.metadata().getColumnNames().contains(oldName)) {
                  throw new PgException.Argument(
                      "42703", "column \"" + oldName + "\" does not exist");
                }
                checkNewColumn(columns, newName, oldName);
                columns.checkWritable(oldName); // a type it cannot read, it cannot carry over
                ColumnType type = columns.type(oldName);
                if (type.encoded()) {
                  rows = Collections.singletonMap(newName, type); // the type follows the column
                }
              }
              Map<String, String> old = new HashMap<>(typeStore.tokens(namespace, name));
              old.keySet().retainAll(Collections.singleton(oldName));
              withTypes(
                  namespace,
                  name,
                  rows,
                  Collections.singleton(newName),
                  () -> admin.renameColumn(namespace, name, oldName, newName),
                  () -> false,
                  false);
              afterDdl(() -> typeStore.deleteUnchanged(namespace, name, old));
              // an index named at CREATE INDEX follows its column
              INDEX_NAMES.replaceAll(
                  (key, indexed) ->
                      key.startsWith(namespace + ".")
                              && indexed[0].equals(name)
                              && indexed[1].equals(oldName)
                          ? new String[] {name, newName}
                          : indexed);
            });
      } else {
        throw new PgException.Argument(
            "0A000",
            "Unsupported ALTER TABLE (ScalarDB supports ADD COLUMN and RENAME COLUMN): " + alter);
      }
    }
    Plan plan =
        Plan.ddl(
            () -> {
              for (Ddl step : steps) {
                step.run();
              }
            },
            "ALTER TABLE");
    plan.notices.addAll(notices);
    return plan;
  }

  /**
   * A column definition's type as written, modifiers included: the parser keeps them in the type
   * name for some types (DECIMAL(5,1)) and apart for others (DEC(5,1)).
   */
  static String declaredText(net.sf.jsqlparser.statement.create.table.ColDataType type) {
    String name = type.getDataType();
    if (type.getArrayData() != null && !type.getArrayData().isEmpty()
        || name.matches("(?i).*\\bARRAY\\b.*")) {
      // ScalarDB has no array column; the element type alone would take array values as scalars
      throw new PgException.Argument(
          "0A000", "Unsupported column type: " + type + " (ScalarDB has no array column type)");
    }
    List<String> args = type.getArgumentsStringList();
    return args == null || args.isEmpty() || name.contains("(")
        ? name
        : name + "(" + String.join(",", args) + ")";
  }

  static long[] typeArguments(String declared) {
    Matcher m = Pattern.compile("\\(([^)]*)\\)").matcher(declared);
    if (!m.find()) {
      return new long[0];
    }
    return Arrays.stream(m.group(1).split(","))
        .map(String::trim)
        .filter(a -> a.matches("-?\\d+"))
        .mapToLong(Long::parseLong)
        .toArray();
  }

  /**
   * The declared type of a new column as this frontend records it: numeric, char(n), varchar(n) and
   * smallint in its metadata table; anything else as its native ScalarDB type.
   */
  private ColumnType recorded(String declaredType) {
    ColumnType declared = ColumnType.declared(declaredType, maxScaledPrecision);
    return declared.encoded() ? declared : ColumnType.of(columnType(declaredType));
  }

  /** x IS NOT NULL, also written x NOTNULL (which JSQLParser marks apart from NOT). */
  static boolean isNotNull(IsNullExpression n) {
    return n.isNot() || n.isUseNotNull();
  }

  /** Whether a table or column exists now, asked after a DDL that would have created it failed. */
  private interface Exists {
    boolean now() throws ExecutionException;
  }

  /**
   * Runs a DDL that creates columns with the types they record: the rows first, then the DDL, so a
   * failure in between leaves an inert row rather than a column without its type (TypeStore). Rows
   * left under the names in {@code scope} (the whole table when null) are removed. If the DDL
   * fails, this statement's rows are taken back; if what it would have created exists after all
   * (another session's), the rows that were there are put back, and with {@code ifNotExists} that
   * is no error. Without types and without the metadata table, the DDL simply runs.
   */
  private void withTypes(
      String namespace,
      String table,
      Map<String, ColumnType> types,
      @Nullable Set<String> scope,
      Ddl ddl,
      Exists exists,
      boolean ifNotExists)
      throws ExecutionException {
    if (types.isEmpty() && !typeStore.exists()) {
      ddl.run();
      return;
    }
    if (!types.isEmpty()) {
      typeStore.requireTable();
    }
    String token = java.util.UUID.randomUUID().toString();
    Map<String, Result> previous = typeStore.write(namespace, table, types, scope, token);
    try {
      ddl.run();
    } catch (ExecutionException | RuntimeException e) {
      boolean other;
      try {
        other = exists.now();
        typeStore.undo(namespace, table, types.keySet(), token, previous, other);
      } catch (ExecutionException | RuntimeException undo) {
        e.addSuppressed(undo); // the DDL's own failure is the one to report
        throw e;
      }
      if (other && ifNotExists) {
        return;
      }
      throw e;
    }
  }

  /**
   * Deletes rows a DDL that has taken effect no longer needs. A failure only leaves inert rows, so
   * it is logged rather than reported as the statement's: the DDL itself succeeded.
   */
  private static void afterDdl(Ddl cleanup) {
    try {
      cleanup.run();
    } catch (ExecutionException | RuntimeException e) {
      logger.warn("Leftover rows in the frontend's metadata table after DDL: {}", e.toString());
    }
  }

  /** A table's column map from ScalarDB now, not the session's cache; null if there is none. */
  @Nullable private ColumnMap freshColumnMap(String namespace, String table) throws ExecutionException {
    TableMetadata stored = admin.getTableMetadata(namespace, table);
    return stored == null
        ? null
        : ColumnMap.of(namespace, table, stored, typeStore.read(namespace, table, stored));
  }

  /**
   * Refuses a new column name that a column already has (42701), compared without regard to case,
   * as the JDBC backends compare column names.
   */
  private static void checkNewColumn(
      @Nullable ColumnMap columns, String newName, @Nullable String renamed) {
    if (columns == null) {
      return;
    }
    for (String existing : columns.metadata().getColumnNames()) {
      // a rename may change only the case of its own column's name, not keep the name
      if (existing.equalsIgnoreCase(newName)
          && (!existing.equals(renamed) || existing.equals(newName))) {
        throw new PgException.Argument(
            "42701",
            "column \"" + newName + "\" of relation \"" + columns.table + "\" already exists");
      }
    }
  }

  /** The precision and scale PostgreSQL accepts for NUMERIC(p, s), with its messages. */
  static void checkNumericModifiers(long[] args) {
    if (args.length > 0 && (args[0] < 1 || args[0] > 1000)) {
      throw new PgException.Argument(
          "22023", "NUMERIC precision " + args[0] + " must be between 1 and 1000");
    }
    if (args.length > 1 && (args[1] < -1000 || args[1] > 1000)) {
      throw new PgException.Argument(
          "22023", "NUMERIC scale " + args[1] + " must be between -1000 and 1000");
    }
  }

  /** The length PostgreSQL accepts for CHAR(n) and VARCHAR(n), with its messages. */
  static void checkLength(String type, long[] args) {
    if (args.length > 0 && args[0] < 1) {
      throw new PgException.Argument("22023", "length for type " + type + " must be at least 1");
    }
    if (args.length > 0 && args[0] > 10485760) {
      throw new PgException.Argument(
          "22023", "length for type " + type + " cannot exceed 10485760");
    }
  }

  /**
   * A new table, schema or column name. PostgreSQL truncates a name over 63 bytes with a NOTICE,
   * but ScalarDB keeps the full name in its metadata and reads columns back by it, so the table
   * could not be read; and on PostgreSQL each column also gets a {@code before_} copy, which leaves
   * 56 bytes for the column's own name. Such names are refused rather than truncated.
   */
  static String checkedName(String name, boolean column) {
    checkedCharacters(name);
    if (!column && name.indexOf('.') >= 0) {
      // namespace.table names a table in ScalarDB's metadata and in the frontend's: a dot would
      // make "a.b".c and a."b.c" the same table there
      throw new PgException.Argument(
          "42602", "invalid name \"" + name + "\": a table or schema name may not contain a dot");
    }
    int max = column ? 63 - "before_".length() : 63;
    if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > max) {
      throw new PgException.Argument(
          "42622",
          "identifier \""
              + name
              + "\" is too long: at most "
              + max
              + " bytes"
              + (column
                  ? ", since ScalarDB stores a before_ copy of each column within PostgreSQL's 63"
                  : ""));
    }
    return name;
  }

  /**
   * A name the frontend passes to ScalarDB DDL. PostgreSQL allows any character in a quoted
   * identifier, but ScalarDB's JDBC storage writes names into SQL text without escaping them (its
   * metadata INSERT formats them inside {@code '...'}, and each engine encloses them in {@code "},
   * a backquote or brackets), so a quote, backslash, bracket, semicolon or control character could
   * end the literal or the identifier and run SQL of the caller's choosing (todo 053). Such names
   * are refused.
   */
  static String checkedCharacters(String name) {
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '\''
          || c == '"'
          || c == '`'
          || c == '['
          || c == ']'
          || c == '\\'
          || c == ';'
          || Character.isISOControl(c)) {
        throw new PgException.Argument(
            "42602", // invalid_name
            "invalid name \""
                + name.replaceAll("\\p{Cntrl}", "?")
                + "\": quotes, backslashes, brackets, semicolons and control characters are not"
                + " allowed in names, since ScalarDB writes names into SQL unescaped");
      }
    }
    return name;
  }

  /** The ScalarDB type for a PostgreSQL type name; length and precision arguments are ignored. */
  static DataType dataType(String type) {
    switch (type.toLowerCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)", "").trim()) {
      case "int":
      case "integer":
      case "int4":
      case "smallint":
      case "int2":
      case "serial":
        return DataType.INT;
      case "bigint":
      case "int8":
      case "bigserial":
        return DataType.BIGINT;
      case "text":
      case "varchar":
      case "character varying":
      case "char":
      case "character":
      case "bpchar":
      case "string":
        return DataType.TEXT;
      case "real":
      case "float4":
        return DataType.FLOAT;
      case "double precision":
      case "double":
      case "float8":
      case "float":
      case "numeric":
      case "decimal":
      case "dec":
        return DataType.DOUBLE;
      case "boolean":
      case "bool":
        return DataType.BOOLEAN;
      case "bytea":
      case "blob":
        return DataType.BLOB;
      case "uuid":
      case "json":
      case "jsonb":
        return DataType.TEXT; // text-backed: a uuid is validated on cast, JSON has no operators yet
      case "date":
        return DataType.DATE;
      case "time":
      case "time without time zone":
        return DataType.TIME;
      case "timestamp":
      case "timestamp without time zone":
        return DataType.TIMESTAMP;
      case "timestamptz":
      case "timestamp with time zone":
        return DataType.TIMESTAMPTZ;
      default:
        throw new PgException.Argument("0A000", "Unsupported column type: " + type);
    }
  }

  /** Resolves column references to sources, looking outward through enclosing queries. */
  private static final class Scope {
    final List<LogicalPlan.Source> sources;
    @Nullable final Scope outer;
    boolean correlated; // a column was resolved in an enclosing scope

    Scope(List<LogicalPlan.Source> sources, @Nullable Scope outer) {
      this.sources = sources;
      this.outer = outer;
    }

    LogicalPlan.Source source(String qualifier) {
      for (LogicalPlan.Source s : sources) {
        if (s.qualifier.equals(qualifier)) {
          return s;
        }
      }
      throw new IllegalArgumentException("Unknown table or alias: " + qualifier);
    }

    boolean isLocal(LogicalPlan.Source s) {
      return sources.contains(s);
    }

    /** Resolves the column, qualifies it in place, and records it as needed from its source. */
    LogicalPlan.Source resolve(net.sf.jsqlparser.schema.Column c) {
      String name = columnName(c);
      LogicalPlan.Source found = null;
      if (c.getTable() != null) {
        String qualifier = unquote(c.getTable().getName());
        for (LogicalPlan.Source s : sources) {
          if (s.qualifier.equals(qualifier)) {
            if (!s.columns.contains(name)) {
              throw new PgException.Argument("42703", "Unknown column: " + qualifier + "." + name);
            }
            found = s;
          }
        }
      } else {
        for (LogicalPlan.Source s : sources) {
          // a USING column binds to the left side; the right side's copy is merged into it
          if (s.columns.contains(name) && !s.merged.containsKey(name)) {
            if (found != null) {
              throw new PgException.Argument("42702", "Ambiguous column: " + name);
            }
            found = s;
          }
        }
        if (found != null && found.mergedOuter.contains(name)) {
          // ponytail: the left copy is NULL where only the right side has the row; refused rather
          // than read wrong (todo 072)
          throw new PgException.Argument(
              "0A000",
              "Unsupported: the column \""
                  + name
                  + "\" merged by a RIGHT or FULL JOIN USING; qualify it or use COALESCE of both"
                  + " sides");
        }
      }
      if (found == null) {
        if (outer != null) {
          correlated = true;
          return outer.resolve(c);
        }
        throw new PgException.Argument("42703", "Unknown column: " + c);
      }
      c.setTable(new Table(quoteIfNeeded(found.qualifier)));
      found.projections.add(name);
      return found;
    }
  }

  /** Plans any query: a SELECT block, a set operation, a VALUES list, or one in parentheses. */
  private Plan select(Select select, @Nullable Scope outer) throws ExecutionException {
    Map<String, Cte> saved = pushCtes(select.getWithItemsList());
    try {
      if (select instanceof PlainSelect) {
        return block((PlainSelect) select, outer);
      }
      if (select instanceof ParenthesedSelect) {
        return select(((ParenthesedSelect) select).getSelect(), outer);
      }
      if (select instanceof SetOperationList) {
        return setOperation((SetOperationList) select, outer);
      }
      if (select instanceof Values) {
        return values((Values) select);
      }
      throw new PgException.Argument("0A000", "Unsupported query: " + select);
    } finally {
      ctes = saved;
    }
  }

  /** A common table expression in scope, with the CTEs its own query may reference. */
  private static final class Cte {
    final WithItem<?> item;
    final Map<String, Cte> visible;
    final boolean recursive; // in a WITH RECURSIVE list: a UNION body may refer to itself
    @Nullable Plan working; // inside its own step: the previous iteration's rows
    int references; // how often the step referred to the working table

    Cte(WithItem<?> item, Map<String, Cte> visible, boolean recursive) {
      this.item = item;
      this.visible = visible;
      this.recursive = recursive;
    }
  }

  private Map<String, Cte> ctes = Collections.emptyMap();

  /**
   * Brings {@code items} into scope (each sees the earlier ones) and returns the previous scope.
   */
  private Map<String, Cte> pushCtes(@Nullable List<WithItem<?>> items) {
    Map<String, Cte> saved = ctes;
    if (items != null && !items.isEmpty()) {
      Map<String, Cte> scope = new LinkedHashMap<>(ctes);
      boolean recursive = false; // the keyword covers the whole list
      for (WithItem<?> item : items) {
        recursive |= item.isRecursive();
        if (item.getSearchClause() != null || item.getCycleClause() != null) {
          // ponytail: SEARCH adds an ordering column and CYCLE stops cycles; refused until they do
          throw new PgException.Argument(
              "0A000", "SEARCH and CYCLE are not supported: " + item.getAliasName());
        }
        if (!(item.getParenthesedStatement() instanceof Select)) {
          throw new PgException.Argument(
              "0A000",
              "Data-modifying statements in WITH are not supported: " + item.getAliasName());
        }
      }
      for (WithItem<?> item : items) {
        scope.put(
            unquote(item.getAlias().getName()),
            new Cte(item, new LinkedHashMap<>(scope), recursive));
      }
      ctes = scope;
    }
    return saved;
  }

  /** {@code names} replaces the first output column names, as a column list does. */
  private static List<String> renamed(List<String> columns, List<String> names) {
    return renamed(columns, names, null);
  }

  private static List<String> renamed(
      List<String> columns, List<String> names, @Nullable String table) {
    if (names.size() > columns.size()) {
      throw new PgException.Argument(
          "42P10",
          table == null
              ? "The column list " + names + " has more names than the query has columns"
              : "table \""
                  + table
                  + "\" has "
                  + columns.size()
                  + " columns available but "
                  + names.size()
                  + " columns specified");
    }
    List<String> out = new ArrayList<>(names);
    out.addAll(columns.subList(names.size(), columns.size()));
    return out;
  }

  private Plan block(PlainSelect select, @Nullable Scope outer) throws ExecutionException {
    if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()) {
      throw new PgException.Argument(
          "0A000", "SELECT INTO is not supported: use CREATE TABLE and INSERT ... SELECT");
    }
    LogicalPlan logical = bind(select, outer);
    Optimizer.optimize(logical);
    return physical(logical, outer == null);
  }

  /**
   * UNION [ALL], INTERSECT and EXCEPT. INTERSECT binds tighter than the others, which associate to
   * the left, as in PostgreSQL; ORDER BY, LIMIT and OFFSET apply to the whole result.
   */
  private Plan setOperation(SetOperationList set, @Nullable Scope outer) throws ExecutionException {
    List<Plan> terms = new ArrayList<>();
    for (Select member : set.getSelects()) {
      Plan plan = select(member, outer);
      if (!terms.isEmpty() && plan.outputNames.size() != terms.get(0).outputNames.size()) {
        throw new IllegalArgumentException(
            "Each query of a set operation must have the same number of columns: " + set);
      }
      terms.add(plan);
    }
    Plan.unifyColumnTypes(terms);
    List<SetOperation> operations = new ArrayList<>(set.getOperations());
    for (int i = 0; i < operations.size(); ) {
      if (operations.get(i) instanceof net.sf.jsqlparser.statement.select.IntersectOp) {
        terms.set(
            i,
            Plan.setOf(
                Operators.SetOperation.Kind.INTERSECT,
                operations.get(i).isAll(),
                terms.get(i),
                terms.remove(i + 1)));
        operations.remove(i);
      } else {
        i++;
      }
    }
    Plan result = terms.get(0);
    for (int i = 0; i < operations.size(); i++) {
      SetOperation op = operations.get(i);
      Operators.SetOperation.Kind kind;
      boolean all = op.isAll();
      if (op instanceof UnionOp) {
        kind = Operators.SetOperation.Kind.UNION;
      } else if (op instanceof net.sf.jsqlparser.statement.select.ExceptOp
          || op instanceof net.sf.jsqlparser.statement.select.MinusOp) {
        kind = Operators.SetOperation.Kind.EXCEPT;
      } else {
        throw new PgException.Argument("0A000", "Unsupported set operation: " + op);
      }
      result = Plan.setOf(kind, all, result, terms.get(i + 1));
    }
    if (set.getForMode() != null
        || set.getForUpdateTable() != null
        || set.getSelects().stream().anyMatch(m -> m.getForMode() != null)) {
      throw new PgException.Argument(
          "0A000", "FOR UPDATE and FOR SHARE are not allowed with UNION/INTERSECT/EXCEPT");
    }
    if (set.getFetch() != null && withTies(set)) {
      throw new PgException.Argument(
          "0A000", "Unsupported: FETCH ... WITH TIES on UNION/INTERSECT/EXCEPT");
    }
    if (set.getOrderByElements() != null) {
      // as PostgreSQL: a result column's name or position, nothing else
      for (OrderByElement o : set.getOrderByElements()) {
        Expression x = unwrap(o.getExpression());
        if (x instanceof LongValue) {
          continue;
        }
        if (!isColumn(x) || ((net.sf.jsqlparser.schema.Column) x).getTable() != null) {
          throw new PgException.Argument("0A000", "invalid UNION/INTERSECT/EXCEPT ORDER BY clause");
        }
        if (!result.getColumnLabels().contains(columnName(x))) {
          throw new PgException.Argument(
              "42703", "column \"" + columnName(x) + "\" does not exist");
        }
      }
      result.orderBy = set.getOrderByElements();
    }
    if (set.getLimit() != null && !set.getLimit().isLimitAll()) {
      result.limit = intValue(set.getLimit().getRowCount(), -1, "LIMIT");
      if (set.getLimit().getOffset() != null) {
        result.offset = intValue(set.getLimit().getOffset(), 0, "OFFSET");
      }
    }
    if (set.getFetch() != null) {
      result.limit = fetchCount(set.getFetch());
    }
    if (set.getOffset() != null) {
      result.offset = intValue(set.getOffset().getOffset(), 0, "OFFSET");
    }
    return result;
  }

  private static boolean withTies(Select select) {
    return select.getFetch().toString().toUpperCase(Locale.ROOT).contains("WITH TIES");
  }

  /** The rows of a VALUES list; a one-column row parses as a plain parenthesis. */
  static List<List<Expression>> tuples(ExpressionList<?> values) {
    List<List<Expression>> tuples = new ArrayList<>();
    if (values instanceof ParenthesedExpressionList) {
      tuples.add(new ArrayList<Expression>(values));
      return tuples;
    }
    for (Expression row : values) {
      if (row instanceof ParenthesedExpressionList) {
        tuples.add(new ArrayList<Expression>((ParenthesedExpressionList<?>) row));
      } else {
        throw new PgException.Argument("0A000", "Unsupported VALUES row: " + row);
      }
    }
    return tuples;
  }

  /** A VALUES list as a query, with columns named column1, column2, ... as in PostgreSQL. */
  private static Plan values(Values values) {
    return values(tuples(values.getExpressions()));
  }

  private static Plan values(List<List<Expression>> tuples) {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < tuples.get(0).size(); i++) {
      names.add("column" + (i + 1));
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (List<Expression> tuple : tuples) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 0; i < names.size(); i++) {
        row.put(names.get(i), i < tuple.size() ? evalLiteral(tuple.get(i)) : null);
      }
      rows.add(row);
    }
    Plan plan = Plan.constant(names, rows, "SELECT");
    // a column's type from a cast written in it, which a NULL value cannot tell: (null::int[])
    int[] oids = new int[names.size()];
    boolean any = false;
    for (int i = 0; i < oids.length; i++) {
      for (List<Expression> tuple : tuples) {
        if (oids[i] == 0 && i < tuple.size() && unwrap(tuple.get(i)) instanceof CastExpression) {
          oids[i] = inferOid(tuple.get(i), Collections.emptyList());
          any |= oids[i] != 0;
        }
      }
    }
    return any ? plan.withOids(oids) : plan;
  }

  /**
   * Binds a SELECT: resolves every FROM item and column reference (qualifying columns in place),
   * plans its subqueries, and records the clauses as a {@link LogicalPlan}. No physical decision is
   * made here.
   */
  private LogicalPlan bind(PlainSelect select, @Nullable Scope outer) throws ExecutionException {
    checkCommaJoins(select);
    LogicalPlan plan = new LogicalPlan();
    List<Join> joinNodes = select.getJoins() == null ? Collections.emptyList() : select.getJoins();
    List<FromItem> fromItems = new ArrayList<>();
    if (select.getFromItem() != null) {
      fromItems.add(select.getFromItem());
    }
    for (Join j : joinNodes) {
      fromItems.add(j.getRightItem());
    }
    boolean fromCorrelated = false; // a FROM item reads the enclosing query's row
    for (FromItem item : fromItems) {
      // a LATERAL subquery sees the sources before it as an enclosing scope, like a correlated
      // subquery, and the join runs it once per row of those sources
      boolean lateral =
          item instanceof net.sf.jsqlparser.statement.select.LateralSubSelect
              || item instanceof net.sf.jsqlparser.statement.select.TableFunction;
      Scope fromScope =
          new Scope(lateral ? plan.sources : Collections.<LogicalPlan.Source>emptyList(), outer);
      LogicalPlan.Source s = source(item, lateral || outer != null ? fromScope : null);
      // it resolved past this query's own sources: this query is correlated too, so it runs per
      // enclosing row with that row at hand
      fromCorrelated |= fromScope.correlated;
      s.lateral = lateral && s.derived != null; // a table function over earlier sources
      plan.sources.add(s);
    }
    List<List<Expression>> usingConditions = new ArrayList<>(); // from USING/NATURAL, per join
    for (int i = 0; i < joinNodes.size(); i++) {
      Join j = joinNodes.get(i);
      plan.joins.add(
          new LogicalPlan.Join(
              j.isFull()
                  ? LogicalPlan.Join.Kind.FULL
                  : j.isRight()
                      ? LogicalPlan.Join.Kind.RIGHT
                      : j.isLeft() ? LogicalPlan.Join.Kind.LEFT : LogicalPlan.Join.Kind.INNER));
      usingConditions.add(usingConditions(j, plan.sources, i + 1, plan.joins.get(i).kind));
    }
    Scope scope = new Scope(plan.sources, outer);

    // Output columns; names are taken before column references get qualified
    boolean multi = plan.sources.size() > 1;
    for (SelectItem<?> item : select.getSelectItems()) {
      Expression e = item.getExpression();
      if (e instanceof AllColumns) {
        List<LogicalPlan.Source> targets =
            e instanceof AllTableColumns
                ? Collections.singletonList(
                    scope.source(unquote(((AllTableColumns) e).getTable().getName())))
                : plan.sources;
        if (targets.isEmpty()) {
          throw new IllegalArgumentException("SELECT * requires a FROM clause");
        }
        targets.forEach(t -> t.star = true);
        for (Map.Entry<LogicalPlan.Source, String> sc :
            e instanceof AllTableColumns
                ? starColumns(targets.get(0))
                : starColumns(plan.sources, joinNodes)) {
          LogicalPlan.Source s = sc.getKey();
          String c = sc.getValue();
          int position = plan.sources.indexOf(s);
          if (s.catalogTable != null && c.equals(Catalog.TABLEOID)) {
            continue; // a system column
          }
          Expression column = new net.sf.jsqlparser.schema.Column(new Table(s.qualifier), c);
          String name = multi ? s.qualifier + "." + c : c;
          for (int k = position + 1;
              !(e instanceof AllTableColumns) && k < plan.sources.size();
              k++) {
            LogicalPlan.Source later = plan.sources.get(k);
            if (s.qualifier.equals(later.merged.get(c))) {
              name = c;
              if (plan.joins.get(k - 1).kind != LogicalPlan.Join.Kind.INNER) {
                // either side may be null-extended, so the merged column takes whichever is set
                Function coalesce = new Function();
                coalesce.setName("COALESCE");
                coalesce.setParameters(
                    new ExpressionList<Expression>(
                        column,
                        new net.sf.jsqlparser.schema.Column(new Table(later.qualifier), c)));
                column = coalesce;
              }
            }
          }
          plan.outputNames.add(name);
          plan.outputLabels.add(c);
          plan.outputExpressions.add(column);
        }
      } else {
        String label = item.getAlias() != null ? unquote(item.getAlias().getName()) : label(e);
        plan.outputNames.add(label);
        plan.outputLabels.add(label);
        plan.outputExpressions.add(e);
      }
    }
    List<String> names = plan.outputNames;
    makeUnique(names);

    for (Expression c : conjuncts(select.getWhere())) {
      c = rewriteArrayAny(c);
      analyze(c, scope, plan.subplans);
      plan.where.add(c);
    }
    for (int i = 0; i < joinNodes.size(); i++) {
      int nested = joinNodes.get(i).getOnExpressions().size(); // a JOIN b JOIN c ON x ON y: 2
      boolean outerJoin = nested > i + 1;
      for (int k = i - nested + 1; k <= i && !outerJoin; k++) {
        Join n = joinNodes.get(k);
        outerJoin = n.isLeft() || n.isRight() || n.isFull() || n.isOuter();
      }
      if (nested > 1 && outerJoin) {
        // ponytail: a JOIN b JOIN c ON x ON y nests as a JOIN (b JOIN c ON x) ON y; the parser
        // hangs every ON on the last join, which only inner joins do not mind; an outer one is
        // refused until the tree is rebuilt
        throw new PgException.Argument("0A000", "Unsupported outer JOIN with nested ON clauses");
      }
      List<Expression> ons = new ArrayList<>(usingConditions.get(i));
      ons.addAll(joinNodes.get(i).getOnExpressions());
      for (Expression on : ons) {
        for (Expression c : conjuncts(on)) {
          for (LogicalPlan.Source r : analyze(c, scope, plan.subplans)) {
            if (plan.sources.indexOf(r) > i + 1) {
              throw new IllegalArgumentException("ON condition refers to a later table: " + c);
            }
          }
          plan.joins.get(i).on.add(c);
        }
      }
    }

    for (Expression e : plan.outputExpressions) {
      analyze(e, scope, plan.subplans);
      bindWindows(e, select, scope, plan);
    }
    if (select.getGroupBy() != null) {
      bindGroupBy(select.getGroupBy(), scope, plan);
    }
    plan.having = select.getHaving();
    if (plan.having != null) {
      analyze(plan.having, scope, plan.subplans);
    }
    if (outer != null) {
      List<Expression> checked = new ArrayList<>(plan.outputExpressions);
      if (plan.having != null) {
        checked.add(plan.having);
      }
      for (Expression e : checked) {
        refuseOuterAggregates(e, scope);
      }
    }
    plan.aggregated = plan.groupBy != null || plan.having != null;
    for (Expression e : plan.outputExpressions) {
      plan.aggregated |= hasAggregate(e);
    }
    validateGrouping(plan, select);
    plan.distinct = select.getDistinct() != null;
    if (plan.distinct && select.getDistinct().getOnSelectItems() != null) {
      for (SelectItem<?> item : select.getDistinct().getOnSelectItems()) {
        plan.distinctOn.add(groupExpression(item.getExpression(), scope, plan));
      }
    }
    if (select.getOrderByElements() != null) {
      plan.orderBy = select.getOrderByElements();
    }
    for (OrderByElement o : plan.orderBy) {
      boolean outputName =
          o.getExpression() instanceof LongValue
              || (isColumn(o.getExpression())
                  && ((net.sf.jsqlparser.schema.Column) o.getExpression()).getTable() == null
                  && names.contains(columnName(o.getExpression())));
      if (!outputName) {
        analyze(o.getExpression(), scope, plan.subplans);
        bindWindows(o.getExpression(), select, scope, plan);
        if (plan.aggregated) {
          noNestedAggregate(o.getExpression());
          checkGrouped(o.getExpression(), grouped(plan), plan);
        }
      }
    }
    if (!plan.distinctOn.isEmpty() && !plan.orderBy.isEmpty()) {
      // As in PostgreSQL: the ON expressions must be the leading ORDER BY expressions
      for (int i = 0; i < plan.distinctOn.size(); i++) {
        if (i >= plan.orderBy.size()
            || !orderText(plan.orderBy.get(i), plan).equals(plan.distinctOn.get(i).toString())) {
          throw new IllegalArgumentException(
              "SELECT DISTINCT ON expressions must match initial ORDER BY expressions");
        }
      }
    }
    if (select.getLimit() != null) {
      inferCount(select.getLimit().getRowCount());
      inferCount(select.getLimit().getOffset());
    }
    if (select.getOffset() != null) {
      inferCount(select.getOffset().getOffset());
    }
    if (select.getLimit() != null && !select.getLimit().isLimitAll()) {
      plan.limit = intValue(select.getLimit().getRowCount(), -1, "LIMIT");
      if (select.getLimit().getOffset() != null) {
        plan.offset = intValue(select.getLimit().getOffset(), 0, "OFFSET");
      }
    }
    if (select.getOffset() != null) {
      plan.offset = intValue(select.getOffset().getOffset(), 0, "OFFSET");
    }
    if (select.getFetch() != null) {
      plan.limit = fetchCount(select.getFetch());
      plan.withTies = withTies(select);
      if (plan.withTies && plan.orderBy.isEmpty()) {
        throw new PgException.Argument(
            "42601", "WITH TIES cannot be specified without ORDER BY clause");
      }
      if (plan.withTies && select.isSkipLocked()) {
        throw new PgException.Argument(
            "42601", "SKIP LOCKED and WITH TIES options cannot be used together");
      }
      if (plan.withTies && select.getFetch().getExpression() instanceof NullValue) {
        throw new PgException.Argument(
            "2201W", "row count cannot be null in FETCH FIRST ... WITH TIES clause");
      }
    }
    java.util.function.ToIntFunction<Expression> typeOf = x -> inferOid(x, plan.sources);
    for (SelectItem<?> item : select.getSelectItems()) {
      inferParameters(item.getExpression(), typeOf);
    }
    inferParameters(select.getWhere(), typeOf);
    inferParameters(select.getHaving(), typeOf);
    if (select.getJoins() != null) {
      for (net.sf.jsqlparser.statement.select.Join join : select.getJoins()) {
        for (Expression on : join.getOnExpressions()) {
          inferParameters(on, typeOf);
        }
      }
    }
    for (int i = 0; i < plan.outputExpressions.size(); i++) {
      Expression e = plan.outputExpressions.get(i);
      plan.outputTypes.add(outputType(e, plan.sources));
      plan.outputOids.add(inferOid(e, plan.sources));
      plan.outputTypmods.add(inferTypmod(e, plan.sources));
      if (isSetReturning(e)) {
        if (plan.aggregated) {
          throw new PgException.Argument(
              "0A000", "Set-returning functions are not supported with aggregates: " + e);
        }
        plan.expanded.add(plan.outputNames.get(i));
      }
      if (nestedSetReturning(e)) {
        // PostgreSQL expands it under the expression, one row per element
        throw new PgException.Argument(
            "0A000", "Unsupported set-returning function inside an expression: " + e);
      }
    }
    if (plan.distinct && plan.distinctOn.isEmpty()) {
      Set<String> outputs = new HashSet<>();
      for (Expression out : plan.outputExpressions) {
        outputs.add(unwrap(out).toString());
      }
      for (OrderByElement o : plan.orderBy) {
        Expression e = unwrap(o.getExpression());
        boolean inList =
            e instanceof LongValue
                || outputs.contains(e.toString())
                || (isColumn(e)
                    && ((net.sf.jsqlparser.schema.Column) e).getTable() == null
                    && plan.outputNames.contains(columnName(e)));
        if (!inList) {
          throw new PgException.Argument(
              "42P10", "for SELECT DISTINCT, ORDER BY expressions must appear in select list");
        }
      }
    }
    plan.correlated = scope.correlated || fromCorrelated;
    return plan;
  }

  /** {@code unnest(array)} or {@code generate_subscripts(array, 1)} as a select item. */
  private static boolean isSetReturning(Expression e) {
    if (e instanceof RowGetExpression) {
      return isSetReturning(unwrap(((RowGetExpression) e).getExpression())); // (f(x)).field
    }
    if (!(e instanceof Function)) {
      return false;
    }
    String name = ((Function) e).getName().toLowerCase(Locale.ROOT);
    name = name.substring(name.lastIndexOf('.') + 1);
    // pg_proc's proretset, and information_schema's _pg_expandarray
    return name.equals("_pg_expandarray")
        || Builtins.overloads(name).stream().anyMatch(f -> "t".equals(f.row.get("proretset")));
  }

  /** Whether a set-returning call sits below the top of {@code e}, outside subqueries. */
  private static boolean nestedSetReturning(Expression e) {
    boolean[] found = {false};
    Expression call = e;
    while (call instanceof RowGetExpression) {
      call = unwrap(((RowGetExpression) call).getExpression()); // (f(x)).field
    }
    Expression top = isSetReturning(e) ? call : null;
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(Function function, S context) {
            found[0] |= function != top && isSetReturning(function);
            return super.visit(function, context);
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
    return found[0];
  }

  /** Set-returning JSON functions, as a table function in FROM. */
  private static final Set<String> JSON_TABLE_FUNCTIONS =
      new HashSet<>(
          Arrays.asList(
              "json_array_elements",
              "jsonb_array_elements",
              "json_array_elements_text",
              "jsonb_array_elements_text"));

  private static boolean allConstant(List<? extends Expression> expressions) {
    for (Expression e : expressions) {
      if (!isConstant(e)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The type of CASE, COALESCE, NULLIF, GREATEST and LEAST, from all their branches: numeric types
   * unify as PostgreSQL's do (any float gives double precision, else any numeric gives numeric,
   * else the widest integer); otherwise the first typed branch decides.
   */
  static int unifiedOid(List<? extends Expression> branches, List<LogicalPlan.Source> sources) {
    int first = 0;
    int rank = -1;
    boolean allNumeric = true;
    for (Expression b : branches) {
      if (b instanceof NullValue) {
        continue;
      }
      int oid = inferOid(b, sources);
      if (oid == 0) {
        continue;
      }
      if (first == 0) {
        first = oid;
      }
      int r = NUMERIC_RANK.indexOf(oid);
      if (r < 0) {
        allNumeric = false;
      } else {
        rank = Math.max(rank, r);
      }
    }
    return allNumeric && rank >= 0 ? NUMERIC_RANK.get(rank) : first;
  }

  /** Numeric type OIDs from narrowest to widest, as PostgreSQL resolves a common type. */
  private static final List<Integer> NUMERIC_RANK = Arrays.asList(21, 23, 20, 1700, 700, 701);

  /** The type OID an expression's values will have, from its shape; 0 when unknown. */
  static int inferOid(Expression e, List<LogicalPlan.Source> sources) {
    e = unwrap(e);
    if (e instanceof SignedExpression) {
      return inferOid(((SignedExpression) e).getExpression(), sources);
    }
    if (e instanceof BooleanValue) {
      return 16;
    }
    if (isColumn(e)) {
      if (isBooleanLiteral(columnName(e))) {
        return 16;
      }
      DataType type = outputType(e, sources);
      if (type != null) {
        return (int) Catalog.typeOid(type);
      }
      net.sf.jsqlparser.schema.Column c = (net.sf.jsqlparser.schema.Column) e;
      LogicalPlan.Source source = sourceOf(c, sources);
      if (source != null && source.target != null) {
        ColumnType declared = declaredType(source.target, columnName(e));
        if (declared != null && declared.encoded()) {
          return (int) declared.oid(); // numeric, not the bigint it is stored as
        }
      } else if (source != null && source.derived != null) {
        int i = source.columns.indexOf(columnName(e));
        int[] inner = source.derived.outputOids();
        if (i >= 0 && i < inner.length && inner[i] != 0) {
          return inner[i]; // through a subquery or CTE
        }
      }
      for (LogicalPlan.Source s : sources) {
        if (s.catalogTable != null
            && c.getTable() != null
            && s.qualifier.equals(unquote(c.getTable().getName()))) {
          // PostgreSQL's type of the column: oid, "char", name, int2vector, ...
          String table = s.catalogTable.replaceFirst("^Catalog ", "");
          return (int) Catalog.catalogColumnOid(table, columnName(e));
        }
      }
      return 0;
    }
    if (e instanceof LongValue) {
      return ((LongValue) e).getBigIntegerValue().bitLength() < 32 ? 23 : 20;
    }
    if (e instanceof DoubleValue) {
      return 1700;
    }
    if (e instanceof StringValue) {
      return 25;
    }
    if (e instanceof DateTimeLiteralExpression) {
      switch (((DateTimeLiteralExpression) e).getType()) {
        case DATE:
          return 1082;
        case TIME:
          return 1083;
        case TIMESTAMP:
          return 1114;
        default:
          return 1184;
      }
    }
    if (e instanceof net.sf.jsqlparser.expression.TimeKeyExpression) {
      String k =
          ((net.sf.jsqlparser.expression.TimeKeyExpression) e)
              .getStringValue()
              .toUpperCase(Locale.ROOT);
      return k.startsWith("CURRENT_DATE")
          ? 1082
          : k.startsWith("CURRENT_TIME") && !k.startsWith("CURRENT_TIMESTAMP")
                  || k.startsWith("LOCALTIME") && !k.startsWith("LOCALTIMESTAMP")
              ? 1083
              : k.startsWith("LOCALTIMESTAMP") ? 1114 : 1184;
    }
    if (e instanceof net.sf.jsqlparser.expression.IntervalExpression) {
      return 1186;
    }
    if (e instanceof net.sf.jsqlparser.expression.TimezoneExpression) {
      int left =
          inferOid(
              ((net.sf.jsqlparser.expression.TimezoneExpression) e).getLeftExpression(), sources);
      return left == 1184 ? 1114 : 1184;
    }
    if (e instanceof net.sf.jsqlparser.expression.ExtractExpression) {
      return 1700;
    }
    if (e instanceof net.sf.jsqlparser.expression.ArrayConstructor) {
      net.sf.jsqlparser.expression.ArrayConstructor a =
          (net.sf.jsqlparser.expression.ArrayConstructor) e;
      int element =
          a.getExpressions().isEmpty() ? 25 : inferOid(a.getExpressions().get(0), sources);
      return (int) Catalog.arrayOid(element);
    }
    if (e instanceof net.sf.jsqlparser.expression.JsonExpression) {
      net.sf.jsqlparser.expression.JsonExpression j =
          (net.sf.jsqlparser.expression.JsonExpression) e;
      // -> and #> keep json or jsonb, ->> and #>> give text
      return Evaluator.lastJsonOperator(j).endsWith(">>")
          ? 25
          : inferOid(j.getExpression(), sources) == 114 ? 114 : 3802;
    }
    if (e instanceof net.sf.jsqlparser.expression.operators.relational.JsonOperator) {
      return 16;
    }
    if (e instanceof CastExpression) {
      String t =
          ((CastExpression) e)
              .getColDataType()
              .getDataType()
              .toLowerCase(Locale.ROOT)
              .replaceAll("\\s*\\([^)]*\\)", "") // numeric (10, 2) -> numeric
              .trim();
      t = t.startsWith("pg_catalog.") ? t.substring("pg_catalog.".length()) : t;
      if (((CastExpression) e).getColDataType().getArrayData() != null
          && !((CastExpression) e).getColDataType().getArrayData().isEmpty()) {
        CastExpression scalar = new CastExpression();
        net.sf.jsqlparser.statement.create.table.ColDataType type =
            new net.sf.jsqlparser.statement.create.table.ColDataType();
        type.setDataType(((CastExpression) e).getColDataType().getDataType());
        scalar.setColDataType(type);
        scalar.setLeftExpression(((CastExpression) e).getLeftExpression());
        return (int) Catalog.arrayOid(inferOid(scalar, sources));
      }
      switch (t) {
        case "int":
        case "integer":
        case "int4":
        case "serial":
          return 23;
        case "bigint":
        case "int8":
        case "bigserial":
          return 20;
        case "smallint":
        case "int2":
          return 21;
        case "double precision":
        case "double":
        case "float8":
        case "float":
          return 701;
        case "real":
        case "float4":
          return 700;
        case "numeric":
        case "decimal":
          return 1700;
        case "boolean":
        case "bool":
          return 16;
        case "date":
          return 1082;
        case "time":
        case "time without time zone":
          return 1083;
        case "timestamp":
        case "timestamp without time zone":
          return 1114;
        case "timestamptz":
        case "timestamp with time zone":
          return 1184;
        case "interval":
          return 1186;
        case "bytea":
          return 17;
        case "json":
          return 114;
        case "jsonb":
          return 3802;
        case "uuid":
          return 2950;
        case "oid":
          return 26;
        case "regclass":
          return 2205;
        case "regtype":
          return 2206;
        case "regnamespace":
          return 4089;
        default:
          try {
            return (int) Catalog.typeOid(t); // bpchar, varchar, name, regproc, ...
          } catch (PgException.Argument unknown) {
            return 25;
          }
      }
    }
    if (e instanceof net.sf.jsqlparser.expression.CaseExpression) {
      List<Expression> branches = new ArrayList<>();
      for (net.sf.jsqlparser.expression.WhenClause when :
          ((net.sf.jsqlparser.expression.CaseExpression) e).getWhenClauses()) {
        branches.add(when.getThenExpression());
      }
      Expression otherwise = ((net.sf.jsqlparser.expression.CaseExpression) e).getElseExpression();
      if (otherwise != null) {
        branches.add(otherwise);
      }
      return unifiedOid(branches, sources);
    }
    if (e instanceof net.sf.jsqlparser.expression.BinaryExpression) {
      net.sf.jsqlparser.expression.BinaryExpression b =
          (net.sf.jsqlparser.expression.BinaryExpression) e;
      // a quoted literal is of unknown type, and an unknown operand takes the other's, as in
      // PostgreSQL: date_col - '2024-01-01' is date - date
      int l = unknownType(b.getLeftExpression(), sources);
      int r = unknownType(b.getRightExpression(), sources);
      long result =
          l == 0 && r == 0
              ? 0
              : Builtins.operatorOid(
                  b.getStringExpression().trim(), l == 0 ? r : l, r == 0 ? l : r);
      if (result != 0) {
        return (int) result; // the operator PostgreSQL would choose (Builtins)
      }
    }
    if (e instanceof net.sf.jsqlparser.expression.operators.arithmetic.Concat) {
      return 25;
    }
    if (e instanceof ComparisonOperator
        || e instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression
        || e instanceof net.sf.jsqlparser.expression.operators.conditional.OrExpression
        || e instanceof net.sf.jsqlparser.expression.NotExpression
        || e instanceof IsNullExpression
        || e instanceof net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression
        || e instanceof LikeExpression
        || e instanceof net.sf.jsqlparser.expression.operators.relational.InExpression
        || e instanceof net.sf.jsqlparser.expression.operators.relational.ExistsExpression
        || e instanceof net.sf.jsqlparser.expression.operators.relational.Between
        || e instanceof net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperator) {
      return 16;
    }
    if (e instanceof net.sf.jsqlparser.expression.AnalyticExpression) {
      net.sf.jsqlparser.expression.AnalyticExpression a =
          (net.sf.jsqlparser.expression.AnalyticExpression) e;
      String name = a.getName().toUpperCase(Locale.ROOT);
      // the call's arguments: rank(), lag(x, offset, default), nth_value(x, n)
      List<Expression> args = new ArrayList<>();
      for (Expression x : Arrays.asList(a.getExpression(), a.getOffset(), a.getDefaultValue())) {
        if (x != null) {
          args.add(x);
        }
      }
      return functionOid(name.substring(name.lastIndexOf('.') + 1), args, sources);
    }
    if (e instanceof Function) {
      Function f = (Function) e;
      String name = f.getName().toUpperCase(Locale.ROOT);
      name = name.substring(name.lastIndexOf('.') + 1);
      List<Expression> args = new ArrayList<>();
      if (f.getParameters() != null) {
        args.addAll(f.getParameters());
      } else if (f.getNamedParameters() != null) {
        for (Object p : f.getNamedParameters().getExpressions()) {
          args.add((Expression) p); // substring(s FROM n FOR m)
        }
      }
      return functionOid(name, args, sources);
    }
    return 0;
  }

  /** An operand's type; 0 for a quoted literal, unknown until an operator or call takes it. */
  private static int unknownType(Expression e, List<LogicalPlan.Source> sources) {
    return unwrap(e) instanceof StringValue ? 0 : inferOid(e, sources);
  }

  /** A call's result type: PostgreSQL's for the signature it matches (Builtins), or 0. */
  private static int functionOid(
      String name, List<Expression> args, List<LogicalPlan.Source> sources) {
    switch (name) {
      // SQL syntax, not functions pg_proc lists (Evaluator.SYNTAX)
      case "COALESCE":
      case "NULLIF":
      case "GREATEST":
      case "LEAST":
        return unifiedOid(args, sources);
      case "POSITION":
        return 23;
      case "CURRENT_USER":
      case "SESSION_USER":
        return 19;
      default:
        long[] types = new long[args.size()];
        for (int i = 0; i < types.length; i++) {
          types[i] = unknownType(args.get(i), sources);
        }
        return (int) Builtins.resultOid(name, types);
    }
  }

  /**
   * Turns an optimized logical plan into a physical one: the ScalarDB operation of each table (with
   * ORDER BY and LIMIT pushed into a single-table read when nothing in memory could change which
   * rows come first), and the operator tree over them.
   */
  private Plan physical(LogicalPlan lp, boolean streaming) {
    // Columns of pushed-down conditions are projected too, so storages that filter after
    // fetching still see them
    for (LogicalPlan.Source s : lp.sources) {
      for (ConditionalExpression c : s.pushed) {
        s.projections.add(c.getColumn().getName());
      }
      for (List<List<ConditionalExpression>> group : s.pushedOr) {
        for (List<ConditionalExpression> term : group) {
          for (ConditionalExpression c : term) {
            s.projections.add(c.getColumn().getName());
          }
        }
      }
    }
    boolean single = lp.sources.size() == 1 && lp.sources.get(0).isTable();
    boolean pushOrder =
        single
            && !lp.aggregated
            && !lp.orderBy.isEmpty()
            && clusteringOrder(lp.orderBy, lp.sources.get(0).table().metadata);
    boolean pushLimit =
        single
            // ScalarDB reads limit 0 as "no limit", so LIMIT 0 stays in memory
            && lp.limit > 0
            && lp.where.isEmpty()
            && !lp.aggregated
            && lp.windows.isEmpty()
            && !lp.distinct
            && !lp.withTies
            && lp.expanded.isEmpty() // a set-returning function makes more rows than it reads
            && (lp.orderBy.isEmpty() || pushOrder);
    List<OrderByElement> pushedOrder =
        pushOrder ? lp.orderBy : Collections.<OrderByElement>emptyList();
    int pushedLimit = pushLimit ? lp.limit + lp.offset : -1;
    List<Source> sources = new ArrayList<>();
    for (LogicalPlan.Source s : lp.sources) {
      if (s.derived != null) {
        Source source = new Source(s.qualifier, s.columns, null, s.derived, null);
        source.lateral = s.lateral;
        sources.add(source);
      } else if (s.catalogTable != null) {
        Source source = new Source(s.qualifier, s.columns, null, null, s.catalogTable);
        source.loaded = s.catalogRows;
        sources.add(source);
      } else if (s.lookupParams != null || !s.pushedLate.isEmpty()) {
        Source source = new Source(s.qualifier, s.columns, null, null, null);
        source.lookup = s;
        source.lookupParams =
            s.lookupParams == null ? Collections.<String, Expression>emptyMap() : s.lookupParams;
        source.lookupOrderBy = pushedOrder;
        source.lookupLimit = pushedLimit;
        sources.add(source);
      } else {
        Operation scan = operation(s, new ArrayList<>(s.pushed), pushedOrder, pushedLimit);
        sources.add(new Source(s.qualifier, s.columns, scan, null, null));
      }
    }
    Plan plan =
        new Plan(
            null,
            sources,
            lp.joins,
            lp.outputNames,
            lp.outputExpressions,
            lp.outputTypes,
            lp.where,
            lp.groupBy,
            lp.having,
            lp.aggregated,
            lp.windows,
            lp.distinct,
            pushOrder ? Collections.<OrderByElement>emptyList() : lp.orderBy,
            pushLimit ? -1 : lp.limit,
            lp.offset,
            lp.subplans,
            catalog,
            streaming,
            lp);
    plan.labels = lp.outputLabels;
    plan.correlated = lp.correlated;
    return plan;
  }

  /** Renames repeated names in place to name_2, name_3, ... so each can key a row. */
  static void makeUnique(List<String> names) {
    for (int i = 0; i < names.size(); i++) {
      for (int n = 2; names.subList(0, i).contains(names.get(i)); n++) {
        names.set(i, names.get(i).replaceAll("_\\d+$", "") + "_" + n);
      }
    }
  }

  /**
   * The column name PostgreSQL gives an unaliased select item (its FigureColname): a column's own
   * name, a function's name, the inner name or else the type name of a cast, "case", "exists", a
   * scalar subquery's column name, and "?column?" for anything else.
   */
  static String label(Expression e) {
    String name = strongLabel(e);
    if (name != null) {
      return name;
    }
    e = unwrap(e);
    if (e instanceof CastExpression) {
      return castTypeLabel(((CastExpression) e).getColDataType().getDataType());
    }
    if (e instanceof net.sf.jsqlparser.expression.IntervalExpression) {
      return "interval"; // INTERVAL '1 day' is a typed literal, named by its type
    }
    if (e instanceof DateTimeLiteralExpression) {
      return ((DateTimeLiteralExpression) e).getType().name().toLowerCase(Locale.ROOT);
    }
    if (e instanceof net.sf.jsqlparser.expression.ArrayExpression) {
      // a subscript is named after what it indexes: ('{1}'::int[])[1] is int4
      return label(((net.sf.jsqlparser.expression.ArrayExpression) e).getObjExpression());
    }
    return "?column?";
  }

  @Nullable private static String strongLabel(Expression e) {
    e = unwrap(e);
    if (isColumn(e)) {
      // JSQLParser reads TRUE and FALSE as columns
      return isBooleanLiteral(columnName(e)) ? null : columnName(e);
    }
    if (e instanceof Function) {
      String name = ((Function) e).getName();
      name = name.substring(name.lastIndexOf('.') + 1);
      return name.startsWith("\"") ? unquote(name) : name.toLowerCase(Locale.ROOT);
    }
    if (e instanceof net.sf.jsqlparser.expression.AnalyticExpression) {
      return ((net.sf.jsqlparser.expression.AnalyticExpression) e)
          .getName()
          .toLowerCase(Locale.ROOT);
    }
    if (e instanceof CastExpression) {
      return strongLabel(((CastExpression) e).getLeftExpression());
    }
    if (e instanceof net.sf.jsqlparser.expression.CaseExpression) {
      return "case";
    }
    if (e instanceof net.sf.jsqlparser.expression.ArrayConstructor) {
      return "array";
    }
    if (e instanceof net.sf.jsqlparser.expression.ArrayExpression) {
      // a subscript or slice is named after what it indexes, as PostgreSQL does
      return strongLabel(((net.sf.jsqlparser.expression.ArrayExpression) e).getObjExpression());
    }
    if (e instanceof net.sf.jsqlparser.expression.operators.relational.ExistsExpression) {
      return "exists";
    }
    if (e instanceof net.sf.jsqlparser.expression.TrimFunction) {
      net.sf.jsqlparser.expression.TrimFunction.TrimSpecification spec =
          ((net.sf.jsqlparser.expression.TrimFunction) e).getTrimSpecification();
      return spec == null || spec.name().equals("BOTH")
          ? "btrim"
          : spec.name().equals("LEADING") ? "ltrim" : "rtrim";
    }
    if (e instanceof net.sf.jsqlparser.expression.ExtractExpression) {
      return "extract";
    }
    if (e instanceof net.sf.jsqlparser.expression.TimezoneExpression) {
      return "timezone"; // AT TIME ZONE is the function timezone()
    }
    if (e instanceof net.sf.jsqlparser.expression.TimeKeyExpression) {
      return ((net.sf.jsqlparser.expression.TimeKeyExpression) e)
          .getStringValue()
          .toLowerCase(Locale.ROOT);
    }
    if (e instanceof ParenthesedSelect
        && ((ParenthesedSelect) e).getSelect() instanceof PlainSelect) {
      SelectItem<?> first = ((PlainSelect) ((ParenthesedSelect) e).getSelect()).getSelectItem(0);
      return first.getAlias() != null
          ? unquote(first.getAlias().getName())
          : first.getExpression() instanceof AllColumns ? null : label(first.getExpression());
    }
    return null;
  }

  /** PostgreSQL's internal name for a cast target type, as it labels a cast column. */
  private static String castTypeLabel(String type) {
    String t = type.toLowerCase(Locale.ROOT).trim().replaceAll("\\s*\\(.*\\)$", "");
    switch (t) {
      case "int":
      case "integer":
        return "int4";
      case "smallint":
        return "int2";
      case "bigint":
        return "int8";
      case "real":
        return "float4";
      case "float":
      case "double precision":
        return "float8";
      case "decimal":
        return "numeric";
      case "boolean":
        return "bool";
      case "character varying":
        return "varchar";
      case "char":
      case "character":
        return "bpchar";
      case "\"char\"":
        return "char";
      case "timestamp without time zone":
        return "timestamp";
      case "timestamp with time zone":
        return "timestamptz";
      case "time without time zone":
        return "time";
      case "time with time zone":
        return "timetz";
      default:
        return t.startsWith("pg_catalog.") ? t.substring("pg_catalog.".length()) : t;
    }
  }

  /**
   * The equalities a USING or NATURAL join stands for: each named (or, for NATURAL, each common)
   * column of the source at {@code index} equals the same column of the first earlier source that
   * has it. The column is recorded as merged on the right source.
   */
  /** A table's columns for t.*, in its order. */
  private static List<Map.Entry<LogicalPlan.Source, String>> starColumns(LogicalPlan.Source s) {
    List<Map.Entry<LogicalPlan.Source, String>> out = new ArrayList<>();
    for (String c : s.columns) {
      out.add(new java.util.AbstractMap.SimpleEntry<>(s, c));
    }
    return out;
  }

  /**
   * The columns of SELECT * in PostgreSQL's order: a join gives its USING (or NATURAL) columns
   * once, first, then the rest of its left side and of its right side; a comma starts another FROM
   * item.
   */
  private static List<Map.Entry<LogicalPlan.Source, String>> starColumns(
      List<LogicalPlan.Source> sources, List<Join> joins) {
    List<List<Map.Entry<LogicalPlan.Source, String>>> items = new ArrayList<>();
    for (int k = 0; k < sources.size(); k++) {
      LogicalPlan.Source s = sources.get(k);
      List<Map.Entry<LogicalPlan.Source, String>> own = new ArrayList<>();
      for (Map.Entry<LogicalPlan.Source, String> sc : starColumns(s)) {
        if (!s.merged.containsKey(sc.getValue())) { // a USING column comes out from the left side
          own.add(sc);
        }
      }
      if (k == 0 || joins.get(k - 1).isSimple()) {
        items.add(own);
        continue;
      }
      List<Map.Entry<LogicalPlan.Source, String>> left = items.get(items.size() - 1);
      List<Map.Entry<LogicalPlan.Source, String>> first = new ArrayList<>();
      for (Map.Entry<LogicalPlan.Source, String> sc : new ArrayList<>(left)) {
        // NATURAL's common columns in the left side's order, USING's in its own
        if (joins.get(k - 1).isNatural() && s.merged.containsKey(sc.getValue())) {
          first.add(sc);
        }
      }
      if (!joins.get(k - 1).isNatural()) {
        for (Map.Entry<String, String> m : s.merged.entrySet()) {
          for (Map.Entry<LogicalPlan.Source, String> sc : left) {
            if (sc.getValue().equals(m.getKey()) && sc.getKey().qualifier.equals(m.getValue())) {
              first.add(sc);
            }
          }
        }
      }
      if (first.size() != s.merged.size()) {
        // the other side of USING is in an earlier comma-separated FROM item: PostgreSQL's 42703
        throw new PgException.Argument(
            "42703", "column in USING clause does not exist in left table: " + s.merged.keySet());
      }
      left.removeAll(first);
      first.addAll(left);
      first.addAll(own);
      items.set(items.size() - 1, first);
    }
    List<Map.Entry<LogicalPlan.Source, String>> out = new ArrayList<>();
    items.forEach(out::addAll);
    return out;
  }

  private static List<Expression> usingConditions(
      Join j, List<LogicalPlan.Source> sources, int index, LogicalPlan.Join.Kind kind) {
    LogicalPlan.Source right = sources.get(index);
    List<String> columns = new ArrayList<>();
    if (j.isNatural()) {
      for (String c : right.columns) {
        for (LogicalPlan.Source earlier : sources.subList(0, index)) {
          if (earlier.columns.contains(c)) {
            columns.add(c);
            break;
          }
        }
      }
    } else {
      for (net.sf.jsqlparser.schema.Column c : j.getUsingColumns()) {
        columns.add(columnName(c));
      }
    }
    List<Expression> conditions = new ArrayList<>();
    for (String c : columns) {
      LogicalPlan.Source left = null;
      for (LogicalPlan.Source earlier : sources.subList(0, index)) {
        if (earlier.columns.contains(c)) {
          left = earlier;
          break;
        }
      }
      if (left == null || !right.columns.contains(c)) {
        throw new IllegalArgumentException("USING column must exist on both sides: " + c);
      }
      if (sources.subList(0, index).stream()
              .filter(x -> x.columns.contains(c) && !x.merged.containsKey(c))
              .count()
          > 1) {
        // ponytail: counts every earlier FROM item, where PostgreSQL looks only at the join's own
        // left side; that refuses a comma-separated duplicate it would allow
        throw new PgException.Argument(
            "42702", "common column name \"" + c + "\" appears more than once in left table");
      }
      if (left.mergedOuter.contains(c)) {
        // ponytail: the merged value (COALESCE of both sides) is not carried through joins; refused
        // rather than matched on one side's copy
        throw new PgException.Argument(
            "0A000",
            "Unsupported: joining USING ("
                + c
                + ") after a RIGHT or FULL JOIN USING ("
                + c
                + "); join ON COALESCE(...) instead");
      }
      if (kind == LogicalPlan.Join.Kind.RIGHT || kind == LogicalPlan.Join.Kind.FULL) {
        left.mergedOuter.add(c);
      }
      right.merged.put(c, left.qualifier);
      conditions.add(
          new EqualsTo(
              new net.sf.jsqlparser.schema.Column(new Table(left.qualifier), c),
              new net.sf.jsqlparser.schema.Column(new Table(right.qualifier), c)));
    }
    return conditions;
  }

  private LogicalPlan.Source source(FromItem item, @Nullable Scope outer)
      throws ExecutionException {
    String alias = item.getAlias() == null ? null : unquote(item.getAlias().getName());
    if (item instanceof Table) {
      Table table = (Table) item;
      if (table.getSampleClause() != null) {
        throw new PgException.Argument("0A000", "TABLESAMPLE is not supported");
      }
      if (item.getAlias() != null && item.getAlias().getAliasColumns() != null) {
        // t AS x (a, b) renames t's first columns: read as (SELECT * FROM t) AS x (a, b)
        // ponytail: a derived table, so no key or index lookup through the renamed names
        Table bare = new Table(table.getSchemaName(), table.getName());
        Statement star = parseStatement("SELECT * FROM " + bare);
        return derived(select((Select) star, outer), item);
      }
      String schema = table.getSchemaName() == null ? null : unquote(table.getSchemaName());
      String name = unquote(table.getName());
      Cte cte = schema == null ? ctes.get(name) : null;
      if (cte != null) {
        Map<String, Cte> saved = ctes;
        ctes = cte.visible;
        Plan derived;
        try {
          SetOperationList body = cte.recursive ? recursiveBody(cte.item) : null;
          if (cte.working != null) {
            cte.references++;
            derived = cte.working;
          } else if (body != null) {
            derived = recursive(cte, name, body, outer);
          } else {
            derived = select(cte.item.getSelect(), outer);
          }
        } finally {
          ctes = saved;
        }
        List<String> columns = new ArrayList<>(derived.outputNames);
        if (cte.item.getWithItemList() != null) {
          List<String> names = new ArrayList<>();
          for (SelectItem<?> column : cte.item.getWithItemList()) {
            names.add(columnName(column.getExpression()));
          }
          columns = renamed(columns, names);
        }
        return new LogicalPlan.Source(alias != null ? alias : name, columns, null, derived);
      }
      List<String> catalogColumns = Catalog.columns(schema, name);
      if (catalogColumns != null) {
        String qualifier = alias != null ? alias : name;
        LogicalPlan.Source source = new LogicalPlan.Source(qualifier, catalogColumns, null, null);
        source.catalogTable = "Catalog " + Catalog.qualifiedName(schema, name);
        source.catalogRows = catalog.rows(schema, name, qualifier);
        return source;
      }
      Target t = target(table);
      return new LogicalPlan.Source(
          alias != null ? alias : t.table, new ArrayList<>(t.metadata.getColumnNames()), t, null);
    }
    if (item instanceof ParenthesedSelect) {
      return derived(select(((ParenthesedSelect) item).getSelect(), outer), item);
    }
    if (item instanceof net.sf.jsqlparser.statement.select.ParenthesedFromItem) {
      // e.g. a one-row (VALUES (...)) AS v: the alias belongs to the wrapped item
      net.sf.jsqlparser.statement.select.ParenthesedFromItem wrapper =
          (net.sf.jsqlparser.statement.select.ParenthesedFromItem) item;
      if (wrapper.getJoins() != null && !wrapper.getJoins().isEmpty()) {
        throw new PgException.Argument("0A000", "Unsupported FROM item: " + item);
      }
      FromItem inner = wrapper.getFromItem();
      if (inner.getAlias() == null && wrapper.getAlias() != null) {
        inner.setAlias(wrapper.getAlias());
      }
      return source(inner, outer);
    }
    if (item instanceof net.sf.jsqlparser.statement.select.TableFunction) {
      Function function = ((net.sf.jsqlparser.statement.select.TableFunction) item).getFunction();
      if (function.getName().equalsIgnoreCase("VALUES")) {
        // A one-row (VALUES (...)) in FROM parses as a function call
        List<Expression> row = new ArrayList<>();
        if (function.getParameters() != null) {
          row.addAll(function.getParameters());
        }
        return derived(values(Collections.singletonList(row)), item);
      }
      String name = function.getName().toLowerCase(Locale.ROOT);
      name = name.startsWith("pg_catalog.") ? name.substring("pg_catalog.".length()) : name;
      if (name.equals("unnest")
          && function.getParameters() != null
          && function.getParameters().size() == 1
          && isConstant(function.getParameters().get(0))) {
        // unnest(array): one row per element, for an array known while planning
        Object array =
            Evaluator.eval(
                function.getParameters().get(0),
                Collections.<Map<String, Object>>emptyList(),
                literalContext());
        List<?> elements =
            array == null
                ? Collections.emptyList()
                : array instanceof List ? (List<?>) array : parseArrayText(String.valueOf(array));
        String qualifier = alias != null ? alias : "unnest";
        String column = qualifier;
        String ordinal = "ordinality";
        if (item.getAlias() != null && item.getAlias().getAliasColumns() != null) {
          column = unquote(item.getAlias().getAliasColumns().get(0).name);
          if (item.getAlias().getAliasColumns().size() > 1) {
            ordinal = unquote(item.getAlias().getAliasColumns().get(1).name);
          }
        }
        String withClause =
            ((net.sf.jsqlparser.statement.select.TableFunction) item).getWithClause();
        boolean ordinality =
            withClause != null && withClause.toUpperCase(Locale.ROOT).contains("ORDINALITY");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object v : elements) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put(qualifier + "." + column, v);
          if (ordinality) {
            row.put(qualifier + "." + ordinal, (long) rows.size() + 1); // counts from 1
          }
          rows.add(row);
        }
        LogicalPlan.Source source =
            new LogicalPlan.Source(
                qualifier,
                ordinality ? Arrays.asList(column, ordinal) : Collections.singletonList(column),
                null,
                null);
        source.catalogTable = "Function unnest";
        source.catalogRows = rows;
        return source;
      }
      if (JSON_TABLE_FUNCTIONS.contains(name)
          || ((name.equals("unnest") || name.equals("generate_series"))
              && function.getParameters() != null
              && !allConstant(function.getParameters()))) {
        // a table function over the sources before it (or a JSON array): a lateral subquery that
        // expands the function, one row per element
        String column =
            name.equals("unnest") || name.equals("generate_series")
                ? (alias != null ? alias : name)
                : "value";
        List<String> columns = new ArrayList<>();
        if (item.getAlias() != null && item.getAlias().getAliasColumns() != null) {
          for (Alias.AliasColumn c : item.getAlias().getAliasColumns()) {
            columns.add(unquote(c.name));
          }
        }
        String withClause =
            ((net.sf.jsqlparser.statement.select.TableFunction) item).getWithClause();
        boolean ordinality =
            withClause != null && withClause.toUpperCase(Locale.ROOT).contains("ORDINALITY");
        List<SelectItem<?>> items = new ArrayList<>();
        if (name.equals("unnest")) {
          // unnest(a, b, ...): one column per array, walked in step as the select list's
          // set-returning functions are; WITH ORDINALITY adds the row number, from 1 whatever an
          // array's lower bound, as _pg_expandarray's n
          List<Expression> arrays = new ArrayList<>(function.getParameters());
          for (int i = 0; i < arrays.size(); i++) {
            Function unnest = new Function();
            unnest.setName("unnest");
            unnest.setParameters(new ExpressionList<>(arrays.get(i)));
            String label =
                i < columns.size() ? columns.get(i) : arrays.size() == 1 ? column : "unnest";
            items.add(SelectItem.from(unnest, new Alias(label)));
          }
          if (ordinality) {
            // ponytail: numbered along the first array; PostgreSQL numbers along the longest
            Function expand = new Function();
            expand.setName("_pg_expandarray");
            expand.setParameters(new ExpressionList<>(arrays.get(0)));
            String ordinal =
                arrays.size() < columns.size() ? columns.get(arrays.size()) : "ordinality";
            items.add(SelectItem.from(new RowGetExpression(expand, "n"), new Alias(ordinal)));
          }
        } else {
          items.add(
              SelectItem.from(function, new Alias(columns.isEmpty() ? column : columns.get(0))));
        }
        PlainSelect body = new PlainSelect();
        body.setSelectItems(items);
        return derived(select(body, outer), item);
      }
      if (name.equals("pg_get_keywords")) {
        // what pgjdbc's getSQLKeywords() reads
        String qualifier = alias != null ? alias : "pg_get_keywords";
        List<String> columns = Arrays.asList("word", "catcode", "barelabel", "catdesc", "baredesc");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> keyword : Catalog.keywords()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (String column : columns) {
            row.put(qualifier + "." + column, keyword.get(column));
          }
          rows.add(row);
        }
        LogicalPlan.Source source = new LogicalPlan.Source(qualifier, columns, null, null);
        source.catalogTable = "Function pg_get_keywords";
        source.catalogRows = rows;
        return source;
      }
      if (name.equals("pg_available_extensions")) {
        // psql's \\dx: no extension can be installed here, so none is available
        String qualifier = alias != null ? alias : name;
        LogicalPlan.Source source =
            new LogicalPlan.Source(
                qualifier, Arrays.asList("name", "default_version", "comment"), null, null);
        source.catalogTable = "Function " + name;
        source.catalogRows = new ArrayList<>();
        return source;
      }
      if (!name.equals("generate_series")) {
        throw new PgException.Argument("0A000", "Unsupported table function: " + function);
      }
      // generate_series(start, stop [, step]) over integer or numeric values known while
      // planning, such as pgjdbc's array_upper(current_schemas(false), 1): a one-column source
      int arity = function.getParameters() == null ? 0 : function.getParameters().size();
      if (arity < 2 || arity > 3) {
        throw new IllegalArgumentException("generate_series takes 2 or 3 arguments: " + function);
      }
      for (Expression e : function.getParameters()) {
        embed(e);
      }
      Object series =
          Evaluator.eval(
              function,
              Collections.<Map<String, Object>>emptyList(),
              new Evaluator.Context(null, Collections.emptyMap(), catalog, BOUND.get()));
      String qualifier = alias != null ? alias : "generate_series";
      String column = qualifier; // as in PostgreSQL, the alias also names the single column
      if (item.getAlias() != null && item.getAlias().getAliasColumns() != null) {
        column = unquote(item.getAlias().getAliasColumns().get(0).name);
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object v : series == null ? Collections.emptyList() : (List<?>) series) { // NULL: none
        rows.add(Collections.<String, Object>singletonMap(qualifier + "." + column, v));
      }
      LogicalPlan.Source source =
          new LogicalPlan.Source(qualifier, Collections.singletonList(column), null, null);
      source.catalogTable = "Function generate_series";
      source.catalogRows = rows;
      return source;
    }
    if (item instanceof Values) {
      return derived(values((Values) item), item);
    }
    throw new PgException.Argument("0A000", "Unsupported FROM item: " + item);
  }

  /** The body of a recursive CTE when it has the shape {@code anchor UNION [ALL] step}. */
  @Nullable private static SetOperationList recursiveBody(WithItem<?> item) {
    Select body = item.getSelect();
    while (body instanceof ParenthesedSelect) {
      body = ((ParenthesedSelect) body).getSelect();
    }
    if (!(body instanceof SetOperationList)) {
      return null;
    }
    SetOperationList set = (SetOperationList) body;
    boolean union =
        set.getSelects().size() == 2
            && set.getOperations().size() == 1
            && set.getOperations().get(0) instanceof UnionOp;
    return union && set.getOrderByElements() == null && set.getLimit() == null ? set : null;
  }

  /**
   * WITH RECURSIVE: the anchor's rows, then the step evaluated over the previous iteration's rows
   * until it yields none. The step sees the CTE as a working table; when it does not refer to it,
   * the body is an ordinary UNION.
   */
  private Plan recursive(Cte cte, String name, SetOperationList body, @Nullable Scope outer)
      throws ExecutionException {
    Plan anchor = select(body.getSelects().get(0), outer);
    List<String> names = new ArrayList<>(anchor.outputNames);
    if (cte.item.getWithItemList() != null) {
      List<String> listed = new ArrayList<>();
      for (SelectItem<?> column : cte.item.getWithItemList()) {
        listed.add(columnName(column.getExpression()));
      }
      names = renamed(names, listed);
    }
    List<Map<String, Object>> working = new ArrayList<>();
    Cte self = new Cte(cte.item, cte.visible, true);
    self.working = Plan.working(name, names, working, anchor.outputOids());
    Map<String, Cte> scope = new LinkedHashMap<>(cte.visible);
    scope.put(name, self);
    Map<String, Cte> saved = ctes;
    ctes = scope;
    Plan step;
    try {
      step = select(body.getSelects().get(1), outer);
    } finally {
      ctes = saved;
    }
    if (self.references == 0) {
      return select(cte.item.getSelect(), outer);
    }
    wellFormedStep(name, body.getSelects().get(1), self.references);
    int[] anchorOids = anchor.outputOids();
    int[] stepOids = step.outputOids();
    for (int i = 0; i < Math.min(anchorOids.length, stepOids.length); i++) {
      if (NUMERIC_OIDS.contains(anchorOids[i])
          && NUMERIC_OIDS.contains(stepOids[i])
          && anchorOids[i] != stepOids[i]) {
        // the working table keeps the anchor's type, which PostgreSQL checks the step against
        throw new PgException.Argument(
            "42804",
            "recursive query \""
                + name
                + "\" column "
                + (i + 1)
                + " has a different type in the non-recursive term than overall");
      }
    }
    if (step.outputNames.size() != names.size()) {
      throw new IllegalArgumentException(
          "Each query of a set operation must have the same number of columns: " + body);
    }
    boolean all = ((UnionOp) body.getOperations().get(0)).isAll();
    return Plan.recursive(names, anchor.outputTypes, anchor, step, working, all);
  }

  private static final Set<Integer> NUMERIC_OIDS =
      new HashSet<>(Arrays.asList(20, 21, 23, 700, 701, 1700));

  /**
   * PostgreSQL's checkWellFormedRecursion for the recursive term: it refers to the query once, in
   * its own FROM and not on the nullable side of an outer join, with no aggregate, set operation,
   * ORDER BY, LIMIT, OFFSET or FOR UPDATE.
   */
  private static void wellFormedStep(String name, Select step, int references) {
    while (step instanceof ParenthesedSelect && step.getWithItemsList() == null) {
      step = ((ParenthesedSelect) step).getSelect();
    }
    if (step.getWithItemsList() != null && !step.getWithItemsList().isEmpty()) {
      // ponytail: a term reaching the query through its own WITH is let through unchecked, as it
      // was; PostgreSQL's rules for that case are not ported
      return;
    }
    if (!(step instanceof PlainSelect) || references > 1) {
      throw new PgException.Argument(
          "42P19",
          "recursive reference to query \""
              + name
              + "\" must appear once, in the FROM of a plain recursive term");
    }
    PlainSelect select = (PlainSelect) step;
    if (select.getOrderByElements() != null
        || select.getLimit() != null
        || select.getOffset() != null
        || select.getForUpdateTable() != null
        || select.getForMode() != null) {
      throw new PgException.Argument(
          "0A000", "ORDER BY, LIMIT, OFFSET and FOR UPDATE in a recursive query are not supported");
    }
    List<FromItem> items = new ArrayList<>();
    items.add(select.getFromItem());
    List<Join> joins = select.getJoins() == null ? Collections.emptyList() : select.getJoins();
    for (Join j : joins) {
      items.add(j.getRightItem());
    }
    int at = -1; // the self-reference among the FROM items
    for (int i = 0; i < items.size(); i++) {
      FromItem f = items.get(i);
      if (f instanceof Table
          && ((Table) f).getSchemaName() == null
          && unquote(((Table) f).getName()).equals(name)) {
        at = i;
      }
    }
    boolean nullable = false;
    for (int k = 0; at >= 0 && k < joins.size(); k++) {
      Join j = joins.get(k);
      nullable |=
          (j.isLeft() && k + 1 == at) || (j.isRight() && k + 1 > at) || (j.isFull() && k + 1 >= at);
    }
    boolean aggregate = select.getHaving() != null && hasAggregate(select.getHaving());
    for (SelectItem<?> item : select.getSelectItems()) {
      aggregate |= hasAggregate(item.getExpression());
    }
    if (at < 0 || nullable || aggregate) {
      throw new PgException.Argument(
          "42P19",
          "recursive reference to query \""
              + name
              + "\" must not appear within a subquery, an outer join or an aggregate");
    }
  }

  /** A subquery in FROM as a source: its alias names it, and the alias column list its columns. */
  private static LogicalPlan.Source derived(Plan derived, FromItem item) {
    if (item.getAlias() == null) {
      throw new IllegalArgumentException("A subquery in FROM must have an alias: " + item);
    }
    // a derived table's columns are its select list's labels, as in PostgreSQL: SELECT * over a
    // join names its outputs i.id, j.id internally, and only a label two columns share keeps that
    List<String> columns = new ArrayList<>(derived.outputNames);
    List<String> labels = derived.getColumnLabels();
    if (labels.size() == columns.size()) {
      for (int i = 0; i < columns.size(); i++) {
        if (Collections.frequency(labels, labels.get(i)) == 1) {
          columns.set(i, labels.get(i));
        }
      }
    }
    if (item.getAlias().getAliasColumns() != null) {
      List<String> names = new ArrayList<>();
      for (Alias.AliasColumn column : item.getAlias().getAliasColumns()) {
        names.add(unquote(column.name));
      }
      columns = renamed(columns, names, unquote(item.getAlias().getName()));
    }
    return new LogicalPlan.Source(unquote(item.getAlias().getName()), columns, null, derived);
  }

  /**
   * Resolves the columns in {@code e} (qualifying them in place), plans its subqueries, and returns
   * the sources of this query it refers to.
   */
  private Set<LogicalPlan.Source> analyze(Expression e, Scope scope, Map<Select, Plan> subplans)
      throws ExecutionException {
    Set<LogicalPlan.Source> refs = new LinkedHashSet<>();
    for (net.sf.jsqlparser.schema.Column c : columnRefs(e)) {
      LogicalPlan.Source s = scope.resolve(c);
      if (scope.isLocal(s)) {
        refs.add(s);
      }
    }
    checkTextAgainstNumber(e, scope);
    charSemantics(e, scope);
    for (Select sub : subselects(e)) {
      subplans.put(sub, select(sub, scope));
    }
    return refs;
  }

  /**
   * {@code name = 1} on a text column is an error in PostgreSQL (operator does not exist: text =
   * integer); comparing the texts would silently match nothing, so it is refused here too.
   */
  private static void checkTextAgainstNumber(Expression e, Scope scope) {
    checkTextAgainstNumber(
        e,
        c -> {
          String name = columnName(c);
          LogicalPlan.Source s = scope.resolve(c);
          return s.target != null
              ? checkedType(s.target, name)
              : s.derived != null && s.columns.contains(name)
                  ? derivedType(s.derived, s.columns.indexOf(name))
                  : null;
        },
        c -> {
          String name = columnName(c);
          LogicalPlan.Source s = scope.resolve(c);
          if (s.target != null) {
            return isDeclaredNumeric(s.target, name);
          }
          int i = s.derived == null ? -1 : s.columns.indexOf(name);
          int[] oids = s.derived == null ? new int[0] : s.derived.outputOids();
          return i >= 0 && i < oids.length && oids[i] == 1700; // through a subquery or CTE
        });
  }

  /** A derived column's type for the check above: text for text, varchar and char. */
  @Nullable private static DataType derivedType(Plan derived, int i) {
    DataType type = i < derived.outputTypes.size() ? derived.outputTypes.get(i) : null;
    if (type != null) {
      return type;
    }
    int[] oids = derived.outputOids();
    int oid = i < oids.length ? oids[i] : 0;
    return oid == 25 || oid == 1043 || oid == 1042 ? DataType.TEXT : null;
  }

  /** As above, for a single-table UPDATE or DELETE planned by key, without a scope. */
  private static void checkTextAgainstNumber(@Nullable Expression where, Target t) {
    if (where != null) {
      checkTextAgainstNumber(
          where, c -> checkedType(t, columnName(c)), c -> isDeclaredNumeric(t, columnName(c)));
    }
  }

  /**
   * Gives char(n) columns PostgreSQL's bpchar rules where the evaluator would otherwise see only
   * their stored, unpadded text: a quoted value compared with one loses its trailing spaces ({@code
   * c IN ('ab ')} matches 'ab'), and LIKE sees the value padded to n ({@code c LIKE 'ab'} is false
   * for a char(5), {@code c LIKE 'ab '} true). Rewrites {@code e} in place, as column qualification
   * does.
   */
  private static void charSemantics(Expression e, Scope scope) {
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(LikeExpression like, S context) {
            Expression left = unwrap(like.getLeftExpression());
            int n = charLength(left, scope);
            if (n > 0) {
              Function padded = new Function();
              padded.setName("RPAD");
              padded.setParameters(new ExpressionList<Expression>(left, new LongValue(n)));
              like.setLeftExpression(padded);
            }
            return super.visit(like, context);
          }

          @Override
          protected <S> Void visitBinaryExpression(BinaryExpression expr, S context) {
            if (expr instanceof ComparisonOperator) {
              stripAgainst(expr.getLeftExpression(), expr.getRightExpression());
              stripAgainst(expr.getRightExpression(), expr.getLeftExpression());
            }
            return super.visitBinaryExpression(expr, context);
          }

          @Override
          public <S> Void visit(InExpression in, S context) {
            if (in.getRightExpression() instanceof ExpressionList) {
              for (Expression item : (ExpressionList<?>) in.getRightExpression()) {
                stripAgainst(in.getLeftExpression(), item);
              }
            }
            return super.visit(in, context);
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null; // planned on its own
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }

          private void stripAgainst(Expression column, Expression other) {
            Expression literal = unwrap(other);
            if (literal instanceof StringValue && charLength(unwrap(column), scope) > 0) {
              StringValue v = (StringValue) literal;
              v.setValue(v.getValue().replaceAll(" +$", "")); // trailing spaces never count
            }
          }
        });
  }

  /**
   * The n of the char(n) column {@code e} refers to, of a table or through a subquery or CTE, or 0
   * when it is no such column.
   */
  private static int charLength(Expression e, Scope scope) {
    if (!isColumn(e) || ((net.sf.jsqlparser.schema.Column) e).getTable() == null) {
      return 0; // analyze qualifies every column, so a bare name is current_user or the like
    }
    LogicalPlan.Source s = scope.resolve((net.sf.jsqlparser.schema.Column) e);
    String name = columnName(e);
    if (s.target != null) {
      ColumnType type = declaredType(s.target, name);
      return type != null && type.kind == ColumnType.Kind.CHAR ? type.length : 0;
    }
    if (s.derived != null) {
      int i = s.columns.indexOf(name);
      int[] oids = s.derived.outputOids();
      int[] typmods = s.derived.outputTypmods();
      return i >= 0 && i < oids.length && i < typmods.length && oids[i] == 1042 && typmods[i] > 4
          ? typmods[i] - 4
          : 0;
    }
    return 0;
  }

  /** A column's type for the checks below: a numeric stored as text is no text column. */
  @Nullable private static DataType checkedType(Target t, String name) {
    DataType type = t.metadata.getColumnDataType(name);
    return type == null || isDeclaredNumeric(t, name) ? null : type;
  }

  /** True for a column declared numeric(p,s) or numeric and stored scaled or as text. */
  private static boolean isDeclaredNumeric(Target t, String name) {
    if (!t.metadata.getColumnNames().contains(name)) {
      return false;
    }
    return t.columns.type(name).isNumeric();
  }

  private static void checkTextAgainstNumber(
      Expression e,
      java.util.function.Function<net.sf.jsqlparser.schema.Column, DataType> typeOf,
      java.util.function.Predicate<net.sf.jsqlparser.schema.Column> numeric) {
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(LikeExpression like, S context) {
            Expression left = unwrap(like.getLeftExpression());
            if (isTableColumn(left) && numeric.test((net.sf.jsqlparser.schema.Column) left)) {
              // a numeric column has no LIKE in PostgreSQL; its text would match where it errs
              String op =
                  like.getLikeKeyWord() == LikeExpression.KeyWord.ILIKE
                      ? like.isNot() ? "!~~*" : "~~*"
                      : like.isNot() ? "!~~" : "~~";
              throw new PgException.Argument(
                  "42883",
                  "operator does not exist: numeric "
                      + op
                      + " "
                      + operandType(like.getRightExpression()));
            }
            return super.visit(like, context);
          }

          @Override
          protected <S> Void visitBinaryExpression(BinaryExpression expr, S context) {
            if (expr instanceof ComparisonOperator) {
              check(
                  expr.getLeftExpression(), expr.getRightExpression(), expr.getStringExpression());
              check(
                  expr.getRightExpression(), expr.getLeftExpression(), expr.getStringExpression());
            }
            return super.visitBinaryExpression(expr, context);
          }

          @Override
          public <S> Void visit(InExpression in, S context) {
            if (in.getRightExpression() instanceof ExpressionList) {
              for (Expression item : (ExpressionList<?>) in.getRightExpression()) {
                check(in.getLeftExpression(), item, "IN");
              }
            }
            return super.visit(in, context);
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }

          /** A column reference, not a bare current_user, true or the like. */
          private boolean isTableColumn(Expression e) {
            if (!isColumn(e)) {
              return false;
            }
            net.sf.jsqlparser.schema.Column c = (net.sf.jsqlparser.schema.Column) e;
            String name = columnName(c);
            return c.getTable() != null || !(isBooleanLiteral(name) || isKeyword(name));
          }

          /** The type PostgreSQL names for the right side of an operator it cannot find. */
          private String operandType(Expression e) {
            e = unwrap(e);
            if (isTableColumn(e)) {
              net.sf.jsqlparser.schema.Column c = (net.sf.jsqlparser.schema.Column) e;
              if (numeric.test(c)) {
                return "numeric";
              }
              DataType type = typeOf.apply(c);
              return type == null ? "unknown" : Catalog.typeName(Catalog.typeOid(type));
            }
            if (e instanceof CastExpression) {
              try {
                return Catalog.typeName( // character varying, not varchar
                    Catalog.typeOid(
                        castTypeLabel(((CastExpression) e).getColDataType().getDataType())));
              } catch (IllegalArgumentException unknownType) {
                return "unknown";
              }
            }
            if (e instanceof net.sf.jsqlparser.expression.operators.arithmetic.Concat) {
              return "text";
            }
            return "unknown"; // a literal or an untyped parameter
          }

          private void check(Expression column, Expression other, String op) {
            Expression literal = unwrap(other);
            if (literal instanceof SignedExpression) {
              literal = ((SignedExpression) literal).getExpression();
            }
            boolean number = literal instanceof LongValue || literal instanceof DoubleValue;
            if (!number || !isColumn(unwrap(column))) {
              return;
            }
            net.sf.jsqlparser.schema.Column c = (net.sf.jsqlparser.schema.Column) unwrap(column);
            String name = columnName(c);
            if (c.getTable() == null && (isBooleanLiteral(name) || isKeyword(name))) {
              return;
            }
            DataType type = typeOf.apply(c);
            if (type == DataType.TEXT) {
              throw new PgException.Argument(
                  "42883",
                  "operator does not exist: text "
                      + op
                      + (literal instanceof LongValue ? " integer" : " numeric"));
            }
          }
        });
  }

  /**
   * The type modifier of an output expression, as PostgreSQL reports it: a plain column keeps its
   * column's (numeric(10,2) is 655366), also through a subquery or CTE; anything else has none
   * (-1).
   */
  static int inferTypmod(Expression e, List<LogicalPlan.Source> sources) {
    e = unwrap(e);
    if (e instanceof CastExpression) {
      // x::char(5) is char(5), so its value is blank-padded on output; numeric(p,s), varchar(n)
      net.sf.jsqlparser.statement.create.table.ColDataType type =
          ((CastExpression) e).getColDataType();
      if (type.getArrayData() != null && !type.getArrayData().isEmpty()
          || !type.getDataType()
              .toLowerCase(Locale.ROOT)
              .matches(
                  "\\s*(char|character|bpchar|varchar|character varying|numeric|decimal)\\b.*")) {
        return -1; // only these types have a modifier here
      }
      List<String> args = type.getArgumentsStringList();
      String text =
          args == null || args.isEmpty() || type.getDataType().contains("(")
              ? type.getDataType()
              : type.getDataType() + "(" + String.join(",", args) + ")";
      ColumnType declared = ColumnType.declared(text, ColumnType.MAX_SCALED_PRECISION);
      return declared.encoded() ? declared.typmod() : -1;
    }
    if (!isColumn(e)) {
      return -1;
    }
    LogicalPlan.Source source = sourceOf((net.sf.jsqlparser.schema.Column) e, sources);
    if (source == null) {
      return -1;
    }
    if (source.target != null) {
      ColumnType declared = declaredType(source.target, columnName(e));
      return declared != null && declared.encoded() ? declared.typmod() : -1;
    }
    if (source.derived != null) {
      int i = source.columns.indexOf(columnName(e));
      int[] inner = source.derived.outputTypmods();
      return i >= 0 && i < inner.length ? inner[i] : -1;
    }
    return -1;
  }

  /** The source a qualified column reference reads, or null. */
  @Nullable private static LogicalPlan.Source sourceOf(
      net.sf.jsqlparser.schema.Column c, List<LogicalPlan.Source> sources) {
    if (c.getTable() == null) {
      return null;
    }
    String qualifier = unquote(c.getTable().getName());
    for (LogicalPlan.Source s : sources) {
      if (s.qualifier.equals(qualifier)) {
        return s;
      }
    }
    return null;
  }

  /** The declared type of a table's client column, or null if the table has no such column. */
  @Nullable private static ColumnType declaredType(Target t, String name) {
    return t.metadata.getColumnNames().contains(name) ? t.columns.type(name) : null;
  }

  @Nullable private static DataType outputType(Expression e, List<LogicalPlan.Source> sources) {
    if (!isColumn(e) || ((net.sf.jsqlparser.schema.Column) e).getTable() == null) {
      return null;
    }
    String qualifier = unquote(((net.sf.jsqlparser.schema.Column) e).getTable().getName());
    String name = columnName(e);
    for (LogicalPlan.Source s : sources) {
      if (!s.qualifier.equals(qualifier)) {
        continue;
      }
      if (s.target != null) {
        ColumnType declared = declaredType(s.target, name);
        // a declared type is the client's (numeric(10,2), varchar(20)), not the stored one
        return declared != null && declared.encoded()
            ? null
            : s.target.metadata.getColumnDataType(name);
      }
      if (s.derived == null) {
        return null; // catalog table
      }
      int i = s.columns.indexOf(name);
      return i < 0 ? null : s.derived.outputTypes.get(i);
    }
    return null;
  }

  /** Builds the Get or Scan for a table with what was pushed down to it. */
  static Operation operation(
      LogicalPlan.Source s,
      List<ConditionalExpression> pushed,
      List<OrderByElement> orderBy,
      int limit) {
    Target t = s.table();
    // Every column by name: Cluster's ABAC leaves its data tag column out of a read that names none
    List<String> projections =
        new ArrayList<>(s.star ? t.metadata.getColumnNames() : s.projections);
    if (!s.star && projections.isEmpty()) {
      // count(*), EXISTS, SELECT 1: no column is read, but an empty projection list means every
      // column to ScalarDB, so ask for the partition key alone (todo 033)
      projections.addAll(t.metadata.getPartitionKeyNames());
    }
    Key partitionKey = equalsKey(pushed, t.metadata.getPartitionKeyNames());
    Scan base;
    if (partitionKey != null) {
      // Clustering key: an '=' prefix, then at most one range column
      List<Column<?>> prefix = new ArrayList<>();
      ConditionalExpression lower = null;
      ConditionalExpression upper = null;
      for (String name : t.metadata.getClusteringKeyNames()) {
        ConditionalExpression eq = take(pushed, name, ConditionalExpression.Operator.EQ);
        if (eq != null) {
          prefix.add(eq.getColumn());
          continue;
        }
        lower =
            take(
                pushed,
                name,
                ConditionalExpression.Operator.GT,
                ConditionalExpression.Operator.GTE);
        upper =
            take(
                pushed,
                name,
                ConditionalExpression.Operator.LT,
                ConditionalExpression.Operator.LTE);
        break;
      }
      if (prefix.size() == t.metadata.getClusteringKeyNames().size()) {
        GetBuilder.BuildableGetWithPartitionKey get =
            Get.newBuilder().namespace(t.namespace).table(t.table).partitionKey(partitionKey);
        if (!prefix.isEmpty()) {
          get.clusteringKey(key(prefix));
        }
        get.projections(projections);
        if (!s.pushedOr.isEmpty()) {
          return get.whereOr(conjunctions(pushed, s.pushedOr)).build();
        }
        return pushed.isEmpty() ? get.build() : get.where(and(pushed)).build();
      }
      ScanBuilder.BuildableScanWithPartitionKey scan =
          Scan.newBuilder().namespace(t.namespace).table(t.table).partitionKey(partitionKey);
      if (lower != null) {
        scan.start(
            key(prefix, lower.getColumn()),
            lower.getOperator() == ConditionalExpression.Operator.GTE);
      } else if (!prefix.isEmpty()) {
        scan.start(key(prefix), true);
      }
      if (upper != null) {
        scan.end(
            key(prefix, upper.getColumn()),
            upper.getOperator() == ConditionalExpression.Operator.LTE);
      } else if (!prefix.isEmpty()) {
        scan.end(key(prefix), true);
      }
      base = scan.build();
    } else {
      base = null;
      for (String name : t.metadata.getSecondaryIndexNames()) {
        ConditionalExpression eq = take(pushed, name, ConditionalExpression.Operator.EQ);
        if (eq != null) {
          base =
              Scan.newBuilder()
                  .namespace(t.namespace)
                  .table(t.table)
                  .indexKey(Key.newBuilder().add(eq.getColumn()).build())
                  .build();
          break;
        }
      }
      if (base == null) {
        base = Scan.newBuilder().namespace(t.namespace).table(t.table).all().build();
      }
    }
    ScanBuilder.BuildableScanOrScanAllFromExisting scan =
        Scan.newBuilder(base).projections(projections);
    if (limit >= 0) {
      scan.limit(limit);
    }
    for (OrderByElement o : orderBy) {
      String name = columnName(o.getExpression());
      scan.ordering(o.isAsc() ? Scan.Ordering.asc(name) : Scan.Ordering.desc(name));
    }
    if (!s.pushedOr.isEmpty()) {
      return scan.whereOr(conjunctions(pushed, s.pushedOr)).build();
    }
    return pushed.isEmpty() ? scan.build() : scan.where(and(pushed)).build();
  }

  /** {@code base AND (group1) AND (group2) ...} expanded into ScalarDB's OR of ANDs. */
  private static Set<AndConditionSet> conjunctions(
      List<ConditionalExpression> base, List<List<List<ConditionalExpression>>> groups) {
    List<List<ConditionalExpression>> combinations = new ArrayList<>();
    combinations.add(new ArrayList<>(base));
    for (List<List<ConditionalExpression>> group : groups) {
      List<List<ConditionalExpression>> expanded = new ArrayList<>();
      for (List<ConditionalExpression> combination : combinations) {
        for (List<ConditionalExpression> term : group) {
          List<ConditionalExpression> next = new ArrayList<>(combination);
          next.addAll(term);
          expanded.add(next);
        }
      }
      combinations = expanded;
    }
    Set<AndConditionSet> out = new LinkedHashSet<>();
    for (List<ConditionalExpression> combination : combinations) {
      out.add(ConditionSetBuilder.andConditionSet(new HashSet<>(combination)).build());
    }
    return out;
  }

  /** True if the ORDER BY is a clustering-key prefix, all in or all against clustering order. */
  private static boolean clusteringOrder(List<OrderByElement> orderBy, TableMetadata metadata) {
    List<String> clusteringKeys = new ArrayList<>(metadata.getClusteringKeyNames());
    Boolean forward = null;
    for (int i = 0; i < orderBy.size(); i++) {
      OrderByElement o = orderBy.get(i);
      if (!isColumn(o.getExpression())
          || i >= clusteringKeys.size()
          || !columnName(o.getExpression()).equals(clusteringKeys.get(i))) {
        return false;
      }
      boolean sameDirection =
          o.isAsc()
              == (metadata.getClusteringOrder(clusteringKeys.get(i)) == Scan.Ordering.Order.ASC);
      if (forward == null) {
        forward = sameDirection;
      } else if (forward != sameDirection) {
        return false;
      }
    }
    return true;
  }

  // ---- DML ----

  private static List<Mutation> insert(
      net.sf.jsqlparser.statement.insert.Insert insert, Target t, Evaluator.Context ctx) {
    List<String> names = insertColumns(insert, t);
    ExpressionList<?> values =
        insert.getValues() == null ? null : insert.getValues().getExpressions();
    if (values == null) {
      throw new IllegalArgumentException("INSERT must have a VALUES clause: " + insert);
    }
    boolean upsert = insert.getConflictAction() != null;
    if (upsert) {
      checkConflictTarget(insert.getConflictTarget(), t);
    }
    List<Mutation> mutations = new ArrayList<>();
    List<List<Expression>> rows = tuples(values);
    for (List<Expression> row : rows) {
      checkRowLength(insert, names, row, rows.get(0).size(), t);
      Map<String, Column<?>> columns = new LinkedHashMap<>();
      for (int i = 0; i < names.size(); i++) {
        columns.put(
            names.get(i),
            i >= row.size() || isDefaultKeyword(row.get(i))
                ? columnFromText(t, names.get(i), null) // no column defaults: NULL
                : value(t, names.get(i), row.get(i), ctx));
      }
      mutations.add(put(t, columns, upsert));
    }
    return mutations;
  }

  /** An Insert, or an Upsert, of {@code columns} (the key columns are taken out of the map). */
  private static Mutation put(Target t, Map<String, Column<?>> columns, boolean upsert) {
    Key partitionKey = removeKey(columns, t.metadata.getPartitionKeyNames());
    Key clusteringKey =
        t.metadata.getClusteringKeyNames().isEmpty()
            ? null
            : removeKey(columns, t.metadata.getClusteringKeyNames());
    if (upsert) {
      UpsertBuilder.Buildable b =
          Upsert.newBuilder().namespace(t.namespace).table(t.table).partitionKey(partitionKey);
      if (clusteringKey != null) {
        b.clusteringKey(clusteringKey);
      }
      for (Column<?> c : columns.values()) {
        b.value(c);
      }
      return b.build();
    }
    InsertBuilder.Buildable b =
        Insert.newBuilder().namespace(t.namespace).table(t.table).partitionKey(partitionKey);
    if (clusteringKey != null) {
      b.clusteringKey(clusteringKey);
    }
    for (Column<?> c : columns.values()) {
      b.value(c);
    }
    return b.build();
  }

  /** UPDATE SET c = 1, c = 2 is 42601 in PostgreSQL, not the last assignment winning. */
  private static void checkSingleAssignments(List<UpdateSet> sets) {
    Set<String> seen = new HashSet<>();
    for (UpdateSet set : sets) {
      for (int i = 0; i < set.getColumns().size(); i++) {
        String name = assignedColumn(set.getColumn(i));
        if (!seen.add(name)) {
          throw new PgException.Argument(
              "42601", "multiple assignments to same column \"" + name + "\"");
        }
      }
    }
  }

  /**
   * A VALUES row has a value per target column, as in PostgreSQL; without a column list a shorter
   * row leaves the trailing columns to their defaults, which are NULL here, so they must not be key
   * columns. All rows have one length.
   */
  private static void checkRowLength(
      net.sf.jsqlparser.statement.insert.Insert insert,
      List<String> names,
      List<Expression> row,
      int first,
      Target t) {
    if (row.size() != first) {
      throw new PgException.Argument("42601", "VALUES lists must all be the same length");
    }
    if (row.size() > names.size()) {
      throw new PgException.Argument("42601", "INSERT has more expressions than target columns");
    }
    if (row.size() < names.size()) {
      if (insert.getColumns() != null) {
        throw new PgException.Argument("42601", "INSERT has more target columns than expressions");
      }
      for (String key : keyColumns(t)) {
        if (names.indexOf(key) >= row.size()) {
          throw notNullViolation(key);
        }
      }
    }
  }

  /** The target column names of an INSERT: the listed ones, or every column. */
  private static List<String> insertColumns(
      net.sf.jsqlparser.statement.insert.Insert insert, Target t) {
    List<String> names = new ArrayList<>();
    if (insert.getColumns() != null) {
      for (Expression c : insert.getColumns()) {
        names.add(existing(t.metadata, columnName(c)));
      }
    } else {
      names.addAll(t.metadata.getColumnNames());
    }
    for (String key : keyColumns(t)) {
      if (!names.contains(key)) {
        throw notNullViolation(key);
      }
    }
    return names;
  }

  /** ScalarDB detects conflicts on the primary key only, so that is the only valid target. */
  private static void checkConflictTarget(@Nullable InsertConflictTarget target, Target t) {
    if (target == null) {
      return;
    }
    if (target.getConstraintName() != null) {
      if (!unquote(target.getConstraintName()).equals(t.table + "_pkey")) {
        throw new IllegalArgumentException(
            "ON CONFLICT ON CONSTRAINT must name the primary key: " + target.getConstraintName());
      }
      return;
    }
    Set<String> named = new HashSet<>();
    if (target.getIndexColumnNames() != null) {
      for (String c : target.getIndexColumnNames()) {
        named.add(unquote(c));
      }
    }
    if (target.getIndexExpression() != null || !named.equals(new HashSet<>(keyColumns(t)))) {
      throw new IllegalArgumentException(
          "ON CONFLICT target must be the primary key " + keyColumns(t) + ": " + target);
    }
  }

  /**
   * True for {@code ON CONFLICT DO UPDATE SET c = EXCLUDED.c} over exactly the inserted non-key
   * columns, with no WHERE: that is a ScalarDB Upsert, needing no read.
   */
  private boolean upsertable(net.sf.jsqlparser.statement.insert.Insert insert)
      throws ExecutionException {
    InsertConflictAction action = insert.getConflictAction();
    if (action.getConflictActionType() != ConflictActionType.DO_UPDATE
        || action.getWhereExpression() != null) {
      return false;
    }
    Target t = target(insert.getTable());
    Set<String> expected = new HashSet<>(insertColumns(insert, t));
    expected.removeAll(keyColumns(t));
    Set<String> updated = new HashSet<>();
    for (UpdateSet set : action.getUpdateSets()) {
      for (int i = 0; i < set.getColumns().size(); i++) {
        Expression value = defaultToNull(set.getValue(i));
        String column = columnName(set.getColumn(i));
        if (!(value instanceof net.sf.jsqlparser.schema.Column)
            || ((net.sf.jsqlparser.schema.Column) value).getTable() == null
            || !unquote(((net.sf.jsqlparser.schema.Column) value).getTable().getName())
                .equalsIgnoreCase("excluded")
            || !columnName(value).equals(column)) {
          return false;
        }
        updated.add(column);
      }
    }
    return updated.equals(expected);
  }

  /** The one mutation, or none when the statement can match no row. */
  private static List<Mutation> listOf(@Nullable Mutation m) {
    return m == null ? Collections.<Mutation>emptyList() : Collections.singletonList(m);
  }

  /** A keyed UPDATE, or null when its WHERE is false for every row (see {@link #conditions}). */
  @Nullable private static Update update(
      net.sf.jsqlparser.statement.update.Update update, Target t, Evaluator.Context ctx) {
    List<ConditionalExpression> conditions = conditions(update.getWhere(), t, update, ctx);
    if (conditions == null) {
      return null;
    }
    UpdateBuilder.Buildable b =
        Update.newBuilder()
            .namespace(t.namespace)
            .table(t.table)
            .partitionKey(primaryKey(conditions, t.metadata.getPartitionKeyNames(), update));
    if (!t.metadata.getClusteringKeyNames().isEmpty()) {
      b.clusteringKey(primaryKey(conditions, t.metadata.getClusteringKeyNames(), update));
    }
    for (UpdateSet set : update.getUpdateSets()) {
      for (int i = 0; i < set.getColumns().size(); i++) {
        b.value(value(t, columnName(set.getColumn(i)), defaultToNull(set.getValue(i)), ctx));
      }
    }
    // A condition makes ScalarDB report a missing or non-matching row (as 0 rows) instead of
    // silently doing nothing
    b.condition(
        conditions.isEmpty()
            ? ConditionBuilder.updateIfExists()
            : ConditionBuilder.updateIf(conditions));
    return b.build();
  }

  /** A keyed DELETE, or null when its WHERE is false for every row (see {@link #conditions}). */
  @Nullable private static Delete delete(
      net.sf.jsqlparser.statement.delete.Delete delete, Target t, Evaluator.Context ctx) {
    List<ConditionalExpression> conditions = conditions(delete.getWhere(), t, delete, ctx);
    if (conditions == null) {
      return null;
    }
    DeleteBuilder.Buildable b =
        Delete.newBuilder()
            .namespace(t.namespace)
            .table(t.table)
            .partitionKey(primaryKey(conditions, t.metadata.getPartitionKeyNames(), delete));
    if (!t.metadata.getClusteringKeyNames().isEmpty()) {
      b.clusteringKey(primaryKey(conditions, t.metadata.getClusteringKeyNames(), delete));
    }
    b.condition(
        conditions.isEmpty()
            ? ConditionBuilder.deleteIfExists()
            : ConditionBuilder.deleteIf(conditions));
    return b.build();
  }

  // ---- writes driven by a read ----

  /** True if the WHERE pins the full primary key with literals and every SET value is a literal. */
  private boolean keyed(net.sf.jsqlparser.statement.update.Update update)
      throws ExecutionException {
    for (UpdateSet set : update.getUpdateSets()) {
      for (int i = 0; i < set.getColumns().size(); i++) {
        if (!isLiteral(defaultToNull(set.getValue(i)))) {
          return false;
        }
      }
    }
    return keyed(target(update.getTable()), conjuncts(update.getWhere()));
  }

  private boolean keyed(net.sf.jsqlparser.statement.delete.Delete delete)
      throws ExecutionException {
    return keyed(target(delete.getTable()), conjuncts(delete.getWhere()));
  }

  private static boolean keyed(Target t, List<Expression> conjuncts) {
    List<ConditionalExpression> conditions = new ArrayList<>();
    for (Expression c : conjuncts) {
      ConditionalExpression p = pushable(c, t, null, literalContext());
      if (p == null) {
        return false;
      }
      conditions.add(p);
    }
    return equalsKey(conditions, t.metadata.getPartitionKeyNames()) != null
        && (t.metadata.getClusteringKeyNames().isEmpty()
            || equalsKey(conditions, t.metadata.getClusteringKeyNames()) != null);
  }

  private static List<String> keyColumns(Target t) {
    List<String> keys = new ArrayList<>(t.metadata.getPartitionKeyNames());
    keys.addAll(t.metadata.getClusteringKeyNames());
    return keys;
  }

  /** How the statement refers to the table: its alias, or its name. */
  private static String qualifierOf(Table table, Target t) {
    return table.getAlias() != null ? unquote(table.getAlias().getName()) : t.table;
  }

  /**
   * The read driving a write: the target's {@code columns} and the {@code extras} as {@code $n},
   * over the target joined with {@code joins} (UPDATE ... FROM, DELETE ... USING). With {@code
   * distinctOn}, one row per target key, as PostgreSQL writes each target row once however many
   * joined rows match it.
   */
  private Plan readFor(
      Table table,
      String qualifier,
      @Nullable Expression where,
      Collection<String> columns,
      List<Expression> extras,
      List<Join> joins,
      @Nullable Collection<String> distinctOn)
      throws ExecutionException {
    PlainSelect read = new PlainSelect();
    read.setFromItem(table);
    if (!joins.isEmpty()) {
      read.setJoins(new ArrayList<>(joins));
    }
    List<SelectItem<?>> items = new ArrayList<>();
    for (String column : columns) {
      // qualified: a joined table may have a column of the same name
      items.add(SelectItem.from(new net.sf.jsqlparser.schema.Column(new Table(qualifier), column)));
    }
    for (int i = 0; i < extras.size(); i++) {
      items.add(SelectItem.from(extras.get(i), new Alias("$" + (i + 1))));
    }
    read.setSelectItems(items);
    read.setWhere(where);
    if (distinctOn != null) {
      List<SelectItem<?>> on = new ArrayList<>();
      for (String column : distinctOn) {
        on.add(SelectItem.from(new net.sf.jsqlparser.schema.Column(new Table(qualifier), column)));
      }
      net.sf.jsqlparser.statement.select.Distinct distinct =
          new net.sf.jsqlparser.statement.select.Distinct();
      distinct.setOnSelectItems(on);
      read.setDistinct(distinct);
    }
    return block(read, null);
  }

  private static Key keyOf(Target t, Collection<String> names, Map<String, Object> values) {
    Key.Builder key = Key.newBuilder();
    for (String name : names) {
      if (values.get(name) == null) {
        throw notNullViolation(name);
      }
      key.add(writtenColumn(t, name, values.get(name)));
    }
    return key.build();
  }

  /**
   * The row a write leaves, keyed {@code qualifier.column}, from values keyed by column name and
   * converted to the columns' types, as a read would return them.
   */
  private static Map<String, Object> left(Target t, String qualifier, Map<String, Object> values) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (String c : t.metadata.getColumnNames()) {
      Object value = values.get(c);
      row.put(
          qualifier + "." + c,
          t.columns.type(c).encoded()
              ? writtenValue(t, c, value)
              : columnFromValue(t, c, value).getValueAsObject());
    }
    return row;
  }

  /** The RETURNING clause over {@code t}, or null. */
  @Nullable private static Returning returning(@Nullable ReturningClause clause, Target t, String qualifier) {
    if (clause == null) {
      return null;
    }
    Returning returning = new Returning();
    for (SelectItem<?> item : clause) {
      Expression e = item.getExpression();
      if (e instanceof AllColumns) {
        for (String c : t.metadata.getColumnNames()) {
          returning.names.add(c);
          returning.expressions.add(new net.sf.jsqlparser.schema.Column(new Table(qualifier), c));
          returning.column(t, c);
        }
      } else {
        returning.names.add(
            item.getAlias() != null ? unquote(item.getAlias().getName()) : label(e));
        returning.expressions.add(e);
        if (isColumn(e) && t.metadata.getColumnNames().contains(columnName(e))) {
          returning.column(t, columnName(e));
        } else {
          returning.oids.add(0);
          returning.typmods.add(-1);
        }
      }
    }
    return returning;
  }

  /** An Update of {@code t} setting {@code columns} to the matching entries of {@code values}. */
  private static Mutation update(Target t, List<String> columns, Map<String, Object> values) {
    UpdateBuilder.Buildable b =
        Update.newBuilder()
            .namespace(t.namespace)
            .table(t.table)
            .partitionKey(keyOf(t, t.metadata.getPartitionKeyNames(), values));
    if (!t.metadata.getClusteringKeyNames().isEmpty()) {
      b.clusteringKey(keyOf(t, t.metadata.getClusteringKeyNames(), values));
    }
    for (String column : columns) {
      b.value(writtenColumn(t, column, values.get(column)));
    }
    return b.build();
  }

  private Plan updateByScan(net.sf.jsqlparser.statement.update.Update update)
      throws ExecutionException {
    Target t = target(update.getTable());
    String qualifier = qualifierOf(update.getTable(), t);
    List<String> setColumns = new ArrayList<>();
    List<Expression> setValues = new ArrayList<>();
    for (UpdateSet set : update.getUpdateSets()) {
      for (int i = 0; i < set.getColumns().size(); i++) {
        setColumns.add(columnName(set.getColumn(i)));
        setValues.add(defaultToNull(set.getValue(i)));
      }
    }
    for (String column : setColumns) {
      existing(t.metadata, column);
      if (keyColumns(t).contains(column)) {
        throw new IllegalArgumentException("A primary key column cannot be updated: " + column);
      }
    }
    Returning returning = returning(update.getReturningClause(), t, qualifier);
    // UPDATE ... FROM: the FROM items join the target in the driving read
    List<Join> joins = new ArrayList<>();
    if (update.getFromItem() != null) {
      joins.add(simpleJoin(update.getFromItem()));
    }
    if (update.getJoins() != null) {
      joins.addAll(update.getJoins());
    }
    Plan read =
        readFor(
            update.getTable(),
            qualifier,
            update.getWhere(),
            returning == null ? keyColumns(t) : t.metadata.getColumnNames(),
            setValues,
            joins,
            joins.isEmpty() ? null : keyColumns(t));
    return Plan.write(
        read,
        (row, reader) -> {
          Map<String, Object> values = new LinkedHashMap<>(row);
          for (int i = 0; i < setColumns.size(); i++) {
            values.put(setColumns.get(i), row.get("$" + (i + 1)));
          }
          return new Effect(update(t, setColumns, values), left(t, qualifier, values));
        },
        "UPDATE",
        "Update "
            + t.namespace
            + "."
            + t.table
            + ": one ScalarDB Update per row read"
            + (joins.isEmpty() ? "" : " (joined with FROM, one per key)"),
        returning);
  }

  /** The FROM items of {@code DELETE ... USING}, empty without the clause. */
  private static List<FromItem> usingItems(net.sf.jsqlparser.statement.delete.Delete delete) {
    if (delete.getUsingFromItemList() != null) {
      return delete.getUsingFromItemList();
    }
    return delete.getUsingList() == null
        ? Collections.<FromItem>emptyList()
        : new ArrayList<FromItem>(delete.getUsingList());
  }

  /** A comma join: {@code FROM a, b}. */
  private static Join simpleJoin(FromItem item) {
    Join join = new Join();
    join.setSimple(true);
    join.setRightItem(item);
    return join;
  }

  private Plan deleteByScan(net.sf.jsqlparser.statement.delete.Delete delete)
      throws ExecutionException {
    Target t = target(delete.getTable());
    String qualifier = qualifierOf(delete.getTable(), t);
    Returning returning = returning(delete.getReturningClause(), t, qualifier);
    // DELETE ... USING: the USING items join the target in the driving read
    List<Join> joins = new ArrayList<>();
    for (FromItem item : usingItems(delete)) {
      joins.add(simpleJoin(item));
    }
    Plan read =
        readFor(
            delete.getTable(),
            qualifier,
            delete.getWhere(),
            returning == null ? keyColumns(t) : t.metadata.getColumnNames(),
            Collections.<Expression>emptyList(),
            joins,
            joins.isEmpty() ? null : keyColumns(t));
    return Plan.write(
        read,
        (row, reader) -> {
          DeleteBuilder.Buildable b =
              Delete.newBuilder()
                  .namespace(t.namespace)
                  .table(t.table)
                  .partitionKey(keyOf(t, t.metadata.getPartitionKeyNames(), row));
          if (!t.metadata.getClusteringKeyNames().isEmpty()) {
            b.clusteringKey(keyOf(t, t.metadata.getClusteringKeyNames(), row));
          }
          return new Effect(b.build(), left(t, qualifier, row));
        },
        "DELETE",
        "Delete "
            + t.namespace
            + "."
            + t.table
            + ": one ScalarDB Delete per row read"
            + (joins.isEmpty() ? "" : " (joined with USING, one per key)"),
        returning);
  }

  /**
   * INSERT driven by rows: from a query, or literal rows that need RETURNING or an ON CONFLICT that
   * must read the existing row (DO NOTHING, or DO UPDATE with its own expressions).
   */
  /** The types of an INSERT's placeholders: each VALUES item and SET value takes its column's. */
  private void inferWriteParameters(net.sf.jsqlparser.statement.insert.Insert insert)
      throws ExecutionException {
    Target t = target(insert.getTable());
    if (insert.getSelect() instanceof Values) {
      List<String> names = insertColumns(insert, t);
      for (List<Expression> tuple : tuples(((Values) insert.getSelect()).getExpressions())) {
        for (int i = 0; i < Math.min(tuple.size(), names.size()); i++) {
          inferAssigned(t, names.get(i), tuple.get(i));
        }
      }
    }
    InsertConflictAction action = insert.getConflictAction();
    if (action != null && action.getUpdateSets() != null) {
      for (UpdateSet set : action.getUpdateSets()) {
        for (int i = 0; i < set.getColumns().size(); i++) {
          inferAssigned(t, columnName(set.getColumn(i)), set.getValue(i));
        }
      }
    }
  }

  private Plan insertRows(net.sf.jsqlparser.statement.insert.Insert insert)
      throws ExecutionException {
    Target t = target(insert.getTable());
    String qualifier = qualifierOf(insert.getTable(), t);
    List<String> names = insert.getSelect() == null ? new ArrayList<>() : insertColumns(insert, t);
    Plan read;
    List<String> keys; // the driving row's key for each target column
    if (insert.getSelect() == null) {
      // INSERT ... DEFAULT VALUES: there are no column defaults here, so every column is NULL,
      // which the key columns reject
      read =
          Plan.constant(
              names, Collections.singletonList(new LinkedHashMap<String, Object>()), "SELECT");
      keys = names;
    } else if (insert.getSelect() instanceof Values) {
      List<Map<String, Object>> rows = new ArrayList<>();
      List<List<Expression>> tuples = tuples(((Values) insert.getSelect()).getExpressions());
      for (List<Expression> tuple : tuples) {
        checkRowLength(insert, names, tuple, tuples.get(0).size(), t);
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
          row.put(
              names.get(i),
              i >= tuple.size() || isDefaultKeyword(tuple.get(i))
                  ? null
                  : evalLiteral(tuple.get(i)));
        }
        rows.add(row);
      }
      read = Plan.constant(names, rows, "SELECT");
      keys = names;
    } else {
      read = select(insert.getSelect(), null);
      if (read.outputNames.size() != names.size()) {
        throw new IllegalArgumentException(
            "INSERT ... SELECT must produce one value per target column: " + insert);
      }
      keys = read.outputNames;
    }
    InsertConflictAction action = insert.getConflictAction();
    List<String> setColumns = new ArrayList<>();
    List<Expression> setValues = new ArrayList<>();
    boolean doUpdate = false;
    if (action != null) {
      checkConflictTarget(insert.getConflictTarget(), t);
      doUpdate = action.getConflictActionType() == ConflictActionType.DO_UPDATE;
      if (doUpdate) {
        for (UpdateSet set : action.getUpdateSets()) {
          for (int i = 0; i < set.getColumns().size(); i++) {
            String column = existing(t.metadata, columnName(set.getColumn(i)));
            if (keyColumns(t).contains(column)) {
              throw new IllegalArgumentException(
                  "A primary key column cannot be updated: " + column);
            }
            setColumns.add(column);
            setValues.add(defaultToNull(set.getValue(i)));
          }
        }
      }
    }
    Expression conflictWhere = action == null ? null : action.getWhereExpression();
    boolean update = doUpdate;
    List<String> allColumns = new ArrayList<>(t.metadata.getColumnNames());
    // The keys this execution has proposed so far: the Gets all run before the one mutate, so a
    // key repeated within the statement is not found by them (a reader per execution resets it)
    Set<List<Key>> proposedKeys = new HashSet<>();
    Object[] execution = {null};
    return Plan.write(
        read,
        (row, reader) -> {
          Map<String, Object> proposed = new LinkedHashMap<>();
          for (int i = 0; i < names.size(); i++) {
            proposed.put(names.get(i), row.get(keys.get(i)));
          }
          if (action != null) {
            Key partition = keyOf(t, t.metadata.getPartitionKeyNames(), proposed);
            // every column by name, as in operation(): ABAC's data tag column is otherwise left out
            GetBuilder.BuildableGet get =
                Get.newBuilder()
                    .namespace(t.namespace)
                    .table(t.table)
                    .partitionKey(partition)
                    .projections(t.metadata.getColumnNames());
            List<Key> key = new ArrayList<>(Collections.singletonList(partition));
            if (!t.metadata.getClusteringKeyNames().isEmpty()) {
              Key clustering = keyOf(t, t.metadata.getClusteringKeyNames(), proposed);
              get.clusteringKey(clustering);
              key.add(clustering);
            }
            if (execution[0] != reader) {
              execution[0] = reader;
              proposedKeys.clear();
            }
            if (!proposedKeys.add(key)) {
              // As PostgreSQL: the row inserted a moment ago is the conflict
              if (!update) {
                return null;
              }
              throw new PgException.Argument(
                  "21000", "ON CONFLICT DO UPDATE command cannot affect row a second time");
            }
            Get existing = get.build();
            List<Map<String, Object>> found = reader.rows(existing, qualifier, allColumns);
            if (!found.isEmpty()) {
              if (!update) {
                return null; // DO NOTHING
              }
              Map<String, Object> env = found.get(0);
              Map<String, Object> values = new LinkedHashMap<>();
              for (String c : allColumns) {
                values.put(c, env.get(qualifier + "." + c));
              }
              for (Map.Entry<String, Object> e : proposed.entrySet()) {
                Object coerced = writtenValue(t, e.getKey(), e.getValue());
                env.put("excluded." + e.getKey(), coerced);
                env.put("EXCLUDED." + e.getKey(), coerced);
              }
              List<Map<String, Object>> group = Collections.singletonList(env);
              if (conflictWhere != null
                  && !Evaluator.isTrue(Evaluator.eval(conflictWhere, group, read.context(null)))) {
                return null;
              }
              for (int i = 0; i < setColumns.size(); i++) {
                values.put(
                    setColumns.get(i), Evaluator.eval(setValues.get(i), group, read.context(null)));
              }
              return new Effect(update(t, setColumns, values), left(t, qualifier, values));
            }
          }
          Map<String, Column<?>> columns = new LinkedHashMap<>();
          for (Map.Entry<String, Object> e : proposed.entrySet()) {
            columns.put(e.getKey(), writtenColumn(t, e.getKey(), e.getValue()));
          }
          return new Effect(put(t, columns, false), left(t, qualifier, proposed));
        },
        "INSERT 0",
        "Insert "
            + t.namespace
            + "."
            + t.table
            + ": one ScalarDB Insert per row"
            + (action == null
                ? ""
                : ", after a Get to detect a conflict ("
                    + (update ? "DO UPDATE" : "DO NOTHING")
                    + ")"),
        returning(insert.getReturningClause(), t, qualifier));
  }

  /** A ScalarDB table and its metadata. */
  static final class Target {
    final String namespace;
    final String table;
    final TableMetadata metadata; // ScalarDB's (ColumnMap.metadata); types in columns
    final ColumnMap columns;

    Target(String namespace, String table, ColumnMap columns) {
      this.namespace = namespace;
      this.table = table;
      this.metadata = columns.metadata();
      this.columns = columns;
    }
  }

  /** A table's column map, from the session's cache or ScalarDB; null if there is no table. */
  @Nullable ColumnMap columnMap(String namespace, String table) throws ExecutionException {
    String key = namespace + "." + table;
    ColumnMap columns = columnMaps.get(key);
    if (columns == null) {
      TableMetadata stored = admin.getTableMetadata(namespace, table);
      if (stored == null) {
        return null;
      }
      columns = ColumnMap.of(namespace, table, stored, typeStore.read(namespace, table, stored));
      columnMaps.put(key, columns);
    }
    return columns;
  }

  Target target(Table table) throws ExecutionException {
    String name = unquote(table.getName());
    List<String> candidates =
        table.getSchemaName() == null
            ? searchNamespaces() // an unqualified table: the first namespace of search_path that
            // has it
            : Collections.singletonList(namespace(table.getSchemaName()));
    for (String namespace : candidates) {
      ColumnMap columns = columnMap(userNamespace(namespace), name);
      if (columns != null) {
        return new Target(namespace, name, columns);
      }
    }
    // as PostgreSQL words it, with the name as written: relation "pg_catalog.pg_amop" does not
    // exist
    throw new PgException.Argument(
        "42P01",
        "relation \""
            + (table.getSchemaName() == null ? "" : unquote(table.getSchemaName()) + ".")
            + name
            + "\" does not exist");
  }

  // ---- WHERE clause ----

  static List<Expression> conjuncts(@Nullable Expression where) {
    List<Expression> out = new ArrayList<>();
    if (where != null) {
      split(where, out);
    }
    return out;
  }

  private static void split(Expression e, List<Expression> out) {
    e = unwrap(e);
    if (e instanceof AndExpression) {
      split(((AndExpression) e).getLeftExpression(), out);
      split(((AndExpression) e).getRightExpression(), out);
    } else if (e instanceof net.sf.jsqlparser.expression.operators.relational.Between
        && !((net.sf.jsqlparser.expression.operators.relational.Between) e).isNot()
        && !((net.sf.jsqlparser.expression.operators.relational.Between) e).isUsingSymmetric()) {
      // x BETWEEN a AND b = x >= a AND x <= b, so that both bounds can be pushed down
      net.sf.jsqlparser.expression.operators.relational.Between b =
          (net.sf.jsqlparser.expression.operators.relational.Between) e;
      out.add(
          new GreaterThanEquals()
              .withLeftExpression(b.getLeftExpression())
              .withRightExpression(b.getBetweenExpressionStart()));
      out.add(
          new MinorThanEquals()
              .withLeftExpression(b.getLeftExpression())
              .withRightExpression(b.getBetweenExpressionEnd()));
    } else {
      out.add(e);
    }
  }

  /**
   * WHERE conditions for a keyed UPDATE/DELETE, which ScalarDB must be able to evaluate entirely;
   * null when one of them is false for every row. The plan was found keyed with the values it was
   * planned with, and a cached plan meets others: {@code amount = $2} with 1.005 on a numeric(10,2)
   * column, or {@code id = $1} with 5000000000 on an integer column, matches nothing.
   */
  @Nullable private static List<ConditionalExpression> conditions(
      @Nullable Expression where, Target t, Statement statement, Evaluator.Context ctx) {
    List<ConditionalExpression> out = new ArrayList<>();
    for (Expression c : conjuncts(where)) {
      ConditionalExpression p;
      try {
        p = pushable(c, t, null, ctx);
      } catch (IllegalArgumentException e) {
        if (Boolean.FALSE.equals(Plan.beyondRange(c, e, Collections.emptyMap(), ctx))) {
          return null;
        }
        throw e;
      }
      if (p == null) {
        if (neverTrue(c, t, ctx)) {
          return null;
        }
        throw new PgException.Argument("0A000", "Unsupported condition " + c + " in: " + statement);
      }
      out.add(p);
    }
    return out;
  }

  /**
   * A {@code column op value} conjunct that {@link #pushable} could not express because it is false
   * for every row: a comparison with NULL, with a value beyond an integer column's range, or one no
   * stored value of a numeric(p,s) column satisfies (a null scaled condition means that).
   */
  private static boolean neverTrue(Expression e, Target t, Evaluator.Context ctx) {
    if (!(e instanceof ComparisonOperator)
        || operator((ComparisonOperator) e) == null
        || !isColumn(((ComparisonOperator) e).getLeftExpression())) {
      return false;
    }
    ComparisonOperator c = (ComparisonOperator) e;
    String name = existing(t.metadata, columnName(c.getLeftExpression()));
    Object v =
        forColumn(
            t, name, c.getRightExpression(), Collections.<Map<String, Object>>emptyList(), ctx);
    if (v == null || t.columns.type(name).kind == ColumnType.Kind.NUMERIC_SCALED) {
      return true;
    }
    try {
      valueColumn(t, name, c.getRightExpression(), null, ctx);
      return false;
    } catch (IllegalArgumentException failure) {
      return Boolean.FALSE.equals(Plan.beyondRange(e, failure, Collections.emptyMap(), ctx));
    }
  }

  /**
   * The value of {@code e} for column {@code name}: for a TEXT column an untyped parameter is the
   * text it was sent as ('007' stays 007), for any other column its guess (see Evaluator.Untyped).
   */
  @Nullable static Object forColumn(
      Target t, String name, Expression e, List<Map<String, Object>> rows, Evaluator.Context ctx) {
    return t.columns.type(existing(t.metadata, name)).comparesAsText()
        ? Evaluator.evalText(e, rows, ctx)
        : Evaluator.eval(e, rows, ctx);
  }

  /** A column holding the value of a constant expression (a literal or a placeholder). */
  private static Column<?> value(Target t, String name, Expression e, Evaluator.Context ctx) {
    Object v = forColumn(t, name, e, Collections.<Map<String, Object>>emptyList(), ctx);
    if (t.columns.type(existing(t.metadata, name)).encoded()) {
      return writtenColumn(t, name, v); // rounded to the declared scale, not to an integer
    }
    DataType type = t.metadata.getColumnDataType(name);
    if ((type == DataType.INT || type == DataType.BIGINT)
        && v instanceof Number
        && !Evaluator.isIntegral(v)
        && Evaluator.isFinite(v)) {
      // a decimal written to an integer column is rounded as a cast rounds it (numeric 2.5 is 3,
      // float8 2.5 is 2)
      try {
        v = Evaluator.rounded((Number) v);
      } catch (ArithmeticException outOfRange) {
        throw new ArithmeticException(
            (type == DataType.INT ? "integer" : "bigint") + " out of range");
      }
    }
    return columnFromValue(t, name, v);
  }

  /** Returns the ScalarDB condition for a {@code column op literal} conjunct, or null. */
  @Nullable static ConditionalExpression pushable(Expression e, Target t) {
    return pushable(e, t, null, null);
  }

  /**
   * As {@link #pushable(Expression, Target)}, with the compared value evaluated in {@code ctx} (and
   * {@code row}) when a context is given, so placeholders take their bound values. Null when the
   * condition cannot be pushed, or when its value is NULL, which matches nothing.
   */
  @Nullable static ConditionalExpression pushable(
      Expression e, Target t, @Nullable Map<String, Object> row, @Nullable Evaluator.Context ctx) {
    ConditionalExpression p = condition(e, t, row, ctx);
    // Cluster stores an encrypted column as ciphertext and refuses it in a condition, so it is
    // compared here, on the decrypted value
    return p == null || t.metadata.getEncryptedColumnNames().contains(p.getColumn().getName())
        ? null
        : p;
  }

  @Nullable private static ConditionalExpression condition(
      Expression e, Target t, @Nullable Map<String, Object> row, @Nullable Evaluator.Context ctx) {
    if (e instanceof IsNullExpression) {
      IsNullExpression n = (IsNullExpression) e;
      if (!isColumn(n.getLeftExpression())) {
        return null;
      }
      Column<?> column = column(t, columnName(n.getLeftExpression()), null);
      return ConditionBuilder.buildConditionalExpression(
          column,
          isNotNull(n)
              ? ConditionalExpression.Operator.IS_NOT_NULL
              : ConditionalExpression.Operator.IS_NULL);
    }
    if (e instanceof LikeExpression) {
      LikeExpression like = (LikeExpression) e;
      // ScalarDB's LIKE is case-sensitive, so ILIKE (and RLIKE/REGEXP) stay in memory
      if (like.getLikeKeyWord() != LikeExpression.KeyWord.LIKE
          || Evaluator.collation().hasCanonicalTextForm() // refused under ICU, in memory
          || !isColumn(like.getLeftExpression())
          || !(like.getRightExpression() instanceof StringValue
              || like.getRightExpression() instanceof JdbcParameter)
          || (like.getEscape() != null && !(like.getEscape() instanceof StringValue))
          || isDeclaredNumeric(t, columnName(like.getLeftExpression()))
          || t.columns.type(existing(t.metadata, columnName(like.getLeftExpression()))).kind
              == ColumnType.Kind.CHAR) {
        return null; // a char is stored unpadded, and LIKE sees it padded: in memory
      }
      Column<?> column =
          valueColumn(t, columnName(like.getLeftExpression()), like.getRightExpression(), row, ctx);
      if (!(column instanceof TextColumn)) {
        return null;
      }
      // PostgreSQL's default escape character is a backslash
      String escape = like.getEscape() == null ? "\\" : literal(like.getEscape());
      return ConditionBuilder.buildLikeExpression(
          (TextColumn) column,
          like.isNot()
              ? ConditionalExpression.Operator.NOT_LIKE
              : ConditionalExpression.Operator.LIKE,
          escape);
    }
    if (e instanceof ComparisonOperator) {
      ComparisonOperator c = (ComparisonOperator) e;
      ConditionalExpression.Operator op = operator(c);
      if (op == null
          || !isColumn(c.getLeftExpression())
          || !(isLiteral(c.getRightExpression())
              || (ctx != null && isConstant(c.getRightExpression())))) {
        return null;
      }
      ColumnType declared = t.columns.type(existing(t.metadata, columnName(c.getLeftExpression())));
      if (!declared.comparableWhenStored()) {
        return null; // "1.50" is not "1.5" as text, and "10" sorts before "9": in memory
      }
      if (declared.kind == ColumnType.Kind.NUMERIC_SCALED) {
        Expression right = c.getRightExpression();
        Object v =
            ctx == null
                ? (isNull(right) ? null : literal(right))
                : forColumn(
                    t,
                    columnName(c.getLeftExpression()),
                    right,
                    row == null
                        ? Collections.<Map<String, Object>>emptyList()
                        : Collections.singletonList(row),
                    ctx);
        return declared.scaledCondition(columnName(c.getLeftExpression()), op, v);
      }
      Column<?> column;
      try {
        column =
            valueColumn(t, columnName(c.getLeftExpression()), c.getRightExpression(), row, ctx);
      } catch (IllegalArgumentException | ArithmeticException failure) {
        if (failure instanceof PgException.NotRepresentable) {
          return null; // no stored form (a finer timestamp, a longer string): compared in memory
        }
        if (c.getRightExpression() instanceof DoubleValue
            || (c.getRightExpression() instanceof SignedExpression
                && ((SignedExpression) c.getRightExpression()).getExpression()
                    instanceof DoubleValue)) {
          // grade > 12.5 compares as numbers in PostgreSQL; ScalarDB has no such condition on an
          // integer column, so the comparison is evaluated in memory
          return null;
        }
        if (row == null
            && !(c.getRightExpression() instanceof StringValue) // '5000000000' is integer input
            && failure instanceof IllegalArgumentException
            && isOutOfRange((IllegalArgumentException) failure)) {
          return null; // id = 5000000000 is false, not an error, in PostgreSQL: compared in memory
        }
        throw failure;
      }
      return column == null ? null : ConditionBuilder.buildConditionalExpression(column, op);
    }
    return null;
  }

  /** The compared value as a column, or null for NULL. Without a context, the literal's text. */
  @Nullable private static Column<?> valueColumn(
      Target t,
      String name,
      Expression value,
      @Nullable Map<String, Object> row,
      @Nullable Evaluator.Context ctx) {
    if (ctx == null) {
      return isNull(value) ? null : column(t, name, value);
    }
    Object v =
        forColumn(
            t,
            name,
            value,
            row == null
                ? Collections.<Map<String, Object>>emptyList()
                : Collections.singletonList(row),
            ctx);
    return v == null ? null : columnFromValue(t, name, v);
  }

  @Nullable static ConditionalExpression.Operator operator(ComparisonOperator e) {
    if (e instanceof EqualsTo) {
      return ConditionalExpression.Operator.EQ;
    }
    if (e instanceof NotEqualsTo) {
      return ConditionalExpression.Operator.NE;
    }
    if (e instanceof GreaterThan) {
      return ConditionalExpression.Operator.GT;
    }
    if (e instanceof GreaterThanEquals) {
      return ConditionalExpression.Operator.GTE;
    }
    if (e instanceof MinorThan) {
      return ConditionalExpression.Operator.LT;
    }
    if (e instanceof MinorThanEquals) {
      return ConditionalExpression.Operator.LTE;
    }
    return null;
  }

  /** Finds the first condition on {@code name} with one of {@code ops}; removes and returns it. */
  @Nullable private static ConditionalExpression take(
      List<ConditionalExpression> conditions, String name, ConditionalExpression.Operator... ops) {
    for (ConditionalExpression c : conditions) {
      if (c.getColumn().getName().equals(name) && Arrays.asList(ops).contains(c.getOperator())) {
        conditions.remove(c);
        return c;
      }
    }
    return null;
  }

  /**
   * Builds a key from '=' conditions on all of {@code names}, or returns null if any is missing.
   */
  @Nullable private static Key equalsKey(List<ConditionalExpression> conditions, Collection<String> names) {
    List<ConditionalExpression> found = new ArrayList<>();
    for (String name : names) {
      for (ConditionalExpression c : conditions) {
        if (c.getColumn().getName().equals(name)
            && c.getOperator() == ConditionalExpression.Operator.EQ) {
          found.add(c);
          break;
        }
      }
    }
    if (found.size() != names.size()) {
      return null;
    }
    conditions.removeAll(found);
    List<Column<?>> columns = new ArrayList<>();
    for (ConditionalExpression c : found) {
      columns.add(c.getColumn());
    }
    return key(columns);
  }

  private static Key primaryKey(
      List<ConditionalExpression> conditions, Collection<String> names, Statement statement) {
    Key key = equalsKey(conditions, names);
    if (key == null) {
      throw new IllegalArgumentException(
          "The full primary key must be specified with '=' conditions: " + statement);
    }
    return key;
  }

  private static Key removeKey(Map<String, Column<?>> columns, Collection<String> names) {
    List<Column<?>> key = new ArrayList<>();
    for (String name : names) {
      Column<?> c = columns.remove(name);
      if (c == null || c.hasNullValue()) {
        throw notNullViolation(name);
      }
      key.add(c);
    }
    return key(key);
  }

  /** A key column without a value: PostgreSQL's not-null violation (SQLSTATE 23502). */
  private static IllegalArgumentException notNullViolation(String column) {
    return new PgException.Argument(
        "23502", "null value in column \"" + column + "\" violates not-null constraint");
  }

  private static Key key(List<Column<?>> prefix, Column<?>... rest) {
    Key.Builder b = Key.newBuilder();
    for (Column<?> c : prefix) {
      b.add(c);
    }
    for (Column<?> c : rest) {
      b.add(c);
    }
    return b.build();
  }

  private static AndConditionSet and(List<ConditionalExpression> conditions) {
    return ConditionSetBuilder.andConditionSet(new HashSet<>(conditions)).build();
  }

  // ---- expressions, literals, and identifiers ----

  /**
   * Whether {@code e} holds text from a ScalarDB table, which has ScalarDB's collation, as a
   * PostgreSQL column has its own. Catalog text is byte-ordered whatever the database's collation,
   * as in PostgreSQL, and so are literals and parameters. A column not resolved to a source counts
   * as table text unless every source is a catalog.
   */
  static boolean collatedText(Expression e, List<LogicalPlan.Source> sources) {
    boolean catalogOnly =
        !sources.isEmpty() && sources.stream().allMatch(s -> s.catalogTable != null);
    for (net.sf.jsqlparser.schema.Column c : columnRefs(e)) {
      LogicalPlan.Source s = sourceOf(c, sources);
      if (s == null ? !catalogOnly : s.catalogTable == null && isTextOid(inferOid(c, sources))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTextOid(int oid) {
    return oid == 0 || oid == 25 || oid == 1042 || oid == 1043; // unknown, text, bpchar, varchar
  }

  /** The column references in {@code e}, not descending into subqueries. */
  /**
   * In PostgreSQL a comma binds more loosely than JOIN: {@code FROM a, b RIGHT JOIN c ON ...} is a
   * times (b RIGHT JOIN c), whose ON cannot see a (42P01). The frontend joins left to right, which
   * is the same for INNER and LEFT joins but not for RIGHT or FULL ones, so those are refused after
   * a comma.
   */
  private static void checkCommaJoins(PlainSelect select) {
    if (select.getJoins() == null || select.getJoins().stream().noneMatch(Join::isSimple)) {
      return;
    }
    Set<String> earlier = new HashSet<>();
    Set<String> group = new HashSet<>();
    group.add(fromName(select.getFromItem()));
    for (Join j : select.getJoins()) {
      if (j.isSimple()) {
        earlier.addAll(group);
        group = new HashSet<>();
        group.add(fromName(j.getRightItem()));
        continue;
      }
      group.add(fromName(j.getRightItem()));
      for (Expression on : j.getOnExpressions()) {
        for (net.sf.jsqlparser.schema.Column c : columnRefs(on)) {
          String q =
              c.getTable() == null || c.getTable().getName() == null
                  ? null
                  : unquote(c.getTable().getName()).toLowerCase(Locale.ROOT);
          if (q != null && !group.contains(q) && earlier.contains(q)) {
            throw new PgException.Argument(
                "42P01", "invalid reference to FROM-clause entry for table \"" + q + "\"");
          }
        }
      }
      if (!earlier.isEmpty() && (j.isRight() || j.isFull())) {
        throw new PgException.Argument(
            "0A000",
            "Unsupported: a RIGHT or FULL JOIN after a comma in FROM (it joins only the items after"
                + " the comma)");
      }
    }
  }

  /** The name a FROM item is referred to by: its alias, or a table's name; "" for neither. */
  private static String fromName(FromItem item) {
    if (item.getAlias() != null) {
      return unquote(item.getAlias().getName()).toLowerCase(Locale.ROOT);
    }
    return item instanceof Table ? unquote(((Table) item).getName()).toLowerCase(Locale.ROOT) : "";
  }

  static List<net.sf.jsqlparser.schema.Column> columnRefs(Expression e) {
    List<net.sf.jsqlparser.schema.Column> out = new ArrayList<>();
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
            String name = unquote(column.getColumnName());
            if (!(column.getTable() == null && (isBooleanLiteral(name) || isKeyword(name)))) {
              out.add(column);
            }
            if (column.getArrayConstructor() != null) {
              // JSQLParser reads a[b.i] as one column; its subscript refers to columns too
              column.getArrayConstructor().getExpressions().accept(this, context);
            }
            return null;
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
    return out;
  }

  /**
   * An aggregate whose arguments name no column of its own query but an outer one (or a subquery)
   * belongs to the outer query in PostgreSQL, aggregating over its rows; evaluated here it would
   * run once per outer row, so it is refused.
   */
  private static void refuseOuterAggregates(Expression e, Scope scope) {
    List<List<Expression>> arguments = new ArrayList<>(); // per aggregate: arguments and FILTER
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(Function f, S context) {
            if ((Evaluator.isAggregate(f) || isGrouping(f)) && f.getParameters() != null) {
              arguments.add(Collections.singletonList(f.getParameters()));
            }
            return super.visit(f, context);
          }

          @Override
          public <S> Void visit(net.sf.jsqlparser.expression.AnalyticExpression a, S context) {
            if (a.getType() != net.sf.jsqlparser.expression.AnalyticType.OVER) {
              List<Expression> parts = new ArrayList<>();
              if (a.getExpression() != null) {
                parts.add(a.getExpression());
              }
              if (a.getFilterExpression() != null) {
                parts.add(a.getFilterExpression());
              }
              arguments.add(parts);
            }
            return super.visit(a, context);
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
    for (List<Expression> a : arguments) {
      List<net.sf.jsqlparser.schema.Column> columns = new ArrayList<>();
      boolean subqueries = false;
      for (Expression part : a) {
        columns.addAll(columnRefs(part));
        subqueries |= !subselects(part).isEmpty();
      }
      boolean local = columns.stream().anyMatch(c -> scope.isLocal(scope.resolve(c)));
      if (!local && (!columns.isEmpty() || subqueries)) {
        // ponytail: also refuses an aggregate over an uncorrelated subquery alone, which
        // PostgreSQL keeps at this level
        throw new PgException.Argument(
            "0A000", "Unsupported aggregate over only outer-query columns: " + a.get(0));
      }
    }
  }

  /** The subqueries directly in {@code e}, not descending into them. */
  static List<Select> subselects(Expression e) {
    List<Select> out = new ArrayList<>();
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            out.add(select);
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            // JSQLParser dispatches every subquery here; a bare SELECT appears in ARRAY(SELECT ...)
            if (!(select instanceof ParenthesedSelect || select instanceof PlainSelect)) {
              throw new PgException.Argument("0A000", "Unsupported subquery: " + select);
            }
            out.add(select);
            return null;
          }

          @Override
          public <S> Void visit(
              net.sf.jsqlparser.expression.AnyComparisonExpression any, S context) {
            // the adapter does not dispatch the subquery of x op ANY|ALL (SELECT ...)
            out.add(any.getSelect());
            return null;
          }
        });
    return out;
  }

  /** A GROUP BY or DISTINCT ON item: an ordinal, an output column's name, or an expression. */
  private Expression groupExpression(Expression g, Scope scope, LogicalPlan plan)
      throws ExecutionException {
    if (g instanceof LongValue) {
      long n = ((LongValue) g).getValue();
      if (n < 1 || n > plan.outputExpressions.size()) {
        throw new IllegalArgumentException("GROUP BY position " + n + " is not in select list");
      }
      return plan.outputExpressions.get((int) n - 1);
    }
    try {
      analyze(g, scope, plan.subplans);
      return g;
    } catch (IllegalArgumentException e) {
      // the item may name an output column when no input column has that name
      int output =
          isColumn(g) && ((net.sf.jsqlparser.schema.Column) g).getTable() == null
              ? plan.outputLabels.indexOf(columnName(g))
              : -1;
      if (output < 0 || !String.valueOf(e.getMessage()).startsWith("Unknown column")) {
        throw e;
      }
      return plan.outputExpressions.get(output);
    }
  }

  /** An ORDER BY element resolved the same way, as text, for the DISTINCT ON check. */
  private static String orderText(OrderByElement o, LogicalPlan plan) {
    Expression e = o.getExpression();
    if (e instanceof LongValue) {
      int n = (int) ((LongValue) e).getValue();
      return n >= 1 && n <= plan.outputExpressions.size()
          ? plan.outputExpressions.get(n - 1).toString()
          : e.toString();
    }
    if (isColumn(e) && ((net.sf.jsqlparser.schema.Column) e).getTable() == null) {
      int output = plan.outputLabels.indexOf(columnName(e));
      if (output >= 0) {
        return plan.outputExpressions.get(output).toString();
      }
    }
    return e.toString();
  }

  /**
   * GROUP BY items, and GROUPING SETS, ROLLUP and CUBE. A plain item is one set; ROLLUP gives the
   * prefixes of its items, CUBE every subset, GROUPING SETS the sets as written; items combine as a
   * cross product. {@code groupBy} collects every expression, {@code groupingSets} the sets.
   */
  private void bindGroupBy(
      net.sf.jsqlparser.statement.select.GroupByElement groupBy, Scope scope, LogicalPlan plan)
      throws ExecutionException {
    List<List<List<Expression>>> factors = new ArrayList<>(); // per item, its alternative sets
    boolean plain = true;
    for (Object o : groupBy.getGroupByExpressionList()) {
      Expression g = (Expression) o;
      if (g instanceof Function
          && (((Function) g).getName().equalsIgnoreCase("ROLLUP")
              || ((Function) g).getName().equalsIgnoreCase("CUBE"))) {
        plain = false;
        List<List<Expression>> items = new ArrayList<>();
        for (Expression p : ((Function) g).getParameters()) {
          items.add(groupItems(p, scope, plan)); // (a, b) is one composite item
        }
        List<List<Expression>> sets = new ArrayList<>();
        if (((Function) g).getName().equalsIgnoreCase("ROLLUP")) {
          for (int n = items.size(); n >= 0; n--) {
            sets.add(flatten(items.subList(0, n)));
          }
        } else {
          for (int mask = (1 << items.size()) - 1; mask >= 0; mask--) {
            List<List<Expression>> chosen = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
              if ((mask & (1 << i)) != 0) {
                chosen.add(items.get(i));
              }
            }
            sets.add(flatten(chosen));
          }
        }
        factors.add(sets);
      } else {
        factors.add(Collections.singletonList(groupItems(g, scope, plan)));
      }
    }
    if (groupBy.getGroupingSets() != null && !groupBy.getGroupingSets().isEmpty()) {
      plain = false;
      List<List<Expression>> sets = new ArrayList<>();
      for (ExpressionList<?> set : groupBy.getGroupingSets()) {
        List<Expression> members = new ArrayList<>();
        for (Object e : set) {
          members.addAll(groupItems((Expression) e, scope, plan));
        }
        sets.add(members);
      }
      factors.add(sets);
    }
    List<List<Expression>> product = new ArrayList<>();
    product.add(new ArrayList<>());
    for (List<List<Expression>> factor : factors) {
      List<List<Expression>> next = new ArrayList<>();
      for (List<Expression> prefix : product) {
        for (List<Expression> set : factor) {
          List<Expression> combined = new ArrayList<>(prefix);
          combined.addAll(set);
          next.add(combined);
        }
      }
      product = next;
    }
    plan.groupBy = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (List<Expression> set : product) {
      for (Expression e : set) {
        if (seen.add(e.toString())) {
          plan.groupBy.add(e);
        }
      }
    }
    if (!plain) {
      // ponytail: an item named by an output name or position nulls only that output column in a
      // set leaving it out (PostgreSQL's sortgroupref), which matching by expression cannot tell
      // from the same expression listed again: refused then
      List<Expression> members = flatten(product);
      for (Expression e : members) {
        boolean byOutput = plan.outputExpressions.stream().anyMatch(o -> o == e);
        long same =
            java.util.stream.Stream.concat(plan.outputExpressions.stream(), members.stream())
                .filter(o -> o != e && o.toString().equals(e.toString()))
                .count();
        if (byOutput && same > 0) {
          throw new PgException.Argument(
              "0A000",
              "Unsupported GROUPING SETS item: " + e + " named by an output column is also listed");
        }
      }
      plan.groupingSets = product;
    }
  }

  /** A GROUP BY item as its expressions: a parenthesized list is several, else one. */
  private List<Expression> groupItems(Expression g, Scope scope, LogicalPlan plan)
      throws ExecutionException {
    List<Expression> out = new ArrayList<>();
    if (g instanceof net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList) {
      for (Object e :
          (net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList<?>) g) {
        out.add(groupExpression((Expression) e, scope, plan));
      }
    } else {
      out.add(groupExpression(g, scope, plan));
    }
    return out;
  }

  private static List<Expression> flatten(List<List<Expression>> items) {
    List<Expression> out = new ArrayList<>();
    for (List<Expression> item : items) {
      out.addAll(item);
    }
    return out;
  }

  /** Registers the window calls in {@code e} and binds the columns of their windows. */
  private void bindWindows(Expression e, PlainSelect select, Scope scope, LogicalPlan plan)
      throws ExecutionException {
    for (net.sf.jsqlparser.expression.AnalyticExpression a : windowCalls(e)) {
      Windows.Spec spec = Windows.spec(a, select.getWindowDefinitions());
      // The parser's visitor covers the arguments; the window itself is bound here
      for (Expression p : spec.partitionBy) {
        analyze(p, scope, plan.subplans);
      }
      for (OrderByElement o : spec.orderBy) {
        analyze(o.getExpression(), scope, plan.subplans);
      }
      if (a.getFilterExpression() != null) {
        analyze(a.getFilterExpression(), scope, plan.subplans);
      }
      boolean known = false;
      for (Windows.Spec w : plan.windows) {
        known |= w.key().equals(spec.key());
      }
      if (!known) {
        plan.windows.add(spec);
      }
    }
  }

  /** The {@code OVER} calls in {@code e}, not descending into them or into subqueries. */
  private static List<net.sf.jsqlparser.expression.AnalyticExpression> windowCalls(Expression e) {
    List<net.sf.jsqlparser.expression.AnalyticExpression> out = new ArrayList<>();
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(
              net.sf.jsqlparser.expression.AnalyticExpression analytic, S context) {
            if (analytic.getType() == net.sf.jsqlparser.expression.AnalyticType.OVER) {
              out.add(analytic);
            }
            return null;
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
    return out;
  }

  /**
   * PostgreSQL's grouping rules (SQLSTATE 42803): no aggregate in WHERE, no aggregate inside an
   * aggregate, and in a grouped query every output or HAVING column is a grouping expression, sits
   * inside an aggregate, or belongs to a table whose whole primary key is grouped.
   */
  private static void validateGrouping(LogicalPlan plan, PlainSelect select) {
    if (select.getWhere() != null && hasAggregate(select.getWhere())) {
      throw new PgException.Argument("42803", "aggregate functions are not allowed in WHERE");
    }
    for (Expression e : plan.outputExpressions) {
      noNestedAggregate(e);
    }
    if (plan.having != null) {
      noNestedAggregate(plan.having);
    }
    if (select.getWindowDefinitions() != null) {
      Set<String> windows = new HashSet<>();
      for (net.sf.jsqlparser.expression.WindowDefinition d : select.getWindowDefinitions()) {
        if (!windows.add(d.getWindowName().toLowerCase(Locale.ROOT))) {
          throw new PgException.Argument(
              "42P20", "window \"" + d.getWindowName() + "\" is already defined");
        }
      }
    }
    List<Expression> checked = new ArrayList<>(plan.outputExpressions);
    if (plan.having != null) {
      checked.add(plan.having);
    }
    for (Expression e : checked) {
      for (Function g : groupingCalls(e)) {
        // GROUPING's arguments are this level's grouping expressions, as in PostgreSQL
        for (Expression a : g.getParameters()) {
          if (plan.groupBy == null || !grouped(plan).contains(unwrap(a).toString())) {
            throw new PgException.Argument(
                "42803",
                "arguments to GROUPING must be grouping expressions of the associated query level");
          }
        }
      }
    }
    if (!plan.aggregated) {
      return;
    }
    Set<String> grouped = grouped(plan);
    for (Expression e : plan.outputExpressions) {
      checkGrouped(e, grouped, plan);
    }
    if (plan.having != null) {
      checkGrouped(plan.having, grouped, plan);
    }
  }

  private static boolean isGrouping(Function f) {
    return f.getName().equalsIgnoreCase("grouping");
  }

  /** The GROUPING calls in {@code e}, outside subqueries. */
  private static List<Function> groupingCalls(Expression e) {
    List<Function> out = new ArrayList<>();
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(Function f, S context) {
            if (isGrouping(f) && f.getParameters() != null) {
              out.add(f);
            }
            return super.visit(f, context);
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
    return out;
  }

  /** The grouping expressions' texts; GROUP BY c1 resolves to the output's (x + y) as written. */
  private static Set<String> grouped(LogicalPlan plan) {
    Set<String> grouped = new HashSet<>();
    if (plan.groupBy != null) {
      for (Expression g : plan.groupBy) {
        grouped.add(unwrap(g).toString());
      }
    }
    return grouped;
  }

  private static void checkGrouped(Expression e, Set<String> grouped, LogicalPlan plan) {
    e = unwrap(e);
    if (grouped.contains(e.toString())
        || (e instanceof Function && Evaluator.isAggregate((Function) e))
        || Evaluator.isFilteredAggregate(e)) {
      return;
    }
    if (e instanceof net.sf.jsqlparser.expression.AnalyticExpression) {
      for (Expression part : Windows.parts((net.sf.jsqlparser.expression.AnalyticExpression) e)) {
        checkGrouped(part, grouped, plan);
      }
      return;
    }
    for (net.sf.jsqlparser.schema.Column c : columnsOutsideAggregates(e)) {
      if (c.getTable() == null || grouped.contains(c.toString()) || keyGrouped(c, grouped, plan)) {
        continue;
      }
      throw new PgException.Argument(
          "42803", // grouping_error
          "column \""
              + c
              + "\" must appear in the GROUP BY clause or be used in an aggregate function");
    }
  }

  /** PostgreSQL's functional dependency: the column's table has its whole primary key grouped. */
  private static boolean keyGrouped(
      net.sf.jsqlparser.schema.Column c, Set<String> grouped, LogicalPlan plan) {
    String qualifier = unquote(c.getTable().getName());
    for (LogicalPlan.Source s : plan.sources) {
      if (!s.qualifier.equals(qualifier) || s.target == null) {
        continue;
      }
      List<String> keys = new ArrayList<>(s.target.metadata.getPartitionKeyNames());
      keys.addAll(s.target.metadata.getClusteringKeyNames());
      for (String key : keys) {
        if (!grouped.contains(
            new net.sf.jsqlparser.schema.Column(new Table(c.getTable().getName()), key)
                .toString())) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** The column references of {@code e} outside aggregate calls and subqueries. */
  private static List<net.sf.jsqlparser.schema.Column> columnsOutsideAggregates(Expression e) {
    List<net.sf.jsqlparser.schema.Column> out = new ArrayList<>();
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
            String name = unquote(column.getColumnName());
            if (!(column.getTable() == null && (isBooleanLiteral(name) || isKeyword(name)))) {
              out.add(column);
            }
            return null;
          }

          @Override
          public <S> Void visit(Function function, S context) {
            return Evaluator.isAggregate(function) ? null : super.visit(function, context);
          }

          @Override
          public <S> Void visit(
              net.sf.jsqlparser.expression.AnalyticExpression analytic, S context) {
            if (!Evaluator.isFilteredAggregate(analytic)) {
              for (Expression part : Windows.parts(analytic)) {
                part.accept(this, context);
              }
            }
            return null;
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
    return out;
  }

  private static void noNestedAggregate(Expression e) {
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(Function function, S context) {
            if (Evaluator.isAggregate(function) && function.getParameters() != null) {
              for (Expression p : function.getParameters()) {
                if (hasAggregate(p)) {
                  throw new PgException.Argument(
                      "42803", "aggregate function calls cannot be nested");
                }
              }
              if (function.isDistinct() && function.getOrderByElements() != null) {
                Set<String> arguments = new HashSet<>();
                function.getParameters().forEach(p -> arguments.add(p.toString()));
                for (OrderByElement o : function.getOrderByElements()) {
                  if (!arguments.contains(o.getExpression().toString())) {
                    throw new PgException.Argument(
                        "42P10",
                        "in an aggregate with DISTINCT, ORDER BY expressions must appear in"
                            + " argument list");
                  }
                }
              }
              return null;
            }
            return super.visit(function, context);
          }

          @Override
          public <S> Void visit(
              net.sf.jsqlparser.expression.AnalyticExpression analytic, S context) {
            if (Evaluator.isFilteredAggregate(analytic)
                && analytic.getFilterExpression() != null
                && hasAggregate(analytic.getFilterExpression())) {
              throw new PgException.Argument(
                  "42803", "aggregate functions are not allowed in FILTER");
            }
            return super.visit(analytic, context);
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
  }

  private static boolean hasAggregate(Expression e) {
    boolean[] found = {false};
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
          @Override
          public <S> Void visit(Function function, S context) {
            if (Evaluator.isAggregate(function)) {
              found[0] = true;
              return null;
            }
            return super.visit(function, context);
          }

          @Override
          public <S> Void visit(
              net.sf.jsqlparser.expression.AnalyticExpression analytic, S context) {
            if (analytic.getType().name().startsWith("WITHIN_GROUP")) {
              // ordered-set aggregates are not implemented; refused here, at planning, so an empty
              // input, where nothing is evaluated, cannot hide that behind an empty result
              throw new PgException.Argument(
                  "0A000", "Unsupported: ordered-set aggregates (WITHIN GROUP): " + analytic);
            }
            if (analytic.getType() == net.sf.jsqlparser.expression.AnalyticType.OVER) {
              for (Expression part : Windows.parts(analytic)) {
                part.accept(this, context);
              }
            } else {
              found[0] |= Evaluator.isFilteredAggregate(analytic);
            }
            return null;
          }

          @Override
          public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
          }

          @Override
          public <S> Void visit(Select select, S context) {
            return null;
          }
        });
    return found[0];
  }

  private static String existing(TableMetadata metadata, String name) {
    if (metadata.getColumnDataType(name) == null) {
      throw new PgException.Argument("42703", "Unknown column: " + name);
    }
    return name;
  }

  private static Column<?> column(Target t, String name, @Nullable Expression value) {
    ColumnType declared = t.columns.type(existing(t.metadata, name));
    if (value != null && declared.convertsValues() && isNumberLiteral(value)) {
      // s = 40000 compares a number, which no smallint equals; s = '40000' is smallint input
      return declared.exact(name, Evaluator.decimal(String.valueOf(literal(value))));
    }
    return columnFromText(t, name, value == null ? null : literal(value));
  }

  private static boolean isNumberLiteral(Expression e) {
    Expression v = e instanceof SignedExpression ? ((SignedExpression) e).getExpression() : e;
    return v instanceof LongValue || v instanceof DoubleValue;
  }

  /**
   * Converts text to a column of the column's type, failing with PostgreSQL's messages: invalid
   * input syntax (SQLSTATE 22P02, or 22007 for dates and times) or a value out of range (22003, or
   * 22008 for dates and times).
   */
  private static Column<?> columnFromText(Target t, String name, @Nullable String s) {
    ColumnType declared = t.columns.type(existing(t.metadata, name));
    if (declared.convertsValues()) {
      return declared.exact(name, s);
    }
    DataType type = t.metadata.getColumnDataType(name);
    if (type == DataType.TEXT && s != null && s.indexOf('\0') >= 0) {
      // PostgreSQL has no NUL in text, and some backing databases would store it
      throw new PgException.Argument("22021", "invalid byte sequence for encoding \"UTF8\": 0x00");
    }
    try {
      return parseColumn(type, name, s);
    } catch (PgException.Argument | PgException.NotRepresentable e) {
      throw e; // already PostgreSQL's code and wording
    } catch (RuntimeException e) {
      String pgType = pgTypeName(type);
      String failure = String.valueOf(e.getMessage());
      boolean temporal =
          type == DataType.DATE
              || type == DataType.TIME
              || type == DataType.TIMESTAMP
              || type == DataType.TIMESTAMPTZ;
      if (temporal && failure.contains("out of the valid range")) {
        // ScalarDB stores the years 1000 to 9999 (DB-CORE-10158 and the like)
        Matcher bounds = Pattern.compile("between (\\S+) and (\\S+)\\.").matcher(failure);
        String range =
            bounds.find()
                ? " (ScalarDB stores "
                    + pgType
                    + " values from "
                    + bounds.group(1)
                    + " to "
                    + bounds.group(2)
                    + ")"
                : "";
        throw new PgException.Argument(
            "22008", "date/time field value out of range: \"" + s + "\"" + range, e);
      }
      if (temporal && failure.contains("Invalid")) {
        // parsed, but no such day or time, e.g. 2023-02-30
        throw new PgException.Argument(
            "22008", "date/time field value out of range: \"" + s + "\"", e);
      }
      if (e instanceof ArithmeticException
          && (isInteger(s) || type == DataType.FLOAT || type == DataType.DOUBLE)) {
        // a float literal in scientific notation, as PostgreSQL shows it, rather than 1 and 309
        // zeros
        String shown =
            isInteger(s) && type != DataType.FLOAT && type != DataType.DOUBLE
                ? s
                : new BigDecimal(String.valueOf(s).trim()).stripTrailingZeros().toString();
        throw new PgException.Argument(
            "22003", "value \"" + shown + "\" is out of range for type " + pgType, e);
      }
      throw new PgException.Argument(
          temporal ? "22007" : "22P02",
          "invalid input syntax for type " + pgType + ": \"" + s + "\"",
          e);
    }
  }

  /**
   * A column holding a value being written: coerced by the column's declared type as PostgreSQL
   * coerces on INSERT and UPDATE (19.905 into numeric(10,2) is 19.91; spaces beyond a varchar's
   * length are cut), then stored exactly.
   */
  private static Column<?> writtenColumn(Target t, String name, @Nullable Object value) {
    ColumnType declared = t.columns.type(existing(t.metadata, name));
    return declared.encoded()
        ? declared.exact(name, declared.coerce(value))
        : columnFromValue(t, name, value);
  }

  /** A written value as a read returns it (RETURNING, EXCLUDED): coerced, in the client's form. */
  @Nullable private static Object writtenValue(Target t, String name, @Nullable Object value) {
    ColumnType declared = t.columns.type(existing(t.metadata, name));
    return declared.encoded() ? declared.coerce(value) : value;
  }

  static boolean isInteger(@Nullable String s) {
    return s != null && Evaluator.integerInput(s) != null;
  }

  /** {@link Evaluator#integerInput}, failing as a number parser does when {@code s} is not one. */
  private static java.math.BigInteger integerInput(String s) {
    java.math.BigInteger v = Evaluator.integerInput(s);
    if (v == null) {
      throw new NumberFormatException(s);
    }
    return v;
  }

  /** True for the failure of {@link #columnFromText} on an integer beyond the column's range. */
  static boolean isOutOfRange(IllegalArgumentException e) {
    return e instanceof PgException.OutOfRange
        || String.valueOf(e.getMessage()).contains("out of range for type");
  }

  private static String pgTypeName(DataType type) {
    switch (type) {
      case INT:
        return "integer";
      case DOUBLE:
        return "double precision";
      case FLOAT:
        return "real";
      case BLOB:
        return "bytea";
      case TIMESTAMPTZ:
        return "timestamp with time zone";
      default:
        return type.name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * A timestamp ScalarDB can keep as it is: ScalarDB keeps milliseconds and would drop the rest, so
   * a finer value is refused, not rounded. A predicate catches this and compares in memory, where
   * the value is exact.
   */
  private static <T extends java.time.temporal.Temporal> T milliseconds(T value, String text) {
    long nanos = value.getLong(java.time.temporal.ChronoField.NANO_OF_SECOND);
    if (nanos % 1_000_000 != 0) {
      throw new PgException.NotRepresentable(
          "0A000",
          "Unsupported timestamp precision: \""
              + text
              + "\" is finer than a millisecond, which is what ScalarDB stores");
    }
    return value;
  }

  static Column<?> parseColumn(DataType type, String name, @Nullable String s) {
    switch (type) {
      case BOOLEAN:
        return s == null
            ? BooleanColumn.ofNull(name)
            : BooleanColumn.of(name, Evaluator.parseBoolean(s));
      case INT:
        return s == null
            ? IntColumn.ofNull(name)
            : IntColumn.of(name, integerInput(s).intValueExact());
      case BIGINT:
        return s == null
            ? BigIntColumn.ofNull(name)
            : BigIntColumn.of(name, integerInput(s).longValueExact());
      case FLOAT:
        return s == null
            ? FloatColumn.ofNull(name)
            : FloatColumn.of(name, (float) inRange(s, Float.parseFloat(s)));
      case DOUBLE:
        return s == null
            ? DoubleColumn.ofNull(name)
            : DoubleColumn.of(name, inRange(s, Double.parseDouble(s)));
      case TEXT:
        return TextColumn.of(name, s);
      case BLOB:
        return s == null ? BlobColumn.ofNull(name) : BlobColumn.of(name, bytea(s));
      case DATE:
        return s == null ? DateColumn.ofNull(name) : DateColumn.of(name, LocalDate.parse(s));
      case TIME:
        {
          // ScalarDB keeps microseconds, as PostgreSQL does; a finer fraction rounds as there
          if (s == null) {
            return TimeColumn.ofNull(name);
          }
          java.time.LocalTime time = Evaluator.time(s);
          if (time.equals(java.time.LocalTime.MAX)) {
            throw new PgException.Argument(
                "0A000", "time 24:00:00 cannot be stored: ScalarDB's TIME ends at 23:59:59.999999");
          }
          return TimeColumn.of(name, time);
        }
      case TIMESTAMP:
        return s == null
            ? TimestampColumn.ofNull(name)
            : TimestampColumn.of(name, milliseconds(localDateTime(s), s));
      case TIMESTAMPTZ:
        return s == null
            ? TimestampTZColumn.ofNull(name)
            : TimestampTZColumn.of(name, milliseconds(instant(s), s));
      default:
        throw new AssertionError();
    }
  }

  /**
   * The parsed value, unless the text is a finite number that overflowed to Infinity or underflowed
   * to zero, which PostgreSQL rejects as out of range.
   */
  private static double inRange(String s, double parsed) {
    if (Double.isInfinite(parsed) || parsed == 0) {
      BigDecimal exact;
      try {
        exact = new BigDecimal(s.trim());
      } catch (NumberFormatException notDecimal) {
        return parsed; // 'Infinity', 'NaN' and the like mean what they say
      }
      if (Double.isInfinite(parsed) || exact.signum() != 0) {
        throw new ArithmeticException("out of range: " + s);
      }
    }
    return parsed;
  }

  static boolean isLiteral(Expression e) {
    if (e instanceof SignedExpression) {
      return isLiteral(((SignedExpression) e).getExpression());
    }
    return e instanceof NullValue
        || e instanceof JdbcParameter
        || e instanceof StringValue
        || e instanceof LongValue
        || e instanceof DoubleValue
        || e instanceof BooleanValue
        || e instanceof DateTimeLiteralExpression
        || (isColumn(e) && isBooleanLiteral(columnName(e)));
  }

  /** Returns the literal as text (numbers unchanged, strings unquoted), or null for NULL. */
  @Nullable private static String literal(Expression e) {
    if (e instanceof NullValue) {
      return null;
    }
    if (e instanceof JdbcParameter) {
      // the text the client sent: '007' into a text column is 007, into an integer column 7
      Object value = boundRaw((JdbcParameter) e);
      return value == null ? null : String.valueOf(value);
    }
    if (e instanceof StringValue) {
      return stringLiteral((StringValue) e);
    }
    if (e instanceof LongValue || e instanceof DoubleValue) {
      return e.toString();
    }
    if (e instanceof BooleanValue) {
      return String.valueOf(((BooleanValue) e).getValue());
    }
    if (e instanceof SignedExpression) {
      return ((SignedExpression) e).getSign() + literal(((SignedExpression) e).getExpression());
    }
    if (e instanceof DateTimeLiteralExpression) {
      String v = ((DateTimeLiteralExpression) e).getValue();
      return v.startsWith("'") ? v.substring(1, v.length() - 1) : v;
    }
    if (isColumn(e) && isBooleanLiteral(columnName(e))) {
      return columnName(e).toLowerCase(Locale.ROOT);
    }
    throw new PgException.Argument("0A000", "Unsupported literal: " + e);
  }

  /** A string literal's value: doubled quotes undone, and C-style escapes in an {@code E'...'}. */
  static String stringLiteral(StringValue s) {
    String v = s.getNotExcapedValue();
    if ("B".equalsIgnoreCase(s.getPrefix())) {
      for (char c : v.toCharArray()) {
        if (c != '0' && c != '1') {
          throw new PgException.Argument("22P02", "\"" + c + "\" is not a valid binary digit");
        }
      }
    }
    return "E".equalsIgnoreCase(s.getPrefix()) ? unescape(v) : v;
  }

  /**
   * PostgreSQL's escape-string processing: the C escapes, hex, octal, and 4- or 8-digit code
   * points.
   */
  static String unescape(String v) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int high = -1; // a \\u high surrogate waiting for its low half
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (high >= 0 && (c != '\\' || i + 1 >= v.length() || "uU".indexOf(v.charAt(i + 1)) < 0)) {
        throw new PgException.Argument("42601", "invalid Unicode surrogate pair");
      }
      if (c != '\\' || i + 1 >= v.length()) {
        int cp = v.codePointAt(i);
        i += Character.charCount(cp) - 1;
        out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
        continue;
      }
      char n = v.charAt(++i);
      switch (n) {
        case 'b':
          out.write('\b');
          break;
        case 'f':
          out.write('\f');
          break;
        case 'n':
          out.write('\n');
          break;
        case 'r':
          out.write('\r');
          break;
        case 't':
          out.write('\t');
          break;
        case 'x':
          {
            int end = i + 1;
            while (end < v.length() && end < i + 3 && Character.digit(v.charAt(end), 16) >= 0) {
              end++;
            }
            if (end == i + 1) {
              out.write('x');
            } else {
              out.write(Integer.parseInt(v.substring(i + 1, end), 16)); // a byte, as in PostgreSQL
              i = end - 1;
            }
            break;
          }
        case 'u':
        case 'U':
          {
            int len = n == 'u' ? 4 : 8;
            if (i + len >= v.length()
                || !v.substring(i + 1, i + 1 + len)
                    .chars()
                    .allMatch(d -> Character.digit(d, 16) >= 0)) {
              throw new PgException.Argument("22025", "invalid Unicode escape");
            }
            long cp = Long.parseLong(v.substring(i + 1, i + 1 + len), 16);
            i += len;
            if (cp >= 0xD800 && cp <= 0xDBFF && high < 0) {
              high = (int) cp;
              break;
            }
            if (high >= 0 ? cp < 0xDC00 || cp > 0xDFFF : cp >= 0xDC00 && cp <= 0xDFFF) {
              throw new PgException.Argument("42601", "invalid Unicode surrogate pair");
            }
            if (high >= 0) {
              cp = Character.toCodePoint((char) high, (char) cp);
              high = -1;
            }
            if (cp == 0 || cp > Character.MAX_CODE_POINT) {
              throw new PgException.Argument("42601", "invalid Unicode escape value");
            }
            out.writeBytes(
                new String(Character.toChars((int) cp)).getBytes(StandardCharsets.UTF_8));
            break;
          }
        default:
          if (n >= '0' && n <= '7') {
            int end = i;
            while (end < v.length()
                && end < i + 3
                && v.charAt(end) >= '0'
                && v.charAt(end) <= '7') {
              end++;
            }
            out.write(Integer.parseInt(v.substring(i, end), 8) & 0xff);
            i = end - 1;
          } else {
            // backslash, quote, or an unknown escape stands for itself
            out.writeBytes(String.valueOf(n).getBytes(StandardCharsets.UTF_8));
          }
      }
    }
    if (high >= 0) {
      throw new PgException.Argument("42601", "invalid Unicode surrogate pair");
    }
    byte[] bytes = out.toByteArray();
    try {
      for (byte b : bytes) {
        if (b == 0) {
          throw new java.nio.charset.MalformedInputException(1);
        }
      }
      // the escapes make bytes, which must be valid UTF-8 together, as PostgreSQL checks
      return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new PgException.Argument("22021", "invalid byte sequence for encoding \"UTF8\"");
    }
  }

  /** FETCH FIRST n ROWS ONLY; a bare FETCH FIRST ROW ONLY is one row. */
  private static int fetchCount(net.sf.jsqlparser.statement.select.Fetch fetch) {
    return fetch.getExpression() == null ? 1 : intValue(fetch.getExpression(), -1, "LIMIT");
  }

  /** LIMIT/OFFSET value; NULL means "none", as in PostgreSQL, and a negative one is refused. */
  private static int intValue(Expression e, int ifNull, String clause) {
    String s = literal(e);
    if (s == null) {
      return ifNull;
    }
    int v = new BigDecimal(s).intValueExact();
    if (v < 0) {
      throw new PgException.Argument(
          clause.equals("LIMIT") ? "2201W" : "2201X", clause + " must not be negative");
    }
    return v;
  }

  static boolean isColumn(Expression e) {
    return e instanceof net.sf.jsqlparser.schema.Column;
  }

  static String columnName(Expression e) {
    if (isColumn(e)) {
      String name = unquote(((net.sf.jsqlparser.schema.Column) e).getColumnName());
      // JSQLParser keeps an array subscript such as conkey[1] inside the column name
      return name.contains("[") ? name.substring(0, name.indexOf('[')) : name;
    }
    throw new IllegalArgumentException("Expected a column name: " + e);
  }

  /**
   * A column an INSERT or UPDATE assigns to. A subscript ({@code SET j['a'] = v}, {@code INSERT
   * INTO t (a[1])}) would assign one element, but {@link #columnName} drops it and the whole column
   * would be overwritten, so it is refused.
   */
  // ponytail: refused, not implemented; rewrite as jsonb_set(col, path, v, true) (and an array
  // element update) when a client needs it
  private static String assignedColumn(Expression e) {
    if (isColumn(e)
        && (((net.sf.jsqlparser.schema.Column) e).getArrayConstructor() != null
            || ((net.sf.jsqlparser.schema.Column) e).getColumnName().contains("["))) {
      throw new PgException.Argument(
          "0A000",
          "Unsupported: assigning to a subscript of a column ("
              + e
              + "); assign the whole new value instead");
    }
    return columnName(e);
  }

  /** {@code col = DEFAULT} in SET: there are no column defaults here, so it means NULL. */
  private static Expression defaultToNull(Expression e) {
    return isDefaultKeyword(e) ? new NullValue() : e;
  }

  /** {@code DEFAULT} in a VALUES row: there are no column defaults here, so it means NULL. */
  static boolean isDefaultKeyword(Expression e) {
    return isColumn(e)
        && ((net.sf.jsqlparser.schema.Column) e).getTable() == null
        && columnName(e).equalsIgnoreCase("default");
  }

  /** SQL keywords that parse as columns and evaluate to session values, see the Evaluator. */
  static boolean isKeyword(String name) {
    switch (name.toLowerCase(Locale.ROOT)) {
      case "current_catalog":
      case "current_schema":
      case "current_user":
      case "user":
      case "session_user":
      case "current_role":
        return true;
      default:
        return false;
    }
  }

  static boolean isBooleanLiteral(String name) {
    return name.equalsIgnoreCase("true") || name.equalsIgnoreCase("false");
  }

  /** An identifier as PostgreSQL resolves it: quoted as written, unquoted folded to lower case. */
  static String unquote(String identifier) {
    return identifier.startsWith("\"")
        ? identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"")
        : identifier.toLowerCase(Locale.ROOT);
  }

  /** The identifier as SQL text: quoted when unquoting would change it. */
  static String quoteIfNeeded(String identifier) {
    return identifier.matches("[a-z_][a-z0-9_$]*")
        ? identifier
        : "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  // A trailing zone offset as PostgreSQL and its drivers write it: +09, +09:00, -0530
  private static final Pattern OFFSET = Pattern.compile("[+-]\\d{2}(:?\\d{2})?$");

  /**
   * A timestamp as text, with or without a zone offset; the offset is dropped, as for TIMESTAMP.
   */
  static LocalDateTime localDateTime(String s) {
    boolean[] bc = {false};
    String t = withoutEra(s, bc).replace(' ', 'T');
    LocalDateTime out =
        t.length() == 10
            ? LocalDate.parse(t).atStartOfDay() // a date alone, as PostgreSQL accepts it
            : OFFSET.matcher(t).find() && t.indexOf('T') > 0
                ? offsetDateTime(t).toLocalDateTime()
                : LocalDateTime.parse(t);
    // PostgreSQL keeps microseconds: a finer fraction rounds on input ('...00.9999999' is 01)
    return (LocalDateTime)
        Evaluator.roundedTo(era(out.toLocalDate(), bc[0], s).atTime(out.toLocalTime()), 6);
  }

  private static final Pattern ERA = Pattern.compile("(?i)\\s(BC|AD)$");

  /** The trimmed text without a trailing BC or AD; {@code bc[0]} is set for BC. */
  static String withoutEra(String s, boolean[] bc) {
    String t = s.trim();
    Matcher m = ERA.matcher(t);
    if (m.find()) {
      bc[0] = m.group(1).equalsIgnoreCase("BC");
      t = t.substring(0, m.start()).trim();
    }
    return t;
  }

  /**
   * The date as read, its year counted back for BC (1 BC is the year 0), checked as PostgreSQL
   * checks it: there is no year 0 to write, and nothing before 4714-11-24 BC.
   */
  static LocalDate era(LocalDate d, boolean bc, String text) {
    if (d.getYear() == 0) {
      throw new PgException.Argument(
          "22008", "date/time field value out of range: \"" + text.trim() + "\"");
    }
    LocalDate out = bc ? d.withYear(1 - d.getYear()) : d;
    if (out.isBefore(LocalDate.of(-4713, 11, 24))) {
      throw new PgException.Argument("22008", "date out of range: \"" + text.trim() + "\"");
    }
    return out;
  }

  /** A timestamp as text; without an offset it is taken as UTC. */
  static java.time.Instant instant(String s) {
    boolean[] bc = {false};
    String t = withoutEra(s, bc).replace(' ', 'T');
    if (t.endsWith("Z") || t.endsWith("z")) {
      t = t.substring(0, t.length() - 1) + "+00:00"; // pgx and ISO 8601 write UTC as Z
    }
    if (t.length() > 10 && OFFSET.matcher(t).find() && t.indexOf('T') > 0) {
      OffsetDateTime o = offsetDateTime(t);
      return (java.time.Instant)
          Evaluator.roundedTo(
              era(o.toLocalDate(), bc[0], s).atTime(o.toLocalTime()).toInstant(o.getOffset()), 6);
    }
    LocalDateTime local =
        t.length() == 10 ? LocalDate.parse(t).atStartOfDay() : LocalDateTime.parse(t);
    return (java.time.Instant)
        Evaluator.roundedTo(
            Evaluator.instant(
                era(local.toLocalDate(), bc[0], s).atTime(local.toLocalTime()), Evaluator.zone()),
            6);
  }

  private static OffsetDateTime offsetDateTime(String t) {
    Matcher m = OFFSET.matcher(t);
    m.find();
    String offset = m.group();
    if (offset.length() == 3) {
      offset += ":00"; // +09 -> +09:00
    } else if (!offset.contains(":")) {
      offset = offset.substring(0, 3) + ":" + offset.substring(3); // +0900 -> +09:00
    }
    return OffsetDateTime.parse(t.substring(0, m.start()) + offset);
  }

  /**
   * bytea input as PostgreSQL reads it: {@code \x} then hex digit pairs (whitespace between pairs),
   * else the escape format, in which {@code \\} is a backslash, {@code \nnn} an octal byte, and
   * anything else its UTF-8 bytes.
   */
  static byte[] bytea(String s) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    if (s.startsWith("\\x") || s.startsWith("\\X")) {
      int high = -1;
      for (int i = 2; i < s.length(); i++) {
        char c = s.charAt(i);
        if (high < 0 && Character.isWhitespace(c)) {
          continue;
        }
        int digit = Character.digit(c, 16);
        if (digit < 0) {
          throw new PgException.Argument("22023", "invalid hexadecimal digit: \"" + c + "\"");
        }
        if (high < 0) {
          high = digit;
        } else {
          out.write(high << 4 | digit);
          high = -1;
        }
      }
      if (high >= 0) {
        throw new PgException.Argument("22023", "invalid hexadecimal data: odd number of digits");
      }
      return out.toByteArray();
    }
    for (int i = 0; i < s.length(); ) {
      char c = s.charAt(i);
      if (c != '\\') {
        int end = i + Character.charCount(s.codePointAt(i));
        out.writeBytes(s.substring(i, end).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        i = end;
      } else if (i + 1 < s.length() && s.charAt(i + 1) == '\\') {
        out.write('\\');
        i += 2;
      } else if (i + 3 < s.length()
          && s.charAt(i + 1) >= '0'
          && s.charAt(i + 1) <= '3'
          && Character.digit(s.charAt(i + 2), 8) >= 0
          && Character.digit(s.charAt(i + 3), 8) >= 0) {
        out.write(Integer.parseInt(s.substring(i + 1, i + 4), 8));
        i += 4;
      } else {
        throw new PgException.Argument("22P02", "invalid input syntax for type bytea");
      }
    }
    return out.toByteArray();
  }
}
