package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TransactionCrudOperable;
import com.scalar.db.exception.transaction.CrudConflictException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TimingTest {
  @Test
  void wrap_ShouldCountEveryCallOnWhatTheManagerHandsOut() throws Exception {
    DistributedTransactionManager manager = mock(DistributedTransactionManager.class);
    DistributedTransaction transaction = mock(DistributedTransaction.class);
    TransactionCrudOperable.Scanner scanner = mock(TransactionCrudOperable.Scanner.class);
    when(manager.beginReadOnly()).thenReturn(transaction);
    when(transaction.getScanner(any(Scan.class))).thenReturn(scanner);
    when(scanner.one()).thenReturn(Optional.empty());
    CrudConflictException conflict = new CrudConflictException("conflict", null, "tx");
    when(transaction.get(any(Get.class))).thenThrow(conflict);
    long calls = Timing.calls.sum();
    long nanos = Timing.scalardb.sum();

    DistributedTransaction wrapped =
        Timing.wrap(DistributedTransactionManager.class, manager).beginReadOnly();
    TransactionCrudOperable.Scanner rows = wrapped.getScanner(mock(Scan.class));
    assertThat(rows.one()).isEmpty();
    // ScalarDB's own exception comes through, not a reflection wrapper
    assertThatThrownBy(() -> wrapped.get(mock(Get.class))).isSameAs(conflict);
    wrapped.commit();

    assertThat(Proxy.isProxyClass(wrapped.getClass())).isTrue();
    assertThat(Proxy.isProxyClass(rows.getClass())).isTrue();
    assertThat(Timing.calls.sum() - calls).isEqualTo(5); // begin, getScanner, one, get, commit
    assertThat(Timing.scalardb.sum()).isGreaterThan(nanos);
    verify(transaction).commit();
  }
}
