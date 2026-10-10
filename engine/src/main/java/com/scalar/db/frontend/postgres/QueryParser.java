package com.scalar.db.frontend.postgres;

import com.scalar.db.api.AndConditionSet;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionSetBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.CrudOperable;
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
import com.scalar.db.api.Selection;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.api.UpdateBuilder;
import com.scalar.db.api.Upsert;
import com.scalar.db.api.UpsertBuilder;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnsatisfiedConditionException;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
public class QueryParser {
  private final DistributedTransactionAdmin admin;
  private final String defaultNamespace;
  private final Catalog catalog;
  // Per session; dropped after DDL by any session of this process (DDL_GENERATION, todo 011).
  // ponytail: DDL from another process is seen only through ScalarDB's own metadata cache expiry;
  // a cluster-wide schema version would be the upgrade path
  private final Map<String, TableMetadata> metadataCache = new HashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong DDL_GENERATION =
      new java.util.concurrent.atomic.AtomicLong();
  private long seenGeneration = DDL_GENERATION.get();

  public QueryParser(DistributedTransactionAdmin admin, String defaultNamespace) {
    this.admin = admin;
    this.defaultNamespace = defaultNamespace;
    this.catalog = new Catalog(admin, defaultNamespace);
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
    long now = DDL_GENERATION.get();
    if (now == seenGeneration) {
      return false;
    }
    seenGeneration = now;
    metadataCache.clear();
    return true;
  }

  /**
   * A table or a subquery in FROM, with its rows once loaded. Row keys are {@code
   * qualifier.column}.
   */
  static final class Source {
    final String qualifier;
    final List<String> columns;
    @Nullable final Operation scan;
    @Nullable final Plan derived;
    boolean lateral; // the derived subquery runs once per left row of its join
    @Nullable final String catalogTable; // e.g. pg_catalog.pg_class; rows are preloaded
    @Nullable List<Map<String, Object>> loaded;
    // A keyed lookup: the table is read once per outer row, with these columns bound to the
    // outer row's values (index nested-loop join)
    @Nullable LogicalPlan.Source lookup;
    @Nullable Map<String, Expression> lookupParams;
    List<OrderByElement> lookupOrderBy = Collections.emptyList();
    int lookupLimit = -1;

    Source(
        String qualifier,
        List<String> columns,
        @Nullable Operation scan,
        @Nullable Plan derived,
        @Nullable String catalogTable) {
      this.qualifier = qualifier;
      this.columns = columns;
      this.scan = scan;
      this.derived = derived;
      this.catalogTable = catalogTable;
    }

    // Rows are read by the operators built from this source; catalog rows are preloaded here

    /** The ScalarDB read of a source that is not a lookup. */
    Operation scan() {
      return Objects.requireNonNull(scan, () -> qualifier + " has no scan");
    }

    /** The keyed read this source repeats per outer row; only for a lookup source. */
    LogicalPlan.Source lookup() {
      return Objects.requireNonNull(lookup, () -> qualifier + " is not a lookup");
    }

    /** The key columns a lookup binds to outer expressions; only for a lookup source. */
    Map<String, Expression> lookupParams() {
      return Objects.requireNonNull(lookupParams, () -> qualifier + " is not a lookup");
    }
  }

  /** Rows an UPDATE, DELETE or INSERT ... SELECT driven by a read may write, unless raised. */
  public static final int DEFAULT_MAX_ROWS_PER_WRITE = 10_000;

  /** What one row of a read-driven write does: its mutation and the row it leaves behind. */
  static final class Effect {
    final Mutation mutation;
    final Map<String, Object> row; // qualifier.column -> value after the write (before, for DELETE)

    Effect(Mutation mutation, Map<String, Object> row) {
      this.mutation = mutation;
      this.row = row;
    }
  }

  /** Decides what one row of the driving read does; may read ScalarDB (ON CONFLICT). */
  interface Mutator {
    /** The effect for {@code row}, or null to skip it. */
    @Nullable Effect apply(Map<String, Object> row, Reader reader);
  }

  /** A RETURNING clause: output names and the expressions over the row each write leaves. */
  static final class Returning {
    final List<String> names = new ArrayList<>();
    final List<Expression> expressions = new ArrayList<>();
    final List<DataType> types = new ArrayList<>(); // null for computed items
  }

  /** A write driven by a read: one mutation per row the read plan returns. */
  static final class WriteByScan {
    final Plan read;
    final Mutator mutator;
    final String description;
    @Nullable final Returning returning;

    WriteByScan(Plan read, Mutator mutator, String description, @Nullable Returning returning) {
      this.read = read;
      this.mutator = mutator;
      this.description = description;
      this.returning = returning;
    }
  }

  /** A DDL statement: runs against the ScalarDB admin, outside any transaction. */
  public interface Ddl {
    void run() throws ExecutionException;
  }

  /** A pull-based row stream (Volcano style): {@link #next()} returns null once exhausted. */
  public interface Cursor extends AutoCloseable {
    Cursor EMPTY =
        new Cursor() {
          @Override
          @Nullable public Map<String, Object> next() {
            return null;
          }

          @Override
          public void close() {}
        };

    @Nullable Map<String, Object> next();

    /** Releases the ScalarDB scanners behind the stream; safe to call more than once. */
    @Override
    void close();
  }

  /** Runs ScalarDB reads; failures are wrapped so evaluation code stays free of checked types. */
  interface Reader {
    List<Result> read(Operation operation);

    /**
     * Opens a Get or Scan as a row stream with keys qualified as {@code qualifier.column}; every
     * one of {@code columns} is present, null when the storage did not return it.
     */
    Cursor open(Operation operation, String qualifier, List<String> columns);

    /** ScalarDB reads issued so far: one per Get, Scan or opened scanner. */
    long reads();

    /** True if reads may be issued from several threads at once (auto-commit statements). */
    boolean concurrent();

    /**
     * The session's identity, which a read from another thread enters; null if not authenticated.
     */
    @Nullable Authenticator.Identity identity();
  }

  /** A ScalarDB read failed while rows were being pulled; the cause is the original exception. */
  public static final class ReadFailure extends RuntimeException {
    ReadFailure(TransactionException cause) {
      super(cause);
    }

    public TransactionException cause() {
      return (TransactionException) Objects.requireNonNull(getCause());
    }
  }

  /** An INSERT hit an existing primary key: SQLSTATE 23505. */
  public static final class UniqueViolationException extends RuntimeException {
    UniqueViolationException(Mutation insert, Exception cause) {
      super(message(insert.forTable().orElse("")), cause);
    }

    /** At commit, where the table is known only from ScalarDB's message ("Table: ns.t"). */
    UniqueViolationException(Exception cause) {
      super(message(table(String.valueOf(cause.getMessage()))), cause);
    }

    private static String message(String table) {
      return "duplicate key value violates unique constraint \"" + table + "_pkey\"";
    }

    private static String table(String message) {
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("Table: ([^,\\s]+)").matcher(message);
      return m.find() ? m.group(1).substring(m.group(1).lastIndexOf('.') + 1) : "";
    }
  }

  /** ScalarDB operations plus the in-memory processing that completes the statement. */
  public static final class Plan {
    @Nullable private List<Mutation> mutations;

    // DML whose mutations are built from the statement when first needed, with the parameters
    // bound at the time, so the plan can be reused
    @Nullable private java.util.function.Function<Evaluator.Context, List<Mutation>> mutationBuilder;

    private boolean rootFixed; // the root is given, not built from the sources
    private boolean cacheable; // set by parse(): reusable with other parameter values

    /** Warnings for the client (PostgreSQL NOTICEs): what a DDL accepted but cannot enforce. */
    final List<String> notices = new ArrayList<>();

    boolean correlated; // a subquery that reads the enclosing row: evaluated per row, never cached
    @Nullable List<Map<String, Object>> cachedRows; // an uncorrelated subquery's rows, per run
    // x IN (this subquery): cachedRows as a hash set, built from the rows it was made of
    @Nullable Evaluator.InSet inSet;
    @Nullable List<Map<String, Object>> inSetRows;
    private final List<Source> sources;
    private final List<LogicalPlan.Join> joins;
    private final List<String> outputNames;
    private final List<Expression> outputExpressions;
    private final List<DataType> outputTypes; // null entries for computed columns
    private final List<Expression> residual; // WHERE conjuncts evaluated in memory
    @Nullable private final List<Expression> groupBy;
    @Nullable private final Expression having;
    private final boolean aggregated;
    private final List<Windows.Spec> windows; // computed before the select list
    private final boolean distinct;
    private List<OrderByElement> orderBy; // in-memory ordering
    private int limit; // in-memory limit; negative for none
    private boolean unsatisfied; // the keyed UPDATE/DELETE matched no row
    @Nullable private List<String> labels; // the names clients see; outputNames when null
    private int offset;
    // A set operation: its two member plans and how they combine
    private List<Plan> members = Collections.emptyList();
    @Nullable private Operators.SetOperation.Kind setKind;
    private boolean setAll;
    private final Map<Select, Plan> subplans;
    @Nullable private final Catalog catalog;
    @Nullable private String tag;
    @Nullable private Ddl ddl;
    @Nullable private WriteByScan write;
    private List<Object> parameters; // the placeholder values this plan runs with
    private List<Map<String, Object>> returned = Collections.emptyList(); // RETURNING rows
    @Nullable private Plan analyze; // EXPLAIN ANALYZE: the statement to run and report on
    private long started; // nanoTime when an EXPLAIN ANALYZE started
    @Nullable private Reader reader; // set while executing
    private final boolean streaming; // top-level: the first table is read as rows are pulled
    @Nullable private final LogicalPlan logical; // what this plan was made from; null for DML
    @Nullable private Map<String, DataType> columnTypes; // built on first use from logical
    @Nullable private Operator root; // the operator tree; null for DML

    /** A DML plan whose mutations {@code builder} makes from the bound parameters. */
    static Plan deferred(java.util.function.Function<Evaluator.Context, List<Mutation>> builder) {
      Plan plan = of(Collections.<Mutation>emptyList());
      plan.mutations = null;
      plan.mutationBuilder = builder;
      return plan;
    }

    /** The mutations of a DML statement, built on first use with the current parameters. */
    @Nullable List<Mutation> mutations() {
      if (mutations == null && mutationBuilder != null) {
        mutations = mutationBuilder.apply(context(null));
      }
      return mutations;
    }

    /** True if this plan can run again with other parameter values, see {@link #rebind}. */
    public boolean isCacheable() {
      return cacheable;
    }

    /** Whether the plan embeds nothing that depends on the values it was planned with. */
    private boolean reusable() {
      if (analyze != null || ddl != null) {
        return false;
      }
      if (logical == null && mutationBuilder == null && write == null && members.isEmpty()) {
        return false; // a constant result, computed when planned
      }
      for (Source s : sources) {
        if (s.catalogTable != null || (s.derived != null && !s.derived.reusable())) {
          return false;
        }
      }
      for (Plan p : subplans.values()) {
        if (!p.reusable()) {
          return false;
        }
      }
      for (Plan member : members) {
        if (!member.reusable()) {
          return false;
        }
      }
      return write == null || write.read.reusable();
    }

    /**
     * Prepares a cached plan for another execution: binds the new parameter values and discards the
     * previous execution's operator tree and mutations. Any cursor of the previous execution must
     * be closed first.
     */
    public void rebind(List<Object> parameters) {
      this.parameters = new ArrayList<>(parameters);
      if (!rootFixed) {
        root = null;
      }
      if (mutationBuilder != null) {
        mutations = null;
      }
      returned = Collections.emptyList();
      for (Source s : sources) {
        if (s.derived != null) {
          s.derived.rebind(parameters);
        }
      }
      for (Plan p : subplans.values()) {
        p.rebind(parameters);
      }
      for (Plan member : members) {
        member.rebind(parameters);
      }
      if (write != null) {
        write.read.rebind(parameters);
      }
    }

    /**
     * Drops what one execution left behind (the operator tree, the reader, cached subquery rows),
     * so that a plan kept in the session's cache does not hold them until it runs again. The mirror
     * of {@link #rebind}; the next execution rebuilds what it needs.
     */
    void release() {
      if (!rootFixed) {
        root = null;
      }
      reader = null;
      cachedRows = null;
      inSet = null;
      inSetRows = null;
      returned = Collections.emptyList();
      for (Source s : sources) {
        if (s.derived != null) {
          s.derived.release();
        }
      }
      for (Plan p : subplans.values()) {
        p.release();
      }
      for (Plan member : members) {
        member.release();
      }
      if (write != null) {
        write.read.release();
      }
      if (analyze != null) {
        analyze.release();
      }
    }

    static Plan of(List<Mutation> mutations) {
      return new Plan(
          mutations,
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          null,
          null,
          false,
          Collections.emptyList(),
          false,
          Collections.emptyList(),
          -1,
          0,
          Collections.emptyMap(),
          null,
          false,
          null);
    }

    /** {@code left kind right}: the output columns are the left member's. */
    static Plan setOf(Operators.SetOperation.Kind kind, boolean all, Plan left, Plan right) {
      Plan plan =
          new Plan(
              null,
              Collections.emptyList(),
              Collections.emptyList(),
              left.outputNames,
              left.outputExpressions,
              left.outputTypes,
              Collections.emptyList(),
              null,
              null,
              false,
              Collections.emptyList(),
              false,
              Collections.emptyList(),
              -1,
              0,
              Collections.emptyMap(),
              null,
              false,
              null);
      plan.members = Arrays.asList(left, right);
      plan.setKind = kind;
      plan.setAll = all;
      plan.labels = left.labels;
      plan.correlated = left.correlated || right.correlated;
      return plan;
    }

    /**
     * The columns of a set operation must agree in type: a text column against an integer one is
     * PostgreSQL's 42804, and an untyped literal ({@code '7'}, NULL) takes the type of the others,
     * so {@code SELECT '7' UNION SELECT 7} is one row and the result advertises one type.
     */
    private static void unifyColumnTypes(List<Plan> terms) {
      int columns = terms.get(0).outputNames.size();
      for (int i = 0; i < columns; i++) {
        int target = 0;
        String targetCategory = null;
        for (Plan term : terms) {
          if (i >= term.outputExpressions.size()
              || isUntypedLiteral(term.outputExpressions.get(i))) {
            continue; // a VALUES or constant member has no expressions: its values decide
          }
          int[] oids = term.outputOids();
          int oid = i < oids.length ? oids[i] : 0;
          String category = typeCategory(oid);
          if (category == null) {
            continue;
          }
          if (targetCategory == null) {
            target = oid;
            targetCategory = category;
          } else if (!category.equals(targetCategory)) {
            throw new PgException.Argument(
                "42804", // datatype_mismatch
                "UNION types "
                    + Catalog.typeName(target)
                    + " and "
                    + Catalog.typeName(oid)
                    + " cannot be matched");
          }
        }
        if (targetCategory == null) {
          continue;
        }
        String typeName = targetCategory.equals("text") ? "text" : Catalog.typeName(target);
        for (Plan term : terms) {
          if (i >= term.outputExpressions.size()) {
            continue;
          }
          Expression e = term.outputExpressions.get(i);
          if (!isUntypedLiteral(e) || e instanceof NullValue) {
            continue;
          }
          CastExpression cast = new CastExpression();
          cast.setLeftExpression(e);
          net.sf.jsqlparser.statement.create.table.ColDataType type =
              new net.sf.jsqlparser.statement.create.table.ColDataType();
          type.setDataType(typeName);
          cast.setColDataType(type);
          term.outputExpressions.set(i, cast);
          if (term.logical != null && i < term.logical.outputOids.size()) {
            term.logical.outputOids.set(i, target);
          }
          try {
            term.outputTypes.set(i, dataType(typeName));
          } catch (IllegalArgumentException unknownType) {
            // leave the type to the values
          }
        }
      }
    }

    private static boolean isUntypedLiteral(Expression e) {
      e = unwrap(e);
      return e instanceof StringValue || e instanceof NullValue;
    }

    @Nullable private static String typeCategory(int oid) {
      switch (oid) {
        case 16:
          return "boolean";
        case 20:
        case 21:
        case 23:
        case 700:
        case 701:
        case 1700:
          return "numeric";
        case 18:
        case 19:
        case 25:
        case 1043:
          return "text";
        case 1082:
          return "date";
        case 1083:
          return "time";
        case 1114:
        case 1184:
          return "timestamp";
        case 17:
          return "bytea";
        case 114:
        case 3802:
          return "json";
        default:
          return null;
      }
    }

    /** A DDL statement with its PostgreSQL command tag, such as {@code CREATE TABLE}. */
    static Plan ddl(Ddl ddl, String tag) {
      Plan plan =
          constant(
              Collections.<String>emptyList(), Collections.<Map<String, Object>>emptyList(), tag);
      plan.ddl = ddl;
      return plan;
    }

    /** An UPDATE, DELETE or INSERT ... SELECT that reads first; {@code tag} is the command word. */
    static Plan write(
        Plan read, Mutator mutator, String tag, String description, @Nullable Returning returning) {
      Plan plan =
          constant(
              returning == null ? Collections.<String>emptyList() : returning.names,
              Collections.<Map<String, Object>>emptyList(),
              tag);
      if (returning != null) {
        plan.outputTypes.clear();
        plan.outputTypes.addAll(returning.types); // so drivers see int4, not text, for RETURNING id
      }
      plan.write = new WriteByScan(read, mutator, description, returning);
      return plan;
    }

    /** The rows of the RETURNING clause of the last {@link #executeWrite}; empty without one. */
    Cursor returned() {
      return listCursor(returned);
    }

    /** EXPLAIN ANALYZE: runs {@code inner} and reports its tree with actual counts. */
    static Plan analyze(Plan inner) {
      Plan plan =
          constant(
              Collections.singletonList("QUERY PLAN"),
              Collections.<Map<String, Object>>emptyList(),
              "EXPLAIN");
      plan.analyze = inner;
      plan.write = inner.write; // so that the writes run under the caller's transaction and cap
      return plan;
    }

    /** The statement an EXPLAIN ANALYZE runs, or null. */
    @Nullable Plan getAnalyze() {
      return analyze;
    }

    /** The EXPLAIN ANALYZE output after the statement ran: the tree with counts, then the time. */
    Cursor report() {
      List<String> lines = new ArrayList<>();
      explain(Objects.requireNonNull(analyze, "an EXPLAIN ANALYZE plan"), lines, 0, true);
      lines.add(
          String.format(
              Locale.ROOT, "Execution Time: %.3f ms", (System.nanoTime() - started) / 1e6));
      List<Map<String, Object>> rows = new ArrayList<>();
      for (String line : lines) {
        rows.add(Collections.singletonMap("QUERY PLAN", line));
      }
      return listCursor(rows);
    }

    /** The read-then-write form of this statement, or null. */
    @Nullable WriteByScan getWrite() {
      return write;
    }

    /**
     * Runs a read-then-write statement: reads the rows, then issues one mutation per row in the
     * same transaction. Nothing is written when more than {@code cap} rows match; a cap of 0 means
     * no limit.
     *
     * @return the number of rows written
     */
    public <E extends TransactionException> int executeWrite(CrudOperable<E> crud, int cap)
        throws E {
      return executeWrite(crud, cap, null);
    }

    /**
     * As {@link #executeWrite(CrudOperable, int)}, with the session's identity, which reads issued
     * from other threads enter; the calling thread is inside it already.
     */
    public <E extends TransactionException> int executeWrite(
        CrudOperable<E> crud, int cap, @Nullable Authenticator.Identity identity) throws E {
      if (write == null) {
        throw new IllegalStateException("Not a read-then-write statement");
      }
      started = System.nanoTime();
      List<Map<String, Object>> driving = new ArrayList<>();
      List<Mutation> mutations = new ArrayList<>();
      List<Map<String, Object>> left = new ArrayList<>();
      try {
        // The driving rows are read fully first so the mutator's own reads see no open scanner
        try (Cursor rows = write.read.open(crud, identity)) {
          for (Map<String, Object> row = rows.next(); row != null; row = rows.next()) {
            if (cap > 0 && driving.size() >= cap) {
              throw new PgException.Argument(
                  "54000",
                  "The statement would write more than "
                      + cap
                      + " rows, so nothing was written. Narrow the WHERE clause, raise the cap"
                      + " with SET scalardb.max_rows_per_write = <n> (0 for no limit), or use"
                      + " TRUNCATE to empty a table");
            }
            driving.add(row);
          }
        }
        Reader reader = reader(crud, identity);
        for (Map<String, Object> row : driving) {
          Effect effect = write.mutator.apply(row, reader);
          if (effect != null) {
            mutations.add(effect.mutation);
            left.add(effect.row);
          }
        }
      } catch (ReadFailure e) {
        @SuppressWarnings("unchecked")
        E cause = (E) e.cause();
        throw cause;
      }
      if (!mutations.isEmpty()) {
        boolean inserts = mutations.stream().allMatch(m -> m instanceof Insert);
        try {
          crud.mutate(mutations);
        } catch (TransactionException | IllegalArgumentException e) {
          if (inserts
              && e.getMessage() != null
              && (e.getMessage().contains("DB-CORE-20013")
                  || e.getMessage().contains("DB-CORE-10146"))) {
            throw new UniqueViolationException(mutations.get(0), e);
          }
          throw e;
        }
      }
      returned = new ArrayList<>();
      if (write.returning != null) {
        for (Map<String, Object> row : left) {
          Map<String, Object> out = new LinkedHashMap<>();
          for (int i = 0; i < write.returning.names.size(); i++) {
            out.put(
                write.returning.names.get(i),
                Evaluator.eval(
                    write.returning.expressions.get(i),
                    Collections.singletonList(row),
                    context(null)));
          }
          returned.add(out);
        }
      }
      return mutations.size();
    }

    /** Reads through {@code crud}, wrapping its failures in {@link ReadFailure}. */
    private static <E extends TransactionException> Reader reader(
        CrudOperable<E> crud, @Nullable Authenticator.Identity identity) {
      return new Reader() {
        @Nullable @Override
        public Authenticator.Identity identity() {
          return identity;
        }

        private final java.util.concurrent.atomic.AtomicLong reads =
            new java.util.concurrent.atomic.AtomicLong();

        @Override
        public boolean concurrent() {
          // The manager runs each read as its own transaction; a transaction is not thread-safe
          return crud instanceof com.scalar.db.api.DistributedTransactionManager;
        }

        @Override
        public long reads() {
          return reads.get();
        }

        @Override
        public List<Result> read(Operation operation) {
          reads.incrementAndGet();
          try {
            if (operation instanceof Get) {
              Optional<Result> result = crud.get((Get) operation);
              return result.isPresent()
                  ? Collections.singletonList(result.get())
                  : Collections.<Result>emptyList();
            }
            return crud.scan((Scan) operation);
          } catch (TransactionException e) {
            throw new ReadFailure(e);
          }
        }

        @Override
        public Cursor open(Operation operation, String qualifier, List<String> columns) {
          if (operation instanceof Get) {
            return listCursor(rowsOf(qualifier, columns, read(operation)));
          }
          CrudOperable.Scanner<E> scanner;
          reads.incrementAndGet();
          try {
            scanner = crud.getScanner((Scan) operation);
          } catch (TransactionException e) {
            throw new ReadFailure(e);
          }
          return new Cursor() {
            private boolean closed;

            @Override
            @Nullable public Map<String, Object> next() {
              try {
                Optional<Result> result = scanner.one();
                return result.isPresent() ? rowOf(qualifier, columns, result.get()) : null;
              } catch (TransactionException e) {
                throw new ReadFailure(e);
              }
            }

            @Override
            public void close() {
              if (closed) {
                return;
              }
              closed = true;
              try {
                scanner.close();
              } catch (TransactionException e) {
                throw new ReadFailure(e);
              }
            }
          };
        }
      };
    }

    /** The DDL statement to run, or null for a query or DML. */
    @Nullable public Ddl getDdl() {
      return ddl;
    }

    /** The working table of a recursive CTE: whatever rows {@code rows} holds when opened. */
    static Plan working(String name, List<String> names, List<Map<String, Object>> rows) {
      Plan plan = constant(names, rows, "SELECT");
      plan.root = new Operators.Values(rows, "Working table " + name);
      return plan;
    }

    /** WITH RECURSIVE over the anchor and step plans, which are its members. */
    static Plan recursive(
        List<String> names,
        List<DataType> types,
        Plan anchor,
        Plan step,
        List<Map<String, Object>> working,
        boolean all) {
      Plan plan = constant(names, Collections.<Map<String, Object>>emptyList(), "SELECT");
      plan.outputTypes.clear();
      plan.outputTypes.addAll(types);
      plan.members = Arrays.asList(anchor, step);
      plan.root = new Operators.RecursiveUnion(anchor, step, working, names, all);
      return plan;
    }

    /** A plan whose result is known already; {@code tag} is SELECT or EXPLAIN. */
    static Plan constant(List<String> labels, List<Map<String, Object>> rows, String tag) {
      // labels may repeat (a catalog query's NULL, NULL); rows are keyed by unique names
      List<String> names = new ArrayList<>(labels);
      makeUnique(names);
      List<Expression> none = Collections.emptyList();
      List<DataType> types = new ArrayList<>();
      for (int i = 0; i < names.size(); i++) {
        types.add(null);
      }
      Plan plan =
          new Plan(
              null,
              Collections.emptyList(),
              Collections.emptyList(),
              names,
              none,
              types,
              none,
              null,
              null,
              false,
              Collections.emptyList(),
              false,
              Collections.emptyList(),
              -1,
              0,
              Collections.emptyMap(),
              null,
              false,
              null);
      plan.tag = tag;
      plan.labels = labels;
      plan.root = new Operators.Values(rows, "Values");
      plan.rootFixed = true;
      return plan;
    }

    private Plan(
        @Nullable List<Mutation> mutations,
        List<Source> sources,
        List<LogicalPlan.Join> joins,
        List<String> outputNames,
        List<Expression> outputExpressions,
        List<DataType> outputTypes,
        List<Expression> residual,
        @Nullable List<Expression> groupBy,
        @Nullable Expression having,
        boolean aggregated,
        List<Windows.Spec> windows,
        boolean distinct,
        List<OrderByElement> orderBy,
        int limit,
        int offset,
        Map<Select, Plan> subplans,
        @Nullable Catalog catalog,
        boolean streaming,
        @Nullable LogicalPlan logical) {
      this.mutations = mutations;
      this.sources = sources;
      this.joins = joins;
      this.outputNames = outputNames;
      this.outputExpressions = outputExpressions;
      this.outputTypes = outputTypes;
      this.residual = residual;
      this.groupBy = groupBy;
      this.having = having;
      this.aggregated = aggregated;
      this.windows = windows;
      this.distinct = distinct;
      this.orderBy = orderBy;
      this.limit = limit;
      this.offset = offset;
      this.subplans = subplans;
      this.catalog = catalog;
      this.streaming = streaming;
      this.parameters = BOUND.get();
      this.logical = logical;
    }

    /** The bound and optimized logical plan this physical plan was made from; null for DML. */
    @Nullable LogicalPlan getLogical() {
      return logical;
    }

    /** The mutations of a DML statement, or the reads of the tables in FROM (not of subqueries). */
    public List<Operation> getOperations() {
      List<Operation> operations = new ArrayList<>();
      if (mutations() != null) {
        operations.addAll(mutations());
      }
      if (write != null) {
        operations.addAll(write.read.getOperations());
      }
      for (Plan member : members) {
        operations.addAll(member.getOperations());
      }
      for (Source s : sources) {
        if (s.scan != null) {
          operations.add(s.scan);
        } else if (s.lookup != null && selfContained(s)) {
          // bound to parameters only: the read is known without an outer row
          Operation operation =
              lookupOperation(s, Collections.<String, Object>emptyMap(), context(null));
          if (operation != null) {
            operations.add(operation);
          }
        }
      }
      return operations;
    }

    /** The output column names of a SELECT; empty for DML. */
    public List<String> getOutputColumns() {
      return new ArrayList<>(outputNames);
    }

    /** The column names as PostgreSQL would label them, which may repeat; for RowDescription. */
    public List<String> getColumnLabels() {
      return new ArrayList<>(labels != null ? labels : outputNames);
    }

    /** The output column types where known from table metadata; null for computed columns. */
    public List<DataType> getOutputTypes() {
      return new ArrayList<>(outputTypes);
    }

    /**
     * The PostgreSQL type OID of each output column: from table metadata, else inferred from the
     * expression (count is bigint, avg is numeric, a cast is its type, and so on), else 0. Binary
     * drivers such as pgx need the type before the first row arrives.
     */
    public int[] outputOids() {
      int[] oids = new int[outputNames.size()];
      for (int i = 0; i < oids.length; i++) {
        if (i < outputTypes.size() && outputTypes.get(i) != null) {
          oids[i] = (int) Catalog.typeOid(outputTypes.get(i));
        } else if (logical != null && i < logical.outputOids.size()) {
          oids[i] = logical.outputOids.get(i);
        } else if (!members.isEmpty()) {
          int[] inner = members.get(0).outputOids();
          oids[i] = i < inner.length ? inner[i] : 0;
        }
      }
      return oids;
    }

    /** The PostgreSQL command tag for this statement, given the number of rows returned. */
    public String commandTag(int rowCount) {
      if (tag != null) {
        return ddl != null || tag.equals("EXPLAIN") ? tag : tag + " " + rowCount;
      }
      List<Mutation> mutations = mutations();
      if (mutations == null) {
        return "SELECT " + rowCount;
      }
      Mutation first = mutations.get(0);
      if (first instanceof Insert || first instanceof Upsert) {
        return "INSERT 0 " + mutations.size();
      }
      return (first instanceof Update ? "UPDATE " : "DELETE ")
          + (unsatisfied ? 0 : mutations.size());
    }

    /** Executes the statement and returns every row. */
    public <E extends TransactionException> List<Map<String, Object>> execute(CrudOperable<E> crud)
        throws E {
      if (write != null) {
        executeWrite(crud, DEFAULT_MAX_ROWS_PER_WRITE);
        return drain(analyze != null ? report() : returned());
      }
      try (Cursor cursor = open(crud)) {
        return drain(cursor);
      } catch (ReadFailure e) {
        @SuppressWarnings("unchecked") // the reader only wraps what crud threw, which is an E
        E cause = (E) e.getCause();
        throw cause;
      }
    }

    /**
     * Runs the statement's mutations, or opens its operator tree as a pull-based row stream: the
     * first table is read through a ScalarDB scanner as rows are pulled, joins pull from their left
     * side, and LIMIT stops early. Aggregate, Sort and Distinct materialize their input first. The
     * caller must close the stream, which closes the scanners. A read that fails after this method
     * returns throws {@link ReadFailure}.
     *
     * @param crud a transaction, or a transaction manager for auto-commit
     * @return the row stream; empty for DML
     */
    public <E extends TransactionException> Cursor open(CrudOperable<E> crud) throws E {
      return open(crud, null);
    }

    /**
     * As {@link #open(CrudOperable)}, with the session's identity, which reads issued from other
     * threads enter; the calling thread is inside it already.
     */
    public <E extends TransactionException> Cursor open(
        CrudOperable<E> crud, @Nullable Authenticator.Identity identity) throws E {
      if (analyze != null) {
        started = System.nanoTime();
        try (Cursor rows = analyze.open(crud, identity)) {
          while (rows.next() != null) {
            // only the operators' counters are of interest
          }
        }
        return report();
      }
      if (write != null) {
        throw new IllegalStateException("A read-then-write statement runs with executeWrite");
      }
      if (mutations() != null) {
        unsatisfied = false;
        boolean inserts = mutations().stream().allMatch(m -> m instanceof Insert);
        try {
          crud.mutate(mutations());
        } catch (TransactionException | IllegalArgumentException e) {
          // ScalarDB reports an existing key as a conflict (DB-CORE-20013, also raised by real
          // write-write conflicts on upserts) or, within one transaction, as DB-CORE-10146. For a
          // plain INSERT both mean PostgreSQL's unique violation, which clients must not retry.
          if (inserts
              && e.getMessage() != null
              && (e.getMessage().contains("DB-CORE-20013")
                  || e.getMessage().contains("DB-CORE-10146"))) {
            throw new UniqueViolationException(mutations().get(0), e);
          }
          // A keyed UPDATE/DELETE whose row is missing or does not match the WHERE affects 0 rows
          if (!(e instanceof UnsatisfiedConditionException) || mutations().size() != 1 || inserts) {
            throw e;
          }
          unsatisfied = true;
        }
        return Cursor.EMPTY;
      }
      Reader crudReader = reader(crud, identity);
      attach(crudReader);
      try {
        Operator tree = root();
        tree.open(new Operator.Execution(crudReader, null));
        return tree;
      } catch (ReadFailure e) {
        @SuppressWarnings("unchecked")
        E cause = (E) e.getCause();
        throw cause;
      }
    }

    /** The root of the operator tree that runs this statement; null for DML. */
    @Nullable public Operator getRoot() {
      return mutations() == null ? root() : null;
    }

    Operator root() {
      if (root == null) {
        root = buildTree();
      }
      return root;
    }

    private void attach(Reader reader) {
      this.reader = reader;
      cachedRows = null;
      for (Source s : sources) {
        if (s.derived != null) {
          s.derived.attach(reader);
        }
      }
      for (Plan p : subplans.values()) {
        p.attach(reader);
      }
      for (Plan member : members) {
        member.attach(reader);
      }
    }

    Reader reader() {
      if (reader == null) {
        throw new IllegalStateException("Plan is not being executed");
      }
      return reader;
    }

    Evaluator.Context context(@Nullable Map<String, Object> outer) {
      return new Evaluator.Context(outer, subplans, catalog, parameters, columnTypes());
    }

    /** The type of every {@code alias.column} the plan reads, which a NULL value cannot tell. */
    private Map<String, DataType> columnTypes() {
      if (columnTypes == null) {
        Map<String, DataType> types = new java.util.HashMap<>();
        if (logical != null) {
          for (LogicalPlan.Source s : logical.sources) {
            if (s.target != null) {
              for (String name : s.target.metadata.getColumnNames()) {
                types.put(s.qualifier + "." + name, s.target.metadata.getColumnDataType(name));
              }
            } else if (s.derived != null) {
              for (int i = 0; i < s.columns.size() && i < s.derived.outputTypes.size(); i++) {
                if (s.derived.outputTypes.get(i) != null) {
                  types.put(s.qualifier + "." + s.columns.get(i), s.derived.outputTypes.get(i));
                }
              }
            }
          }
        }
        columnTypes = types;
      }
      return columnTypes;
    }

    /** Evaluates the statement for one row of the enclosing query and returns all its rows. */
    List<Map<String, Object>> evaluate(@Nullable Map<String, Object> outer) {
      Operator tree = root();
      tree.open(new Operator.Execution(reader(), outer));
      return readAll(tree);
    }

    /** Composes the operators, bottom up: sources and joins, filter, shaping, limit. */
    private Operator buildTree() {
      Operator op;
      if (setKind != null) {
        op =
            new Operators.SetOperation(
                setKind,
                setAll,
                members.get(0).root(),
                members.get(1).root(),
                outputNames,
                members);
        if (!orderBy.isEmpty()) {
          op = new Operators.Sort(op, orderBy, outputNames, this);
        }
        if (offset > 0 || limit >= 0) {
          op = new Operators.Limit(op, offset, limit);
        }
        return op;
      }
      if (sources.isEmpty()) {
        op =
            new Operators.Values(
                Collections.<Map<String, Object>>singletonList(new LinkedHashMap<String, Object>()),
                "Values (one row, no FROM)");
      } else {
        op = sourceOperator(sources.get(0), streaming);
        List<String> leftColumns = new ArrayList<>(); // keys of the rows joined so far
        for (int i = 1; i < sources.size(); i++) {
          Source right = sources.get(i);
          LogicalPlan.Join spec = joins.get(i - 1);
          for (String c : sources.get(i - 1).columns) {
            leftColumns.add(sources.get(i - 1).qualifier + "." + c);
          }
          List<String> leftKeys = new ArrayList<>(leftColumns);
          if (right.lateral) {
            op = new Operators.LateralJoin(op, right, spec.on, spec.kind, leftKeys, this);
          } else if (right.lookup != null) {
            op = new Operators.LookupJoin(op, right, spec.on, spec.kind, leftKeys, this);
          } else if (!spec.hashLeft.isEmpty()) {
            op =
                new Operators.HashJoin(
                    op,
                    sourceOperator(right, false),
                    right,
                    spec.hashLeft,
                    spec.hashRight,
                    spec.on,
                    spec.kind,
                    leftKeys,
                    this);
          } else {
            op =
                new Operators.NestedLoopJoin(
                    op, sourceOperator(right, false), right, spec.on, spec.kind, leftKeys, this);
          }
        }
      }
      if (!residual.isEmpty()) {
        op = new Operators.Filter(op, residual, this);
      }
      // Sort and Distinct need the source columns (or hidden aggregate values) next to the output
      // columns, so the row is only narrowed to the output columns at the end
      boolean reshaped = !orderBy.isEmpty() || distinct;
      if (aggregated) {
        op =
            new Operators.Aggregate(
                op,
                groupBy,
                logical == null ? null : logical.groupingSets,
                having,
                outputNames,
                outputExpressions,
                orderBy,
                windows,
                this);
      } else {
        if (!windows.isEmpty()) {
          op = new Operators.Window(op, windows, this);
        }
        op = new Operators.Project(op, outputNames, outputExpressions, reshaped, this);
        if (logical != null && !logical.expanded.isEmpty()) {
          op = new Operators.Expand(op, logical.expanded);
        }
      }
      if (!orderBy.isEmpty()) {
        op = new Operators.Sort(op, orderBy, outputNames, this);
      }
      if (distinct) {
        op =
            new Operators.Distinct(
                op,
                outputNames,
                logical == null ? Collections.<Expression>emptyList() : logical.distinctOn,
                this);
      }
      if (offset > 0 || limit >= 0) {
        op =
            logical != null && logical.withTies && !orderBy.isEmpty()
                ? new Operators.LimitWithTies(op, offset, limit, orderBy, outputNames, this)
                : new Operators.Limit(op, offset, limit);
      }
      if (reshaped) {
        op = new Operators.Project(op, outputNames, null, false, this);
      }
      return op;
    }

    private Operator sourceOperator(Source s, boolean stream) {
      if (s.derived != null) {
        return new Operators.Rekey(s.derived, s.qualifier, s.columns);
      }
      if (s.lookup != null) {
        return new Operators.Lookup(s, this);
      }
      if (s.loaded != null) {
        return new Operators.Values(
            s.loaded, s.catalogTable + " AS " + s.qualifier + " (in memory)");
      }
      return new Operators.Scan(s.scan(), s.qualifier, s.columns, stream);
    }

    /** Reads a lookup source for one outer row, binding its lookup columns to the row's values. */
    List<Map<String, Object>> lookupRows(
        Source s, Map<String, Object> left, Evaluator.Context ctx) {
      Operation operation = lookupOperation(s, left, ctx);
      return operation == null
          ? new ArrayList<Map<String, Object>>()
          : rowsOf(s.qualifier, s.columns, reader().read(operation));
    }

    /**
     * The lookups of several outer rows, as candidate rows in the same order. Rows whose Gets share
     * a partition key and name full clustering keys are read with one Scan over that partition, an
     * OR of their keys, so N lookups cost one read instead of N round trips; the others are read
     * one by one. {@code run} executes the independent reads, concurrently or in order.
     */
    List<List<Map<String, Object>>> lookupRows(
        Source s,
        List<Map<String, Object>> rows,
        Evaluator.Context ctx,
        java.util.function.Consumer<List<Runnable>> run) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>>[] results = new List[rows.size()];
      List<Operation> operations = new ArrayList<>();
      Map<Key, List<Integer>> groups = new LinkedHashMap<>();
      List<Runnable> tasks = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        Operation operation = lookupOperation(s, rows.get(i), ctx);
        operations.add(operation);
        if (operation == null) {
          results[i] = new ArrayList<>();
        } else if (operation instanceof Get && ((Get) operation).getClusteringKey().isPresent()) {
          groups
              .computeIfAbsent(((Get) operation).getPartitionKey(), k -> new ArrayList<>())
              .add(i);
        } else {
          tasks.add(lookupOne(s, i, operations, results));
        }
      }
      for (List<Integer> group : groups.values()) {
        if (group.size() == 1 || !sameConditions(group, operations)) {
          for (int i : group) {
            tasks.add(lookupOne(s, i, operations, results));
          }
          continue;
        }
        tasks.add(
            () -> {
              try {
                lookupGroup(s, group, operations, results);
              } catch (IllegalArgumentException e) {
                if (!String.valueOf(e.getMessage()).contains("DB-CORE-10106")) {
                  throw e;
                }
                // The transaction wrote one of these records: ScalarDB refuses to scan it but
                // serves it to a Get, so this group goes back to one read per row
                for (int i : group) {
                  lookupOne(s, i, operations, results).run();
                }
              }
            });
      }
      run.accept(tasks);
      return Arrays.asList(results);
    }

    private Runnable lookupOne(
        Source s, int i, List<Operation> operations, List<Map<String, Object>>[] results) {
      return () -> results[i] = rowsOf(s.qualifier, s.columns, reader().read(operations.get(i)));
    }

    /** True if the group's Gets carry the same conditions, so one scan can serve them all. */
    private static boolean sameConditions(List<Integer> group, List<Operation> operations) {
      Set<Selection.Conjunction> first = ((Get) operations.get(group.get(0))).getConjunctions();
      for (int i : group) {
        if (!((Get) operations.get(i)).getConjunctions().equals(first)) {
          return false;
        }
      }
      return true;
    }

    /** One Scan over the group's partition, an OR of the Gets' clustering keys, split by key. */
    private void lookupGroup(
        Source s,
        List<Integer> group,
        List<Operation> operations,
        List<Map<String, Object>>[] results) {
      Get first = (Get) operations.get(group.get(0));
      List<String> keyNames = new ArrayList<>(s.lookup().table().metadata.getClusteringKeyNames());
      Set<AndConditionSet> keys = new LinkedHashSet<>();
      for (int i : group) {
        Get get = (Get) operations.get(i);
        Set<ConditionalExpression> equalities = new LinkedHashSet<>();
        for (Column<?> column : get.getClusteringKey().get().getColumns()) {
          equalities.add(
              ConditionBuilder.buildConditionalExpression(
                  column, ConditionalExpression.Operator.EQ));
        }
        if (get.getConjunctions().isEmpty()) {
          keys.add(ConditionSetBuilder.andConditionSet(equalities).build());
        }
        for (Selection.Conjunction conjunction : get.getConjunctions()) {
          Set<ConditionalExpression> all = new LinkedHashSet<>(equalities);
          all.addAll(conjunction.getConditions());
          keys.add(ConditionSetBuilder.andConditionSet(all).build());
        }
      }
      List<String> projections = new ArrayList<>(first.getProjections());
      if (!projections.isEmpty()) {
        for (String name : keyNames) {
          if (!projections.contains(name)) {
            projections.add(name);
          }
        }
      }
      Scan scan =
          Scan.newBuilder()
              .namespace(first.forNamespace().get())
              .table(first.forTable().get())
              .partitionKey(first.getPartitionKey())
              .whereOr(keys)
              .projections(projections)
              .build();
      Map<List<Object>, List<Result>> byKey = new HashMap<>();
      for (Result result : reader().read(scan)) {
        List<Object> key = new ArrayList<>();
        for (String name : keyNames) {
          key.add(Objects.requireNonNull(result.getColumns().get(name), name).getValueAsObject());
        }
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
      }
      for (int i : group) {
        List<Object> key = new ArrayList<>();
        for (Column<?> column : ((Get) operations.get(i)).getClusteringKey().get().getColumns()) {
          key.add(column.getValueAsObject());
        }
        results[i] =
            rowsOf(
                s.qualifier, s.columns, byKey.getOrDefault(key, Collections.<Result>emptyList()));
      }
    }

    /**
     * The read of a lookup source for one outer row: its lookup columns and late-bound conditions
     * take the row's and the parameters' values. Null when a value is NULL, which matches nothing.
     */
    @Nullable Operation lookupOperation(Source s, Map<String, Object> left, Evaluator.Context ctx) {
      List<ConditionalExpression> conditions = new ArrayList<>(s.lookup().pushed);
      for (Map.Entry<String, Expression> p : s.lookupParams().entrySet()) {
        Object value =
            forColumn(
                s.lookup().table().metadata,
                p.getKey(),
                p.getValue(),
                Collections.singletonList(left),
                ctx);
        if (value == null) {
          return null; // NULL never equals anything
        }
        Column<?> column;
        try {
          column = columnFromValue(s.lookup().table().metadata, p.getKey(), value);
        } catch (IllegalArgumentException e) {
          if (isOutOfRange(e)) {
            return null; // an int column never equals a value beyond its range
          }
          throw e;
        }
        conditions.add(
            ConditionBuilder.buildConditionalExpression(column, ConditionalExpression.Operator.EQ));
      }
      for (Expression late : s.lookup().pushedLate) {
        ConditionalExpression condition;
        try {
          condition = pushable(late, s.lookup().table().metadata, left, ctx);
        } catch (IllegalArgumentException e) {
          Boolean constant = beyondRange(late, e, left, ctx);
          if (constant == null) {
            throw e;
          }
          if (!constant) {
            return null;
          }
          continue; // true for every row of the table
        }
        if (condition == null) {
          return null;
        }
        conditions.add(condition);
      }
      return operation(s.lookup(), conditions, s.lookupOrderBy, s.lookupLimit);
    }

    /**
     * A comparison of an int column with a number beyond its range, which ScalarDB cannot express:
     * its truth for every row of the table, or null if {@code failure} is something else.
     */
    @Nullable private static Boolean beyondRange(
        Expression e,
        IllegalArgumentException failure,
        Map<String, Object> row,
        Evaluator.Context ctx) {
      if (!isOutOfRange(failure) || !(e instanceof ComparisonOperator)) {
        return null;
      }
      ComparisonOperator c = (ComparisonOperator) e;
      Object v = Evaluator.eval(c.getRightExpression(), Collections.singletonList(row), ctx);
      ConditionalExpression.Operator op = operator(c);
      if (!(v instanceof Number) || op == null) {
        return null;
      }
      boolean above = ((Number) v).doubleValue() > 0;
      switch (op) {
        case EQ:
          return false;
        case NE:
          return true;
        case LT:
        case LTE:
          return above;
        case GT:
        case GTE:
          return !above;
        default:
          return null;
      }
    }

    /** True if the lookup's bindings refer to parameters only, not to an outer row. */
    private static boolean selfContained(Source s) {
      for (Expression e : s.lookupParams().values()) {
        if (!columnRefs(e).isEmpty()) {
          return false;
        }
      }
      return true;
    }
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
  static Map<String, Object> rowOf(String qualifier, List<String> columns, Result result) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (String name : result.getContainedColumnNames()) {
      row.put(qualifier + "." + name, result.getAsObject(name));
    }
    for (String name : columns) {
      row.putIfAbsent(qualifier + "." + name, null);
    }
    return row;
  }

  static List<Map<String, Object>> rowsOf(
      String qualifier, List<String> columns, List<Result> results) {
    List<Map<String, Object>> rows = new ArrayList<>(results.size());
    for (Result result : results) {
      rows.add(rowOf(qualifier, columns, result));
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

  private static void explain(Plan plan, List<String> out, int indent, boolean actual) {
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

  static String text(List<Expression> expressions) {
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
      t.append(" conditions=").append(text(b.pushedLate));
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
  private static Column<?> columnFromValue(
      TableMetadata metadata, String name, @Nullable Object value) {
    if (value == null) {
      return columnFromText(metadata, name, null);
    }
    DataType type = metadata.getColumnDataType(existing(metadata, name));
    if (type == DataType.TIMESTAMP && value instanceof java.time.Instant) {
      // PostgreSQL compares a timestamp with a timestamptz in the session's zone
      value = LocalDateTime.ofInstant((java.time.Instant) value, Evaluator.zone());
    } else if (type == DataType.TIMESTAMPTZ && value instanceof LocalDateTime) {
      value = ((LocalDateTime) value).atZone(Evaluator.zone()).toInstant();
    }
    String text;
    if (value instanceof java.nio.ByteBuffer) {
      java.nio.ByteBuffer b = ((java.nio.ByteBuffer) value).duplicate();
      StringBuilder hex = new StringBuilder("\\x");
      while (b.hasRemaining()) {
        hex.append(String.format("%02x", b.get()));
      }
      text = hex.toString();
    } else {
      text =
          value instanceof BigDecimal
              ? ((BigDecimal) value).toPlainString()
              // an array keeps PostgreSQL's text form, {a,b}, not Java's [a, b]
              : value instanceof List ? Evaluator.text(value) : String.valueOf(value);
    }
    return columnFromText(metadata, name, text);
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
    catalog.invalidate();
    ctes = Collections.emptyMap();
    int needed = parameterCount(sql);
    if (needed > parameters.size()) {
      throw new IllegalArgumentException("Missing value for parameter $" + needed);
    }
    BOUND.set(parameters);
    EMBEDDED.set(false);
    try {
      Plan plan = plan(sql);
      plan.cacheable = !EMBEDDED.get() && plan.reusable();
      return plan;
    } catch (IllegalArgumentException e) {
      // psql describes objects with catalog queries beyond this engine (UNION, table functions,
      // arrays); for the tables involved the answer is always "nothing", so return that
      // Only a read that the engine cannot plan; a misspelled column, or a DML statement whose text
      // happens to mention pg_catalog, is reported as it would be anywhere else (todo 024)
      String m = String.valueOf(e.getMessage());
      String word = trimStatement(sql).split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
      if (sql.toLowerCase(Locale.ROOT).contains("pg_catalog.")
          && (word.equals("SELECT") || word.equals("WITH") || word.startsWith("("))
          && !m.startsWith("Unknown column")
          && !m.startsWith("Ambiguous column")) {
        return Plan.constant(catalogColumnNames(sql), Collections.emptyList(), "SELECT");
      }
      throw e;
    } finally {
      BOUND.remove();
    }
  }

  /**
   * The output column names of a catalog query this engine cannot plan, so that the empty answer
   * still describes its columns as PostgreSQL would: a driver that gets no RowDescription treats
   * the statement as one that returns no result at all (SQLAlchemy's ResourceClosedError). Empty
   * when they cannot be told, such as for {@code *} or SQL JSQLParser does not accept.
   */
  private List<String> catalogColumnNames(String sql) {
    try {
      Statement statement = parseStatement(sql);
      Select select = statement instanceof Select ? (Select) statement : null;
      while (select != null && !(select instanceof PlainSelect)) {
        if (select instanceof SetOperationList) {
          select = ((SetOperationList) select).getSelects().get(0);
        } else if (select instanceof ParenthesedSelect) {
          select = ((ParenthesedSelect) select).getSelect();
        } else {
          return Collections.emptyList();
        }
      }
      if (select == null) {
        return Collections.emptyList();
      }
      List<String> names = new ArrayList<>();
      for (SelectItem<?> item : ((PlainSelect) select).getSelectItems()) {
        Expression e = item.getExpression();
        if (e instanceof AllColumns || e instanceof AllTableColumns) {
          return Collections.emptyList();
        }
        names.add(item.getAlias() != null ? unquote(item.getAlias().getName()) : label(e));
      }
      return names;
    } catch (RuntimeException e) {
      return Collections.emptyList();
    }
  }

  // The parameters of the statement being planned on this thread. Planning helpers that look at
  // literals are static (the optimizer shares them), so they read the values from here; every
  // Plan created meanwhile takes a snapshot for its execution, which may happen later.
  private static final ThreadLocal<List<Object>> BOUND =
      ThreadLocal.withInitial(Collections::emptyList);

  // Set when a placeholder's value became part of the plan being made (a key, a LIMIT, a VALUES
  // row): such a plan serves this execution only, see Plan#isCacheable
  private static final ThreadLocal<Boolean> EMBEDDED = ThreadLocal.withInitial(() -> false);

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
      if (c == '"') {
        quoted = true;
        wasQuoted = true;
      } else if (c == '{') {
        throw new PgException.Argument("0A000", "Nested arrays are not supported: " + text);
      } else if (c == ',') {
        out.add(
            wasQuoted || !cur.toString().trim().equalsIgnoreCase("NULL")
                ? cur.toString().trim()
                : null);
        cur.setLength(0);
        wasQuoted = false;
      } else {
        cur.append(c);
      }
      any = true;
    }
    if (any) {
      out.add(
          wasQuoted || !cur.toString().trim().equalsIgnoreCase("NULL")
              ? cur.toString().trim()
              : null);
    }
    return out;
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
    return escapeStringQuotes(
        sql.replaceAll("(?i)OPERATOR\\(pg_catalog\\.([^)]+)\\)", "$1")
            // a reserved word as a column alias, as Sequelize writes AS primary, AS unique
            .replaceAll(
                "(?i)\\bAS\\s+(primary|unique|order|group|user|limit|offset|default|check|references|constraint|desc|asc|key|index|exists|using|on|join|all|any|some|case|end|is|in|not|null|like|between|distinct|having|window|over)\\b(?!\")",
                "AS \"$1\""));
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

  private static final Pattern COORDINATOR_TABLES =
      Pattern.compile("(?is)(CREATE|DROP)\\s+COORDINATOR\\s+TABLES(\\s+IF\\s+(NOT\\s+)?EXISTS)?");

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
    Matcher schema = CREATE_SCHEMA.matcher(trimmed);
    if (schema.matches()) {
      boolean ifNotExists = schema.group(1) != null;
      String name = checkedName(namespace(schema.group(2)), false);
      return Plan.ddl(() -> admin.createNamespace(name, ifNotExists), "CREATE SCHEMA");
    }
    Matcher explain = EXPLAIN.matcher(trimmed);
    if (explain.matches()) {
      Plan inner = plan(explain.group(2));
      if (inner.ddl != null) {
        throw new PgException.Argument("0A000", "EXPLAIN is not supported for DDL: " + sql);
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
      String name = unquote(table.getName());
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
          if (!seen.add(columnName(c))) {
            throw new PgException.Argument(
                "42701", "column \"" + columnName(c) + "\" specified more than once");
          }
        }
      }
      if (insert.getConflictAction() != null
          && insert.getConflictAction().getUpdateSets() != null) {
        checkSingleAssignments(insert.getConflictAction().getUpdateSets());
      }
      Map<String, Cte> saved = pushCtes(insert.getWithItemsList());
      try {
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
        return Plan.deferred(ctx -> Collections.singletonList(update(update, t, ctx)));
      } finally {
        ctes = saved;
      }
    }
    if (statement instanceof net.sf.jsqlparser.statement.delete.Delete) {
      net.sf.jsqlparser.statement.delete.Delete delete =
          (net.sf.jsqlparser.statement.delete.Delete) statement;
      Map<String, Cte> saved = pushCtes(delete.getWithItemsList());
      try {
        if (!usingItems(delete).isEmpty()
            || !keyed(delete)
            || delete.getReturningClause() != null) {
          return deleteByScan(delete);
        }
        Target t = target(delete.getTable());
        checkTextAgainstNumber(delete.getWhere(), t);
        return Plan.deferred(ctx -> Collections.singletonList(delete(delete, t, ctx)));
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

  /** A FROM item while planning: what it is, and what has been pushed down to it so far. */
  // ---- DDL ----

  private String namespaceOf(Table table) {
    return namespace(table.getSchemaName());
  }

  /** The user the client connected as, for {@code current_user}. */
  void setUser(String user) {
    catalog.user = user;
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
    searchPath = entries.isEmpty() ? Arrays.asList("$user", "public") : entries;
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
  private String namespace(@Nullable String schema) {
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
   * otherwise.
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
          unenforced(column, definition.getColDataType().getDataType(), specs, keyColumn));
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
      builder.addColumn(column, columnType(definition.getColDataType().getDataType()));
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
      builder.addPartitionKey(column.trim());
    }
    for (String spec : clusteringKey) {
      String[] parts = spec.trim().split("\\s+", -1);
      if (!columns.contains(parts[0])) {
        throw new IllegalArgumentException("Unknown clustering key column: " + parts[0]);
      }
      boolean desc = parts.length > 1 && parts[1].equalsIgnoreCase("DESC");
      builder.addClusteringKey(parts[0], desc ? Scan.Ordering.Order.DESC : Scan.Ordering.Order.ASC);
    }
    TableMetadata metadata = builder.build();
    boolean ifNotExists = create.isIfNotExists();
    Plan plan =
        Plan.ddl(() -> admin.createTable(namespace, name, metadata, ifNotExists), "CREATE TABLE");
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
      Evaluator.checkCollation(collate.group(1));
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
        out.add(
            "CHAR on "
                + col
                + " is stored as text: no blank padding, so comparisons do not ignore trailing"
                + " spaces, and the length is not enforced");
        break;
      case "varchar":
      case "character varying":
        checkLength("varchar", args);
        if (withArgs) {
          out.add("the length of " + col + " (" + declared.trim() + ") is not enforced");
        }
        break;
      case "numeric":
      case "decimal":
      case "dec":
        if (args.length > 0 && (args[0] < 1 || args[0] > 1000)) {
          throw new PgException.Argument(
              "22023", "NUMERIC precision " + args[0] + " must be between 1 and 1000");
        }
        if (args.length > 1 && (args[1] < -1000 || args[1] > 1000)) {
          throw new PgException.Argument(
              "22023", "NUMERIC scale " + args[1] + " must be between -1000 and 1000");
        }
        out.add(
            "NUMERIC on "
                + col
                + " is stored as double precision (binary floating point): precision and scale are"
                + " not kept, and decimal fractions are approximate");
        break;
      case "smallint":
      case "int2":
        out.add("SMALLINT on " + col + " is stored as integer: its range is not enforced");
        break;
      case "timestamp":
      case "timestamp without time zone":
      case "timestamptz":
      case "timestamp with time zone":
        if (args.length == 0 || args[0] > 3) {
          out.add(
              "TIMESTAMP on "
                  + col
                  + " is stored with millisecond precision (ScalarDB's): finer fractions are"
                  + " rounded");
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
  private static DataType columnType(String declared) {
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
    String name = unquote(create.getTable().getName());
    List<String> columns = create.getIndex().getColumnsNames();
    if (columns.size() != 1) {
      throw new IllegalArgumentException("ScalarDB indexes cover one column: " + create);
    }
    if (!columns.get(0).matches("\"?[\\w$ ]+\"?")) {
      throw new PgException.Argument(
          "0A000", "Expression indexes are not supported: " + columns.get(0));
    }
    String column = unquote(columns.get(0));
    String generated = name + "_" + column + "_idx";
    String given =
        create.getIndex().getName() == null ? null : unquote(create.getIndex().getName());
    Plan plan =
        Plan.ddl(
            () -> {
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
          String name = unquote(target.getName());
          return Plan.ddl(() -> admin.dropTable(namespace, name, ifExists), "DROP TABLE");
        }
      case "SCHEMA":
        {
          String namespace = unquote(target.getName());
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
                    admin.dropTable(namespace, table, true);
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
    String name = unquote(alter.getTable().getName());
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
          notices.addAll(unenforced(column, c.getColDataType().getDataType(), specs, false));
          DataType type = dataType(c.getColDataType().getDataType());
          steps.add(() -> admin.addNewColumnToTable(namespace, name, column, type));
        }
      } else if (expression.getOperation() == AlterOperation.RENAME
          && expression.getColOldName() != null) {
        String oldName = unquote(expression.getColOldName());
        String newName = checkedName(unquote(expression.getColumnName()), true);
        steps.add(() -> admin.renameColumn(namespace, name, oldName, newName));
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

  /** The numeric arguments of a declared type: {10, 2} for {@code NUMERIC (10, 2)}. */
  private static long[] typeArguments(String declared) {
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

  /** The length PostgreSQL accepts for CHAR(n) and VARCHAR(n), with its messages. */
  private static void checkLength(String type, long[] args) {
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
    boolean referenced; // the step referred to the working table

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
    if (names.size() > columns.size()) {
      throw new IllegalArgumentException(
          "The column list " + names + " has more names than the query has columns");
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
    if (set.getOrderByElements() != null) {
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
    return Plan.constant(names, rows, "SELECT");
  }

  /**
   * Binds a SELECT: resolves every FROM item and column reference (qualifying columns in place),
   * plans its subqueries, and records the clauses as a {@link LogicalPlan}. No physical decision is
   * made here.
   */
  private LogicalPlan bind(PlainSelect select, @Nullable Scope outer) throws ExecutionException {
    LogicalPlan plan = new LogicalPlan();
    List<Join> joinNodes = select.getJoins() == null ? Collections.emptyList() : select.getJoins();
    List<FromItem> fromItems = new ArrayList<>();
    if (select.getFromItem() != null) {
      fromItems.add(select.getFromItem());
    }
    for (Join j : joinNodes) {
      fromItems.add(j.getRightItem());
    }
    for (FromItem item : fromItems) {
      // a LATERAL subquery sees the sources before it as an enclosing scope, like a correlated
      // subquery, and the join runs it once per row of those sources
      boolean lateral =
          item instanceof net.sf.jsqlparser.statement.select.LateralSubSelect
              || item instanceof net.sf.jsqlparser.statement.select.TableFunction;
      LogicalPlan.Source s = source(item, lateral ? new Scope(plan.sources, outer) : outer);
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
      usingConditions.add(usingConditions(j, plan.sources, i + 1));
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
        for (LogicalPlan.Source s : targets) {
          s.star = true;
          int position = plan.sources.indexOf(s);
          for (String c : s.columns) {
            if (!(e instanceof AllTableColumns) && s.merged.containsKey(c)) {
              continue; // a USING column comes out once, from the left side
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
      plan.withTies = select.getFetch().toString().toUpperCase(Locale.ROOT).contains("WITH TIES");
    }
    for (int i = 0; i < plan.outputExpressions.size(); i++) {
      Expression e = plan.outputExpressions.get(i);
      plan.outputTypes.add(outputType(e, plan.sources));
      plan.outputOids.add(inferOid(e, plan.sources));
      if (isSetReturning(e)) {
        if (plan.aggregated) {
          throw new PgException.Argument(
              "0A000", "Set-returning functions are not supported with aggregates: " + e);
        }
        plan.expanded.add(plan.outputNames.get(i));
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
    plan.correlated = scope.correlated;
    return plan;
  }

  /** {@code unnest(array)} or {@code generate_subscripts(array, 1)} as a select item. */
  private static boolean isSetReturning(Expression e) {
    if (!(e instanceof Function)) {
      return false;
    }
    String name = ((Function) e).getName().toLowerCase(Locale.ROOT);
    name = name.substring(name.lastIndexOf('.') + 1);
    return name.equals("unnest")
        || name.equals("generate_subscripts")
        || JSON_TABLE_FUNCTIONS.contains(name);
  }

  /** Set-returning JSON functions, in the select list or as a table function in FROM. */
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
      for (LogicalPlan.Source s : sources) {
        if (s.catalogTable != null
            && c.getTable() != null
            && s.qualifier.equals(unquote(c.getTable().getName()))) {
          return (int) Catalog.catalogColumnOid(columnName(e)); // name, int2vector
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
      return Evaluator.lastJsonOperator((net.sf.jsqlparser.expression.JsonExpression) e)
              .endsWith(">>")
          ? 25
          : 3802;
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
        case "oid":
        case "regclass":
        case "regtype":
        case "regnamespace":
          return 26;
        default:
          return 25;
      }
    }
    if (e instanceof net.sf.jsqlparser.expression.CaseExpression) {
      for (net.sf.jsqlparser.expression.WhenClause when :
          ((net.sf.jsqlparser.expression.CaseExpression) e).getWhenClauses()) {
        int oid = inferOid(when.getThenExpression(), sources);
        if (oid != 0 && !(when.getThenExpression() instanceof NullValue)) {
          return oid;
        }
      }
      Expression otherwise = ((net.sf.jsqlparser.expression.CaseExpression) e).getElseExpression();
      return otherwise == null ? 0 : inferOid(otherwise, sources);
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
    if (e instanceof net.sf.jsqlparser.expression.BinaryExpression) {
      net.sf.jsqlparser.expression.BinaryExpression b =
          (net.sf.jsqlparser.expression.BinaryExpression) e;
      int l = inferOid(b.getLeftExpression(), sources);
      int r = inferOid(b.getRightExpression(), sources);
      boolean lTemporal = l == 1082 || l == 1083 || l == 1114 || l == 1184;
      boolean rTemporal = r == 1082 || r == 1083 || r == 1114 || r == 1184;
      if (l == 1186 && r == 1186) {
        return 1186;
      }
      if ((lTemporal && r == 1186) || (l == 1186 && rTemporal)) {
        int t = lTemporal ? l : r;
        return t == 1082 ? 1114 : t; // date + interval is a timestamp
      }
      if (lTemporal && rTemporal) {
        return l == 1082 && r == 1082 ? 23 : 1186; // date - date is days; else an interval
      }
      if (l == 1082 && (r == 23 || r == 20 || r == 21)
          || r == 1082 && (l == 23 || l == 20 || l == 21)) {
        return 1082;
      }
      if (l == 1186 || r == 1186) {
        return 1186;
      }
      if (l == 701 || r == 701 || l == 700 || r == 700) {
        return 701;
      }
      if (l == 1700 || r == 1700) {
        return 1700;
      }
      if (l == 20 || r == 20) {
        return 20;
      }
      return l == 0 || r == 0 ? 0 : 23;
    }
    if (e instanceof net.sf.jsqlparser.expression.AnalyticExpression) {
      net.sf.jsqlparser.expression.AnalyticExpression a =
          (net.sf.jsqlparser.expression.AnalyticExpression) e;
      String name = a.getName().toUpperCase(Locale.ROOT);
      name = name.substring(name.lastIndexOf('.') + 1);
      switch (name) {
        case "ROW_NUMBER":
        case "RANK":
        case "DENSE_RANK":
          return 20;
        case "NTILE":
          return 23;
        case "PERCENT_RANK":
        case "CUME_DIST":
          return 701;
        default:
          return a.getExpression() == null
              ? (name.equals("COUNT") ? 20 : 0)
              : functionOid(name, Collections.singletonList(a.getExpression()), sources);
      }
    }
    if (e instanceof Function) {
      Function f = (Function) e;
      String name = f.getName().toUpperCase(Locale.ROOT);
      name = name.substring(name.lastIndexOf('.') + 1);
      List<Expression> args =
          f.getParameters() == null
              ? Collections.<Expression>emptyList()
              : new ArrayList<Expression>(f.getParameters());
      return functionOid(name, args, sources);
    }
    return 0;
  }

  private static int functionOid(
      String name, List<Expression> args, List<LogicalPlan.Source> sources) {
    int first = args.isEmpty() ? 0 : inferOid(args.get(0), sources);
    switch (name) {
      case "COUNT":
        return 20;
      case "JSON_BUILD_OBJECT":
      case "JSON_BUILD_ARRAY":
      case "TO_JSON":
      case "JSON_AGG":
      case "JSON_OBJECT_AGG":
      case "JSON_EXTRACT_PATH":
      case "JSON_ARRAY_ELEMENTS":
        return 114;
      case "JSONB_BUILD_OBJECT":
      case "JSONB_BUILD_ARRAY":
      case "TO_JSONB":
      case "JSONB_AGG":
      case "JSONB_OBJECT_AGG":
      case "JSONB_EXTRACT_PATH":
      case "JSONB_ARRAY_ELEMENTS":
        return 3802;
      case "JSON_TYPEOF":
      case "JSONB_TYPEOF":
      case "JSON_EXTRACT_PATH_TEXT":
      case "JSONB_EXTRACT_PATH_TEXT":
      case "JSON_ARRAY_ELEMENTS_TEXT":
      case "JSONB_ARRAY_ELEMENTS_TEXT":
        return 25;
      case "JSON_ARRAY_LENGTH":
      case "JSONB_ARRAY_LENGTH":
        return 23;
      case "ARRAY_AGG":
        return (int) Catalog.arrayOid(first);
      case "UNNEST":
        return (int) Catalog.elementOid(first);
      case "GENERATE_SUBSCRIPTS":
        return 23;
      case "CURRENT_SCHEMAS":
        return 1009;
      case "TO_REGTYPE":
      case "TO_REGCLASS":
        return 26;
      case "SUM":
        return first == 23 || first == 21 ? 20 : first == 20 ? 1700 : first == 0 ? 0 : first;
      case "AVG":
        return first == 701 || first == 700 ? 701 : 1700;
      case "MIN":
      case "MAX":
      case "COALESCE":
      case "NULLIF":
      case "GREATEST":
      case "LEAST":
      case "ABS":
      case "ROUND":
      case "FLOOR":
      case "CEIL":
      case "CEILING":
      case "TRUNC":
      case "MOD":
      case "SIGN":
      case "FIRST_VALUE":
      case "LAST_VALUE":
      case "NTH_VALUE":
      case "LAG":
      case "LEAD":
      case "DATE_TRUNC":
        for (Expression a : args) {
          int oid = inferOid(a, sources);
          if (oid != 0 && !(a instanceof NullValue)) {
            return oid;
          }
        }
        return 0;
      case "BOOL_AND":
      case "BOOL_OR":
      case "EVERY":
      case "STARTS_WITH":
        return 16;
      case "LENGTH":
      case "CHAR_LENGTH":
      case "CHARACTER_LENGTH":
      case "OCTET_LENGTH":
      case "POSITION":
      case "STRPOS":
      case "ASCII":
      case "CARDINALITY":
      case "ARRAY_LENGTH":
        return 23;
      case "POWER":
      case "POW":
      case "SQRT":
      case "RANDOM":
      case "EXP":
      case "LN":
      case "LOG":
      case "PI":
        return 701;
      case "NOW":
      case "CURRENT_TIMESTAMP":
      case "TRANSACTION_TIMESTAMP":
      case "STATEMENT_TIMESTAMP":
        return 1184;
      case "CURRENT_DATE":
        return 1082;
      case "EXTRACT":
      case "DATE_PART":
        return 1700;
      case "AGE":
        return 1186;
      case "TO_CHAR":
      case "UPPER":
      case "LOWER":
      case "CONCAT":
      case "SUBSTRING":
      case "SUBSTR":
      case "TRIM":
      case "LTRIM":
      case "RTRIM":
      case "BTRIM":
      case "REPLACE":
      case "LEFT":
      case "RIGHT":
      case "REVERSE":
      case "STRING_AGG":
      case "INITCAP":
      case "LPAD":
      case "RPAD":
      case "REPEAT":
      case "TRANSLATE":
      case "MD5":
      case "CHR":
      case "FORMAT_TYPE":
      case "PG_GET_USERBYID":
      case "PG_ENCODING_TO_CHAR":
      case "PG_GET_INDEXDEF":
      case "PG_GET_CONSTRAINTDEF":
      case "PG_GET_EXPR":
      case "OBJ_DESCRIPTION":
      case "COL_DESCRIPTION":
      case "ARRAY_TO_STRING":
      case "CURRENT_SCHEMA":
      case "CURRENT_USER":
      case "VERSION":
      case "CURRENT_SETTING":
      case "SET_CONFIG":
        return 25;
      case "PG_BACKEND_PID":
        return 23;
      default:
        return 0;
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
    if (e instanceof CastExpression) {
      return castTypeLabel(((CastExpression) e).getColDataType().getDataType());
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
      case "timestamp without time zone":
        return "timestamp";
      case "timestamp with time zone":
        return "timestamptz";
      default:
        return t.startsWith("pg_catalog.") ? t.substring("pg_catalog.".length()) : t;
    }
  }

  /**
   * The equalities a USING or NATURAL join stands for: each named (or, for NATURAL, each common)
   * column of the source at {@code index} equals the same column of the first earlier source that
   * has it. The column is recorded as merged on the right source.
   */
  private static List<Expression> usingConditions(
      Join j, List<LogicalPlan.Source> sources, int index) {
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
            cte.referenced = true;
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
          || (name.equals("unnest")
              && function.getParameters() != null
              && !allConstant(function.getParameters()))) {
        // a table function over the sources before it (or a JSON array): a lateral subquery that
        // expands the function, one row per element
        String column = name.equals("unnest") ? (alias != null ? alias : "unnest") : "value";
        if (item.getAlias() != null && item.getAlias().getAliasColumns() != null) {
          column = unquote(item.getAlias().getAliasColumns().get(0).name);
        }
        PlainSelect body = new PlainSelect();
        body.setSelectItems(
            Collections.<SelectItem<?>>singletonList(SelectItem.from(function, new Alias(column))));
        return derived(select(body, outer), item);
      }
      if (!name.endsWith("generate_series")) {
        if (!name.startsWith("pg_catalog.")) {
          throw new PgException.Argument("0A000", "Unsupported table function: " + function);
        }
        // psql's describe queries join pg_catalog functions such as unnest(); for the tables
        // involved the answer is always "nothing", so the function yields no rows
        String qualifier = alias != null ? alias : name.substring("pg_catalog.".length());
        LogicalPlan.Source source =
            new LogicalPlan.Source(qualifier, Collections.singletonList(qualifier), null, null);
        source.catalogTable = "Function " + function.getName();
        source.catalogRows = new ArrayList<>();
        return source;
      }
      // generate_series(start, stop [, step]) over integer literals: a one-column source
      List<Long> args = new ArrayList<>();
      for (Expression e :
          function.getParameters() == null
              ? Collections.<Expression>emptyList()
              : function.getParameters()) {
        if (!isLiteral(e) || literal(e) == null) {
          throw new IllegalArgumentException(
              "generate_series needs integer start, stop and step values: " + function);
        }
        args.add(new BigDecimal(literal(e)).longValueExact());
      }
      if (args.size() < 2 || args.size() > 3) {
        throw new IllegalArgumentException("generate_series takes 2 or 3 arguments: " + function);
      }
      long step = args.size() == 3 ? args.get(2) : 1;
      if (step == 0) {
        throw new IllegalArgumentException("step size cannot equal zero");
      }
      String qualifier = alias != null ? alias : "generate_series";
      String column = qualifier; // as in PostgreSQL, the alias also names the single column
      if (item.getAlias() != null && item.getAlias().getAliasColumns() != null) {
        column = unquote(item.getAlias().getAliasColumns().get(0).name);
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (long v = args.get(0); step > 0 ? v <= args.get(1) : v >= args.get(1); v += step) {
        rows.add(Collections.<String, Object>singletonMap(qualifier + "." + column, v));
        if (rows.size() > DEFAULT_MAX_ROWS_PER_WRITE * 100) {
          throw new IllegalArgumentException("generate_series produces too many rows: " + function);
        }
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
    self.working = Plan.working(name, names, working);
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
    if (!self.referenced) {
      return select(cte.item.getSelect(), outer);
    }
    if (step.outputNames.size() != names.size()) {
      throw new IllegalArgumentException(
          "Each query of a set operation must have the same number of columns: " + body);
    }
    boolean all = ((UnionOp) body.getOperations().get(0)).isAll();
    return Plan.recursive(names, anchor.outputTypes, anchor, step, working, all);
  }

  /** A subquery in FROM as a source: its alias names it, and the alias column list its columns. */
  private static LogicalPlan.Source derived(Plan derived, FromItem item) {
    if (item.getAlias() == null) {
      throw new IllegalArgumentException("A subquery in FROM must have an alias: " + item);
    }
    List<String> columns = new ArrayList<>(derived.outputNames);
    if (item.getAlias().getAliasColumns() != null) {
      List<String> names = new ArrayList<>();
      for (Alias.AliasColumn column : item.getAlias().getAliasColumns()) {
        names.add(unquote(column.name));
      }
      columns = renamed(columns, names);
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
              ? s.target.metadata.getColumnDataType(name)
              : s.derived != null && s.columns.contains(name)
                  ? s.derived.outputTypes.get(s.columns.indexOf(name))
                  : null;
        });
  }

  /** As above, for a single-table UPDATE or DELETE planned by key, without a scope. */
  private static void checkTextAgainstNumber(@Nullable Expression where, Target t) {
    if (where != null) {
      checkTextAgainstNumber(where, c -> t.metadata.getColumnDataType(columnName(c)));
    }
  }

  private static void checkTextAgainstNumber(
      Expression e, java.util.function.Function<net.sf.jsqlparser.schema.Column, DataType> typeOf) {
    e.accept(
        new ExpressionVisitorAdapter<Void>() {
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
        return s.target.metadata.getColumnDataType(name);
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
  private static Operation operation(
      LogicalPlan.Source s,
      List<ConditionalExpression> pushed,
      List<OrderByElement> orderBy,
      int limit) {
    Target t = s.table();
    List<String> projections = s.star ? Collections.emptyList() : new ArrayList<>(s.projections);
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
    for (List<Expression> row : tuples(values)) {
      if (row.size() != names.size()) {
        throw new IllegalArgumentException("INSERT row must have one value per column: " + row);
      }
      Map<String, Column<?>> columns = new LinkedHashMap<>();
      for (int i = 0; i < names.size(); i++) {
        columns.put(
            names.get(i),
            isDefaultKeyword(row.get(i))
                ? columnFromText(t.metadata, names.get(i), null) // no column defaults: NULL
                : value(t.metadata, names.get(i), row.get(i), ctx));
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
        String name = columnName(set.getColumn(i));
        if (!seen.add(name)) {
          throw new PgException.Argument(
              "42601", "multiple assignments to same column \"" + name + "\"");
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

  private static Update update(
      net.sf.jsqlparser.statement.update.Update update, Target t, Evaluator.Context ctx) {
    List<ConditionalExpression> conditions = conditions(update.getWhere(), t.metadata, update, ctx);
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
        b.value(
            value(t.metadata, columnName(set.getColumn(i)), defaultToNull(set.getValue(i)), ctx));
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

  private static Delete delete(
      net.sf.jsqlparser.statement.delete.Delete delete, Target t, Evaluator.Context ctx) {
    List<ConditionalExpression> conditions = conditions(delete.getWhere(), t.metadata, delete, ctx);
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
      ConditionalExpression p = pushable(c, t.metadata, null, literalContext());
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
      key.add(columnFromValue(t.metadata, name, values.get(name)));
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
      row.put(
          qualifier + "." + c, columnFromValue(t.metadata, c, values.get(c)).getValueAsObject());
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
          returning.types.add(t.metadata.getColumnDataType(c));
        }
      } else {
        returning.names.add(
            item.getAlias() != null ? unquote(item.getAlias().getName()) : label(e));
        returning.expressions.add(e);
        returning.types.add(
            isColumn(e) && t.metadata.getColumnNames().contains(columnName(e))
                ? t.metadata.getColumnDataType(columnName(e))
                : null);
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
      b.value(columnFromValue(t.metadata, column, values.get(column)));
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
      for (List<Expression> tuple : tuples(((Values) insert.getSelect()).getExpressions())) {
        if (tuple.size() != names.size()) {
          throw new IllegalArgumentException("INSERT row must have one value per column: " + tuple);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
          row.put(names.get(i), isDefaultKeyword(tuple.get(i)) ? null : evalLiteral(tuple.get(i)));
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
            GetBuilder.BuildableGet get =
                Get.newBuilder().namespace(t.namespace).table(t.table).partitionKey(partition);
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
            List<Result> found = reader.read(get.build());
            if (!found.isEmpty()) {
              if (!update) {
                return null; // DO NOTHING
              }
              Map<String, Object> env = rowOf(qualifier, allColumns, found.get(0));
              Map<String, Object> values = new LinkedHashMap<>();
              for (String c : allColumns) {
                values.put(c, env.get(qualifier + "." + c));
              }
              for (Map.Entry<String, Object> e : proposed.entrySet()) {
                env.put("excluded." + e.getKey(), e.getValue());
                env.put("EXCLUDED." + e.getKey(), e.getValue());
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
            columns.put(e.getKey(), columnFromValue(t.metadata, e.getKey(), e.getValue()));
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
    final TableMetadata metadata;

    Target(String namespace, String table, TableMetadata metadata) {
      this.namespace = namespace;
      this.table = table;
      this.metadata = metadata;
    }
  }

  private Target target(Table table) throws ExecutionException {
    String name = unquote(table.getName());
    List<String> candidates =
        table.getSchemaName() == null
            ? searchNamespaces() // an unqualified table: the first namespace of search_path that
            // has it
            : Collections.singletonList(namespace(table.getSchemaName()));
    for (String namespace : candidates) {
      String key = namespace + "." + name;
      TableMetadata metadata = metadataCache.get(key);
      if (metadata == null) {
        metadata = admin.getTableMetadata(namespace, name);
        if (metadata != null) {
          metadataCache.put(key, metadata);
        }
      }
      if (metadata != null) {
        return new Target(namespace, name, metadata);
      }
    }
    throw new PgException.Argument(
        "42P01",
        "Table not found: "
            + (table.getSchemaName() == null ? String.join(", ", candidates) : candidates.get(0))
            + "."
            + name);
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

  /** WHERE conditions for UPDATE/DELETE, which ScalarDB must be able to evaluate entirely. */
  private static List<ConditionalExpression> conditions(
      @Nullable Expression where,
      TableMetadata metadata,
      Statement statement,
      Evaluator.Context ctx) {
    List<ConditionalExpression> out = new ArrayList<>();
    for (Expression c : conjuncts(where)) {
      ConditionalExpression p = pushable(c, metadata, null, ctx);
      if (p == null) {
        throw new PgException.Argument("0A000", "Unsupported condition " + c + " in: " + statement);
      }
      out.add(p);
    }
    return out;
  }

  /**
   * The value of {@code e} for column {@code name}: for a TEXT column an untyped parameter is the
   * text it was sent as ('007' stays 007), for any other column its guess (see Evaluator.Untyped).
   */
  @Nullable private static Object forColumn(
      TableMetadata metadata,
      String name,
      Expression e,
      List<Map<String, Object>> rows,
      Evaluator.Context ctx) {
    return metadata.getColumnDataType(name) == DataType.TEXT
        ? Evaluator.evalText(e, rows, ctx)
        : Evaluator.eval(e, rows, ctx);
  }

  /** A column holding the value of a constant expression (a literal or a placeholder). */
  private static Column<?> value(
      TableMetadata metadata, String name, Expression e, Evaluator.Context ctx) {
    Object v = forColumn(metadata, name, e, Collections.<Map<String, Object>>emptyList(), ctx);
    DataType type = metadata.getColumnDataType(name);
    if ((type == DataType.INT || type == DataType.BIGINT)
        && v instanceof Number
        && !Evaluator.isIntegral(v)
        && Evaluator.isFinite(v)) {
      // a decimal written to an integer column is rounded, as PostgreSQL does (2.5 is 3)
      v = new BigDecimal(Evaluator.text(v)).setScale(0, RoundingMode.HALF_UP);
    }
    return columnFromValue(metadata, name, v);
  }

  /** Returns the ScalarDB condition for a {@code column op literal} conjunct, or null. */
  @Nullable static ConditionalExpression pushable(Expression e, TableMetadata metadata) {
    return pushable(e, metadata, null, null);
  }

  /**
   * As {@link #pushable(Expression, TableMetadata)}, with the compared value evaluated in {@code
   * ctx} (and {@code row}) when a context is given, so placeholders take their bound values. Null
   * when the condition cannot be pushed, or when its value is NULL, which matches nothing.
   */
  @Nullable static ConditionalExpression pushable(
      Expression e,
      TableMetadata metadata,
      @Nullable Map<String, Object> row,
      @Nullable Evaluator.Context ctx) {
    if (e instanceof IsNullExpression) {
      IsNullExpression n = (IsNullExpression) e;
      if (!isColumn(n.getLeftExpression())) {
        return null;
      }
      Column<?> column = column(metadata, columnName(n.getLeftExpression()), null);
      return ConditionBuilder.buildConditionalExpression(
          column,
          n.isNot()
              ? ConditionalExpression.Operator.IS_NOT_NULL
              : ConditionalExpression.Operator.IS_NULL);
    }
    if (e instanceof LikeExpression) {
      LikeExpression like = (LikeExpression) e;
      // ScalarDB's LIKE is case-sensitive, so ILIKE (and RLIKE/REGEXP) stay in memory
      if (like.getLikeKeyWord() != LikeExpression.KeyWord.LIKE
          || !isColumn(like.getLeftExpression())
          || !(like.getRightExpression() instanceof StringValue
              || like.getRightExpression() instanceof JdbcParameter)
          || (like.getEscape() != null && !(like.getEscape() instanceof StringValue))) {
        return null;
      }
      Column<?> column =
          valueColumn(
              metadata, columnName(like.getLeftExpression()), like.getRightExpression(), row, ctx);
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
      Column<?> column;
      try {
        column =
            valueColumn(
                metadata, columnName(c.getLeftExpression()), c.getRightExpression(), row, ctx);
      } catch (IllegalArgumentException | ArithmeticException failure) {
        if (c.getRightExpression() instanceof DoubleValue
            || (c.getRightExpression() instanceof SignedExpression
                && ((SignedExpression) c.getRightExpression()).getExpression()
                    instanceof DoubleValue)) {
          // grade > 12.5 compares as numbers in PostgreSQL; ScalarDB has no such condition on an
          // integer column, so the comparison is evaluated in memory
          return null;
        }
        if (row == null
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
      TableMetadata metadata,
      String name,
      Expression value,
      @Nullable Map<String, Object> row,
      @Nullable Evaluator.Context ctx) {
    if (ctx == null) {
      return isNull(value) ? null : column(metadata, name, value);
    }
    Object v =
        forColumn(
            metadata,
            name,
            value,
            row == null
                ? Collections.<Map<String, Object>>emptyList()
                : Collections.singletonList(row),
            ctx);
    return v == null ? null : columnFromValue(metadata, name, v);
  }

  @Nullable private static ConditionalExpression.Operator operator(ComparisonOperator e) {
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

  /** The column references in {@code e}, not descending into subqueries. */
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
    if (!plan.aggregated) {
      return;
    }
    Set<String> grouped = new HashSet<>();
    if (plan.groupBy != null) {
      for (Expression g : plan.groupBy) {
        grouped.add(
            unwrap(g).toString()); // GROUP BY c1 resolves to the output's (x + y) as written
      }
    }
    for (Expression e : plan.outputExpressions) {
      checkGrouped(e, grouped, plan);
    }
    if (plan.having != null) {
      checkGrouped(plan.having, grouped, plan);
    }
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
              return null;
            }
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

  private static Column<?> column(TableMetadata metadata, String name, @Nullable Expression value) {
    return columnFromText(metadata, name, value == null ? null : literal(value));
  }

  /**
   * Converts text to a column of the column's type, failing with PostgreSQL's messages: invalid
   * input syntax (SQLSTATE 22P02, or 22007 for dates and times) or a value out of range (22003, or
   * 22008 for dates and times).
   */
  private static Column<?> columnFromText(TableMetadata metadata, String name, @Nullable String s) {
    DataType type = metadata.getColumnDataType(existing(metadata, name));
    if (type == DataType.TEXT && s != null && s.indexOf('\0') >= 0) {
      // PostgreSQL has no NUL in text, and some backing databases would store it
      throw new PgException.Argument("22021", "invalid byte sequence for encoding \"UTF8\": 0x00");
    }
    try {
      return parseColumn(type, name, s);
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

  private static boolean isInteger(@Nullable String s) {
    return s != null && s.trim().matches("[+-]?\\d+");
  }

  /** True for the failure of {@link #columnFromText} on an integer beyond the column's range. */
  static boolean isOutOfRange(IllegalArgumentException e) {
    return String.valueOf(e.getMessage()).contains("out of range for type");
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

  private static Column<?> parseColumn(DataType type, String name, @Nullable String s) {
    switch (type) {
      case BOOLEAN:
        return s == null
            ? BooleanColumn.ofNull(name)
            : BooleanColumn.of(name, Evaluator.parseBoolean(s));
      case INT:
        return s == null
            ? IntColumn.ofNull(name)
            : IntColumn.of(name, new BigDecimal(s.trim()).intValueExact()); // ' 42' is 42
      case BIGINT:
        return s == null
            ? BigIntColumn.ofNull(name)
            : BigIntColumn.of(name, new BigDecimal(s.trim()).longValueExact());
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
        return s == null ? BlobColumn.ofNull(name) : BlobColumn.of(name, hex(s));
      case DATE:
        return s == null ? DateColumn.ofNull(name) : DateColumn.of(name, LocalDate.parse(s));
      case TIME:
        // ScalarDB keeps microseconds, as PostgreSQL does; a finer fraction rounds half up as there
        return s == null
            ? TimeColumn.ofNull(name)
            : TimeColumn.of(name, (LocalTime) Evaluator.roundedTo(LocalTime.parse(s), 6));
      case TIMESTAMP:
        // ScalarDB keeps milliseconds (said at CREATE TABLE); of() would truncate, so round first
        return s == null
            ? TimestampColumn.ofNull(name)
            : TimestampColumn.of(name, (LocalDateTime) Evaluator.roundedTo(localDateTime(s), 3));
      case TIMESTAMPTZ:
        return s == null
            ? TimestampTZColumn.ofNull(name)
            : TimestampTZColumn.of(name, (java.time.Instant) Evaluator.roundedTo(instant(s), 3));
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
    return "E".equalsIgnoreCase(s.getPrefix()) ? unescape(v) : v;
  }

  /**
   * PostgreSQL's escape-string processing: the C escapes, hex, octal, and 4- or 8-digit code
   * points.
   */
  static String unescape(String v) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c != '\\' || i + 1 >= v.length()) {
        out.append(c);
        continue;
      }
      char n = v.charAt(++i);
      switch (n) {
        case 'b':
          out.append('\b');
          break;
        case 'f':
          out.append('\f');
          break;
        case 'n':
          out.append('\n');
          break;
        case 'r':
          out.append('\r');
          break;
        case 't':
          out.append('\t');
          break;
        case 'x':
          {
            int end = i + 1;
            while (end < v.length() && end < i + 3 && Character.digit(v.charAt(end), 16) >= 0) {
              end++;
            }
            if (end == i + 1) {
              out.append('x');
            } else {
              out.append((char) Integer.parseInt(v.substring(i + 1, end), 16));
              i = end - 1;
            }
            break;
          }
        case 'u':
        case 'U':
          {
            int len = n == 'u' ? 4 : 8;
            if (i + len < v.length()) {
              out.appendCodePoint(Integer.parseInt(v.substring(i + 1, i + 1 + len), 16));
              i += len;
            } else {
              out.append(n);
            }
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
            out.append((char) Integer.parseInt(v.substring(i, end), 8));
            i = end - 1;
          } else {
            out.append(n); // backslash, quote, or an unknown escape stands for itself
          }
      }
    }
    return out.toString();
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
    String t = s.trim().replace(' ', 'T');
    if (t.length() == 10) {
      return LocalDate.parse(t).atStartOfDay(); // a date alone, as PostgreSQL accepts it
    }
    return OFFSET.matcher(t).find() && t.indexOf('T') > 0
        ? offsetDateTime(t).toLocalDateTime()
        : LocalDateTime.parse(t);
  }

  /** A timestamp as text; without an offset it is taken as UTC. */
  static java.time.Instant instant(String s) {
    String t = s.trim().replace(' ', 'T');
    if (t.endsWith("Z") || t.endsWith("z")) {
      t = t.substring(0, t.length() - 1) + "+00:00"; // pgx and ISO 8601 write UTC as Z
    }
    if (t.length() == 10) {
      return LocalDate.parse(t).atStartOfDay().atZone(Evaluator.zone()).toInstant();
    }
    return OFFSET.matcher(t).find() && t.indexOf('T') > 0
        ? offsetDateTime(t).toInstant()
        : LocalDateTime.parse(t).atZone(Evaluator.zone()).toInstant();
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

  /** Decodes a PostgreSQL bytea hex literal such as {@code \x0a0b}. */
  static byte[] hex(String s) {
    String h = s.startsWith("\\x") ? s.substring(2) : s;
    byte[] out = new byte[h.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
