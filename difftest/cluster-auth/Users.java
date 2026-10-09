import com.scalar.db.api.AuthAdmin;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.service.TransactionFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

/**
 * Manages Cluster users as the superuser, since the frontend has no CREATE USER or GRANT yet.
 *
 * <pre>
 * java -cp &lt;classpath&gt; Users.java admin.properties list
 * java -cp &lt;classpath&gt; Users.java admin.properties create carol carolpw [superuser]
 * java -cp &lt;classpath&gt; Users.java admin.properties grant carol authdemo READ,WRITE
 * java -cp &lt;classpath&gt; Users.java admin.properties revoke carol authdemo WRITE
 * java -cp &lt;classpath&gt; Users.java admin.properties drop carol
 * </pre>
 *
 * Privileges are ScalarDB's: READ, WRITE, DELETE, CREATE, DROP, TRUNCATE, ALTER, GRANT. A grant is
 * on a namespace, which covers every table in it.
 */
public class Users {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("usage: Users.java <admin.properties> list|create|grant|revoke|drop ...");
      System.exit(1);
    }
    Properties properties = new Properties();
    try (InputStream in = new FileInputStream(args[0])) {
      properties.load(in);
    }
    try (DistributedTransactionAdmin admin =
        TransactionFactory.create(properties).getTransactionAdmin()) {
      switch (args[1]) {
        case "list":
          for (AuthAdmin.User user : admin.getUsers()) {
            System.out.println(user.getName() + (user.isSuperuser() ? " (superuser)" : ""));
          }
          break;
        case "create":
          if (args.length > 4 && args[4].equals("superuser")) {
            admin.createUser(args[2], args[3], AuthAdmin.UserOption.SUPERUSER);
          } else {
            admin.createUser(args[2], args[3]);
          }
          System.out.println("created " + args[2]);
          break;
        case "grant":
          admin.grant(args[2], args[3], privileges(args[4]));
          System.out.println("granted " + args[4] + " on " + args[3] + " to " + args[2]);
          break;
        case "revoke":
          admin.revoke(args[2], args[3], privileges(args[4]));
          System.out.println("revoked " + args[4] + " on " + args[3] + " from " + args[2]);
          break;
        case "drop":
          admin.dropUser(args[2]);
          System.out.println("dropped " + args[2]);
          break;
        default:
          System.err.println("unknown command " + args[1]);
          System.exit(1);
      }
    }
  }

  private static AuthAdmin.Privilege[] privileges(String list) {
    return Arrays.stream(list.split(","))
        .map(String::trim)
        .map(p -> AuthAdmin.Privilege.valueOf(p.toUpperCase(java.util.Locale.ROOT)))
        .toArray(AuthAdmin.Privilege[]::new);
  }
}
