package com.scalar.db.frontend.postgres;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Upsert;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.Column;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A {@link TypeStore} over an in-memory {@code column_types}, for tests: the real store's code on a
 * table that applies each write at once (a transaction's commit and rollback do nothing).
 */
final class MemoryTypeStore {
  /** The rows by {@code full_table_name/column_name}, in key order. */
  final Map<String, Map<String, Column<?>>> rows = new TreeMap<>();

  final TypeStore store;

  /** When set, every transaction on the table fails to begin, as an unreachable store would. */
  volatile boolean failing;

  /** Makes the table exist for {@code admin}, which must be a Mockito mock. */
  MemoryTypeStore(DistributedTransactionAdmin admin) throws Exception {
    when(admin.tableExists(TypeStore.NAMESPACE, TypeStore.TABLE)).thenReturn(true);
    DistributedTransactionManager manager = mock(DistributedTransactionManager.class);
    when(manager.begin(anyMap()))
        .thenAnswer(
            i -> {
              if (failing) {
                throw new com.scalar.db.exception.transaction.TransactionException(
                    "store unreachable", null);
              }
              return transaction();
            });
    when(manager.scan(any())).thenAnswer(i -> scan(i.getArgument(0)));
    store = new TypeStore(manager, admin, new Properties());
  }

  /** Records a declared type, as a DDL would. */
  void seed(String namespace, String table, String column, ColumnType type) throws Exception {
    seed(namespace, table, column, type, "seed");
  }

  /** Records a declared type as the DDL {@code token}, such as another session's. */
  void seed(String namespace, String table, String column, ColumnType type, String token)
      throws Exception {
    store.write(
        namespace,
        table,
        Collections.singletonMap(column, type),
        Collections.singleton(column),
        token);
  }

  /** Records a row as another frontend might have written it. */
  void seedRaw(
      String namespace, String table, String column, String sqlType, String encoding, String type) {
    Map<String, Column<?>> row = new LinkedHashMap<>();
    row.put("full_table_name", TextColumn.of("full_table_name", namespace + "." + table));
    row.put("column_name", TextColumn.of("column_name", column));
    row.put("sql_type", TextColumn.of("sql_type", sqlType));
    row.put("type_precision", IntColumn.ofNull("type_precision"));
    row.put("type_scale", IntColumn.ofNull("type_scale"));
    row.put("type_length", IntColumn.ofNull("type_length"));
    row.put("encoding", TextColumn.of("encoding", encoding));
    row.put("data_type", TextColumn.of("data_type", type));
    row.put("created_by", TextColumn.of("created_by", "other"));
    rows.put(namespace + "." + table + "/" + column, row);
  }

  /** The rows as {@code ns.table/column SQL_TYPE p s n ENCODING DATA_TYPE}, nulls as {@code -}. */
  List<String> describe() {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, Map<String, Column<?>>> e : rows.entrySet()) {
      StringBuilder b = new StringBuilder(e.getKey());
      for (String c :
          new String[] {
            "sql_type", "type_precision", "type_scale", "type_length", "encoding", "data_type"
          }) {
        Column<?> v = e.getValue().get(c);
        b.append(' ').append(v == null || v.hasNullValue() ? "-" : v.getValueAsObject());
      }
      out.add(b.toString());
    }
    return out;
  }

  private DistributedTransaction transaction() throws Exception {
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(tx.scan(any())).thenAnswer(i -> scan(i.getArgument(0)));
    doAnswer(
            i -> {
              Upsert upsert = i.getArgument(0);
              Map<String, Column<?>> row = new LinkedHashMap<>();
              for (Column<?> c : upsert.getPartitionKey().getColumns()) {
                row.put(c.getName(), c);
              }
              for (Column<?> c : upsert.getClusteringKey().get().getColumns()) {
                row.put(c.getName(), c);
              }
              row.putAll(upsert.getColumns());
              rows.put(key(upsert.getPartitionKey(), upsert.getClusteringKey().get()), row);
              return null;
            })
        .when(tx)
        .upsert(any());
    doAnswer(
            i -> {
              Delete delete = i.getArgument(0);
              rows.remove(key(delete.getPartitionKey(), delete.getClusteringKey().get()));
              return null;
            })
        .when(tx)
        .delete(any(Delete.class));
    return tx;
  }

  private List<Result> scan(Scan scan) {
    String full = scan.getPartitionKey().getColumns().get(0).getTextValue();
    List<Result> out = new ArrayList<>();
    for (Map.Entry<String, Map<String, Column<?>>> e : rows.entrySet()) {
      if (e.getKey().startsWith(full + "/")) {
        out.add(new ResultImpl(e.getValue(), TypeStore.METADATA));
      }
    }
    return out;
  }

  private static String key(Key partition, Key clustering) {
    return partition.getColumns().get(0).getTextValue()
        + "/"
        + clustering.getColumns().get(0).getTextValue();
  }
}
