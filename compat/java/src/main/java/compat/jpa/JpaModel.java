package compat.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public class JpaModel {
  @Entity(name = "JpaWidget")
  @Table(name = "jpa_widget")
  public static class Widget {
    @Id public Long id;
    public String name;
    public BigDecimal price;
    public Integer qty;
    public Boolean active;
    public LocalDateTime createdAt;

    public Widget() {}

    public Widget(Long id, String name, String price) {
      this.id = id;
      this.name = name;
      this.price = new BigDecimal(price);
      this.qty = 1;
      this.active = true;
      this.createdAt = LocalDateTime.of(2024, 1, 1, 0, 0);
    }

    @Override
    public String toString() {
      return id + ":" + name + ":" + price;
    }
  }

  // The application generates the id, so no database support is needed
  @Entity
  @Table(name = "jpa_uuid")
  public static class UuidRow {
    @Id @GeneratedValue(strategy = GenerationType.UUID) public UUID id;
    public String name;
  }

  // The database generates the id: an identity column
  @Entity
  @Table(name = "jpa_identity")
  public static class IdentityRow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
  }

  // The database generates the id: a sequence (Hibernate's default for @GeneratedValue)
  @Entity
  @Table(name = "jpa_seq")
  public static class SeqRow {
    @Id @GeneratedValue public Long id;
    public String name;
  }

  public interface Widgets extends JpaRepository<Widget, Long> {
    List<Widget> findByNameOrderById(String name);

    @Query("SELECT w FROM JpaWidget w WHERE w.price > :price ORDER BY w.id")
    List<Widget> pricierThan(BigDecimal price);

    @Query(value = "SELECT count(*) FROM jpa_widget WHERE active", nativeQuery = true)
    long countActiveNative();
  }

  public interface UuidRows extends JpaRepository<UuidRow, UUID> {}

  public interface IdentityRows extends JpaRepository<IdentityRow, Long> {}

  public interface SeqRows extends JpaRepository<SeqRow, Long> {}
}
