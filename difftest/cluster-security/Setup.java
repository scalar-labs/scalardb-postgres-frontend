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
 * Prepares the encryption and ABAC run as the default superuser: the namespace secdemo; an ABAC
 * policy lv with the levels PUB and CONF; alice at CONF and bob at PUB, both with READ, WRITE,
 * DELETE and CREATE on the namespace; and the table docs under the policy, with one row at each
 * level. Run with the frontend's fat jar and the Cluster client SDK on the classpath:
 *
 * <pre>java -cp ../../server/build/libs/scalardb-postgres-frontend.jar:'sdk-classpath/lib/*' Setup.java admin.properties</pre>
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
    admin.createNamespace("secdemo", true);
    admin.createTable(
        "secdemo",
        "docs",
        TableMetadata.newBuilder()
            .addColumn("id", DataType.INT)
            .addColumn("body", DataType.TEXT)
            .addPartitionKey("id")
            .build(),
        true);
    if (!admin.getPolicy("lv").isPresent()) {
      admin.createPolicy("lv", null); // data tag column lv_data_tag
      admin.createLevel("lv", "PUB", "Public", 10);
      admin.createLevel("lv", "CONF", "Confidential", 20);
      admin.createTablePolicy("docs_lv", "lv", "secdemo", "docs");
    }
    for (String[] user : new String[][] {{"alice", "alicepw", "CONF"}, {"bob", "bobpw", "PUB"}}) {
      if (!admin.getUser(user[0]).isPresent()) {
        admin.createUser(user[0], user[1]);
      }
      admin.grant(
          user[0],
          "secdemo",
          AuthAdmin.Privilege.READ,
          AuthAdmin.Privilege.WRITE,
          AuthAdmin.Privilege.DELETE,
          AuthAdmin.Privilege.CREATE);
      admin.setLevelsToUser("lv", user[0], user[2], user[2], user[2]);
    }
    DistributedTransactionManager manager = factory.getTransactionManager();
    DistributedTransaction tx = manager.start();
    for (String[] row : new String[][] {{"1", "public note", "PUB"}, {"2", "secret plan", "CONF"}}) {
      tx.upsert(
          Upsert.newBuilder()
              .namespace("secdemo")
              .table("docs")
              .partitionKey(Key.ofInt("id", Integer.parseInt(row[0])))
              .textValue("body", row[1])
              .textValue("lv_data_tag", row[2])
              .build());
    }
    tx.commit();
    manager.close();
    admin.close();
    System.out.println("ready: secdemo.docs (PUB row 1, CONF row 2); alice at CONF, bob at PUB");
  }
}
