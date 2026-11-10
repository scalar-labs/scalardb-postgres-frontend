package compat.jdbc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;

public class JdbcModel {
  @Table("jdbc_widget")
  public record Widget(@Id Long id, String name, BigDecimal price, Integer qty, Boolean active, Instant createdAt) {}

  public interface Widgets extends ListCrudRepository<Widget, Long>, ListPagingAndSortingRepository<Widget, Long> {
    List<Widget> findByNameOrderById(String name);

    @Query("SELECT * FROM jdbc_widget WHERE price > :price ORDER BY id")
    List<Widget> pricierThan(BigDecimal price);
  }
}
