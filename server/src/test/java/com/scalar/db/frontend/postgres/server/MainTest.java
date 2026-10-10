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
