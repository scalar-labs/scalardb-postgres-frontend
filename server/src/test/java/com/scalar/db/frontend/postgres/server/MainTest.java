package com.scalar.db.frontend.postgres.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;

public class MainTest {
  private static Properties properties(String... pairs) {
    Properties properties = new Properties();
    for (int i = 0; i < pairs.length; i += 2) {
      properties.setProperty(pairs[i], pairs[i + 1]);
    }
    return properties;
  }

  @Test
  public void collationProblem_ShouldAcceptOnlyBytewiseCollations() {
    assertThat(Main.collationProblem(true, "C")).isNull();
    assertThat(Main.collationProblem(true, "C.UTF-8")).isNull();
    assertThat(Main.collationProblem(true, "ja_JP.UTF-8")).contains("ja_JP.UTF-8");
    assertThat(Main.collationProblem(false, "utf8mb4_0900_bin")).isNull();
    assertThat(Main.collationProblem(false, "utf8mb4_bin")).isNotNull(); // pads spaces
    assertThat(Main.collationProblem(false, "utf8mb4_0900_ai_ci")).isNotNull();
    // nothing to check against Cluster, or another storage
    Main.checkBackendCollation(properties("scalar.db.transaction_manager", "cluster"));
  }

  @Test
  public void checkedAuth_ShouldRequireClusterTlsAndNoServiceUser() {
    assertThat(Main.checkedAuth(properties())).isEqualTo("none");
    assertThat(
            Main.checkedAuth(
                properties(
                    "scalar.db.postgres.auth", "userpass",
                    "scalar.db.transaction_manager", "cluster",
                    "scalar.db.postgres.ssl.cert", "server.crt",
                    "scalar.db.postgres.ssl.key", "server.key")))
        .isEqualTo("userpass");
    assertThat(
            Main.checkedAuth(
                properties(
                    "scalar.db.postgres.auth", "OIDC_JWT",
                    "scalar.db.transaction_manager", "cluster",
                    "scalar.db.postgres.ssl.require", "false")))
        .isEqualTo("oidc_jwt");
    assertThatThrownBy(() -> Main.checkedAuth(properties("scalar.db.postgres.auth", "ldap")))
        .hasMessageContaining("must be none, userpass or oidc_jwt");
    assertThatThrownBy(() -> Main.checkedAuth(properties("scalar.db.postgres.auth", "auto")))
        .hasMessageContaining("one auth type per client");
    assertThatThrownBy(
            () ->
                Main.checkedAuth(
                    properties(
                        "scalar.db.postgres.auth", "userpass",
                        "scalar.db.transaction_manager", "consensus-commit")))
        .hasMessageContaining("needs scalar.db.transaction_manager=cluster");
    assertThatThrownBy(
            () ->
                Main.checkedAuth(
                    properties(
                        "scalar.db.postgres.auth", "userpass",
                        "scalar.db.transaction_manager", "cluster",
                        "scalar.db.username", "service",
                        "scalar.db.postgres.ssl.require", "false")))
        .hasMessageContaining("leave scalar.db.username and scalar.db.password unset");
    assertThatThrownBy(
            () ->
                Main.checkedAuth(
                    properties(
                        "scalar.db.postgres.auth", "userpass",
                        "scalar.db.transaction_manager", "cluster")))
        .hasMessageContaining("ssl.cert");
  }

  @Test
  public void checkedAuth_ShouldSetTheSdkAuthTypeToMatch() {
    Properties oidc =
        properties(
            "scalar.db.postgres.auth", "oidc_jwt",
            "scalar.db.transaction_manager", "cluster",
            "scalar.db.postgres.ssl.require", "false");
    Main.checkedAuth(oidc);
    assertThat(oidc.getProperty("scalar.db.cluster.client.auth.type")).isEqualTo("oidc_jwt");
    assertThat(oidc.getProperty("scalar.db.cluster.auth.enabled")).isEqualTo("true");
    Properties none = properties();
    Main.checkedAuth(none);
    assertThat(none.stringPropertyNames()).isEmpty();
    assertThatThrownBy(
            () ->
                Main.checkedAuth(
                    properties(
                        "scalar.db.postgres.auth", "oidc_jwt",
                        "scalar.db.transaction_manager", "cluster",
                        "scalar.db.postgres.ssl.require", "false",
                        "scalar.db.cluster.client.auth.type", "userpass")))
        .hasMessageContaining("needs scalar.db.cluster.client.auth.type=oidc_jwt, not userpass");
    assertThatThrownBy(
            () ->
                Main.checkedAuth(
                    properties(
                        "scalar.db.postgres.auth", "userpass",
                        "scalar.db.transaction_manager", "cluster",
                        "scalar.db.postgres.ssl.require", "false",
                        "scalar.db.cluster.auth.enabled", "false")))
        .hasMessageContaining("needs scalar.db.cluster.auth.enabled=true");
  }

  @Test
  public void resolvePlaceholders_ShouldResolveEveryValueAndDropEmptyOnes() {
    Properties properties =
        properties(
            "scalar.db.postgres.ssl.require", "${sys:frontend.test.require:-false}",
            "scalar.db.username", "${sys:frontend.test.unset:-}",
            "scalar.db.storage", "jdbc");
    Main.resolvePlaceholders(properties);
    assertThat(properties.getProperty("scalar.db.postgres.ssl.require")).isEqualTo("false");
    assertThat(properties.containsKey("scalar.db.username")).isFalse();
    assertThat(properties.getProperty("scalar.db.storage")).isEqualTo("jdbc");
  }
}
