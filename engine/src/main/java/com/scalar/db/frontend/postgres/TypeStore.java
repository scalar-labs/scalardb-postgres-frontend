package com.scalar.db.frontend.postgres;

import com.scalar.db.api.AuthAdmin;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Upsert;
import com.scalar.db.api.UpsertBuilder;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;

/**
 * The declared types of columns, one row per column in {@code
 * scalardb_postgres_frontend.column_types} for the whole deployment (docs/type-encoding.md is the
 * format). The table is created by {@code CREATE FRONTEND METADATA TABLES} only. With Cluster
 * authentication on, it is read and written as the service user named in the properties, whose
 * credentials go on each operation, so they work whatever auth type the sessions use; otherwise as
 * the configured ScalarDB user, like every other call.
 *
 * <p>A row is used only while its column exists with the ScalarDB type the row names: a row left by
 * a dropped table, or by a DDL that failed half way, is inert. DDL writes rows before it changes
 * the table and deletes them after, so a failure leaves such a row rather than a column without its
 * type (see {@link #write} and {@link #undo}).
 */
class TypeStore {
  static final String NAMESPACE = "scalardb_postgres_frontend";
  static final String TABLE = "column_types";
  static final String DEFAULT_USER = "scalardb_postgres_frontend";

  static final TableMetadata METADATA =
      TableMetadata.newBuilder()
          .addColumn("full_table_name", DataType.TEXT)
          .addColumn("column_name", DataType.TEXT)
          .addColumn("sql_type", DataType.TEXT)
          .addColumn("type_precision", DataType.INT)
          .addColumn("type_scale", DataType.INT)
          .addColumn("type_length", DataType.INT)
          .addColumn("encoding", DataType.TEXT)
          .addColumn("data_type", DataType.TEXT)
          .addColumn("created_by", DataType.TEXT)
          .addPartitionKey("full_table_name")
          .addClusteringKey("column_name")
          .build();

  /** No store at all: nothing is read, and declaring a type fails. For tests and tools. */
  static final TypeStore NONE = new TypeStore();

  private static final SecureRandom RANDOM = new SecureRandom();

  @Nullable private final DistributedTransactionManager manager;
  @Nullable private final DistributedTransactionAdmin admin;
  private final boolean auth; // Cluster authentication: rows are the service user's
  private final String user;
  @Nullable private final Map<String, String> attributes; // null: auth on, no password configured
  private volatile boolean exists; // once seen, assumed to stay

  private TypeStore() {
    this.manager = null;
    this.admin = null;
    this.auth = false;
    this.user = DEFAULT_USER;
    this.attributes = Collections.emptyMap();
  }

  /**
   * The store of a deployment: {@code scalar.db.postgres.auth} says whether rows are the service
   * user's, {@code scalar.db.postgres.metadata_user} and {@code metadata_password} who that is.
   */
  TypeStore(
      DistributedTransactionManager manager,
      DistributedTransactionAdmin admin,
      Properties properties) {
    this.manager = manager;
    this.admin = admin;
    this.auth =
        !properties
            .getProperty("scalar.db.postgres.auth", "none")
            .trim()
            .toLowerCase(Locale.ROOT)
            .equals("none");
    this.user = properties.getProperty("scalar.db.postgres.metadata_user", DEFAULT_USER).trim();
    String password = properties.getProperty("scalar.db.postgres.metadata_password");
    if (!auth) {
      this.attributes = Collections.emptyMap();
    } else if (password == null) {
      this.attributes = null;
    } else {
      Map<String, String> a = new HashMap<>();
      a.put("auth-type", "userpass"); // the Cluster SDK's AuthOperationAttributes
      a.put("auth-userpass-username", user);
      a.put("auth-userpass-password", password);
      this.attributes = Collections.unmodifiableMap(a);
    }
  }

  /** True when rows are the service user's, whose properties the setup command prints. */
  boolean authenticated() {
    return auth;
  }

  /** True once {@code CREATE FRONTEND METADATA TABLES} has run. */
  boolean exists() throws ExecutionException {
    if (!exists && admin != null) {
      // ponytail: checked on each read until it exists; negative caching if it shows in profiles
      exists = admin.tableExists(NAMESPACE, TABLE);
    }
    return exists;
  }

  /** Refuses a declared type until the table exists. */
  void requireTable() throws ExecutionException {
    if (!exists()) {
      throw new PgException.Argument(
          "55000",
          "a column type this frontend must record (NUMERIC, DECIMAL, VARCHAR(n), CHAR(n),"
              + " SMALLINT) needs its metadata table: run CREATE FRONTEND METADATA TABLES as a"
              + " superuser first");
    }
  }

  /**
   * The declared types of a table's columns, by column name. A row whose column is gone, or now has
   * another ScalarDB type, is ignored.
   */
  Map<String, ColumnType> read(String namespace, String table, TableMetadata stored)
      throws ExecutionException {
    Map<String, ColumnType> types = new HashMap<>();
    if (!exists()) {
      return types;
    }
    for (Result row : scan(namespace, table)) {
      String column = row.getText("column_name");
      if (column == null || !stored.getColumnNames().contains(column)) {
        continue;
      }
      DataType type = stored.getColumnDataType(column);
      if (!type.name().equals(row.getText("data_type"))) {
        continue;
      }
      types.put(
          column,
          ColumnType.fromRow(
              String.valueOf(row.getText("sql_type")),
              integer(row, "type_precision"),
              integer(row, "type_scale"),
              integer(row, "type_length"),
              String.valueOf(row.getText("encoding")),
              type));
    }
    return types;
  }

  /**
   * Writes the rows of {@code types} for a table, as DDL {@code token}, before the DDL changes the
   * table: in one transaction, replacing the rows of the columns in {@code scope} (every row of the
   * table when null) and removing those of them {@code types} has none for, which an earlier table
   * or column of that name left. Returns what it replaced or removed, for {@link #undo}.
   */
  Map<String, Result> write(
      String namespace,
      String table,
      Map<String, ColumnType> types,
      @Nullable Collection<String> scope,
      String token)
      throws ExecutionException {
    String full = namespace + "." + table;
    return java.util.Objects.requireNonNull(
        inTransaction(
            tx -> {
              Map<String, Result> previous = new LinkedHashMap<>();
              for (Result row : rows(tx, full)) {
                String column = String.valueOf(row.getText("column_name"));
                if (scope == null || scope.contains(column) || types.containsKey(column)) {
                  previous.put(column, row);
                  if (!types.containsKey(column)) {
                    tx.delete(delete(full, column));
                  }
                }
              }
              for (Map.Entry<String, ColumnType> e : types.entrySet()) {
                ColumnType t = e.getValue();
                tx.upsert(
                    upsert(full, e.getKey())
                        .textValue("sql_type", t.sqlType())
                        .intValue("type_precision", t.rowPrecision())
                        .intValue("type_scale", t.rowScale())
                        .intValue("type_length", t.rowLength())
                        .textValue("encoding", t.encoding())
                        .textValue("data_type", t.storage.name())
                        .textValue("created_by", token)
                        .build());
              }
              return previous;
            }));
  }

  /**
   * After the DDL that {@link #write} prepared failed: deletes the rows still {@code token}'s, and
   * with {@code restore} (the table or column turned out to exist, made by someone else) puts back
   * the rows {@code write} replaced or removed, where nobody has written since.
   */
  void undo(
      String namespace,
      String table,
      Collection<String> columns,
      String token,
      Map<String, Result> previous,
      boolean restore)
      throws ExecutionException {
    String full = namespace + "." + table;
    inTransaction(
        tx -> {
          Map<String, Result> now = new HashMap<>();
          for (Result row : rows(tx, full)) {
            now.put(String.valueOf(row.getText("column_name")), row);
          }
          for (String column : columns) {
            Result row = now.get(column);
            if (row != null && token.equals(row.getText("created_by"))) {
              Result before = restore ? previous.get(column) : null;
              if (before != null) {
                tx.upsert(copy(full, column, before)); // over ours, not after deleting it
              } else {
                tx.delete(delete(full, column));
              }
              now.put(column, before != null ? before : row);
            }
          }
          if (restore) {
            for (Map.Entry<String, Result> e : previous.entrySet()) {
              if (!now.containsKey(e.getKey())) {
                tx.upsert(copy(full, e.getKey(), e.getValue()));
              }
            }
          }
          return null;
        });
  }

  /**
   * The DDL token of each row of a table, taken before a DDL that ends by deleting rows, so that
   * {@link #deleteUnchanged} leaves alone a row another session wrote in between.
   */
  Map<String, String> tokens(String namespace, String table) throws ExecutionException {
    Map<String, String> tokens = new HashMap<>();
    if (exists()) {
      for (Result row : scan(namespace, table)) {
        tokens.put(
            String.valueOf(row.getText("column_name")), String.valueOf(row.getText("created_by")));
      }
    }
    return tokens;
  }

  /**
   * Deletes the rows of {@code tokens} that still carry the token they had: a table's, after it is
   * dropped, or a column's, after it is renamed away. A row rewritten since (the same name created
   * again by another session) is kept.
   */
  void deleteUnchanged(String namespace, String table, Map<String, String> tokens)
      throws ExecutionException {
    if (tokens.isEmpty()) {
      return;
    }
    String full = namespace + "." + table;
    inTransaction(
        tx -> {
          for (Result row : rows(tx, full)) {
            String name = String.valueOf(row.getText("column_name"));
            if (tokens.containsKey(name) && tokens.get(name).equals(row.getText("created_by"))) {
              tx.delete(delete(full, name));
            }
          }
          return null;
        });
  }

  /**
   * {@code CREATE FRONTEND METADATA TABLES}: the namespace and the table and, with authentication,
   * the service user with a random password and a grant on this table only. Creates only what is
   * missing: an existing user keeps its password. Returns the properties to add, when there are.
   */
  List<Map<String, Object>> create() throws ExecutionException {
    if (admin == null) {
      throw new IllegalStateException("No ScalarDB admin to create the metadata table with");
    }
    admin.createNamespace(NAMESPACE, true);
    admin.createTable(NAMESPACE, TABLE, METADATA, true);
    exists = true;
    List<Map<String, Object>> rows = new ArrayList<>();
    if (!auth) {
      return rows;
    }
    String password = null;
    if (!admin.getUser(user).isPresent()) {
      byte[] bytes = new byte[32];
      RANDOM.nextBytes(bytes);
      password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      admin.createUser(user, password);
    }
    admin.grant(
        user,
        NAMESPACE,
        TABLE,
        AuthAdmin.Privilege.READ,
        AuthAdmin.Privilege.WRITE,
        AuthAdmin.Privilege.DELETE);
    rows.add(property("scalar.db.postgres.metadata_user", user));
    rows.add(
        property(
            "scalar.db.postgres.metadata_password",
            password != null
                ? password
                : "(unchanged: the user existed; ALTER USER "
                    + user
                    + " PASSWORD sets a new one)"));
    return rows;
  }

  // ---- rows ----

  private interface Body<R> {
    @Nullable R run(DistributedTransaction tx) throws TransactionException;
  }

  @Nullable private <R> R inTransaction(Body<R> body) throws ExecutionException {
    if (manager == null) {
      throw new IllegalStateException("No ScalarDB transaction manager for the metadata table");
    }
    DistributedTransaction tx;
    try {
      tx = manager.begin(credentials());
    } catch (TransactionException e) {
      throw failed(e);
    }
    try {
      R result = body.run(tx);
      tx.commit();
      return result;
    } catch (TransactionException e) {
      rollback(tx);
      throw failed(e);
    } catch (RuntimeException e) {
      rollback(tx);
      throw e;
    }
  }

  private static void rollback(DistributedTransaction tx) {
    try {
      tx.rollback();
    } catch (TransactionException | RuntimeException ignored) {
      // the failure that led here is what the caller needs to see
    }
  }

  private List<Result> scan(String namespace, String table) throws ExecutionException {
    if (manager == null) {
      return Collections.emptyList();
    }
    try {
      return manager.scan(
          Scan.newBuilder()
              .namespace(NAMESPACE)
              .table(TABLE)
              .partitionKey(Key.ofText("full_table_name", namespace + "." + table))
              .attributes(credentials())
              .build());
    } catch (TransactionException e) {
      throw failed(e);
    }
  }

  private static List<Result> rows(DistributedTransaction tx, String full)
      throws TransactionException {
    return tx.scan(
        Scan.newBuilder()
            .namespace(NAMESPACE)
            .table(TABLE)
            .partitionKey(Key.ofText("full_table_name", full))
            .build());
  }

  private Map<String, String> credentials() {
    if (attributes == null) {
      throw new PgException.Argument(
          "55000",
          "the frontend's metadata table needs its service user: set"
              + " scalar.db.postgres.metadata_password (CREATE FRONTEND METADATA TABLES, run as a"
              + " superuser, prints it)");
    }
    return attributes;
  }

  private ExecutionException failed(TransactionException e) {
    return new ExecutionException(
        "the frontend's metadata table " + NAMESPACE + "." + TABLE + ": " + e.getMessage(), e);
  }

  private static UpsertBuilder.Buildable upsert(String full, String column) {
    return Upsert.newBuilder()
        .namespace(NAMESPACE)
        .table(TABLE)
        .partitionKey(Key.ofText("full_table_name", full))
        .clusteringKey(Key.ofText("column_name", column));
  }

  private static Upsert copy(String full, String column, Result row) {
    return upsert(full, column)
        .textValue("sql_type", row.getText("sql_type"))
        .intValue("type_precision", integer(row, "type_precision"))
        .intValue("type_scale", integer(row, "type_scale"))
        .intValue("type_length", integer(row, "type_length"))
        .textValue("encoding", row.getText("encoding"))
        .textValue("data_type", row.getText("data_type"))
        .textValue("created_by", row.getText("created_by"))
        .build();
  }

  private static Delete delete(String full, String column) {
    return Delete.newBuilder()
        .namespace(NAMESPACE)
        .table(TABLE)
        .partitionKey(Key.ofText("full_table_name", full))
        .clusteringKey(Key.ofText("column_name", column))
        .build();
  }

  @Nullable private static Integer integer(Result row, String column) {
    return row.isNull(column) ? null : row.getInt(column);
  }

  private static Map<String, Object> property(String name, String value) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("property", name);
    row.put("value", value);
    return row;
  }
}
