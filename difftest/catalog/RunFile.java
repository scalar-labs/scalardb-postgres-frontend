import java.nio.file.*;
import java.sql.*;
/** Runs each query of a file split by "\n-----\n"; prints index | label | rows or ERR. */
public class RunFile {
  public static void main(String[] a) throws Exception {
    String sql = Files.readString(Path.of(a[1]));
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + a[0] + "/audit", "postgres", ""); Statement s = c.createStatement()) {
      int i = 0;
      for (String q : sql.split("\n-----\n")) {
        String label = q.lines().filter(l -> l.startsWith("-- [")).reduce((x, y) -> y).orElse("").replaceAll(" \\(.*", "");
        String r;
        try (ResultSet rs = s.executeQuery(q)) { int n = 0; while (rs.next()) n++; r = String.valueOf(n); }
        catch (SQLException e) { r = "ERR " + e.getMessage().split("\n")[0]; }
        System.out.println(i++ + " | " + label + " | " + r);
      }
    }
  }
}
