package com.scalar.db.frontend.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

public class ToCharTest {
  @Test
  public void toChar_ShouldWriteWhatPostgreSql16Writes() throws Exception {
    // to_char-cases.txt: zone, type, value, template and PostgreSQL 16's answer
    List<String> differences = new ArrayList<>();
    int cases = 0;
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/to_char-cases.txt")),
                StandardCharsets.UTF_8))) {
      for (String line = in.readLine(); line != null; line = in.readLine()) {
        if (line.startsWith("#")) {
          continue;
        }
        String[] c = line.split("\t", -1);
        ZoneId zone = ZoneId.of(c[0]);
        Object value;
        switch (c[1]) {
          case "timestamp":
            value = LocalDateTime.parse(c[2].replace(' ', 'T'));
            break;
          case "timestamptz":
            value = LocalDateTime.parse(c[2].replace(' ', 'T')).atZone(zone).toInstant();
            break;
          case "date":
            value = LocalDate.parse(c[2]);
            break;
          case "int4":
            value = Integer.valueOf(c[2]);
            break;
          case "int8":
            value = Long.valueOf(c[2]);
            break;
          case "numeric":
            value = new BigDecimal(c[2]);
            break;
          case "float8":
            value = Double.valueOf(c[2]);
            break;
          default:
            value = Float.valueOf(c[2]);
        }
        String got;
        try {
          got = ToChar.toChar(value, c[3], zone);
        } catch (PgException.Argument e) {
          got = "ERROR " + e.sqlState();
        }
        cases++;
        if (!c[4].equals(got)) {
          differences.add(line + "\t-> [" + got + "]");
        }
      }
    }
    assertThat(cases).isGreaterThan(4000);
    assertThat(differences).isEmpty();
  }
}
