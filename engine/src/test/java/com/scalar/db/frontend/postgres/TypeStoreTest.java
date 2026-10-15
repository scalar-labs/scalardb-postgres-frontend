package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.api.AuthAdmin;
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
}
