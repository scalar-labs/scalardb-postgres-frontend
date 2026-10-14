package com.scalar.db.frontend.postgres;

import com.scalar.db.api.AuthAdmin;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.io.DataType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * Virtual {@code pg_catalog} and {@code information_schema} tables built from ScalarDB metadata,
 * enough for hand-written catalog queries and for psql's {@code \l}, {@code \dn}, {@code \dt},
 * {@code \du}, {@code \dp}, {@code \d}, {@code \d table}, and {@code \di}. Namespaces appear as
 * both schemas and databases; every table gets a {@code <table>_pkey} primary-key index and a
 * {@code <table>_<column>_idx} index per secondary index. Rows are rebuilt lazily after each {@link
 * #invalidate()}.
 */
final class Catalog {
  static final String OWNER = "scalardb";
  static final String TABLEOID = "tableoid";
  private static final long OWNER_OID = 10;
  private static final long HEAP_AM = 2;
  private static final long BTREE_AM = 403;
  private static final long PG_CLASS_OID = 1259; // pg_description.classoid of a table
  private static final long DEFAULT_TABLESPACE = 1663;
  private static final long PG_CATALOG_NAMESPACE = 11;
  // pg_settings: name, setting, vartype; what pgjdbc and DBeaver read when connecting
  private static final String[][] SETTINGS = {
    {"block_size", "8192", "integer"},
    {"integer_datetimes", "on", "bool"},
    {"max_function_args", "100", "integer"},
    {"max_identifier_length", "63", "integer"},
    {"max_index_keys", "32", "integer"},
    {"server_encoding", "UTF8", "string"},
    {"server_version", "16.0", "string"},
    {"server_version_num", "160000", "integer"},
    {"standard_conforming_strings", "on", "bool"},
    {"search_path", "\"$user\", public", "string"}
  };
  // tableoid, the system column every catalog row has: the catalog table's own pg_class OID
  private static final Map<String, Long> CATALOG_OIDS = new HashMap<>();

  static {
    CATALOG_OIDS.put("pg_class", PG_CLASS_OID);
    CATALOG_OIDS.put("pg_type", 1247L);
    CATALOG_OIDS.put("pg_namespace", 2615L);
    CATALOG_OIDS.put("pg_constraint", 2606L);
    CATALOG_OIDS.put("pg_opclass", 2616L);
    CATALOG_OIDS.put("pg_opfamily", 2753L);
    CATALOG_OIDS.put("pg_rewrite", 2618L);
    CATALOG_OIDS.put("pg_trigger", 2620L);
    CATALOG_OIDS.put("pg_database", 1262L);
  }

  private static final Map<String, List<String>> PG_CATALOG = new LinkedHashMap<>();
  private static final Map<String, List<String>> INFORMATION_SCHEMA = new LinkedHashMap<>();
  private static final Map<Long, String> TYPE_NAMES = new LinkedHashMap<>();
  private static final Map<Long, String> INTERNAL_TYPE_NAMES = new LinkedHashMap<>();

  static {
    PG_CATALOG.put(
        "pg_database",
        cols(
            "oid",
            "datname",
            "datdba",
            "encoding",
            "datlocprovider",
            "datcollate",
            "datctype",
            "datlocale",
            "daticulocale",
            "daticurules",
            "datacl",
            "dattablespace",
            "datallowconn",
            "datistemplate",
            "datconnlimit"));
    PG_CATALOG.put("pg_namespace", cols("oid", "nspname", "nspowner", "nspacl"));
    PG_CATALOG.put(
        "pg_class",
        cols(
            "oid",
            "relname",
            "relnamespace",
            "relkind",
            "relowner",
            "relam",
            "reltablespace",
            "relhasindex",
            "relhasrules",
            "relhastriggers",
            "relrowsecurity",
            "relforcerowsecurity",
            "relispartition",
            "reloftype",
            "relpersistence",
            "relreplident",
            "reltoastrelid",
            "relchecks",
            "reloptions",
            "relpartbound",
            "reltuples",
            "relacl",
            "relpages",
            "reltype",
            "relhassubclass"));
    PG_CATALOG.put("pg_am", cols("oid", "amname", "amtype", "amhandler"));
    PG_CATALOG.put(
        "pg_attribute",
        cols(
            "attrelid",
            "attnum",
            "attname",
            "atttypid",
            "atttypmod",
            "attnotnull",
            "atthasdef",
            "attisdropped",
            "attcollation",
            "attidentity",
            "attgenerated",
            "attstorage",
            "attcompression",
            "attstattarget",
            "attacl",
            "attlen",
            "attndims",
            "attinhcount",
            "attislocal"));
    PG_CATALOG.put(
        "pg_type",
        cols(
            "oid",
            "typname",
            "typnamespace",
            "typcollation",
            "typelem",
            "typdelim",
            "typinput",
            "typtype",
            "typbasetype",
            "typarray",
            "typlen",
            "typcategory",
            "typrelid",
            "typnotnull",
            "typtypmod",
            "typowner",
            "typdefault",
            // what DBeaver reads too
            "typbyval",
            "typalign",
            "typstorage",
            "typndims",
            "typispreferred",
            "typoutput",
            "typreceive",
            "typsend",
            "typmodin",
            "typmodout",
            "typanalyze"));
    PG_CATALOG.put(
        "pg_range",
        cols(
            "rngtypid",
            "rngsubtype",
            "rngmultitypid",
            "rngcollation",
            "rngsubopc",
            "rngcanonical",
            "rngsubdiff"));
    PG_CATALOG.put("pg_enum", cols("oid", "enumtypid", "enumsortorder", "enumlabel"));
    PG_CATALOG.put("pg_statio_all_tables", cols("relid", "schemaname", "relname"));
    INFORMATION_SCHEMA.put(
        "table_constraints",
        cols(
            "constraint_catalog",
            "constraint_schema",
            "constraint_name",
            "table_catalog",
            "table_schema",
            "table_name",
            "constraint_type"));
    INFORMATION_SCHEMA.put(
        "key_column_usage",
        cols(
            "constraint_catalog",
            "constraint_schema",
            "constraint_name",
            "table_catalog",
            "table_schema",
            "table_name",
            "column_name",
            "ordinal_position"));
    PG_CATALOG.put(
        "pg_index",
        cols(
            "indexrelid",
            "indrelid",
            "indisprimary",
            "indisunique",
            "indisclustered",
            "indisvalid",
            "indisreplident",
            "indkey",
            "indoption",
            "indpred",
            "indexprs",
            "indisexclusion",
            "indimmediate",
            "indcheckxmin",
            "indisready"));
    PG_CATALOG.put(
        "pg_constraint",
        cols(
            "oid",
            "conname",
            "contype",
            "conrelid",
            "conindid",
            "condeferrable",
            "condeferred",
            "conperiod",
            "conparentid",
            "confrelid",
            "conkey",
            "connoinherit",
            "conislocal",
            "coninhcount",
            "convalidated",
            "contypid",
            "confkey",
            "confupdtype",
            "confdeltype",
            "confmatchtype",
            "connamespace"));
    PG_CATALOG.put("pg_attrdef", cols("oid", "adrelid", "adnum", "adbin"));
    PG_CATALOG.put(
        "pg_collation",
        cols(
            "oid",
            "collname",
            "collnamespace",
            "collprovider",
            "collisdeterministic",
            "collencoding",
            "collcollate",
            "collctype",
            "colllocale",
            "colliculocale", // PostgreSQL 16's name, which psql asks a 16 server for
            "collicurules"));
    PG_CATALOG.put("pg_opclass", cols("oid", "opcname", "opcmethod", "opcnamespace"));
    PG_CATALOG.put("pg_opfamily", cols("oid", "opfname", "opfmethod", "opfnamespace"));
    PG_CATALOG.put(
        "pg_policy",
        cols(
            "oid",
            "polname",
            "polpermissive",
            "polroles",
            "polqual",
            "polwithcheck",
            "polcmd",
            "polrelid"));
    PG_CATALOG.put(
        "pg_statistic_ext",
        cols("oid", "stxrelid", "stxnamespace", "stxname", "stxkind", "stxstattarget"));
    PG_CATALOG.put("pg_inherits", cols("inhrelid", "inhparent", "inhseqno", "inhdetachpending"));
    PG_CATALOG.put(
        "pg_roles",
        cols(
            "oid",
            "rolname",
            "rolsuper",
            "rolinherit",
            "rolcreaterole",
            "rolcreatedb",
            "rolcanlogin",
            "rolconnlimit",
            "rolvaliduntil",
            "rolreplication",
            "rolbypassrls",
            "rolpassword"));
    PG_CATALOG.put(
        "pg_auth_members",
        cols("oid", "roleid", "member", "grantor", "admin_option", "inherit_option", "set_option"));
    PG_CATALOG.put(
        "pg_publication",
        cols(
            "oid",
            "pubname",
            "pubowner",
            "puballtables",
            "pubinsert",
            "pubupdate",
            "pubdelete",
            "pubtruncate",
            "pubgencols",
            "pubviaroot"));
    PG_CATALOG.put("pg_publication_rel", cols("prpubid", "prrelid", "prqual", "prattrs"));
    PG_CATALOG.put("pg_publication_namespace", cols("pnpubid", "pnnspid"));
    PG_CATALOG.put("pg_rewrite", cols("oid", "rulename", "ev_class", "ev_enabled"));
    PG_CATALOG.put(
        "pg_trigger", cols("oid", "tgname", "tgrelid", "tgenabled", "tgisinternal", "tgfoid"));
    // empty: ScalarDB has no functions, dependencies, policies or shared comments
    PG_CATALOG.put(
        "pg_proc",
        cols(
            "oid",
            "proname",
            "pronamespace",
            "prokind",
            "prorettype",
            "proretset",
            "proargtypes",
            "proargnames",
            "proargmodes",
            "proallargtypes"));
    PG_CATALOG.put("pg_language", cols("oid", "lanname"));
    PG_CATALOG.put(
        "pg_depend",
        cols("classid", "objid", "objsubid", "refclassid", "refobjid", "refobjsubid", "deptype"));
    PG_CATALOG.put("pg_shdescription", cols("objoid", "classoid", "description"));
    PG_CATALOG.put(
        "pg_policies",
        cols(
            "schemaname",
            "tablename",
            "policyname",
            "permissive",
            "roles",
            "cmd",
            "qual",
            "with_check"));
    PG_CATALOG.put("pg_description", cols("objoid", "classoid", "objsubid", "description"));
    PG_CATALOG.put(
        "pg_settings",
        cols("name", "setting", "unit", "vartype", "context", "reset_val", "short_desc"));
    PG_CATALOG.put("pg_tablespace", cols("oid", "spcname", "spcowner", "spcacl", "spcoptions"));
    PG_CATALOG.put(
        "pg_indexes", cols("schemaname", "tablename", "indexname", "tablespace", "indexdef"));
    INFORMATION_SCHEMA.put("schemata", cols("catalog_name", "schema_name"));
    INFORMATION_SCHEMA.put(
        "tables", cols("table_catalog", "table_schema", "table_name", "table_type"));
    INFORMATION_SCHEMA.put(
        "columns",
        cols(
            "table_catalog",
            "table_schema",
            "table_name",
            "column_name",
            "ordinal_position",
            "data_type",
            "is_nullable",
            "udt_name",
            "character_maximum_length",
            "column_default",
            "numeric_precision",
            "numeric_scale",
            "datetime_precision"));
    type(16, "bool", "boolean");
    type(17, "bytea", "bytea");
    type(20, "int8", "bigint");
    type(23, "int4", "integer");
    type(25, "text", "text");
    type(700, "float4", "real");
    type(701, "float8", "double precision");
    type(1082, "date", "date");
    type(1083, "time", "time without time zone");
    type(1114, "timestamp", "timestamp without time zone");
    type(1184, "timestamptz", "timestamp with time zone");
    type(1186, "interval", "interval");
    type(1700, "numeric", "numeric");
    type(21, "int2", "smallint");
    type(1043, "varchar", "character varying");
    type(1042, "bpchar", "character");
    type(114, "json", "json");
    type(3802, "jsonb", "jsonb");
    type(19, "name", "name");
    type(22, "int2vector", "int2vector"); // pg_index.indkey, which drivers look up by OID
  }

  /** pg_get_keywords(): PostgreSQL's SQL keywords, from keywords.txt, read on first use. */
  static List<Map<String, Object>> keywords() {
    return Keywords.ROWS;
  }

  private static final class Keywords {
    static final List<Map<String, Object>> ROWS = load();

    private static List<Map<String, Object>> load() {
      List<Map<String, Object>> rows = new ArrayList<>();
      try (java.io.BufferedReader in =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      Catalog.class.getResourceAsStream("keywords.txt"), "keywords.txt"),
                  java.nio.charset.StandardCharsets.UTF_8))) {
        for (String line = in.readLine(); line != null; line = in.readLine()) {
          String[] f = line.split(" "); // word, catcode, barelabel
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("word", f[0]);
          row.put("catcode", f[1]);
          row.put("barelabel", f[2].equals("t"));
          row.put("catdesc", KEYWORD_CATEGORIES.get(f[1]));
          row.put("baredesc", f[2].equals("t") ? "can be bare label" : "requires AS");
          rows.add(row);
        }
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return Collections.unmodifiableList(rows);
    }
  }

  private static final Map<String, String> KEYWORD_CATEGORIES = new HashMap<>();

  static {
    KEYWORD_CATEGORIES.put("U", "unreserved");
    KEYWORD_CATEGORIES.put("C", "unreserved (cannot be function or type name)");
    KEYWORD_CATEGORIES.put("T", "reserved (can be function or type name)");
    KEYWORD_CATEGORIES.put("R", "reserved");
  }

  /** PostgreSQL's int2vector, as pg_index.indkey: a list whose text is space-separated. */
  static final class Int2Vector extends ArrayList<Object> {
    Int2Vector(List<Object> values) {
      super(values);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (Object v : this) {
        sb.append(sb.length() > 0 ? " " : "").append(v);
      }
      return sb.toString();
    }
  }

  /** The type OID of a catalog column that is not inferred from its value: name, int2vector. */
  static long catalogColumnOid(String column) {
    if (column.equals("indkey")) {
      return 22;
    }
    return column.endsWith("name") ? 19 : 0;
  }

  private static final Map<Long, Long> ARRAY_OIDS = new LinkedHashMap<>();

  static {
    long[][] pairs = {
      {16, 1000},
      {17, 1001},
      {19, 1003},
      {20, 1016},
      {21, 1005},
      {23, 1007},
      {25, 1009},
      {700, 1021},
      {701, 1022},
      {1042, 1014},
      {1043, 1015},
      {1082, 1182},
      {1083, 1183},
      {1114, 1115},
      {1184, 1185},
      {1186, 1187},
      {1700, 1231},
      {114, 199},
      {3802, 3807}
    };
    for (long[] p : pairs) {
      ARRAY_OIDS.put(p[0], p[1]);
    }
  }

  /** The array type's OID for an element type; text[] when the element type is unknown. */
  static long arrayOid(long element) {
    return ARRAY_OIDS.getOrDefault(element, 1009L);
  }

  /** The element type of an array type's OID, or 0. */
  static long elementOid(long array) {
    for (Map.Entry<Long, Long> e : ARRAY_OIDS.entrySet()) {
      if (e.getValue() == array) {
        return e.getKey();
      }
    }
    return 0;
  }

  /** pg_type.typlen, which RowDescription reports too. */
  static long typeLength(long oid) {
    switch ((int) oid) {
      case 16:
        return 1;
      case 21:
        return 2;
      case 23:
      case 700:
      case 1082:
        return 4;
      case 20:
      case 701:
      case 1083:
      case 1114:
      case 1184:
        return 8;
      case 1186:
        return 16;
      case 19:
        return 64;
      default:
        return -1;
    }
  }

  /** pg_type.typalign from typlen: c, s, i or d; variable-length types align as int. */
  private static String typeAlign(long length) {
    return length == 1 ? "c" : length == 2 ? "s" : length == 8 ? "d" : "i";
  }

  private static String typeCategory(long oid) {
    switch ((int) oid) {
      case 16:
        return "B";
      case 17:
        return "U";
      case 19:
      case 25:
      case 1042:
      case 1043:
        return "S";
      case 1082:
      case 1083:
      case 1114:
      case 1184:
        return "D";
      case 1186:
        return "T";
      case 22:
        return "A";
      default:
        return "N";
    }
  }

  private static List<String> cols(String... names) {
    return Collections.unmodifiableList(Arrays.asList(names));
  }

  private static void type(long oid, String internalName, String name) {
    INTERNAL_TYPE_NAMES.put(oid, internalName);
    TYPE_NAMES.put(oid, name);
  }

  private final DistributedTransactionAdmin admin;
  final String database;
  String user = OWNER; // the connecting user, for current_user
  boolean clusterUsers; // pg_roles lists Cluster's users and roles, not the OWNER placeholder
  private boolean acls; // pg_class.relacl holds Cluster's grants: one call per grantee and table
  private boolean aclsLoaded;
  @Nullable private Map<String, List<Map<String, Object>>> tables;
  private final Map<Long, String> indexDefs = new HashMap<>();
  private final Map<Long, String> constraintDefs = new HashMap<>();
  private final Map<Long, List<String>> indexColumns = new HashMap<>();
  private final Map<Long, String> descriptions = new HashMap<>(); // table oid -> its comment
  private final Map<String, Long> relationOids = new HashMap<>(); // namespace.name -> oid
  private final Map<String, Long> namespaceOids = new HashMap<>();

  Catalog(DistributedTransactionAdmin admin, String database) {
    this.admin = admin;
    this.database = database;
  }

  /** The columns of a catalog table, or null if {@code schema.name} is not one. */
  @Nullable static List<String> columns(@Nullable String schema, String name) {
    if (schema == null || schema.equals("pg_catalog")) {
      List<String> columns = PG_CATALOG.get(name);
      if (columns != null) {
        List<String> withTableOid = new ArrayList<>(columns);
        withTableOid.add(TABLEOID); // a system column: SELECT * leaves it out
        return withTableOid;
      }
    }
    return "information_schema".equals(schema) ? INFORMATION_SCHEMA.get(name) : null;
  }

  static String qualifiedName(@Nullable String schema, String name) {
    return ("information_schema".equals(schema) ? "information_schema." : "pg_catalog.") + name;
  }

  /** The PostgreSQL type OID of a ScalarDB type. */
  static long typeOid(DataType type) {
    switch (type) {
      case BOOLEAN:
        return 16;
      case INT:
        return 23;
      case BIGINT:
        return 20;
      case FLOAT:
        return 700;
      case DOUBLE:
        return 701;
      case BLOB:
        return 17;
      case DATE:
        return 1082;
      case TIME:
        return 1083;
      case TIMESTAMP:
        return 1114;
      case TIMESTAMPTZ:
        return 1184;
      default:
        return 25;
    }
  }

  /** The SQL type name for a type OID, as {@code format_type} returns it. */
  static String typeName(long oid) {
    String name = TYPE_NAMES.get(oid);
    return name == null ? "unknown" : name;
  }

  /**
   * {@code format_type(oid, typmod)}: the type name with its modifier, as PostgreSQL prints it. A
   * typmod of -1 means "none given"; for {@code character} PostgreSQL then prints {@code bpchar},
   * while a NULL typmod (the one-argument form) prints {@code character}. An unknown OID is {@code
   * ???}.
   */
  static String typeName(long oid, @Nullable Long typmod) {
    long element = elementOid(oid);
    if (element != 0 && typmod != null && typmod >= 0) {
      return typeName(element, typmod) + "[]"; // character varying(20)[]
    }
    String name = TYPE_NAMES.get(oid);
    if (name == null) {
      return "???";
    }
    if (typmod == null) {
      return name;
    }
    long t = typmod;
    switch ((int) oid) {
      case 1700:
        if (t < 4) {
          return name;
        }
        long packed = t - 4;
        long scale = ((packed & 0x7ff) ^ 0x400) - 0x400; // 11-bit two's complement
        return "numeric(" + (packed >> 16) + "," + scale + ")";
      case 1043:
        return t < 4 ? name : "character varying(" + (t - 4) + ")";
      case 1042:
        return t < 4 ? "bpchar" : "character(" + (t - 4) + ")";
      case 1083:
      case 1114:
      case 1184:
        if (t < 0) {
          return name;
        }
        int space = name.indexOf(' ');
        return name.substring(0, space) + "(" + t + ")" + name.substring(space);
      default:
        return name;
    }
  }

  /**
   * Whether the next load fills pg_class.relacl and pg_namespace.nspacl (psql's {@code \dp} and
   * {@code \dn+}). It asks Cluster for every grantee's privileges on every table, so only a
   * statement that names one of them turns it on.
   */
  void setAcls(boolean wanted) {
    if (wanted && clusterUsers && !aclsLoaded) {
      invalidate();
    }
    acls = wanted && clusterUsers;
  }

  /** Forgets the loaded rows so the next query sees the current ScalarDB metadata. */
  void invalidate() {
    tables = null;
    relationOids.clear();
    namespaceOids.clear();
    descriptions.clear();
  }

  // ponytail: a one-second freshness window for DDL from other processes; a cluster-wide schema
  // version would let the catalog live until the schema actually changes
  private static final long FRESH_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
  private long generation = -1;
  private long invalidatedAt;

  /**
   * Drops the loaded catalog when DDL ran in this process since (its generation moved) or it is
   * older than a second, so a burst of catalog queries (psql's \d, an ORM's introspection) loads
   * every table's metadata once rather than once per statement (todo 039).
   */
  void invalidateIfStale(long ddlGeneration) {
    long now = System.nanoTime();
    if (ddlGeneration != generation || now - invalidatedAt > FRESH_NANOS) {
      invalidate();
      generation = ddlGeneration;
      invalidatedAt = now;
    }
  }

  /** The schema name a namespace is shown under: the connected one is {@code public}. */
  String schemaName(String namespace) {
    return namespace.equals(database) ? "public" : namespace;
  }

  /** The OID of a table or index named as in a {@code regclass} cast: {@code [schema.]name}. */
  long relationOid(String name) {
    try {
      load();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    String n = name.trim().replace("\"", "");
    Long oid = n.contains(".") ? relationOids.get(n) : relationOids.get(database + "." + n);
    if (oid == null && !n.contains(".")) {
      for (Map.Entry<String, Long> e : relationOids.entrySet()) {
        if (e.getKey().endsWith("." + n)) {
          oid = e.getValue();
          break;
        }
      }
    }
    if (oid == null) {
      // a catalog table itself, as in pgjdbc's d.classoid = 'pg_class'::regclass
      oid = CATALOG_OIDS.get(n.startsWith("pg_catalog.") ? n.substring(11) : n);
    }
    if (oid == null) {
      throw new IllegalArgumentException("relation \"" + n + "\" does not exist");
    }
    return oid;
  }

  /** The OID of a namespace named as in a {@code regnamespace} cast. */
  long namespaceOid(String name) {
    try {
      load();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    Long oid = namespaceOids.get(name.trim().replace("\"", ""));
    if (oid == null) {
      throw new IllegalArgumentException("schema \"" + name + "\" does not exist");
    }
    return oid;
  }

  /** The OID of a type named as in a {@code regtype} cast, by internal or SQL name. */
  static long typeOid(String name) {
    String n = name.trim().toLowerCase(Locale.ROOT);
    n = n.startsWith("pg_catalog.") ? n.substring("pg_catalog.".length()) : n;
    for (Map.Entry<Long, String> e : INTERNAL_TYPE_NAMES.entrySet()) {
      if (e.getValue().equals(n) || n.equals(TYPE_NAMES.get(e.getKey()))) {
        return e.getKey();
      }
    }
    throw new IllegalArgumentException("type \"" + name + "\" does not exist");
  }

  @Nullable String indexDef(long oid) throws ExecutionException {
    load();
    return indexDefs.get(oid);
  }

  /** pg_get_indexdef(oid, n, pretty): the index's n-th column, counting from 1. */
  @Nullable String indexColumn(long oid, long n) throws ExecutionException {
    load();
    List<String> columns = indexColumns.get(oid);
    return columns == null || n < 1 || n > columns.size() ? null : columns.get((int) n - 1);
  }

  /** A table's comment: which of its key columns are the partition key and the clustering key. */
  @Nullable String description(long oid) throws ExecutionException {
    load();
    return descriptions.get(oid);
  }

  @Nullable String constraintDef(long oid) throws ExecutionException {
    load();
    return constraintDefs.get(oid);
  }

  /** The rows of a catalog table, with keys qualified as {@code qualifier.column}. */
  List<Map<String, Object>> rows(@Nullable String schema, String name, String qualifier)
      throws ExecutionException {
    load();
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row :
        Objects.requireNonNull(tables, "loaded above")
            .getOrDefault(qualifiedName(schema, name), Collections.emptyList())) {
      Map<String, Object> qualified = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : row.entrySet()) {
        qualified.put(qualifier + "." + e.getKey(), e.getValue());
      }
      if (!"information_schema".equals(schema)) {
        qualified.put(qualifier + "." + TABLEOID, CATALOG_OIDS.getOrDefault(name, 0L));
      }
      out.add(qualified);
    }
    return out;
  }

  private void load() throws ExecutionException {
    if (tables != null) {
      return;
    }
    Map<String, List<Map<String, Object>>> t = new HashMap<>();
    for (String name : PG_CATALOG.keySet()) {
      t.put("pg_catalog." + name, new ArrayList<>());
    }
    for (String name : INFORMATION_SCHEMA.keySet()) {
      t.put("information_schema." + name, new ArrayList<>());
    }
    row(t, "pg_catalog.pg_am", HEAP_AM, "heap", "t", "heap_tableam_handler");
    row(t, "pg_catalog.pg_am", BTREE_AM, "btree", "i", "bthandler");
    row(t, "pg_catalog.pg_tablespace", DEFAULT_TABLESPACE, "pg_default", OWNER_OID, null, null);
    row(t, "pg_catalog.pg_tablespace", 1664L, "pg_global", OWNER_OID, null, null);
    // the schema of the built-in types, which drivers join pg_type with
    row(t, "pg_catalog.pg_namespace", PG_CATALOG_NAMESPACE, "pg_catalog", OWNER_OID, null);
    // the defaults of the settings drivers and GUI tools read here; SHOW and current_setting()
    // give a session's own values
    for (String[] setting : SETTINGS) {
      row(
          t,
          "pg_catalog.pg_settings",
          setting[0],
          setting[1],
          null,
          setting[2],
          "internal",
          setting[1],
          "");
    }
    if (!clusterUsers) {
      role(t, OWNER_OID, OWNER, true, true);
    }
    for (Map.Entry<Long, String> type : INTERNAL_TYPE_NAMES.entrySet()) {
      long oid = type.getKey();
      String name = type.getValue();
      long arrayOid = ARRAY_OIDS.getOrDefault(oid, 0L);
      row(
          t,
          "pg_catalog.pg_type",
          oid,
          name,
          PG_CATALOG_NAMESPACE,
          oid == 25 || oid == 1043 ? 100L : 0L,
          0L,
          ",",
          name + "in",
          "b",
          0L,
          arrayOid,
          typeLength(oid),
          typeCategory(oid),
          0L,
          false,
          -1L,
          OWNER_OID,
          null,
          typeLength(oid) > 0,
          typeAlign(typeLength(oid)),
          typeLength(oid) > 0 ? "p" : "x",
          0L,
          false,
          name + "out",
          name + "recv",
          name + "send",
          "-",
          "-",
          "-");
      if (arrayOid != 0) {
        // the array type of each base type, which drivers look up by typelem
        row(
            t,
            "pg_catalog.pg_type",
            arrayOid,
            "_" + name,
            PG_CATALOG_NAMESPACE,
            0L,
            oid,
            ",",
            "array_in",
            "b",
            0L,
            0L,
            -1L,
            "A",
            0L,
            false,
            -1L,
            OWNER_OID,
            null,
            false,
            typeAlign(typeLength(oid)),
            "x",
            0L,
            false,
            "array_out",
            "array_recv",
            "array_send",
            "-",
            "-",
            "array_typanalyze");
      }
    }
    Map<String, Boolean> grantees = acls ? grantees() : Collections.emptyMap();
    long oid = 16384;
    for (String namespace : new TreeSet<>(admin.getNamespaceNames())) {
      if (namespace.equals("scalardb") || namespace.equals("coordinator")) {
        continue; // ScalarDB's own namespaces
      }
      long namespaceOid = oid++;
      String schema = schemaName(namespace); // the connected namespace is PostgreSQL's public
      namespaceOids.put(namespace, namespaceOid);
      namespaceOids.put(schema, namespaceOid);
      Map<String, java.util.Set<AuthAdmin.Privilege>> namespaceGrants =
          grantees.isEmpty() ? Collections.emptyMap() : namespaceGrants(grantees, namespace);
      row(
          t,
          "pg_catalog.pg_namespace",
          namespaceOid,
          schema,
          OWNER_OID,
          namespaceGrants.isEmpty() ? null : namespaceAcl(namespaceGrants));
      row(
          t,
          "pg_catalog.pg_database",
          namespaceOid,
          namespace,
          OWNER_OID,
          6L,
          "c",
          "C",
          "C",
          null,
          null,
          null,
          null,
          DEFAULT_TABLESPACE,
          true,
          false,
          -1L);
      row(t, "information_schema.schemata", database, schema);
      for (String table : new TreeSet<>(admin.getNamespaceTableNames(namespace))) {
        TableMetadata stored = admin.getTableMetadata(namespace, table);
        if (stored == null) {
          continue;
        }
        // columns, keys and indexes under the names clients use (amount, not amount__pg_n10_2)
        ColumnMap columnMap = ColumnMap.of(namespace, table, stored);
        TableMetadata metadata = columnMap.clientView();
        long relOid = oid++;
        relation(
            t,
            relOid,
            table,
            namespaceOid,
            "r",
            HEAP_AM,
            true,
            grantees.isEmpty() ? null : acl(grantees, namespaceGrants, namespace, table));
        relationOids.put(namespace + "." + table, relOid);
        relationOids.put(schema + "." + table, relOid);
        row(t, "pg_catalog.pg_statio_all_tables", relOid, schema, table);
        row(t, "information_schema.tables", database, schema, table, "BASE TABLE");
        List<String> keys = new ArrayList<>(metadata.getPartitionKeyNames());
        keys.addAll(metadata.getClusteringKeyNames());
        // indkey and conkey in index order, the partition key then the clustering key
        List<String> columnNames = new ArrayList<>(metadata.getColumnNames());
        List<Object> keyNumbers = new ArrayList<>();
        List<Object> keyOptions = new ArrayList<>(); // pg_index.indoption: 1 for DESC
        for (String key : keys) {
          keyNumbers.add((long) columnNames.indexOf(key) + 1);
          boolean descending =
              metadata.getClusteringKeyNames().contains(key)
                  && metadata.getClusteringOrder(key) == com.scalar.db.api.Scan.Ordering.Order.DESC;
          keyOptions.add(descending ? 1L : 0L);
        }
        int attnum = 0;
        for (String column : metadata.getColumnNames()) {
          attnum++;
          DataType type = metadata.getColumnDataType(column);
          ColumnType declared = columnMap.type(column);
          // numeric(10,2) stored as a scaled bigint is numeric(10,2) here, as format_type shows it
          boolean numeric = declared.kind == ColumnType.Kind.NUMERIC_SCALED;
          long typeOid = numeric ? declared.oid() : typeOid(type);
          long typmod = numeric ? declared.typmod() : -1L;
          boolean key = keys.contains(column);
          row(
              t,
              "pg_catalog.pg_attribute",
              relOid,
              (long) attnum,
              column,
              typeOid,
              typmod,
              key,
              false,
              false,
              0L,
              "",
              "",
              "p",
              "",
              -1L,
              null,
              typeLength(typeOid),
              0L,
              0L,
              true);
          row(
              t,
              "information_schema.columns",
              database,
              schema,
              table,
              column,
              (long) attnum,
              typeName(typeOid),
              key ? "NO" : "YES",
              INTERNAL_TYPE_NAMES.get(typeOid),
              null,
              null,
              numeric ? (long) declared.precision : null,
              numeric ? (long) declared.scale : null,
              null);
        }
        String columnList = String.join(", ", keys);
        // The primary key as ScalarDB sees it: a read needs the whole partition key to be a Scan
        // rather than a ScanAll, and a DESC clustering column stores its rows in that order
        List<String> ordered = new ArrayList<>(metadata.getPartitionKeyNames());
        List<String> clustering = new ArrayList<>();
        for (String column : metadata.getClusteringKeyNames()) {
          clustering.add(
              metadata.getClusteringOrder(column) == com.scalar.db.api.Scan.Ordering.Order.DESC
                  ? column + " DESC"
                  : column);
        }
        ordered.addAll(clustering);
        String description =
            "ScalarDB partition key ("
                + String.join(", ", metadata.getPartitionKeyNames())
                + ")"
                + (clustering.isEmpty()
                    ? ""
                    : ", clustering key (" + String.join(", ", clustering) + ")");
        descriptions.put(relOid, description);
        row(t, "pg_catalog.pg_description", relOid, PG_CLASS_OID, 0L, description);
        row(
            t,
            "information_schema.table_constraints",
            database,
            schema,
            table + "_pkey",
            database,
            schema,
            table,
            "PRIMARY KEY");
        for (int k = 0; k < keys.size(); k++) {
          row(
              t,
              "information_schema.key_column_usage",
              database,
              schema,
              table + "_pkey",
              database,
              schema,
              table,
              keys.get(k),
              (long) (k + 1));
        }
        long pkeyOid = oid++;
        relation(t, pkeyOid, table + "_pkey", namespaceOid, "i", BTREE_AM, false, null);
        row(
            t,
            "pg_catalog.pg_index",
            pkeyOid,
            relOid,
            true,
            true,
            false,
            true,
            false,
            new Int2Vector(keyNumbers),
            new Int2Vector(keyOptions),
            null,
            null,
            false,
            true,
            false,
            true);
        String pkeyDef =
            "CREATE UNIQUE INDEX "
                + table
                + "_pkey ON "
                + schema
                + "."
                + table
                + " USING btree ("
                + String.join(", ", ordered)
                + ")";
        indexDefs.put(pkeyOid, pkeyDef);
        indexColumns.put(pkeyOid, keys);
        relationOids.put(namespace + "." + table + "_pkey", pkeyOid);
        relationOids.put(schema + "." + table + "_pkey", pkeyOid);
        row(t, "pg_catalog.pg_indexes", schema, table, table + "_pkey", null, pkeyDef);
        long constraintOid = oid++;
        row(
            t,
            "pg_catalog.pg_constraint",
            constraintOid,
            table + "_pkey",
            "p",
            relOid,
            pkeyOid,
            false,
            false,
            false,
            0L,
            0L,
            keyNumbers,
            false,
            true,
            0L,
            true,
            0L,
            null,
            " ",
            " ",
            " ",
            namespaceOid);
        constraintDefs.put(constraintOid, "PRIMARY KEY (" + columnList + ")");
        for (String index : new TreeSet<>(metadata.getSecondaryIndexNames())) {
          long indexOid = oid++;
          String indexName = table + "_" + index + "_idx";
          relation(t, indexOid, indexName, namespaceOid, "i", BTREE_AM, false, null);
          int indexPosition = new ArrayList<>(metadata.getColumnNames()).indexOf(index) + 1;
          long indexAttnum = indexPosition;
          row(
              t,
              "pg_catalog.pg_index",
              indexOid,
              relOid,
              false,
              false,
              false,
              true,
              false,
              new Int2Vector(Collections.singletonList((Object) indexAttnum)),
              new Int2Vector(Collections.singletonList((Object) 0L)),
              null,
              null,
              false,
              true,
              false,
              true);
          String indexDef =
              "CREATE INDEX "
                  + indexName
                  + " ON "
                  + schema
                  + "."
                  + table
                  + " USING btree ("
                  + index
                  + ")";
          indexDefs.put(indexOid, indexDef);
          indexColumns.put(indexOid, Collections.singletonList(index));
          relationOids.put(namespace + "." + indexName, indexOid);
          relationOids.put(schema + "." + indexName, indexOid);
          row(t, "pg_catalog.pg_indexes", schema, table, indexName, null, indexDef);
        }
      }
    }
    if (clusterUsers) {
      roles(t, oid);
    }
    aclsLoaded = acls;
    tables = t;
  }

  /** Who can hold grants, name to whether a user: Cluster's roles and its users but superusers. */
  private Map<String, Boolean> grantees() throws ExecutionException {
    Map<String, Boolean> grantees = new java.util.TreeMap<>();
    for (AuthAdmin.User u : admin.getUsers()) {
      if (!u.isSuperuser()) {
        grantees.put(u.getName(), true);
      }
    }
    for (AuthAdmin.Role r : admin.getRoles()) {
      grantees.put(r.getName(), false);
    }
    return grantees;
  }

  /** Each grantee's grants on a namespace, which apply to all of its tables. */
  private Map<String, java.util.Set<AuthAdmin.Privilege>> namespaceGrants(
      Map<String, Boolean> grantees, String namespace) throws ExecutionException {
    Map<String, java.util.Set<AuthAdmin.Privilege>> held = new java.util.TreeMap<>();
    for (Map.Entry<String, Boolean> g : grantees.entrySet()) {
      held.put(
          g.getKey(),
          g.getValue()
              ? admin.getPrivileges(g.getKey(), namespace)
              : admin.getRolePrivileges(g.getKey(), namespace));
    }
    return held;
  }

  /**
   * A schema's aclitem[]: {@code C} (CREATE) for whoever may create tables in it, or null. Its
   * other grants are on its tables, where {@code \dp} shows them; ScalarDB has no USAGE.
   */
  @Nullable private static List<String> namespaceAcl(
      Map<String, java.util.Set<AuthAdmin.Privilege>> namespaceGrants) {
    List<String> items = new ArrayList<>();
    for (Map.Entry<String, java.util.Set<AuthAdmin.Privilege>> g : namespaceGrants.entrySet()) {
      if (g.getValue().contains(AuthAdmin.Privilege.CREATE)) {
        String grantable = g.getValue().contains(AuthAdmin.Privilege.GRANT) ? "*" : "";
        items.add(g.getKey() + "=C" + grantable + "/" + OWNER);
      }
    }
    return items.isEmpty() ? null : items;
  }

  /**
   * A table's aclitem[], from each grantee's table and namespace grants (PostgreSQL's GRANT ON ALL
   * TABLES IN SCHEMA lands on every table too), or null when nobody holds any. CREATE, DROP and
   * ALTER have no table letter in PostgreSQL and are left out.
   */
  // ponytail: one call per grantee and table, and names are not quoted; a bulk privilege call in
  // Cluster's AuthAdmin would make \dp cheap
  @Nullable private List<String> acl(
      Map<String, Boolean> grantees,
      Map<String, java.util.Set<AuthAdmin.Privilege>> namespaceGrants,
      String namespace,
      String table)
      throws ExecutionException {
    List<String> items = new ArrayList<>();
    for (Map.Entry<String, Boolean> g : grantees.entrySet()) {
      java.util.Set<AuthAdmin.Privilege> held = java.util.EnumSet.noneOf(AuthAdmin.Privilege.class);
      held.addAll(namespaceGrants.getOrDefault(g.getKey(), Collections.emptySet()));
      held.addAll(
          g.getValue()
              ? admin.getPrivileges(g.getKey(), namespace, table)
              : admin.getRolePrivileges(g.getKey(), namespace, table));
      String item = aclItem(g.getKey(), held);
      if (item != null) {
        items.add(item);
      }
    }
    return items.isEmpty() ? null : items;
  }

  /**
   * {@code grantee=arwdD/scalardb}, in PostgreSQL's letter order, each with {@code *} if grantable.
   */
  @Nullable static String aclItem(String grantee, java.util.Set<AuthAdmin.Privilege> held) {
    StringBuilder letters = new StringBuilder();
    String grantable = held.contains(AuthAdmin.Privilege.GRANT) ? "*" : "";
    for (char c : "arwdD".toCharArray()) {
      AuthAdmin.Privilege needed =
          c == 'r'
              ? AuthAdmin.Privilege.READ
              : c == 'd'
                  ? AuthAdmin.Privilege.DELETE
                  : c == 'D' ? AuthAdmin.Privilege.TRUNCATE : AuthAdmin.Privilege.WRITE;
      if (held.contains(needed)) {
        letters.append(c).append(grantable);
      }
    }
    return letters.length() == 0 ? null : grantee + "=" + letters + "/" + OWNER;
  }

  /**
   * pg_roles and pg_auth_members from Cluster's users (who can log in) and roles (who cannot), for
   * psql's {@code \du} and {@code \drg}. Grantors are not recorded, so {@code grantor} is 0.
   */
  // ponytail: one getRolesForUser call per user on each catalog load; a bulk call would scale
  // better
  private void roles(Map<String, List<Map<String, Object>>> t, long oid) throws ExecutionException {
    List<AuthAdmin.User> users = admin.getUsers();
    List<AuthAdmin.Role> roles = admin.getRoles();
    Map<String, Long> oids = new HashMap<>();
    for (AuthAdmin.User u : users) {
      oids.put(u.getName(), oid);
      role(t, oid++, u.getName(), u.isSuperuser(), true);
    }
    for (AuthAdmin.Role r : roles) {
      oids.put(r.getName(), oid);
      role(t, oid++, r.getName(), false, false);
    }
    for (AuthAdmin.User u : users) {
      for (AuthAdmin.RoleForUser r : admin.getRolesForUser(u.getName())) {
        member(t, oid++, oids.get(r.getName()), oids.get(u.getName()), r.hasAdminOptionOnUser());
      }
    }
    for (AuthAdmin.Role r : roles) {
      for (AuthAdmin.GrantedRoleRef g : r.getGrantedRoles()) {
        member(t, oid++, oids.get(g.getName()), oids.get(r.getName()), g.hasAdminOption());
      }
    }
  }

  private static void role(
      Map<String, List<Map<String, Object>>> t,
      long oid,
      String name,
      boolean superuser,
      boolean login) {
    row(
        t,
        "pg_catalog.pg_roles",
        oid,
        name,
        superuser,
        true,
        false,
        false,
        login,
        -1L,
        null,
        false,
        false,
        "********");
  }

  private static void member(
      Map<String, List<Map<String, Object>>> t,
      long oid,
      @Nullable Long role,
      @Nullable Long member,
      boolean admin) {
    row(t, "pg_catalog.pg_auth_members", oid, role, member, 0L, admin, true, true);
  }

  private static void relation(
      Map<String, List<Map<String, Object>>> t,
      long oid,
      String name,
      long namespaceOid,
      String kind,
      long am,
      boolean hasIndex,
      @Nullable List<String> acl) {
    row(
        t,
        "pg_catalog.pg_class",
        oid,
        name,
        namespaceOid,
        kind,
        OWNER_OID,
        am,
        0L,
        hasIndex,
        false,
        false,
        false,
        false,
        false,
        0L,
        "p",
        "d",
        0L,
        0L,
        null,
        null,
        -1.0,
        acl,
        0L,
        0L,
        false);
  }

  private static void row(
      Map<String, List<Map<String, Object>>> t, String table, @Nullable Object... values) {
    List<String> columns =
        table.startsWith("pg_catalog.")
            ? PG_CATALOG.get(table.substring("pg_catalog.".length()))
            : INFORMATION_SCHEMA.get(table.substring("information_schema.".length()));
    if (columns == null) {
      throw new AssertionError("Unknown catalog table: " + table);
    }
    if (columns.size() != values.length) {
      throw new AssertionError(table + " expects " + columns.size() + " values");
    }
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i++) {
      row.put(columns.get(i), values[i]);
    }
    t.computeIfAbsent(table, k -> new ArrayList<>()).add(row);
  }
}
