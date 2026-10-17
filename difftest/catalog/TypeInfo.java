import java.sql.*;
public class TypeInfo {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + a[0] + "/audit", "postgres", "")) {
      try (ResultSet rs = c.getMetaData().getTypeInfo()) { while (rs.next()) System.out.println("type " + rs.getString("TYPE_NAME") + " " + rs.getInt("DATA_TYPE")); }
      try (ResultSet rs = c.getMetaData().getVersionColumns(null, "public", "items")) { while (rs.next()) System.out.println("version " + rs.getString("COLUMN_NAME") + " " + rs.getInt("DATA_TYPE") + " " + rs.getString("TYPE_NAME")); }
      try (ResultSet rs = c.getMetaData().getColumns(null, "public", "items", "%")) { while (rs.next()) System.out.println("col " + rs.getString("COLUMN_NAME") + " " + rs.getInt("DATA_TYPE") + " " + rs.getString("TYPE_NAME") + " " + rs.getInt("COLUMN_SIZE") + "," + rs.getInt("DECIMAL_DIGITS")); }
    }
  }
}
