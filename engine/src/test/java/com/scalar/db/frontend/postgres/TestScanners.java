package com.scalar.db.frontend.postgres;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.scalar.db.api.CrudOperable;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Operation;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TransactionCrudOperable;
import com.scalar.db.api.TransactionManagerCrudOperable;
import com.scalar.db.common.BatchResultImpl;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Makes a mocked {@code getScanner} serve whatever the mock's {@code scan} is stubbed to return.
 */
final class TestScanners {
  private TestScanners() {}

  static void stub(DistributedTransaction transaction) throws Exception {
    when(transaction.getScanner(any()))
        .thenAnswer(
            invocation -> {
              Scan scan = invocation.getArgument(0);
              return new TransactionScanner(transaction.scan(scan).iterator());
            });
    // a batch answers with the mock's own get and scan stubs, as ScalarDB's default batch does
    when(transaction.batch(any()))
        .thenAnswer(
            invocation -> {
              List<CrudOperable.BatchResult> results = new ArrayList<>();
              for (Operation operation : invocation.<List<Operation>>getArgument(0)) {
                results.add(
                    operation instanceof Get
                        ? new BatchResultImpl(transaction.get((Get) operation))
                        : new BatchResultImpl(transaction.scan((Scan) operation)));
              }
              return results;
            });
  }

  static void stub(DistributedTransactionManager manager) throws Exception {
    when(manager.getScanner(any()))
        .thenAnswer(
            invocation -> {
              Scan scan = invocation.getArgument(0);
              return new ManagerScanner(manager.scan(scan).iterator());
            });
  }

  private static List<Result> remaining(Iterator<Result> results) {
    List<Result> out = new ArrayList<>();
    results.forEachRemaining(out::add);
    return out;
  }

  private static final class TransactionScanner implements TransactionCrudOperable.Scanner {
    private final Iterator<Result> results;

    TransactionScanner(Iterator<Result> results) {
      this.results = results;
    }

    @Override
    public Optional<Result> one() {
      return results.hasNext() ? Optional.of(results.next()) : Optional.empty();
    }

    @Override
    public List<Result> all() {
      return remaining(results);
    }

    @Override
    public void close() {}

    @Override
    public Iterator<Result> iterator() {
      return results;
    }
  }

  private static final class ManagerScanner implements TransactionManagerCrudOperable.Scanner {
    private final Iterator<Result> results;

    ManagerScanner(Iterator<Result> results) {
      this.results = results;
    }

    @Override
    public Optional<Result> one() {
      return results.hasNext() ? Optional.of(results.next()) : Optional.empty();
    }

    @Override
    public List<Result> all() {
      return remaining(results);
    }

    @Override
    public void close() {}

    @Override
    public Iterator<Result> iterator() {
      return results;
    }
  }
}
