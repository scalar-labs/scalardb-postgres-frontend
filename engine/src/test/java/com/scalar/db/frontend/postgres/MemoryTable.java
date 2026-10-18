package com.scalar.db.frontend.postgres;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Mutation;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.ScanAll;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.api.Upsert;
import com.scalar.db.common.ResultImpl;
import com.scalar.db.io.Column;
import com.scalar.db.io.Key;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One table held in memory behind a mocked transaction: get and scan answer by key, mutate applies
 * the writes, and every operation is recorded in {@link #executed}.
 *
 * <p>ponytail: scans ignore conditions, ranges and orderings, and writes ignore their conditions;
 * ScalarDB's own semantics are checked by difftest against a real backend
 */
final class MemoryTable {
  final List<Result> rows = new ArrayList<>();
  final List<Operation> executed = new ArrayList<>();
  private final TableMetadata metadata;

  MemoryTable(TableMetadata metadata, DistributedTransaction transaction) throws Exception {
    this.metadata = metadata;
    when(transaction.get(any()))
        .thenAnswer(
            invocation -> {
              Get get = invocation.getArgument(0);
              executed.add(get);
              return rows.stream().filter(r -> matches(r, get)).findFirst();
            });
    when(transaction.scan(any()))
        .thenAnswer(
            invocation -> {
              Scan scan = invocation.getArgument(0);
              executed.add(scan);
              return rows.stream()
                  .filter(r -> scan instanceof ScanAll || matches(r, scan))
                  .collect(Collectors.toList());
            });
    TestScanners.stub(transaction);
    doAnswer(
            invocation -> {
              List<Mutation> mutations = invocation.getArgument(0);
              executed.addAll(mutations);
              mutations.forEach(this::apply);
              return null;
            })
        .when(transaction)
        .mutate(anyList());
  }

  private static boolean matches(Result r, Operation operation) {
    return keyMatches(r, operation.getPartitionKey())
        && operation.getClusteringKey().map(k -> keyMatches(r, k)).orElse(true);
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

  private void apply(Mutation mutation) {
    int at = 0;
    while (at < rows.size() && !matches(rows.get(at), mutation)) {
      at++;
    }
    boolean exists = at < rows.size();
    if (mutation instanceof Delete) {
      if (exists) {
        rows.remove(at);
      }
      return;
    }
    if (mutation instanceof Update && !exists) {
      return;
    }
    Map<String, Column<?>> columns = new LinkedHashMap<>();
    for (String name : metadata.getColumnNames()) {
      columns.put(
          name,
          exists
              ? rows.get(at).getColumns().get(name)
              : QueryParser.parseColumn(metadata.getColumnDataType(name), name, null));
    }
    mutation.getPartitionKey().getColumns().forEach(c -> columns.put(c.getName(), c));
    mutation
        .getClusteringKey()
        .ifPresent(k -> k.getColumns().forEach(c -> columns.put(c.getName(), c)));
    columns.putAll(
        mutation instanceof Put
            ? ((Put) mutation).getColumns()
            : mutation instanceof Insert
                ? ((Insert) mutation).getColumns()
                : mutation instanceof Upsert
                    ? ((Upsert) mutation).getColumns()
                    : ((Update) mutation).getColumns());
    Result row = new ResultImpl(columns, metadata);
    if (exists) {
      rows.set(at, row);
    } else {
      rows.add(row);
    }
  }
}
