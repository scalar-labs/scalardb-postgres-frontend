import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Calls every DatabaseMetaData method that reads the catalog; prints name | rows or ERR. */
public class Meta {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + a[0] + "/audit", "postgres", "")) {
      DatabaseMetaData m = c.getMetaData();
      Map<String, Callable<ResultSet>> calls = new LinkedHashMap<>();
      calls.put("getCatalogs", m::getCatalogs);
      calls.put("getSchemas", m::getSchemas);
      calls.put("getSchemas(s%)", () -> m.getSchemas(null, "s%"));
      calls.put("getTableTypes", m::getTableTypes);
      calls.put("getTables(all)", () -> m.getTables(null, null, "%", null));
      calls.put("getTables(TABLE)", () -> m.getTables(null, null, "%", new String[] {"TABLE"}));
      calls.put("getTables(VIEW)", () -> m.getTables(null, null, "%", new String[] {"VIEW"}));
      calls.put("getTables(SYSTEM TABLE)", () -> m.getTables(null, null, "%", new String[] {"SYSTEM TABLE"}));
      calls.put("getColumns(items)", () -> m.getColumns(null, "public", "items", "%"));
      calls.put("getColumns(all)", () -> m.getColumns(null, null, "%", "%"));
      calls.put("getPrimaryKeys", () -> m.getPrimaryKeys(null, "sales", "orders"));
      calls.put("getIndexInfo", () -> m.getIndexInfo(null, "public", "items", false, false));
      calls.put("getImportedKeys", () -> m.getImportedKeys(null, "sales", "orders"));
      calls.put("getExportedKeys", () -> m.getExportedKeys(null, "public", "items"));
      calls.put("getCrossReference", () -> m.getCrossReference(null, "public", "items", null, "sales", "orders"));
      calls.put("getBestRowIdentifier", () -> m.getBestRowIdentifier(null, "public", "items", DatabaseMetaData.bestRowSession, true));
      calls.put("getVersionColumns", () -> m.getVersionColumns(null, "public", "items"));
      calls.put("getTablePrivileges", () -> m.getTablePrivileges(null, null, "%"));
      calls.put("getColumnPrivileges", () -> m.getColumnPrivileges(null, "public", "items", "%"));
      calls.put("getFunctions(all)", () -> m.getFunctions(null, null, "%"));
      calls.put("getFunctions(upper)", () -> m.getFunctions(null, null, "upper"));
      calls.put("getFunctionColumns(upper)", () -> m.getFunctionColumns(null, null, "upper", "%"));
      calls.put("getProcedures(all)", () -> m.getProcedures(null, null, "%"));
      calls.put("getProcedureColumns(all)", () -> m.getProcedureColumns(null, null, "%", "%"));
      calls.put("getTypeInfo", m::getTypeInfo);
      calls.put("getUDTs", () -> m.getUDTs(null, null, "%", null));
      calls.put("getSuperTypes", () -> m.getSuperTypes(null, null, "%"));
      calls.put("getSuperTables", () -> m.getSuperTables(null, null, "%"));
      calls.put("getAttributes", () -> m.getAttributes(null, null, "%", "%"));
      calls.put("getClientInfoProperties", m::getClientInfoProperties);
      calls.put("getPseudoColumns", () -> m.getPseudoColumns(null, null, "%", "%"));
      for (Map.Entry<String, Callable<ResultSet>> e : calls.entrySet()) {
        if (a.length > 1 && !e.getKey().equals(a[1])) continue;
        String r;
        try (ResultSet rs = e.getValue().call()) {
          int n = 0;
          while (rs.next()) n++;
          r = String.valueOf(n);
        } catch (Exception x) {
          r = "ERR " + String.valueOf(x.getMessage()).split("\n")[0];
        }
        System.out.println(e.getKey() + " | " + r);
      }
      for (String s : new String[] {"getSQLKeywords", "getNumericFunctions", "getStringFunctions", "getSystemFunctions", "getTimeDateFunctions"}) {
        String r;
        try {
          r = String.valueOf(DatabaseMetaData.class.getMethod(s).invoke(m)).length() + " chars";
        } catch (Exception x) {
          r = "ERR " + x.getCause();
        }
        System.out.println(s + " | " + r);
      }
    }
  }
}
