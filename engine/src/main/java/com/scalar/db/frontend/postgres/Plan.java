package com.scalar.db.frontend.postgres;

import static com.scalar.db.frontend.postgres.QueryParser.BOUND;
import static com.scalar.db.frontend.postgres.QueryParser.DEFAULT_MAX_ROWS_PER_WRITE;
import static com.scalar.db.frontend.postgres.QueryParser.columnFromValue;
import static com.scalar.db.frontend.postgres.QueryParser.columnOid;
import static com.scalar.db.frontend.postgres.QueryParser.columnRefs;
import static com.scalar.db.frontend.postgres.QueryParser.dataType;
import static com.scalar.db.frontend.postgres.QueryParser.drain;
import static com.scalar.db.frontend.postgres.QueryParser.explain;
import static com.scalar.db.frontend.postgres.QueryParser.forColumn;
import static com.scalar.db.frontend.postgres.QueryParser.isOutOfRange;
import static com.scalar.db.frontend.postgres.QueryParser.listCursor;
import static com.scalar.db.frontend.postgres.QueryParser.makeUnique;
import static com.scalar.db.frontend.postgres.QueryParser.operation;
import static com.scalar.db.frontend.postgres.QueryParser.operator;
import static com.scalar.db.frontend.postgres.QueryParser.pushable;
import static com.scalar.db.frontend.postgres.QueryParser.readAll;
import static com.scalar.db.frontend.postgres.QueryParser.rowOf;
import static com.scalar.db.frontend.postgres.QueryParser.rowsOf;
import static com.scalar.db.frontend.postgres.QueryParser.unwrap;

import com.scalar.db.api.AndConditionSet;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionSetBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.CrudOperable;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Selection;
import com.scalar.db.api.Update;
import com.scalar.db.api.Upsert;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnsatisfiedConditionException;
import com.scalar.db.frontend.postgres.QueryParser.Target;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.Select;

/** ScalarDB operations plus the in-memory processing that completes the statement. */
final class Plan {
  @Nullable private List<Mutation> mutations;

  // DML whose mutations are built from the statement when first needed, with the parameters
  // bound at the time, so the plan can be reused
  @Nullable private java.util.function.Function<Evaluator.Context, List<Mutation>> mutationBuilder;

  private boolean rootFixed; // the root is given, not built from the sources
  boolean cacheable; // set by parse(): reusable with other parameter values
  // the statement's data-modifying WITH queries, run before it in its transaction
  List<Prelude> prelude = Collections.emptyList();

  /** A data-modifying WITH query: its write, and the working table its RETURNING rows fill. */
  static final class Prelude {
    final Plan write;
    final List<Map<String, Object>> rows;

    Prelude(Plan write, List<Map<String, Object>> rows) {
      this.write = write;
      this.rows = rows;
    }
  }

  /** Runs the data-modifying WITH queries, each once, filling their working tables. */
  <E extends TransactionException> void runPrelude(CrudOperable<E> crud, int maxRows) throws E {
    for (Prelude p : prelude) {
      p.rows.clear();
      if (p.write.write != null) {
        p.write.executeWrite(crud, maxRows);
        p.rows.addAll(drain(p.write.returned()));
      } else {
        p.rows.addAll(p.write.execute(crud));
      }
    }
  }

  Map<Integer, Integer> parameterOids = Collections.emptyMap(); // $n -> inferred type

  /**
   * The type PostgreSQL would give placeholder {@code $index}, from where it stands (a cast, a
   * column it is compared with or assigned to); 0, unspecified, where the frontend cannot tell, so
   * a driver such as pgx picks the type from its value as it did before.
   */
  public int parameterOid(int index) {
    return parameterOids.getOrDefault(index, 0);
  }

  /** Warnings for the client (PostgreSQL NOTICEs): what a DDL accepted but cannot enforce. */
  final List<String> notices = new ArrayList<>();

  boolean correlated; // a subquery that reads the enclosing row: evaluated per row, never cached
  @Nullable List<Map<String, Object>> cachedRows; // an uncorrelated subquery's rows, per run
  // x IN (this subquery): cachedRows as a hash set, built from the rows it was made of
  @Nullable Evaluator.InSet inSet;
  @Nullable List<Map<String, Object>> inSetRows;
  private final List<Source> sources;
  private final List<LogicalPlan.Join> joins;
  final List<String> outputNames;
  private final List<Expression> outputExpressions;
  final List<DataType> outputTypes; // null entries for computed columns
  private final List<Expression> residual; // WHERE conjuncts evaluated in memory
  @Nullable private final List<Expression> groupBy;
  @Nullable private final Expression having;
  private final boolean aggregated;
  private final List<Windows.Spec> windows; // computed before the select list
  private final boolean distinct;
  List<OrderByElement> orderBy; // in-memory ordering
  int limit; // in-memory limit; negative for none
  private boolean unsatisfied; // the keyed UPDATE/DELETE matched no row
  private String keyedWord = "UPDATE"; // the tag of a keyed write that planned no mutation
  @Nullable List<String> labels; // the names clients see; outputNames when null
  int offset;
  // A set operation: its two member plans and how they combine
  private List<Plan> members = Collections.emptyList();
  @Nullable private int[] fixedOids; // a recursive query's working table: the anchor's types
  @Nullable private Operators.SetOperation.Kind setKind;
  private boolean setAll;
  final Map<Select, Plan> subplans;
  @Nullable private final Catalog catalog;
  @Nullable private String tag;
  @Nullable Ddl ddl;
  @Nullable WriteByScan write;
  private List<Object> parameters; // the placeholder values this plan runs with
  private List<Map<String, Object>> returned = Collections.emptyList(); // RETURNING rows
  @Nullable private Plan analyze; // EXPLAIN ANALYZE: the statement to run and report on
  private long started; // nanoTime when an EXPLAIN ANALYZE started
  @Nullable private Reader reader; // set while executing
  // The column maps of the tables the statement reads and writes, namespace.table -> map
  private Map<String, ColumnMap> columnMaps = Collections.emptyMap();
  private final boolean streaming; // top-level: the first table is read as rows are pulled
  @Nullable private final LogicalPlan logical; // what this plan was made from; null for DML
  @Nullable private Map<String, DataType> columnTypes; // built on first use from logical
  // the type each CASE, COALESCE, ... widens to, worked out once (Evaluator.widened); a plan is
  // one session's, cached by its QueryProcessor, so a plain map
  private final Map<Expression, Integer> widenings = new java.util.IdentityHashMap<>();
  @Nullable private Operator root; // the operator tree; null for DML

  /** A DML plan whose mutations {@code builder} makes from the bound parameters. */
  static Plan deferred(java.util.function.Function<Evaluator.Context, List<Mutation>> builder) {
    Plan plan =
        new Plan(
            Collections.<String>emptyList(),
            Collections.<Expression>emptyList(),
            Collections.<DataType>emptyList());
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

  /** Whether running the plan writes rows: its own writes, or its data-modifying WITH queries. */
  boolean writes() {
    return write != null || mutations != null || mutationBuilder != null || !prelude.isEmpty();
  }

  /** True if this plan can run again with other parameter values, see {@link #rebind}. */
  public boolean isCacheable() {
    return cacheable;
  }

  /** Whether the plan embeds nothing that depends on the values it was planned with. */
  boolean reusable() {
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
   * previous execution's operator tree and mutations. Any cursor of the previous execution must be
   * closed first.
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
   * Drops what one execution left behind (the operator tree, the reader, cached subquery rows), so
   * that a plan kept in the session's cache does not hold them until it runs again. The mirror of
   * {@link #rebind}; the next execution rebuilds what it needs.
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

  /** {@code left kind right}: the output columns are the left member's. */
  static Plan setOf(Operators.SetOperation.Kind kind, boolean all, Plan left, Plan right) {
    Plan plan = new Plan(left.outputNames, left.outputExpressions, left.outputTypes);
    plan.members = Arrays.asList(left, right);
    plan.setKind = kind;
    plan.setAll = all;
    plan.labels = left.labels;
    plan.correlated = left.correlated || right.correlated;
    return plan;
  }

  /**
   * The columns of a set operation must agree in type: a text column against an integer one is
   * PostgreSQL's 42804, and an untyped literal ({@code '7'}, NULL) takes the type of the others, so
   * {@code SELECT '7' UNION SELECT 7} is one row and the result advertises one type.
   */
  static void unifyColumnTypes(List<Plan> terms) {
    int columns = terms.get(0).outputNames.size();
    for (int i = 0; i < columns; i++) {
      int target = 0;
      String targetCategory = null;
      for (Plan term : terms) {
        if (i >= term.outputExpressions.size() || isUntypedLiteral(term.outputExpressions.get(i))) {
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
        String.format(Locale.ROOT, "Execution Time: %.3f ms", (System.nanoTime() - started) / 1e6));
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
   * Runs a read-then-write statement: reads the rows, then issues one mutation per row in the same
   * transaction. Nothing is written when more than {@code cap} rows match; a cap of 0 means no
   * limit.
   *
   * @return the number of rows written
   */
  public <E extends TransactionException> int executeWrite(CrudOperable<E> crud, int cap) throws E {
    if (write == null) {
      throw new IllegalStateException("Not a read-then-write statement");
    }
    started = System.nanoTime();
    List<Map<String, Object>> driving = new ArrayList<>();
    List<Mutation> mutations = new ArrayList<>();
    List<Map<String, Object>> left = new ArrayList<>();
    int written = 0; // rows, as the command tag counts them
    try {
      // The driving rows are read fully first so the mutator's own reads see no open scanner
      try (Cursor rows = write.read.open(crud)) {
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
      Reader reader = reader(crud, columnMaps);
      for (Map<String, Object> row : driving) {
        Effect effect = write.mutator.apply(row, reader);
        if (effect != null) {
          mutations.addAll(effect.mutations);
          left.add(effect.row);
          written++;
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
        crud.mutate(ColumnMap.checked(mutations, columnMaps));
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
    return written;
  }

  /** Reads through {@code crud}, wrapping its failures in {@link ReadFailure}. */
  private static <E extends TransactionException> Reader reader(
      CrudOperable<E> crud, Map<String, ColumnMap> columnMaps) {
    return new Reader() {
      @Nullable @Override
      public ColumnMap columnMap(Operation operation) {
        return ColumnMap.forOperation(operation, columnMaps);
      }

      /** The operation, checked against its table's columns that are never written. */
      private <T extends Operation> T stored(T operation) {
        ColumnMap map = columnMap(operation);
        return map == null ? operation : map.checked(operation);
      }

      private final java.util.concurrent.atomic.AtomicLong reads =
          new java.util.concurrent.atomic.AtomicLong();

      @Override
      public long reads() {
        return reads.get();
      }

      @Override
      public List<Result> read(Operation operation) {
        reads.incrementAndGet();
        try {
          if (operation instanceof Get) {
            Optional<Result> result = crud.get(stored((Get) operation));
            return result.isPresent()
                ? Collections.singletonList(result.get())
                : Collections.<Result>emptyList();
          }
          return crud.scan(stored((Scan) operation));
        } catch (TransactionException e) {
          throw new ReadFailure(e);
        }
      }

      @Override
      public List<List<Result>> readAll(List<Operation> operations) {
        reads.addAndGet(operations.size());
        List<Operation> checked = new ArrayList<>();
        for (Operation operation : operations) {
          checked.add(stored(operation));
        }
        List<CrudOperable.BatchResult> batch;
        try {
          batch = crud.batch(checked);
        } catch (TransactionException e) {
          throw new ReadFailure(e);
        }
        List<List<Result>> out = new ArrayList<>();
        for (CrudOperable.BatchResult result : batch) {
          out.add(
              result.getType() == CrudOperable.BatchResult.Type.GET
                  ? result
                      .getGetResult()
                      .map(Collections::singletonList)
                      .orElse(Collections.emptyList())
                  : result.getScanResult());
        }
        return out;
      }

      @Override
      public Cursor open(Operation operation, String qualifier, List<String> columns) {
        ColumnMap map = columnMap(operation);
        if (operation instanceof Get) {
          return listCursor(rowsOf(qualifier, columns, map, read(operation)));
        }
        CrudOperable.Scanner<E> scanner;
        reads.incrementAndGet();
        try {
          scanner = crud.getScanner(stored((Scan) operation));
        } catch (TransactionException e) {
          throw new ReadFailure(e);
        }
        return new Cursor() {
          private boolean closed;

          @Override
          @Nullable public Map<String, Object> next() {
            try {
              Optional<Result> result = scanner.one();
              return result.isPresent() ? rowOf(qualifier, columns, map, result.get()) : null;
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

  /** This plan, with its output columns typed {@code oids} (0 where unknown). */
  Plan withOids(int[] oids) {
    fixedOids = oids;
    return this;
  }

  /** The working table of a recursive CTE: whatever rows {@code rows} holds when opened. */
  static Plan working(String name, List<String> names, List<Map<String, Object>> rows, int[] oids) {
    Plan plan = constant(names, rows, "SELECT");
    plan.fixedOids = oids;
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
    Plan plan = new Plan(names, none, types);
    plan.tag = tag;
    plan.labels = labels;
    plan.root = new Operators.Values(rows, "Values");
    plan.rootFixed = true;
    return plan;
  }

  /** A plan with only output columns, for set operations and constant results. */
  private Plan(
      List<String> outputNames, List<Expression> outputExpressions, List<DataType> outputTypes) {
    this(
        null,
        Collections.emptyList(),
        Collections.emptyList(),
        outputNames,
        outputExpressions,
        outputTypes,
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

  Plan(
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
    if (fixedOids != null) {
      return fixedOids.clone();
    }
    int[] oids = new int[outputNames.size()];
    Returning returning = write == null ? null : write.returning;
    for (int i = 0; i < oids.length; i++) {
      if (returning != null) {
        oids[i] = i < returning.oids.size() ? returning.oids.get(i) : 0; // int4, not text
      } else if (i < outputTypes.size() && outputTypes.get(i) != null) {
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

  /** The type modifier of each output column (numeric(10,2), ...); -1 where there is none. */
  public int[] outputTypmods() {
    int[] typmods = new int[outputNames.size()];
    java.util.Arrays.fill(typmods, -1);
    Returning returning = write == null ? null : write.returning;
    for (int i = 0; i < typmods.length; i++) {
      if (returning != null) {
        typmods[i] = i < returning.typmods.size() ? returning.typmods.get(i) : -1;
      } else if (logical != null && i < logical.outputTypmods.size()) {
        typmods[i] = logical.outputTypmods.get(i);
      } else if (!members.isEmpty()) {
        // a set operation keeps a modifier only when every branch has the same one
        Integer common = null;
        for (Plan member : members) {
          int[] inner = member.outputTypmods();
          int typmod = i < inner.length ? inner[i] : -1;
          common = common == null || common == typmod ? typmod : -1;
        }
        typmods[i] = common == null ? -1 : common;
      }
    }
    return typmods;
  }

  /** Names the keyed UPDATE or DELETE this plan is, for when it plans no mutation. */
  Plan keyed(String word) {
    keyedWord = word;
    return this;
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
    if (mutations.isEmpty()) {
      return keyedWord + " 0";
    }
    Mutation first = mutations.get(0);
    if (first instanceof Insert || first instanceof Upsert) {
      return "INSERT 0 " + mutations.size();
    }
    return (first instanceof Update ? "UPDATE " : "DELETE ") + (unsatisfied ? 0 : mutations.size());
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
    if (analyze != null) {
      started = System.nanoTime();
      try (Cursor rows = analyze.open(crud)) {
        while (rows.next() != null) {
          // only the operators' counters are of interest
        }
      }
      return report();
    }
    if (write != null) {
      throw new IllegalStateException("A read-then-write statement runs with executeWrite");
    }
    if (mutations() != null && mutations().isEmpty()) {
      unsatisfied = false;
      return Cursor.EMPTY; // a keyed write that can match no row: UPDATE 0
    }
    if (mutations() != null) {
      unsatisfied = false;
      boolean inserts = mutations().stream().allMatch(m -> m instanceof Insert);
      try {
        crud.mutate(ColumnMap.checked(mutations(), columnMaps));
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
    Reader crudReader = reader(crud, columnMaps);
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

  /**
   * Gives the statement's column maps to this plan and to the plans that open a reader of their own
   * (the read of a read-then-write statement, the statement EXPLAIN ANALYZE runs); every other plan
   * inside reads through the reader {@link #attach} hands it.
   */
  void useColumnMaps(Map<String, ColumnMap> maps) {
    columnMaps = maps;
    if (write != null) {
      write.read.useColumnMaps(maps);
    }
    if (analyze != null) {
      analyze.useColumnMaps(maps);
    }
    for (Prelude p : prelude) {
      p.write.useColumnMaps(
          maps); // numeric(p,s) is stored scaled: the writes read it through these
    }
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
    return new Evaluator.Context(
        outer,
        subplans,
        catalog,
        parameters,
        columnTypes(),
        logical == null ? Collections.<LogicalPlan.Source>emptyList() : logical.sources,
        widenings);
  }

  /** The type of every {@code alias.column} the plan reads, which a NULL value cannot tell. */
  private Map<String, DataType> columnTypes() {
    if (columnTypes == null) {
      Map<String, DataType> types = new java.util.HashMap<>();
      if (logical != null) {
        for (LogicalPlan.Source s : logical.sources) {
          if (s.target != null) {
            for (String name : s.target.metadata.getColumnNames()) {
              if (!s.target.columns.type(name).isNumeric()) { // char is text, smallint int
                types.put(s.qualifier + "." + name, s.target.metadata.getColumnDataType(name));
              }
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
              setKind, setAll, members.get(0).root(), members.get(1).root(), outputNames, members);
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
      return new Operators.Values(s.loaded, s.catalogTable + " AS " + s.qualifier + " (in memory)");
    }
    return new Operators.Scan(s.scan(), s.qualifier, s.columns, stream);
  }

  /** Reads a lookup source for one outer row, binding its lookup columns to the row's values. */
  List<Map<String, Object>> lookupRows(Source s, Map<String, Object> left, Evaluator.Context ctx) {
    Operation operation = lookupOperation(s, left, ctx);
    return operation == null
        ? new ArrayList<Map<String, Object>>()
        : reader().rows(operation, s.qualifier, s.columns);
  }

  /**
   * The lookups of several outer rows, as candidate rows in the same order. Rows whose Gets share a
   * partition key and name full clustering keys are read with one Scan over that partition, an OR
   * of their keys, so N lookups cost one read instead of N round trips; the other Gets go to
   * ScalarDB as one batch (one round trip through Cluster), and the remaining Scans one by one.
   */
  List<List<Map<String, Object>>> lookupRows(
      Source s, List<Map<String, Object>> rows, Evaluator.Context ctx) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>>[] results = new List[rows.size()];
    List<Operation> operations = new ArrayList<>();
    Map<Key, List<Integer>> groups = new LinkedHashMap<>();
    List<Integer> gets = new ArrayList<>(); // read in one batch
    for (int i = 0; i < rows.size(); i++) {
      Operation operation = lookupOperation(s, rows.get(i), ctx);
      operations.add(operation);
      if (operation == null) {
        results[i] = new ArrayList<>();
      } else if (operation instanceof Get && ((Get) operation).getClusteringKey().isPresent()) {
        groups.computeIfAbsent(((Get) operation).getPartitionKey(), k -> new ArrayList<>()).add(i);
      } else if (operation instanceof Get) {
        gets.add(i);
      } else {
        results[i] = reader().rows(operation, s.qualifier, s.columns);
      }
    }
    for (List<Integer> group : groups.values()) {
      if (group.size() == 1 || !sameConditions(group, operations)) {
        gets.addAll(group);
        continue;
      }
      try {
        lookupGroup(s, group, operations, results);
      } catch (IllegalArgumentException e) {
        if (!String.valueOf(e.getMessage()).contains("DB-CORE-10106")) {
          throw e;
        }
        // The transaction wrote one of these records: ScalarDB refuses to scan it but serves it
        // to a Get, so this group goes back to one Get per row
        gets.addAll(group);
      }
    }
    if (gets.size() == 1) {
      int i = gets.get(0);
      results[i] = reader().rows(operations.get(i), s.qualifier, s.columns);
    } else if (!gets.isEmpty()) {
      List<Operation> batch = new ArrayList<>();
      for (int i : gets) {
        batch.add(operations.get(i));
      }
      List<List<Result>> read = reader().readAll(batch);
      for (int k = 0; k < gets.size(); k++) {
        results[gets.get(k)] =
            rowsOf(s.qualifier, s.columns, reader().columnMap(batch.get(k)), read.get(k));
      }
    }
    return Arrays.asList(results);
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
            ConditionBuilder.buildConditionalExpression(column, ConditionalExpression.Operator.EQ));
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
    // The Gets' keys hold stored values, so the results are matched by their stored key values
    ColumnMap map = reader().columnMap(scan);
    Map<List<Object>, List<Result>> byKey = new HashMap<>();
    for (Result result : reader().read(scan)) {
      List<Object> key = new ArrayList<>();
      for (String name : keyNames) {
        key.add(
            Operators.normalize(
                Objects.requireNonNull(result.getColumns().get(name), name).getValueAsObject()));
      }
      byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
    }
    for (int i : group) {
      List<Object> key = new ArrayList<>();
      for (Column<?> column : ((Get) operations.get(i)).getClusteringKey().get().getColumns()) {
        key.add(Operators.normalize(column.getValueAsObject()));
      }
      results[i] =
          rowsOf(
              s.qualifier,
              s.columns,
              map,
              byKey.getOrDefault(key, Collections.<Result>emptyList()));
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
              s.lookup().table(), p.getKey(), p.getValue(), Collections.singletonList(left), ctx);
      if (value == null) {
        return null; // NULL never equals anything
      }
      Column<?> column;
      try {
        column = columnFromValue(s.lookup().table(), p.getKey(), value);
      } catch (IllegalArgumentException e) {
        if (isOutOfRange(e) || e instanceof PgException.NotRepresentable) {
          return null; // a key never equals a value beyond its range or with no exact stored form
        }
        throw e;
      }
      conditions.add(
          ConditionBuilder.buildConditionalExpression(column, ConditionalExpression.Operator.EQ));
    }
    for (Expression late : s.lookup().pushedLate) {
      ConditionalExpression condition;
      try {
        condition = pushable(late, s.lookup().table(), left, ctx);
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
  @Nullable static Boolean beyondRange(
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

  /** What one row of a read-driven write does: its mutation and the row it leaves behind. */
  static final class Effect {
    final List<Mutation> mutations; // one, or a Delete and an Insert for a row whose key changes
    final Map<String, Object> row; // qualifier.column -> value after the write (before, for DELETE)

    Effect(Mutation mutation, Map<String, Object> row) {
      this(Collections.singletonList(mutation), row);
    }

    Effect(List<Mutation> mutations, Map<String, Object> row) {
      this.mutations = mutations;
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
    final List<Integer> oids = new ArrayList<>(); // 0 for computed items
    final List<Integer> typmods = new ArrayList<>(); // -1 for computed items

    /** Records a plain column of {@code t}, as typed as a SELECT of it would be. */
    void column(Target t, String name) {
      ColumnType declared = t.columns.type(name);
      boolean exact = declared.encoded(); // varchar(n), not text; numeric(10,2), not bigint
      oids.add(columnOid(t, name));
      typmods.add(exact ? declared.typmod() : -1);
    }
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
  interface Ddl {
    void run() throws ExecutionException;
  }

  /** A pull-based row stream (Volcano style): {@link #next()} returns null once exhausted. */
  interface Cursor extends AutoCloseable {
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
    /** Runs a Get or Scan; the results carry stored values, see {@link ColumnMap#value}. */
    List<Result> read(Operation operation);

    /**
     * Runs Gets and Scans as one ScalarDB batch; each one's results, in order, as {@link #read}.
     */
    List<List<Result>> readAll(List<Operation> operations);

    /** The column map of the table an operation names; null when none is known. */
    @Nullable ColumnMap columnMap(Operation operation);

    /** Runs a Get or Scan and returns its rows keyed {@code qualifier.column}, decoded. */
    default List<Map<String, Object>> rows(
        Operation operation, String qualifier, List<String> columns) {
      return rowsOf(qualifier, columns, columnMap(operation), read(operation));
    }

    /**
     * Opens a Get or Scan as a row stream with keys qualified as {@code qualifier.column}; every
     * one of {@code columns} is present, null when the storage did not return it.
     */
    Cursor open(Operation operation, String qualifier, List<String> columns);

    /** ScalarDB reads issued so far: one per Get, Scan or opened scanner. */
    long reads();
  }

  /** A ScalarDB read failed while rows were being pulled; the cause is the original exception. */
  static final class ReadFailure extends RuntimeException {
    ReadFailure(TransactionException cause) {
      super(cause);
    }

    public TransactionException cause() {
      return (TransactionException) Objects.requireNonNull(getCause());
    }
  }

  /** An INSERT hit an existing primary key: SQLSTATE 23505. */
  static final class UniqueViolationException extends RuntimeException {
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
}
