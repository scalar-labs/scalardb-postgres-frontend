package com.scalar.db.frontend.postgres;

import com.scalar.db.api.AndConditionSet;
import com.scalar.db.api.ConditionBuilder;
import com.scalar.db.api.ConditionSetBuilder;
import com.scalar.db.api.ConditionalExpression;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DeleteBuilder;
import com.scalar.db.api.DeleteIf;
import com.scalar.db.api.Get;
import com.scalar.db.api.GetBuilder;
import com.scalar.db.api.GetWithIndex;
import com.scalar.db.api.Insert;
import com.scalar.db.api.InsertBuilder;
import com.scalar.db.api.LikeExpression;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.MutationCondition;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Put;
import com.scalar.db.api.PutBuilder;
import com.scalar.db.api.PutIf;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.ScanAll;
import com.scalar.db.api.ScanBuilder;
import com.scalar.db.api.ScanWithIndex;
import com.scalar.db.api.Selection;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.api.UpdateBuilder;
import com.scalar.db.api.UpdateIf;
import com.scalar.db.api.Upsert;
import com.scalar.db.api.UpsertBuilder;
import com.scalar.db.io.Column;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * One table's columns as the client sees them and as ScalarDB stores them. A column whose declared
 * type is recorded in its name ({@code amount__pg_n10_2}, see {@link ColumnType}) is {@code amount}
 * to the client: the planner, the evaluator and the catalog work with {@link #clientView()} only,
 * and an operation is rewritten to storage names by {@link #toStorage} where it is executed. A
 * table without such columns is passed through untouched.
 *
 * <p>A column the frontend cannot honour is shown under its full ScalarDB name and never written: a
 * suffix it does not know, or two columns whose client names would be the same (written by an older
 * frontend or another client), so that neither silently wins.
 */
final class ColumnMap {
  final String namespace;
  final String table;
  private final TableMetadata clientView;
  private final Map<String, String> storageByClient = new HashMap<>();
  private final Map<String, String> clientByStorage = new HashMap<>();
  private final Map<String, ColumnType> typeByStorage = new HashMap<>();
  private final Set<String> readOnly = new HashSet<>(); // client names never written
  private final boolean renames; // some client name differs from its storage name

  /**
   * The kinds whose names and values this frontend translates in both directions. A column of any
   * other kind (a code this build does not know, or one whose value rules it does not apply yet) is
   * shown under its ScalarDB name with its stored value, and never written, so that a value is
   * never read one way and written another.
   */
  // ponytail: grows with the plan's phases (numeric scaled, numeric text, char and smallint)
  private static final Set<ColumnType.Kind> TRANSLATED =
      EnumSet.of(ColumnType.Kind.NATIVE, ColumnType.Kind.VARCHAR);

  private ColumnMap(String namespace, String table, TableMetadata stored) {
    this.namespace = namespace;
    this.table = table;
    Map<String, String> clients = new HashMap<>();
    for (String storage : stored.getColumnNames()) {
      ColumnType type = ColumnType.parse(storage, stored.getColumnDataType(storage));
      if (TRANSLATED.contains(type.kind)) {
        typeByStorage.put(storage, type);
        clients.put(storage, type.clientName(storage));
      } else {
        typeByStorage.put(storage, ColumnType.of(stored.getColumnDataType(storage)));
        clients.put(storage, storage);
        readOnly.add(storage);
      }
    }
    // Two columns that would both be called the same (compared as MySQL and SQL Server compare
    // column names) keep their full names, and neither is written
    Map<String, List<String>> byFolded = new HashMap<>();
    for (String storage : stored.getColumnNames()) {
      byFolded
          .computeIfAbsent(
              clients.getOrDefault(storage, storage).toLowerCase(Locale.ROOT),
              k -> new ArrayList<>())
          .add(storage);
    }
    for (List<String> same : byFolded.values()) {
      boolean decoded = same.stream().anyMatch(s -> !clients.getOrDefault(s, s).equals(s));
      if (same.size() > 1 && decoded) {
        for (String storage : same) {
          clients.put(storage, storage);
          typeByStorage.put(storage, ColumnType.of(stored.getColumnDataType(storage)));
          readOnly.add(storage);
        }
      }
    }
    boolean anyRename = false;
    TableMetadata.Builder view = TableMetadata.newBuilder();
    for (String storage : stored.getColumnNames()) {
      String client = clients.getOrDefault(storage, storage);
      storageByClient.put(client, storage);
      clientByStorage.put(storage, client);
      anyRename |= !client.equals(storage);
      view.addColumn(
          client,
          stored.getColumnDataType(storage),
          stored.getEncryptedColumnNames().contains(storage));
    }
    for (String key : stored.getPartitionKeyNames()) {
      view.addPartitionKey(clients.getOrDefault(key, key));
    }
    for (String key : stored.getClusteringKeyNames()) {
      view.addClusteringKey(clients.getOrDefault(key, key), stored.getClusteringOrder(key));
    }
    for (String index : stored.getSecondaryIndexNames()) {
      view.addSecondaryIndex(clients.getOrDefault(index, index));
    }
    this.clientView = anyRename ? view.build() : stored;
    this.renames = anyRename;
  }

  /** The map of a table, from the metadata ScalarDB returns for it. */
  static ColumnMap of(String namespace, String table, TableMetadata stored) {
    return new ColumnMap(namespace, table, stored);
  }

  /** The table as the client sees it: client names, the ScalarDB types they are stored as. */
  TableMetadata clientView() {
    return clientView;
  }

  /** True when some column is stored under another name than the client's. */
  boolean renames() {
    return renames;
  }

  /** The ScalarDB name of a client column; 42703 for a name the client cannot see. */
  String storageName(String client) {
    String storage = storageByClient.get(client);
    if (storage == null) {
      throw new PgException.Argument(
          "42703", "column \"" + client + "\" of relation \"" + table + "\" does not exist");
    }
    return storage;
  }

  /** The client's name of a ScalarDB column; the name itself for a column not in the table. */
  String clientName(String storage) {
    return clientByStorage.getOrDefault(storage, storage);
  }

  /** The declared type of a client column. */
  ColumnType type(String client) {
    ColumnType type = typeByStorage.get(storageName(client));
    if (type == null) {
      throw new IllegalStateException("No type for " + client);
    }
    return type;
  }

  /** Refuses a write to a column this frontend shows but cannot honour. */
  void checkWritable(String client) {
    if (readOnly.contains(client)) {
      throw new PgException.Argument(
          "0A000",
          "column \""
              + client
              + "\" of relation \""
              + table
              + "\" is shown under its ScalarDB name and cannot be written: its type encoding is"
              + " unknown to this frontend, or another column has the same name once decoded");
    }
  }

  /**
   * The value of a stored column of a result, as the evaluator sees it: decoded when the column's
   * type is recorded in its name, as ScalarDB returns it otherwise.
   */
  @Nullable Object value(String storage, Result result, Map<String, Column<?>> columns) {
    ColumnType type = typeByStorage.get(storage);
    if (type == null || type.kind == ColumnType.Kind.NATIVE) {
      return result.getAsObject(storage);
    }
    Column<?> column = columns.get(storage);
    return column == null ? null : type.decode(column, table);
  }

  // ---- operations ----

  /**
   * An operation with client names rewritten to storage names: keys, values, projections, orderings
   * and conditions. Untouched when no column is renamed; a write to a column that is shown but
   * never written fails with 0A000.
   */
  <T extends Operation> T toStorage(T operation) {
    if (!renames && readOnly.isEmpty()) {
      return operation;
    }
    if (operation instanceof Mutation && !(operation instanceof Delete)) {
      checkWrites((Mutation) operation);
    }
    if (!renames) {
      return operation;
    }
    Operation out;
    if (operation instanceof Get) {
      out = get((Get) operation);
    } else if (operation instanceof Scan) {
      out = scan((Scan) operation);
    } else if (operation instanceof Put) {
      out = put((Put) operation);
    } else if (operation instanceof Insert) {
      out = insert((Insert) operation);
    } else if (operation instanceof Upsert) {
      out = upsert((Upsert) operation);
    } else if (operation instanceof Update) {
      out = update((Update) operation);
    } else if (operation instanceof Delete) {
      out = delete((Delete) operation);
    } else {
      throw new IllegalArgumentException("Unsupported operation: " + operation);
    }
    @SuppressWarnings("unchecked")
    T rewritten = (T) out;
    return rewritten;
  }

  /** Each mutation of a list rewritten, looked up by its table in {@code maps}. */
  static List<Mutation> toStorage(List<Mutation> mutations, Map<String, ColumnMap> maps) {
    if (maps.isEmpty()) {
      return mutations;
    }
    List<Mutation> out = new ArrayList<>(mutations.size());
    for (Mutation m : mutations) {
      ColumnMap map = forOperation(m, maps);
      out.add(map == null ? m : map.toStorage(m));
    }
    return out;
  }

  /** The map of the table an operation names, from {@code namespace.table}; null if not mapped. */
  @Nullable static ColumnMap forOperation(Operation operation, Map<String, ColumnMap> maps) {
    if (maps.isEmpty()
        || !operation.forNamespace().isPresent()
        || !operation.forTable().isPresent()) {
      return null;
    }
    return maps.get(operation.forNamespace().get() + "." + operation.forTable().get());
  }

  private void checkWrites(Mutation mutation) {
    for (Column<?> c : mutation.getPartitionKey().getColumns()) {
      checkWritable(c.getName());
    }
    if (mutation.getClusteringKey().isPresent()) {
      for (Column<?> c : mutation.getClusteringKey().get().getColumns()) {
        checkWritable(c.getName());
      }
    }
    for (String name : values(mutation)) {
      checkWritable(name);
    }
  }

  private static Collection<String> values(Mutation mutation) {
    if (mutation instanceof Put) {
      return ((Put) mutation).getColumns().keySet();
    }
    if (mutation instanceof Insert) {
      return ((Insert) mutation).getColumns().keySet();
    }
    if (mutation instanceof Upsert) {
      return ((Upsert) mutation).getColumns().keySet();
    }
    if (mutation instanceof Update) {
      return ((Update) mutation).getColumns().keySet();
    }
    return new ArrayList<>();
  }

  private Get get(Get get) {
    GetBuilder.BuildableGetOrGetWithIndexFromExisting b = Get.newBuilder(get);
    if (get instanceof GetWithIndex) {
      b.indexKey(key(get.getPartitionKey()));
    } else {
      b.partitionKey(key(get.getPartitionKey()));
      if (get.getClusteringKey().isPresent()) {
        b.clusteringKey(key(get.getClusteringKey().get()));
      }
    }
    if (!get.getProjections().isEmpty()) {
      b.clearProjections().projections(names(get.getProjections()));
    }
    if (get.getConjunctions().isEmpty()) {
      return b.build();
    }
    return b.clearConditions().whereOr(conditions(get.getConjunctions())).build();
  }

  private Scan scan(Scan scan) {
    ScanBuilder.BuildableScanOrScanAllFromExisting b = Scan.newBuilder(scan);
    if (scan instanceof ScanWithIndex) {
      b.indexKey(key(scan.getPartitionKey()));
    } else if (!(scan instanceof ScanAll)) {
      b.partitionKey(key(scan.getPartitionKey()));
      Optional<Key> start = scan.getStartClusteringKey();
      if (start.isPresent()) {
        b.start(key(start.get()), scan.getStartInclusive());
      }
      Optional<Key> end = scan.getEndClusteringKey();
      if (end.isPresent()) {
        b.end(key(end.get()), scan.getEndInclusive());
      }
    }
    if (!scan.getOrderings().isEmpty()) {
      List<Scan.Ordering> orderings = new ArrayList<>();
      for (Scan.Ordering o : scan.getOrderings()) {
        orderings.add(new Scan.Ordering(storageName(o.getColumnName()), o.getOrder()));
      }
      b.clearOrderings().orderings(orderings);
    }
    if (!scan.getProjections().isEmpty()) {
      b.clearProjections().projections(names(scan.getProjections()));
    }
    if (scan.getConjunctions().isEmpty()) {
      return b.build();
    }
    return b.clearConditions().whereOr(conditions(scan.getConjunctions())).build();
  }

  private Put put(Put put) {
    PutBuilder.BuildableFromExisting b =
        Put.newBuilder(put).partitionKey(key(put.getPartitionKey())).clearValues();
    if (put.getClusteringKey().isPresent()) {
      b.clusteringKey(key(put.getClusteringKey().get()));
    }
    for (Column<?> c : put.getColumns().values()) {
      b.value(written(c));
    }
    if (put.getCondition().isPresent()) {
      b.condition(condition(put.getCondition().get()));
    }
    return b.build();
  }

  private Insert insert(Insert insert) {
    InsertBuilder.BuildableFromExisting b =
        Insert.newBuilder(insert).partitionKey(key(insert.getPartitionKey())).clearValues();
    if (insert.getClusteringKey().isPresent()) {
      b.clusteringKey(key(insert.getClusteringKey().get()));
    }
    for (Column<?> c : insert.getColumns().values()) {
      b.value(written(c));
    }
    return b.build();
  }

  private Upsert upsert(Upsert upsert) {
    UpsertBuilder.BuildableFromExisting b =
        Upsert.newBuilder(upsert).partitionKey(key(upsert.getPartitionKey())).clearValues();
    if (upsert.getClusteringKey().isPresent()) {
      b.clusteringKey(key(upsert.getClusteringKey().get()));
    }
    for (Column<?> c : upsert.getColumns().values()) {
      b.value(written(c));
    }
    return b.build();
  }

  private Update update(Update update) {
    UpdateBuilder.BuildableFromExisting b =
        Update.newBuilder(update).partitionKey(key(update.getPartitionKey())).clearValues();
    if (update.getClusteringKey().isPresent()) {
      b.clusteringKey(key(update.getClusteringKey().get()));
    }
    for (Column<?> c : update.getColumns().values()) {
      b.value(written(c));
    }
    if (update.getCondition().isPresent()) {
      b.condition(condition(update.getCondition().get()));
    }
    return b.build();
  }

  private Delete delete(Delete delete) {
    DeleteBuilder.BuildableFromExisting b =
        Delete.newBuilder(delete).partitionKey(key(delete.getPartitionKey()));
    if (delete.getClusteringKey().isPresent()) {
      b.clusteringKey(key(delete.getClusteringKey().get()));
    }
    if (delete.getCondition().isPresent()) {
      b.condition(condition(delete.getCondition().get()));
    }
    return b.build();
  }

  private Key key(Key key) {
    Key.Builder b = Key.newBuilder();
    for (Column<?> c : key.getColumns()) {
      b.add(column(c));
    }
    return b.build();
  }

  private Column<?> column(Column<?> c) {
    return c.copyWith(storageName(c.getName()));
  }

  /** A value being written, renamed and coerced by its column's write rules. */
  private Column<?> written(Column<?> c) {
    String storage = storageName(c.getName());
    ColumnType type = typeByStorage.get(storage);
    if (type == null || type.kind == ColumnType.Kind.NATIVE) {
      return c.copyWith(storage);
    }
    return type.exact(storage, type.coerce(c.getValueAsObject()));
  }

  private List<String> names(List<String> clients) {
    List<String> out = new ArrayList<>(clients.size());
    for (String c : clients) {
      out.add(storageName(c));
    }
    return out;
  }

  private Set<AndConditionSet> conditions(Set<Selection.Conjunction> conjunctions) {
    Set<AndConditionSet> out = new LinkedHashSet<>();
    for (Selection.Conjunction conjunction : conjunctions) {
      Set<ConditionalExpression> renamed = new LinkedHashSet<>();
      for (ConditionalExpression e : conjunction.getConditions()) {
        renamed.add(condition(e));
      }
      out.add(ConditionSetBuilder.andConditionSet(renamed).build());
    }
    return out;
  }

  private ConditionalExpression condition(ConditionalExpression e) {
    Column<?> c = column(e.getColumn());
    if (e instanceof LikeExpression) {
      return ConditionBuilder.buildLikeExpression(
          (TextColumn) c, e.getOperator(), ((LikeExpression) e).getEscape());
    }
    return ConditionBuilder.buildConditionalExpression(c, e.getOperator());
  }

  private MutationCondition condition(MutationCondition condition) {
    List<ConditionalExpression> renamed = new ArrayList<>();
    for (ConditionalExpression e : condition.getExpressions()) {
      renamed.add(condition(e));
    }
    if (condition instanceof PutIf) {
      return ConditionBuilder.putIf(renamed);
    }
    if (condition instanceof DeleteIf) {
      return ConditionBuilder.deleteIf(renamed);
    }
    if (condition instanceof UpdateIf) {
      return ConditionBuilder.updateIf(renamed);
    }
    return condition; // PutIfExists, PutIfNotExists, DeleteIfExists, UpdateIfExists: no columns
  }
}
