package compat.mybatis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WidgetMapper {
  class Row {
    public Long id;
    public String name;
    public BigDecimal price;

    @Override
    public String toString() {
      return id + ":" + name + ":" + price;
    }
  }

  @Insert("INSERT INTO mb_widget (id, name, price) VALUES (#{id}, #{name}, #{price})")
  int insert(@Param("id") long id, @Param("name") String name, @Param("price") BigDecimal price);

  // pgjdbc appends RETURNING to read the generated keys
  @Insert("INSERT INTO mb_widget (id, name, price) VALUES (#{id}, #{name}, #{price})")
  @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
  int insertReturningKey(Row row);

  @Select("SELECT id, name, price FROM mb_widget WHERE id = #{id}")
  Row find(long id);

  @Select({
    "<script>SELECT id, name, price FROM mb_widget WHERE id IN",
    "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>",
    "ORDER BY id</script>"
  })
  List<Row> findIn(@Param("ids") List<Long> ids);

  @Select("SELECT id, name, price FROM mb_widget WHERE name = #{name} ORDER BY id")
  List<Row> findByName(String name);

  @Select("SELECT name, count(*) AS n, sum(price) AS total FROM mb_widget GROUP BY name ORDER BY name")
  List<Map<String, Object>> totals();

  @Update("UPDATE mb_widget SET price = #{price} WHERE id = #{id}")
  int updatePrice(@Param("id") long id, @Param("price") BigDecimal price);

  @Delete("DELETE FROM mb_widget WHERE id = #{id}")
  int delete(long id);
}
