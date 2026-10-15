package com.scalar.db.frontend.postgres;

import com.scalar.db.api.Delete;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.api.Upsert;
import com.scalar.db.io.Column;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * One table's columns with their declared types: the ScalarDB type, or the type recorded for the
 * column in {@link TypeStore} ({@code amount numeric(10,2)} stored as a BIGINT of cents). The
 * planner, the evaluator and the catalog read a column's type here, and stored values are decoded
 * here. A table without recorded types is passed through untouched.
 *
 * <p>A column whose recorded type this frontend cannot read is shown with its ScalarDB type and
 * stored value, and never written, so that a value is never read one way and written another.
 */
final class ColumnMap {
  final String namespace;
  final String table;
  private final TableMetadata metadata;
  private final Map<String, ColumnType> types = new HashMap<>();
  private final Set<String> readOnly = new HashSet<>(); // recorded types this frontend cannot read
  private final boolean decodes; // some column's stored value differs from the client's

  private ColumnMap(
      String namespace, String table, TableMetadata metadata, Map<String, ColumnType> recorded) {
    this.namespace = namespace;
    this.table = table;
    this.metadata = metadata;
    boolean decodes = false;
    for (String column : metadata.getColumnNames()) {
      ColumnType type = recorded.get(column);
      if (type == null) {
        type = ColumnType.of(metadata.getColumnDataType(column));
      } else if (type.kind == ColumnType.Kind.UNKNOWN_ENCODING) {
        readOnly.add(column);
        type = ColumnType.of(metadata.getColumnDataType(column));
      } else {
        decodes = true;
      }
      types.put(column, type);
    }
    this.decodes = decodes;
  }

  /** The map of a table, from its ScalarDB metadata and the types recorded for its columns. */
  static ColumnMap of(
      String namespace, String table, TableMetadata metadata, Map<String, ColumnType> recorded) {
    return new ColumnMap(namespace, table, metadata, recorded);
  }

  /** The table's ScalarDB metadata: the columns clients see, with the types they are stored as. */
  TableMetadata metadata() {
    return metadata;
  }

  /** True when some column's stored value is decoded for clients. */
  boolean decodes() {
    return decodes;
  }

  /** The declared type of a column. */
  ColumnType type(String column) {
    ColumnType type = types.get(column);
    if (type == null) {
      throw new PgException.Argument(
          "42703", "column \"" + column + "\" of relation \"" + table + "\" does not exist");
    }
    return type;
  }

  /** Refuses a write to a column this frontend shows but cannot honour. */
  void checkWritable(String column) {
    if (readOnly.contains(column)) {
      throw new PgException.Argument(
          "0A000",
          "column \""
              + column
              + "\" of relation \""
              + table
              + "\" cannot be written: the type recorded for it is unknown to this frontend (a newer"
              + " frontend may have created it)");
    }
  }

  /** The value of a stored column of a result, as the evaluator sees it: decoded when recorded. */
  @Nullable Object value(String column, Result result, Map<String, Column<?>> columns) {
    ColumnType type = types.get(column);
    if (type == null || type.kind == ColumnType.Kind.NATIVE) {
      return result.getAsObject(column);
    }
    Column<?> stored = columns.get(column);
    return stored == null ? null : type.decode(stored, table);
  }

  // ---- operations ----

  /** The operation, once checked: a write to a column that is shown but never written is 0A000. */
  <T extends Operation> T checked(T operation) {
    if (!readOnly.isEmpty() && operation instanceof Mutation && !(operation instanceof Delete)) {
      Mutation mutation = (Mutation) operation;
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
    return operation;
  }

  /** Each mutation of a list checked, looked up by its table in {@code maps}. */
  static List<Mutation> checked(List<Mutation> mutations, Map<String, ColumnMap> maps) {
    for (Mutation m : mutations) {
      ColumnMap map = forOperation(m, maps);
      if (map != null) {
        map.checked(m);
      }
    }
    return mutations;
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
}
