import com.scalar.db.api.AuthAdmin;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Upsert;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.TransactionFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Prepares the authentication run as the default superuser: the namespace and table, two rows,
 * the users alice (READ, WRITE and DELETE on the namespace) and bob (nothing). Run with the frontend's fat
 * jar (ScalarDB core) and the Cluster client SDK on the classpath:
 *
 * <pre>java -cp ../../server/build/libs/scalardb-postgres-frontend.jar:<sdk.jar> Setup.java admin.properties</pre>
 */
public class Setup {
  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();
    try (InputStream in = new FileInputStream(args[0])) {
      properties.load(in);
    }
    TransactionFactory factory = TransactionFactory.create(properties);
    DistributedTransactionAdmin admin = factory.getTransactionAdmin();
    admin.createCoordinatorTables(true);
    admin.createNamespace("authdemo", true);
    admin.createTable(
        "authdemo",
        "items",
        TableMetadata.newBuilder()
            .addColumn("id", DataType.INT)
            .addColumn("name", DataType.TEXT)
            .addPartitionKey("id")
            .build(),
        true);
    for (String[] user : new String[][] {{"alice", "alicepw"}, {"bob", "bobpw"}}) {
      if (!admin.getUser(user[0]).isPresent()) {
        admin.createUser(user[0], user[1]);
      }
    }
    admin.grant(
        "alice",
        "authdemo",
        AuthAdmin.Privilege.READ,
        AuthAdmin.Privilege.WRITE,
        AuthAdmin.Privilege.DELETE); // DELETE is its own privilege in ScalarDB
    DistributedTransactionManager manager = factory.getTransactionManager();
    DistributedTransaction tx = manager.start();
    for (String[] row : new String[][] {{"1", "a"}, {"2", "b"}}) {
      tx.upsert(
          Upsert.newBuilder()
              .namespace("authdemo")
              .table("items")
              .partitionKey(Key.ofInt("id", Integer.parseInt(row[0])))
              .textValue("name", row[1])
              .build());
    }
    tx.commit();
    manager.close();
    admin.close();
    System.out.println("ready: authdemo.items with 2 rows; users alice (READ, WRITE, DELETE) and bob (nothing)");
  }
}
