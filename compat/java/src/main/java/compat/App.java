package compat;

import compat.jdbc.JdbcModel;
import compat.jpa.JpaModel;
import compat.mybatis.WidgetMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A typical application session through Spring Data JDBC, Spring Data JPA (Hibernate) and MyBatis
 * against a frontend on port 15444, database `orm`. Each step prints `ok` or `FAIL`.
 */
@SpringBootApplication
@EnableJdbcRepositories(basePackages = "compat.jdbc", considerNestedRepositories = true)
@EnableJpaRepositories(basePackages = "compat.jpa", considerNestedRepositories = true)
@EntityScan("compat.jpa")
public class App {
  public static void main(String[] args) {
    System.exit(SpringApplication.exit(SpringApplication.run(App.class, args)));
  }

  static void step(String name, Callable<?> body) {
    try {
      String r = String.valueOf(body.call());
      System.out.println("ok   " + name + " " + r.substring(0, Math.min(200, r.length())));
    } catch (Throwable e) {
      Throwable root = e;
      while (root.getCause() != null && root.getCause() != root) root = root.getCause();
      String m = String.valueOf(root.getMessage()).lines().findFirst().orElse("");
      System.out.println("FAIL " + name + " " + root.getClass().getSimpleName() + ": " + m.substring(0, Math.min(200, m.length())));
    }
  }

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Bean
  CommandLineRunner run(
      DataSource ds,
      JdbcTemplate sql,
      TransactionTemplate tx,
      JdbcAggregateTemplate agg,
      JdbcModel.Widgets jdbc,
      JpaModel.Widgets jpa,
      JpaModel.UuidRows uuids,
      JpaModel.IdentityRows identities,
      JpaModel.SeqRows seqs,
      WidgetMapper mb) {
    return args -> {
      step("pgjdbc metadata", () -> {
        try (Connection c = ds.getConnection()) {
          DatabaseMetaData md = c.getMetaData();
          List<String> t = new ArrayList<>();
          try (ResultSet rs = md.getTables(null, null, "jpa_%", new String[] {"TABLE"})) {
            while (rs.next()) t.add(rs.getString("TABLE_NAME"));
          }
          return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion() + " " + t;
        }
      });

      // Spring Data JDBC
      step("jdbc create table", () -> {
        sql.execute("DROP TABLE IF EXISTS jdbc_widget");
        sql.execute("CREATE TABLE jdbc_widget (id bigint PRIMARY KEY, name varchar(100), price numeric(10,2), qty int, active boolean, created_at timestamptz)");
        return "done";
      });
      Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
      step("jdbc insert (assigned id)", () -> {
        agg.insert(new JdbcModel.Widget(1L, "a", d("1.50"), 2, true, t0));
        agg.insert(new JdbcModel.Widget(2L, "b", d("2.50"), 3, false, t0));
        return jdbc.count();
      });
      step("jdbc save (generated id)", () -> jdbc.save(new JdbcModel.Widget(null, "gen", d("1"), 1, true, t0)));
      step("jdbc findById", () -> jdbc.findById(1L));
      step("jdbc findAllById (IN)", () -> jdbc.findAllById(List.of(1L, 2L)));
      step("jdbc derived query", () -> jdbc.findByNameOrderById("a"));
      step("jdbc @Query", () -> jdbc.pricierThan(d("2")));
      step("jdbc save (update)", () -> {
        jdbc.save(new JdbcModel.Widget(1L, "a", d("9.50"), 2, true, t0));
        return jdbc.findById(1L).orElseThrow().price();
      });
      step("jdbc paging", () -> jdbc.findAll(PageRequest.of(0, 1, Sort.by("id"))));
      step("jdbc transaction: insert, then derived query", () -> tx.execute(s -> {
        agg.insert(new JdbcModel.Widget(3L, "c", d("3"), 1, true, t0));
        return jdbc.findByNameOrderById("c");
      }));
      step("jdbc transaction: insert, then findById", () -> tx.execute(s -> {
        agg.insert(new JdbcModel.Widget(4L, "d", d("4"), 1, true, t0));
        return jdbc.findById(4L);
      }));
      step("jdbc rollback", () -> {
        tx.executeWithoutResult(s -> {
          agg.insert(new JdbcModel.Widget(5L, "e", d("5"), 1, true, t0));
          s.setRollbackOnly();
        });
        return jdbc.existsById(5L);
      });
      step("jdbc delete", () -> {
        jdbc.deleteById(2L);
        return jdbc.count();
      });

      // Spring Data JPA (Hibernate); ddl-auto=update created the tables at startup
      step("jpa tables created", () -> sql.queryForList(
          "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'jpa%' ORDER BY 1", String.class));
      step("jpa clear", () -> {
        jpa.deleteAllInBatch();
        return jpa.count();
      });
      step("jpa save (assigned id)", () -> {
        jpa.save(new JpaModel.Widget(1L, "a", "1.50"));
        jpa.save(new JpaModel.Widget(2L, "b", "2.50"));
        return jpa.count();
      });
      step("jpa findById", () -> jpa.findById(1L));
      step("jpa findAllById (IN)", () -> jpa.findAllById(List.of(1L, 2L)));
      step("jpa derived query", () -> jpa.findByNameOrderById("a"));
      step("jpa JPQL", () -> jpa.pricierThan(d("2")));
      step("jpa native query", () -> jpa.countActiveNative());
      step("jpa update (dirty checking)", () -> {
        tx.executeWithoutResult(s -> jpa.findById(1L).orElseThrow().price = d("9.50"));
        return jpa.findById(1L).orElseThrow();
      });
      step("jpa paging", () -> jpa.findAll(PageRequest.of(0, 1, Sort.by("id"))).getContent());
      step("jpa transaction: save, then JPQL (auto flush)", () -> tx.execute(s -> {
        jpa.save(new JpaModel.Widget(3L, "c", "3"));
        return jpa.findByNameOrderById("c");
      }));
      step("jpa transaction: save, then findById", () -> tx.execute(s -> {
        jpa.save(new JpaModel.Widget(4L, "d", "4"));
        return jpa.findById(4L);
      }));
      step("jpa delete", () -> {
        jpa.deleteById(2L);
        return jpa.count();
      });
      step("jpa @GeneratedValue UUID", () -> {
        JpaModel.UuidRow r = new JpaModel.UuidRow();
        r.name = "u";
        return uuids.findById(uuids.save(r).id).map(x -> x.name);
      });
      step("jpa @GeneratedValue IDENTITY", () -> {
        JpaModel.IdentityRow r = new JpaModel.IdentityRow();
        r.name = "i";
        return identities.save(r).id;
      });
      step("jpa @GeneratedValue SEQUENCE", () -> {
        JpaModel.SeqRow r = new JpaModel.SeqRow();
        r.name = "s";
        return seqs.save(r).id;
      });

      // MyBatis
      step("mybatis create table", () -> {
        sql.execute("DROP TABLE IF EXISTS mb_widget");
        sql.execute("CREATE TABLE mb_widget (id bigint PRIMARY KEY, name varchar(100), price numeric(10,2))");
        return "done";
      });
      step("mybatis insert", () -> mb.insert(1, "a", d("1.50")) + mb.insert(2, "b", d("2.50")));
      step("mybatis insert, useGeneratedKeys", () -> {
        WidgetMapper.Row r = new WidgetMapper.Row();
        r.id = 6L;
        r.name = "f";
        r.price = d("6");
        mb.insertReturningKey(r);
        return r.id;
      });
      step("mybatis select", () -> mb.find(1));
      step("mybatis foreach IN", () -> mb.findIn(List.of(1L, 2L)));
      step("mybatis group by", () -> mb.totals());
      step("mybatis update", () -> mb.updatePrice(1, d("9.50")) + " " + mb.find(1));
      step("mybatis transaction: insert, then select by name", () -> tx.execute(s -> {
        mb.insert(3, "c", d("3"));
        return mb.findByName("c");
      }));
      step("mybatis transaction: insert, then select by id", () -> tx.execute(s -> {
        mb.insert(4, "d", d("4"));
        return mb.find(4);
      }));
      step("mybatis delete", () -> mb.delete(2) + " " + mb.findIn(List.of(1L, 2L, 3L, 4L, 6L)));
    };
  }
}
