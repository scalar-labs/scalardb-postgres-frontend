package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.scalar.db.api.AuthAdmin;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The metadata table's setup command, and whose credentials its rows are read with. */
public class TypeStoreTest {
  private static Properties auth(String... more) {
    Properties p = new Properties();
    p.setProperty("scalar.db.postgres.auth", "oidc_jwt");
    for (int i = 0; i < more.length; i += 2) {
      p.setProperty(more[i], more[i + 1]);
    }
    return p;
  }

  private static final TableMetadata T =
      TableMetadata.newBuilder().addColumn("id", DataType.INT).addPartitionKey("id").build();

  @Test
  public void create_WithoutAuth_ShouldCreateTheTableOnly() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    TypeStore store =
        new TypeStore(mock(DistributedTransactionManager.class), admin, new Properties());
    assertThat(store.create()).isEmpty();
    verify(admin).createNamespace(TypeStore.NAMESPACE, true);
    verify(admin).createTable(TypeStore.NAMESPACE, TypeStore.TABLE, TypeStore.METADATA, true);
    verify(admin, never()).createUser(anyString(), any());
    assertThat(store.exists()).isTrue();
  }

  @Test
  public void create_WithAuth_ShouldMakeTheServiceUserOnceAndGrantTheTableOnly() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    TypeStore store = new TypeStore(mock(DistributedTransactionManager.class), admin, auth());
    assertThat(store.needsServiceUser()).isTrue();
    when(admin.getUser(TypeStore.DEFAULT_USER)).thenReturn(Optional.empty());
    List<Map<String, Object>> rows = store.create();
    org.mockito.ArgumentCaptor<String> password = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(admin)
        .createUser(org.mockito.ArgumentMatchers.eq(TypeStore.DEFAULT_USER), password.capture());
    assertThat(password.getValue()).hasSize(43); // 32 random bytes
    verify(admin)
        .grant(
            TypeStore.DEFAULT_USER,
            TypeStore.NAMESPACE,
            TypeStore.TABLE,
            AuthAdmin.Privilege.READ,
            AuthAdmin.Privilege.WRITE,
            AuthAdmin.Privilege.DELETE);
    assertThat(rows)
        .extracting(r -> r.get("value"))
        .containsExactly(TypeStore.DEFAULT_USER, password.getValue());
    // run again: the user exists, so its password is left alone
    when(admin.getUser(TypeStore.DEFAULT_USER)).thenReturn(Optional.of(mock(AuthAdmin.User.class)));
    assertThat(store.create().get(1).get("value").toString()).startsWith("(unchanged");
    verify(admin).createUser(anyString(), any());
  }

  @Test
  public void create_WithAFrontendIdentity_ShouldCreateTheTableOnly() throws Exception {
    // a Cluster node: the rows are written under its own authority, so no user, password or
    // property
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    TypeStore store =
        new TypeStore(mock(DistributedTransactionManager.class), admin, auth(), new Frontend());
    assertThat(store.needsServiceUser()).isFalse();
    assertThat(store.create()).isEmpty();
    verify(admin).createNamespace(TypeStore.NAMESPACE, true);
    verify(admin).createTable(TypeStore.NAMESPACE, TypeStore.TABLE, TypeStore.METADATA, true);
    verifyNoMoreInteractions(admin);
    assertThat(store.exists()).isTrue();
  }

  @Test
  public void read_ShouldUseTheServiceUsersCredentials() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    when(admin.tableExists(TypeStore.NAMESPACE, TypeStore.TABLE)).thenReturn(true);
    DistributedTransactionManager manager = mock(DistributedTransactionManager.class);
    when(manager.scan(any())).thenReturn(Collections.emptyList());
    new TypeStore(
            manager,
            admin,
            auth(
                "scalar.db.postgres.metadata_user",
                "meta",
                "scalar.db.postgres.metadata_password",
                "pw"))
        .read("ns", "t", T);
    org.mockito.ArgumentCaptor<Scan> scan = org.mockito.ArgumentCaptor.forClass(Scan.class);
    verify(manager).scan(scan.capture());
    assertThat(scan.getValue().getAttributes())
        .containsEntry("auth-type", "userpass")
        .containsEntry("auth-userpass-username", "meta")
        .containsEntry("auth-userpass-password", "pw");
  }

  @Test
  public void read_WithAuthAndNoPassword_ShouldFailWith55000OnceTheTableExists() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    TypeStore store = new TypeStore(mock(DistributedTransactionManager.class), admin, auth());
    assertThat(store.read("ns", "t", T)).isEmpty(); // no table yet: nothing to read
    when(admin.tableExists(TypeStore.NAMESPACE, TypeStore.TABLE)).thenReturn(true);
    assertThatThrownBy(() -> store.read("ns", "t", T))
        .hasMessageContaining("scalar.db.postgres.metadata_password");
  }

  /** An identity that records whether a call ran inside it, as a Cluster node's would. */
  private static final class Frontend implements Authenticator.Identity {
    int inside;
    int calls;

    @Override
    public String user() {
      return "frontend";
    }

    @Override
    public boolean superuser() {
      return false;
    }

    @Override
    public <R, T extends Throwable> R run(com.scalar.db.util.ThrowableSupplier<R, T> call)
        throws T {
      calls++;
      inside++;
      try {
        return call.get();
      } finally {
        inside--;
      }
    }

    @Override
    public void close() {}
  }

  @Test
  public void withAFrontendIdentity_ShouldReadAndWriteAsItWithNoCredentials() throws Exception {
    DistributedTransactionAdmin admin = mock(DistributedTransactionAdmin.class);
    when(admin.tableExists(TypeStore.NAMESPACE, TypeStore.TABLE)).thenReturn(true);
    DistributedTransactionManager manager = mock(DistributedTransactionManager.class);
    Frontend frontend = new Frontend();
    when(manager.scan(any()))
        .thenAnswer(
            i -> {
              assertThat(frontend.inside).isEqualTo(1);
              return Collections.emptyList();
            });
    // the properties name a service user, which a host with its own identity does not use
    TypeStore store =
        new TypeStore(manager, admin, auth("scalar.db.postgres.metadata_password", "pw"), frontend);
    store.read("ns", "t", T);
    org.mockito.ArgumentCaptor<Scan> scan = org.mockito.ArgumentCaptor.forClass(Scan.class);
    verify(manager).scan(scan.capture());
    assertThat(scan.getValue().getAttributes()).isEmpty();
    assertThat(frontend.calls).isEqualTo(1);
    // writes too: begun inside the identity, with no credentials
    DistributedTransaction tx = mock(DistributedTransaction.class);
    when(tx.scan(any())).thenReturn(Collections.emptyList());
    when(manager.begin(org.mockito.ArgumentMatchers.<Map<String, String>>any()))
        .thenAnswer(
            i -> {
              assertThat(frontend.inside).isEqualTo(1);
              assertThat((Map<?, ?>) i.getArgument(0)).isEmpty();
              return tx;
            });
    store.write(
        "ns",
        "t",
        Collections.singletonMap(
            "n", ColumnType.declared("numeric(10,2)", ColumnType.MAX_SCALED_PRECISION)),
        null,
        "token");
    verify(manager).begin(org.mockito.ArgumentMatchers.<Map<String, String>>any());
    verify(tx).commit();
    assertThat(frontend.calls).isEqualTo(2);
    // and the setup command creates no service user for it
    assertThat(store.needsServiceUser()).isFalse();
  }
}
