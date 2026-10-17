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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  // anyone reads the catalog and no one writes it, which PostgreSQL writes as PUBLIC's SELECT
  private static final List<String> CATALOG_ACL = Collections.singletonList("=r/" + OWNER);
  // information_schema's namespace in PostgreSQL 16, which its domains are in (catalog-types.txt)
  private static final long INFORMATION_SCHEMA_OID = 13276;
  private static final long FIRST_USER_OID = 16384; // the first of the tables', as PostgreSQL's
  private static final long ICU_COLLATION_OID = 16383; // ScalarDB's ICU collation, below table oids
  private static final long HEAP_AM = 2;
  private static final long BTREE_AM = 403;
  // pg_description's and pg_depend's classid of a table, a namespace and a constraint
  private static final long PG_CLASS_OID = 1259;
  private static final long PG_NAMESPACE_OID = 2615;
  private static final long PG_CONSTRAINT_OID = 2606;
  private static final long DEFAULT_TABLESPACE = 1663;
  private static final long PG_CATALOG_NAMESPACE = 11;

  /**
   * The settings the frontend has, with PostgreSQL 16's vartype, context, unit and description:
   * name, default, vartype, context, unit, short_desc. pg_settings, SHOW, SHOW ALL and
   * current_setting all read them; the session's own values come from QueryProcessor.
   */
  static final String[][] SETTINGS = {
    {
      "application_name",
      "",
      "string",
      "user",
      null,
      "Sets the application name to be reported in statistics and logs."
    },
    {"block_size", "8192", "integer", "internal", null, "Shows the size of a disk block."},
    {
      "client_encoding", "UTF8", "string", "user", null, "Sets the client's character set encoding."
    },
    {
      "client_min_messages",
      "notice",
      "enum",
      "user",
      null,
      "Sets the message levels that are sent to the client."
    },
    {
      "DateStyle",
      "ISO, MDY",
      "string",
      "user",
      null,
      "Sets the display format for date and time values."
    },
    // read committed: ScalarDB's SNAPSHOT reads each record's committed version with no snapshot
    // across records, which is what PostgreSQL calls READ COMMITTED; the server raises this for
    // SERIALIZABLE
    {
      "default_transaction_isolation",
      "read committed",
      "enum",
      "user",
      null,
      "Sets the transaction isolation level of each new transaction."
    },
    {
      "default_transaction_read_only",
      "off",
      "bool",
      "user",
      null,
      "Sets the default read-only status of new transactions."
    },
    {
      "integer_datetimes",
      "on",
      "bool",
      "internal",
      null,
      "Shows whether datetimes are integer based."
    },
    {
      "max_function_args",
      "100",
      "integer",
      "internal",
      null,
      "Shows the maximum number of function arguments."
    },
    {
      "max_identifier_length",
      "63",
      "integer",
      "internal",
      null,
      "Shows the maximum identifier length."
    },
    {
      "max_index_keys", "32", "integer", "internal", null, "Shows the maximum number of index keys."
    },
    {
      QueryProcessor.MAX_ROWS_PER_WRITE,
      String.valueOf(QueryParser.DEFAULT_MAX_ROWS_PER_WRITE),
      "integer",
      "user",
      null,
      "Sets the most rows a read-then-write statement may write; 0 for no limit."
    },
    {
      "search_path",
      "\"$user\", public",
      "string",
      "user",
      null,
      "Sets the schema search order for names that are not schema-qualified."
    },
    {
      "server_encoding",
      "UTF8",
      "string",
      "internal",
      null,
      "Shows the server (database) character set encoding."
    },
    {"server_version", "16.0", "string", "internal", null, "Shows the server version."},
    {
      "server_version_num",
      "160000",
      "integer",
      "internal",
      null,
      "Shows the server version as an integer."
    },
    {
      "standard_conforming_strings",
      "on",
      "bool",
      "user",
      null,
      "Causes '...' strings to treat backslashes literally."
    },
    {
      "statement_timeout",
      "0",
      "integer",
      "user",
      "ms",
      "Sets the maximum allowed duration of any statement."
    },
    {
      "TimeZone",
      "UTC",
      "string",
      "user",
      null,
      "Sets the time zone for displaying and interpreting time stamps."
    },
    {
      "transaction_isolation",
      "read committed",
      "enum",
      "user",
      null,
      "Sets the current transaction's isolation level."
    }
  };

  /** A setting's row in {@link #SETTINGS}, by its exact name; null for one not listed. */
  @Nullable static String[] setting(String name) {
    for (String[] s : SETTINGS) {
      if (s[0].equals(name)) {
        return s;
      }
    }
    return null;
  }

  // tableoid, the system column every catalog row has: the catalog table's own pg_class OID
  private static final Map<String, Long> CATALOG_OIDS = new HashMap<>();

  static {
    // PostgreSQL 16's oid of each catalog relation (catalog-types.txt)
    CatalogTypes.RELATIONS.forEach(
        (relation, f) -> {
          if (relation.startsWith("pg_catalog.")) {
            CATALOG_OIDS.put(relation.substring("pg_catalog.".length()), Long.parseLong(f[0]));
          }
        });
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
            "typanalyze",
            "typacl"));
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
            "indisready",
            "indnatts",
            "indnkeyatts",
            "indcollation",
            "indclass",
            "indnullsnotdistinct",
            "indislive"));
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
    PG_CATALOG.put(
        "pg_opclass",
        cols(
            "oid",
            "opcmethod",
            "opcname",
            "opcnamespace",
            "opcowner",
            "opcfamily",
            "opcintype",
            "opcdefault",
            "opckeytype"));
    PG_CATALOG.put("pg_opfamily", cols("oid", "opfmethod", "opfname", "opfnamespace", "opfowner"));
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
    // empty: ScalarDB has no dependencies, policies or shared comments
    PG_CATALOG.put(
        "pg_language",
        cols(
            "oid",
            "lanname",
            "lanowner",
            "lanispl",
            "lanpltrusted",
            "lanplcallfoid",
            "laninline",
            "lanvalidator",
            "lanacl"));
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
        cols(
            "name",
            "setting",
            "unit",
            "vartype",
            "context",
            "source",
            "boot_val",
            "reset_val",
            "short_desc"));
    PG_CATALOG.put("pg_tablespace", cols("oid", "spcname", "spcowner", "spcacl", "spcoptions"));
    PG_CATALOG.put(
        "pg_indexes", cols("schemaname", "tablename", "indexname", "tablespace", "indexdef"));
    PG_CATALOG.put(
        "pg_tables",
        cols(
            "schemaname",
            "tablename",
            "tableowner",
            "tablespace",
            "hasindexes",
            "hasrules",
            "hastriggers",
            "rowsecurity"));
    PG_CATALOG.put(
        "pg_user",
        cols(
            "usename",
            "usesysid",
            "usecreatedb",
            "usesuper",
            "userepl",
            "usebypassrls",
            "passwd",
            "valuntil",
            "useconfig"));
    PG_CATALOG.put("pg_timezone_names", cols("name", "abbrev", "utc_offset", "is_dst"));
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
            "numeric_precision_radix",
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
    type(30, "oidvector", "oidvector"); // pg_index.indclass and indcollation
    type(26, "oid", "oid");
    type(18, "char", "\"char\""); // the one-byte codes of catalog columns: relkind, typtype
    type(2950, "uuid", "uuid"); // uuid values; a uuid column is stored as TEXT
    // the OID aliases a cast like 'items'::regclass gives, printed as the object's name
    type(24, "regproc", "regproc");
    type(2205, "regclass", "regclass");
    type(2206, "regtype", "regtype");
    type(4089, "regnamespace", "regnamespace");
    type(2202, "regprocedure", "regprocedure");
    type(194, "pg_node_tree", "pg_node_tree"); // pg_get_expr's argument
    // the pseudo-types that pg_proc's polymorphic functions take and return
    type(2276, "any", "\"any\"");
    type(2277, "anyarray", "anyarray");
    type(2283, "anyelement", "anyelement");
    type(2776, "anynonarray", "anynonarray");
    type(5077, "anycompatible", "anycompatible");
    type(5078, "anycompatiblearray", "anycompatiblearray");
    type(2279, "trigger", "trigger"); // what psql's \\df tells trigger functions apart by
    type(3838, "event_trigger", "event_trigger");
  }

  private static final java.util.Set<Long> PSEUDO_TYPES =
      new java.util.HashSet<>(
          Arrays.asList(2276L, 2277L, 2283L, 2776L, 5077L, 5078L, 2279L, 3838L));

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

  /**
   * {type, opclass, opfamily}: PostgreSQL 16's default btree operator class of each type, in its
   * pg_opclass order, which an unordered string_agg (psql's \dAf) shows.
   */
  private static final long[][] OPCLASSES = {
    {16, 10003, 424},
    {1042, 10004, 426},
    {17, 10006, 428},
    {1082, 3122, 434},
    {700, 10012, 1970},
    {701, 3123, 1970},
    {21, 1979, 1976},
    {23, 1978, 1976},
    {20, 3124, 1976},
    {1186, 10022, 1982},
    {1700, 3125, 1988},
    {25, 3126, 1994},
    {1083, 10038, 1996},
    {1184, 3127, 434},
    {1114, 3128, 434},
    {2950, 10065, 2968},
    {3802, 10088, 4033}
  };

  private static final Map<Long, String> OPCLASS_NAMES = new HashMap<>();
  private static final Map<Long, String> OPFAMILY_NAMES = new HashMap<>();

  static {
    for (long[] c : OPCLASSES) {
      OPCLASS_NAMES.put(c[1], INTERNAL_TYPE_NAMES.get(c[0]) + "_ops");
    }
    OPFAMILY_NAMES.put(424L, "bool_ops");
    OPFAMILY_NAMES.put(428L, "bytea_ops");
    OPFAMILY_NAMES.put(1976L, "integer_ops");
    OPFAMILY_NAMES.put(1994L, "text_ops");
    OPFAMILY_NAMES.put(1970L, "float_ops");
    OPFAMILY_NAMES.put(426L, "bpchar_ops");
    OPFAMILY_NAMES.put(434L, "datetime_ops");
    OPFAMILY_NAMES.put(1996L, "time_ops");
    OPFAMILY_NAMES.put(1982L, "interval_ops");
    OPFAMILY_NAMES.put(1988L, "numeric_ops");
    OPFAMILY_NAMES.put(2968L, "uuid_ops");
    OPFAMILY_NAMES.put(4033L, "jsonb_ops");
  }

  /** The default btree operator class of a column type: varchar's is text's; 0 if it has none. */
  private static long defaultOpclass(long typeOid) {
    long type = typeOid == 1043 ? 25 : typeOid;
    for (long[] c : OPCLASSES) {
      if (c[0] == type) {
        return c[1];
      }
    }
    return 0;
  }

  private static List<Object> map(List<String> keys, Map<String, Object> values) {
    List<Object> mapped = new ArrayList<>();
    for (String key : keys) {
      mapped.add(values.get(key));
    }
    return mapped;
  }

  /** PostgreSQL's oidvector, as pg_index.indclass: an int2vector of oids. */
  static final class OidVector extends Int2Vector {
    OidVector(List<Object> values) {
      super(values);
    }
  }

  /** PostgreSQL's int2vector, as pg_index.indkey: a list whose text is space-separated. */
  static class Int2Vector extends ArrayList<Object> {
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

  /**
   * A value of a reg* type: an OID that prints as its object's name ({@code integer}, {@code
   * sales.orders}, {@code -} for 0), and compares, casts to a number and goes out in binary as the
   * OID.
   */
  static final class Reg {
    final long oid;
    final String name;

    Reg(long oid, String name) {
      this.oid = oid;
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Reg && ((Reg) o).oid == oid;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(oid);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  /**
   * A reg* value for an OID: regclass, regtype, regnamespace, regproc or regprocedure; an unknown
   * OID prints as the number.
   */
  Reg reg(String type, long oid) {
    try {
      return regOf(type, oid);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  private Reg regOf(String type, long oid) throws ExecutionException {
    if (oid == 0) {
      return new Reg(0, "-");
    }
    String name = null;
    switch (type) {
      case "regtype":
        name = TYPE_NAMES.containsKey(oid) || elementOid(oid) != 0 ? typeName(oid) : null;
        break;
      case "regnamespace":
        name = namespaceName(oid);
        break;
      case "regproc":
      case "regprocedure":
        Builtins.Function f = Builtins.function(oid);
        name =
            f == null
                ? null
                : type.equals("regproc")
                    // qualified when the name alone is ambiguous, as PostgreSQL prints it
                    ? (Builtins.overloads(f.name).size() > 1 ? "pg_catalog." : "") + f.name
                    : f.name + "(" + f.row.get("pg_get_function_identity_arguments") + ")";
        break;
      case "regclass":
        Map<String, Object> c = rowOf("pg_class", oid);
        if (c != null) {
          Object namespace = c.get("x.relnamespace");
          String schema = namespace instanceof Long ? namespaceName((Long) namespace) : null;
          String relation = (String) c.get("x.relname");
          // qualified when its bare name finds another, quoted as an identifier, as PostgreSQL
          name =
              Boolean.TRUE.equals(visible(oid))
                  ? Evaluator.quoteIdent(String.valueOf(relation))
                  : Evaluator.quoteIdent(String.valueOf(schema))
                      + "."
                      + Evaluator.quoteIdent(String.valueOf(relation));
        }
        break;
      default:
        throw new IllegalArgumentException("not a reg* type: " + type);
    }
    return new Reg(oid, name == null ? String.valueOf(oid) : name);
  }

  /** A pg_catalog table's row of this OID, or null. */
  @Nullable Map<String, Object> rowOf(String table, long oid) {
    try {
      for (Map<String, Object> r : rows("pg_catalog", table, "x")) {
        if (Objects.equals(r.get("x.oid"), oid)) {
          return r;
        }
      }
      return null;
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Whether pg_type or pg_am names this function as a type's I/O function or an access method's.
   */
  boolean ioFunction(String name) {
    try {
      for (Map<String, Object> r : rows("pg_catalog", "pg_type", "x")) {
        for (String c : new String[] {"typinput", "typoutput", "typreceive", "typsend"}) {
          if (name.equals(r.get("x." + c))) {
            return true;
          }
        }
      }
      for (Map<String, Object> r : rows("pg_catalog", "pg_am", "x")) {
        if (name.equals(r.get("x.amhandler"))) {
          return true;
        }
      }
      return false;
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Whether objects of this namespace are found by bare name: pg_catalog, or on search_path. */
  boolean onSearchPath(long namespace) {
    String name = namespaceName(namespace);
    return namespace == PG_CATALOG_NAMESPACE || name != null && searchPath.contains(name);
  }

  /** pg_get_userbyid: the role's name, or PostgreSQL's placeholder for an unknown OID. */
  String roleName(long oid) {
    Map<String, Object> role = rowOf("pg_roles", oid);
    return role != null
        ? String.valueOf(role.get("x.rolname"))
        : oid == OWNER_OID ? OWNER : "unknown (OID=" + oid + ")"; // the tables' owner
  }

  /** pg_relation_is_publishable: a user table is, an index or a catalog is not; null if none. */
  @Nullable Boolean publishable(long oid) {
    Map<String, Object> relation = rowOf("pg_class", oid);
    return relation == null ? null : "r".equals(relation.get("x.relkind")) && oid >= FIRST_USER_OID;
  }

  @Nullable private String namespaceName(long oid) {
    Map<String, Object> n = rowOf("pg_namespace", oid);
    return n == null ? null : (String) n.get("x.nspname");
  }

  /** The type OID of a catalog column that is not inferred from its value: name, int2vector. */
  static long catalogColumnOid(String table, String column) {
    Long oid = CatalogTypes.TYPES.get(table + " " + column);
    if (oid == null || oid == 24 && !BUILTIN_ROWS.containsKey(table)) {
      // ponytail: pg_type's and pg_am's regproc columns hold names, of functions pg_proc lacks
      return column.equals("indkey") ? 22 : column.endsWith("name") ? 19 : 0;
    }
    long element = elementOid(oid);
    return TYPE_NAMES.containsKey(element != 0 ? Long.valueOf(element) : oid) ? oid : 25;
  }

  /**
   * Each catalog table that answers no rows by design, and why that is the truth here. A declared
   * table that is not listed must answer rows; ClientCatalogTest checks both.
   */
  static final Map<String, String> EMPTY_BECAUSE = new LinkedHashMap<>();

  // schema.name -> the relation's rows, which are Builtins', in PostgreSQL 16's text
  private static final Map<String, List<Map<String, String>>> BUILTIN_ROWS = new LinkedHashMap<>();

  static {
    empty("pg_trigger", "no triggers");
    empty("pg_rewrite", "no rules: the catalog views are built in, not defined by rules");
    empty("pg_policy", "no row-level security policies");
    empty("pg_policies", "no row-level security policies");
    empty("pg_inherits", "no table inheritance or partitions");
    empty("pg_enum", "no enum types");
    empty("pg_range", "no range types");
    empty("pg_attrdef", "no column defaults");
    empty("pg_statistic_ext", "no extended statistics");
    empty("pg_publication", "no publications");
    empty("pg_publication_rel", "no publications");
    empty("pg_publication_namespace", "no publications");
    empty("pg_shdescription", "no comments on shared objects");
    empty("pg_sequence", "no sequences");
    empty("pg_sequences", "no sequences");
    empty("pg_matviews", "no materialized views");
    empty("pg_extension", "no extensions");
    empty("pg_available_extensions", "no extensions");
    empty("pg_event_trigger", "no event triggers");
    empty("pg_default_acl", "no default privileges");
    empty("pg_db_role_setting", "no per-role or per-database settings");
    empty("pg_subscription", "no subscriptions");
    empty("pg_largeobject_metadata", "no large objects");
    empty("pg_foreign_data_wrapper", "no foreign data");
    empty("pg_foreign_server", "no foreign data");
    empty("pg_foreign_table", "no foreign data");
    empty("pg_user_mappings", "no foreign data");
    empty("pg_prepared_xacts", "no two-phase transactions");
    empty("pg_ts_config", "no text search");
    empty("pg_ts_dict", "no text search");
    empty("pg_ts_parser", "no text search");
    empty("pg_ts_template", "no text search");
    empty("pg_conversion", "no encoding conversions: the server encoding is UTF8 only");
    empty("pg_amop", "the operator families' members are not exposed");
    empty("pg_amproc", "the operator families' members are not exposed");
    empty("pg_parameter_acl", "no parameter privileges");
    empty("pg_stats", "no statistics are gathered");
    empty("pg_auth_members", "no role memberships, unless ScalarDB Cluster's roles have them");
    declare("pg_views"); // the catalog views, which catalogRelations lists
    // information_schema views with PostgreSQL 16's columns; empty where none of their objects
    // exist
    declare("information_schema.views");
    empty("information_schema.triggers", "no triggers");
    empty("information_schema.sequences", "no sequences");
    empty("information_schema.referential_constraints", "no foreign keys");
    declare("information_schema.constraint_column_usage");
    // what the frontend implements, as PostgreSQL 16 lists it (Builtins)
    builtin("pg_proc", Builtins.PROCS);
    builtin("pg_aggregate", Builtins.AGGREGATES);
    builtin("pg_operator", Builtins.OPERATORS);
    builtin("pg_cast", Builtins.CASTS);
    builtin("information_schema.routines", Builtins.table("information_schema-routines.txt"));
    builtin("information_schema.parameters", Builtins.table("information_schema-parameters.txt"));
    builtin("information_schema.domains", Builtins.table("information_schema-domains.txt"));
    builtin(
        "information_schema.character_sets",
        Builtins.table("information_schema-character_sets.txt"));
  }

  /** Declares a catalog relation that is empty here, with PostgreSQL 16's columns if new. */
  private static void empty(String relation, String reason) {
    EMPTY_BECAUSE.put(relation, reason);
    declare(relation);
  }

  /**
   * Declares a catalog relation, {@code schema.name} or a pg_catalog table's bare name, with
   * PostgreSQL 16's columns if not declared already; returns its qualified name.
   */
  private static String declare(String relation) {
    String qualified = relation.contains(".") ? relation : "pg_catalog." + relation;
    (qualified.startsWith("pg_catalog.") ? PG_CATALOG : INFORMATION_SCHEMA)
        .computeIfAbsent(
            qualified.substring(qualified.indexOf('.') + 1),
            r ->
                Collections.unmodifiableList(
                    Objects.requireNonNull(
                        CatalogTypes.COLUMNS.get(qualified), qualified + " in catalog-types.txt")));
    return qualified;
  }

  /** Declares a catalog relation of PostgreSQL 16's columns whose rows are Builtins'. */
  private static void builtin(String relation, List<Map<String, String>> rows) {
    BUILTIN_ROWS.put(declare(relation), rows);
  }

  /** A catalog column's value from PostgreSQL's text for it. */
  @Nullable private static Object catalogValue(String table, String column, @Nullable String text) {
    if (text == null) {
      return null;
    }
    int type = CatalogTypes.TYPES.getOrDefault(table + " " + column, 25L).intValue();
    switch (type) {
      case 16:
        return text.equals("t");
      case 20:
      case 21:
      case 23:
      case 26:
        return Long.parseLong(text);
      case 700:
        return Double.parseDouble(text);
      case 24: // written as its OID and name: 852 int48eq
        int space = text.indexOf(' ');
        return space < 0
            ? new Reg(0, "-")
            : new Reg(Long.parseLong(text.substring(0, space)), text.substring(space + 1));
      case 30:
        List<Object> oids = new ArrayList<>();
        for (String o : text.isEmpty() ? new String[0] : text.split(" ")) {
          oids.add(Long.parseLong(o));
        }
        return new OidVector(oids);
      case 1028: // oid[]
      case 1009: // text[]
      case 1002: // "char"[]
        // {a,b} or {"",x}: catalog arrays hold no commas or quotes inside an element
        List<Object> elements = new ArrayList<>();
        String inside = text.substring(1, text.length() - 1);
        for (String e : inside.isEmpty() ? new String[0] : inside.split(",", -1)) {
          String element = e.replaceAll("^\"(.*)\"$", "$1");
          elements.add(type == 1028 ? (Object) Long.parseLong(element) : element);
        }
        return elements;
      default:
        return text;
    }
  }

  /** Every declared catalog table, qualified: pg_catalog.pg_class, information_schema.tables. */
  static List<String> declaredTables() {
    List<String> out = new ArrayList<>();
    PG_CATALOG.keySet().forEach(t -> out.add("pg_catalog." + t));
    INFORMATION_SCHEMA.keySet().forEach(t -> out.add("information_schema." + t));
    return out;
  }

  /** PostgreSQL 16's type of each catalog column, from catalog-types.txt, read on first use. */
  private static final class CatalogTypes {
    static final Map<String, Long> TYPES = new HashMap<>();
    static final Map<String, List<String>> COLUMNS = new HashMap<>(); // table -> its columns
    // table column -> PostgreSQL 16's atttypid, atttypmod, attnotnull, attcollation, attlen,
    // attndims, attstorage
    static final Map<String, String[]> ATTRIBUTES = new HashMap<>();
    // schema.table -> its oid and relkind in PostgreSQL 16
    static final Map<String, String[]> RELATIONS = new LinkedHashMap<>();
    // information_schema's domains: oid, name, base type, typmod, array type, namespace, collation,
    // default (empty for none)
    static final List<String[]> DOMAINS = new ArrayList<>();
    // their CHECK constraints: oid, name, domain, definition, pretty definition
    static final List<String[]> CHECKS = new ArrayList<>();

    static {
      try (java.io.BufferedReader in =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      Catalog.class.getResourceAsStream("catalog-types.txt"), "catalog-types.txt"),
                  java.nio.charset.StandardCharsets.UTF_8))) {
        for (String line = in.readLine(); line != null; line = in.readLine()) {
          String[] f = line.split(" ", -1);
          if (line.startsWith("@ ")) {
            RELATIONS.put(f[1], new String[] {f[2], f[3]});
          } else if (line.startsWith("% domain ")) {
            String[] d = Arrays.copyOf(Arrays.copyOfRange(f, 2, f.length), 8);
            d[7] = String.join(" ", Arrays.copyOfRange(f, 9, f.length)); // a default has spaces
            DOMAINS.add(d);
          } else if (line.startsWith("% check ")) {
            String[] definitions = String.join(" ", Arrays.copyOfRange(f, 5, f.length)).split("\t");
            CHECKS.add(new String[] {f[2], f[3], f[4], definitions[0], definitions[1]});
          } else if (!line.startsWith("#")) { // table column type-oid, then pg_attribute's facts
            TYPES.put(f[0] + " " + f[1], Long.parseLong(f[2]));
            COLUMNS.computeIfAbsent(f[0], k -> new ArrayList<>()).add(f[1]);
            ATTRIBUTES.put(f[0] + " " + f[1], Arrays.copyOfRange(f, 3, 10));
          }
        }
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
  }

  private static final Map<Long, Long> ARRAY_OIDS = new LinkedHashMap<>();
  private static final Set<Long> DOMAIN_OIDS = new HashSet<>(); // information_schema's domains

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
      {3802, 3807},
      {26, 1028},
      {18, 1002},
      {2950, 2951},
      {24, 1008},
      {2205, 2210},
      {2206, 2211},
      {4089, 4090},
      {2202, 2207}
    };
    for (long[] p : pairs) {
      ARRAY_OIDS.put(p[0], p[1]);
    }
    // information_schema's domains, which format_type and regtype name qualified
    for (String[] d : CatalogTypes.DOMAINS) {
      DOMAIN_OIDS.add(Long.parseLong(d[0]));
      TYPE_NAMES.put(Long.parseLong(d[0]), "information_schema." + d[1]);
      ARRAY_OIDS.put(Long.parseLong(d[0]), Long.parseLong(d[4]));
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

  /** information_schema's numeric_precision of a fixed numeric type: bits for binary ones. */
  @Nullable private static Long nativePrecision(long oid) {
    switch ((int) oid) {
      case 21:
        return 16L;
      case 23:
        return 32L;
      case 20:
        return 64L;
      case 700:
        return 24L;
      case 701:
        return 53L;
      default:
        return null;
    }
  }

  /** information_schema's numeric_scale: 0 for the integers, none for floats. */
  @Nullable private static Long nativeScale(long oid) {
    return oid == 21 || oid == 23 || oid == 20 ? 0L : null;
  }

  /** pg_type.typlen, which RowDescription reports too. */
  static long typeLength(long oid) {
    switch ((int) oid) {
      case 16:
      case 18:
        return 1;
      case 21:
        return 2;
      case 23:
      case 700:
      case 1082:
      case 26:
      case 24:
      case 2205:
      case 2206:
      case 4089:
      case 2202:
        return 4;
      case 20:
      case 701:
      case 1083:
      case 1114:
      case 1184:
        return 8;
      case 1186:
      case 2950:
        return 16;
      case 19:
        return 64;
      default:
        // the pseudo-types but the array ones are four bytes wide
        return PSEUDO_TYPES.contains(oid) && oid != 2277 && oid != 5078 ? 4 : -1;
    }
  }

  /** pg_type.typcollation: default for the string types, C for name, none for the others. */
  static long typeCollation(long oid) {
    return oid == 25 || oid == 1042 || oid == 1043 ? 100 : oid == 19 ? 950 : 0;
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
      case 194:
        return "Z";
      default:
        return PSEUDO_TYPES.contains(oid) ? "P" : "N";
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
  TypeStore typeStore = TypeStore.NONE; // the declared types of columns
  boolean clusterUsers; // pg_roles lists Cluster's users and roles, not the OWNER placeholder
  private boolean acls; // pg_class.relacl holds Cluster's grants: one call per grantee and table
  private boolean aclsLoaded;
  @Nullable private Map<String, List<Map<String, Object>>> tables;
  private final Map<Long, String> indexDefs = new HashMap<>();
  private final Map<Long, String> constraintDefs = new HashMap<>();
  // pg_get_constraintdef(oid, true), where it differs: information_schema's domain checks
  private final Map<Long, String> prettyConstraintDefs = new HashMap<>();
  private final Map<Long, List<String>> indexColumns = new HashMap<>();
  // "catalog oid" -> its comment: a table's keys, the internal language's
  private final Map<String, String> descriptions = new HashMap<>();
  private final Map<String, Long> relationOids = new HashMap<>(); // namespace.name -> oid
  private final Map<Long, String> relationNames = new HashMap<>(); // a user relation's oid -> name
  // the session's search_path as namespaces, in order (QueryParser.setSearchPath)
  List<String> searchPath;
  private final Map<String, Long> namespaceOids = new HashMap<>();

  Catalog(DistributedTransactionAdmin admin, String database) {
    this.admin = admin;
    this.database = database;
    this.searchPath = Collections.singletonList(database);
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
    long element = elementOid(oid);
    return name != null ? name : element != 0 ? typeName(element) + "[]" : "unknown";
  }

  /**
   * {@code format_type(oid, typmod)}: the type name with its modifier, as PostgreSQL prints it. A
   * typmod of -1 means "none given"; for {@code character} PostgreSQL then prints {@code bpchar},
   * while a NULL typmod (the one-argument form) prints {@code character}. An unknown OID is {@code
   * ???}.
   */
  static String typeName(long oid, @Nullable Long typmod) {
    long element = elementOid(oid);
    if (element != 0 && (typmod != null && typmod >= 0 || !TYPE_NAMES.containsKey(oid))) {
      return typeName(element, typmod) + "[]"; // integer[], character varying(20)[]
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
    relationNames.clear();
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

  /**
   * pg_table_is_visible: whether this relation is the one its bare name finds, the first of that
   * name on search_path. Catalog relations are, as pg_catalog is always searched.
   */
  @Nullable Boolean visible(long oid) throws ExecutionException {
    load();
    String name = relationNames.get(oid);
    if (name == null) {
      return CATALOG_OIDS.containsValue(oid) ? true : null; // NULL for no such relation
    }
    List<String> path = new ArrayList<>(searchPath);
    if (!path.contains("pg_catalog")) {
      path.add(0, "pg_catalog");
    }
    for (String namespace : path) {
      Long found =
          namespace.equals("pg_catalog")
              ? CATALOG_OIDS.get(name)
              : relationOids.get(namespace + "." + name);
      if (found != null) {
        return found == oid;
      }
    }
    return false;
  }

  /** current_schema: the first schema of search_path that exists, or NULL. */
  @Nullable Object currentSchema() {
    try {
      List<Object> schemas = schemas(false);
      return schemas.isEmpty() ? null : schemas.get(0);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * current_schemas: search_path's schemas that exist, as shown (the connected namespace as
   * public), with pg_catalog first when implicit ones are wanted and the path does not place it.
   */
  List<Object> schemas(boolean implicit) throws ExecutionException {
    load();
    List<Object> schemas = new ArrayList<>();
    if (implicit && !searchPath.contains("pg_catalog")) {
      schemas.add("pg_catalog");
    }
    for (String namespace : searchPath) {
      if (namespace.equals("pg_catalog")
          || namespace.equals(database)
          || namespaceOids.containsKey(namespace)) {
        schemas.add(namespace.equals("pg_catalog") ? namespace : schemaName(namespace));
      }
    }
    return schemas;
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
    List<String> parts = nameParts(name);
    if (parts.size() == 3 && parts.get(0).equals(database)) {
      parts = parts.subList(1, 3); // database.schema.name, of this database
    } else if (parts.size() > 2) {
      throw new PgException.Argument(
          parts.size() == 3 ? "0A000" : "42601",
          parts.size() == 3
              ? "cross-database references are not implemented: \"" + name + "\""
              : "improper relation name (too many dotted names): " + name);
    }
    String n = String.join(".", parts);
    Long oid;
    if (parts.size() == 2) {
      oid =
          parts.get(0).equals("pg_catalog") ? CATALOG_OIDS.get(parts.get(1)) : relationOids.get(n);
    } else {
      // pg_catalog first unless search_path places it, then search_path's namespaces in order;
      // a catalog table itself, as in pgjdbc's d.classoid = 'pg_class'::regclass
      oid = searchPath.contains("pg_catalog") ? null : CATALOG_OIDS.get(n);
      for (int i = 0; oid == null && i < searchPath.size(); i++) {
        String namespace = searchPath.get(i);
        oid =
            namespace.equals("pg_catalog")
                ? CATALOG_OIDS.get(n)
                : relationOids.get(namespace + "." + n);
      }
    }
    if (oid == null) {
      throw new PgException.Argument("42P01", "relation \"" + n + "\" does not exist");
    }
    return oid;
  }

  /**
   * A dotted name's parts as identifiers: unquoted ones folded to lower case, quoted ones as
   * written ({@code ""} for a quote inside).
   */
  static List<String> nameParts(String name) {
    List<String> parts = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    boolean quoted = false;
    boolean started = false; // the part has a character or a quote
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '"' && quoted && i + 1 < name.length() && name.charAt(i + 1) == '"') {
        part.append(c);
        i++;
      } else if (c == '"') {
        quoted = !quoted;
        started = true;
      } else if (quoted) {
        part.append(c);
      } else if (c == '.' && started) {
        parts.add(part.toString());
        part.setLength(0);
        started = false;
      } else if (c == '.') {
        throw new PgException.Argument("42602", "invalid name syntax");
      } else if (!Character.isWhitespace(c)) {
        part.append(Character.toLowerCase(c));
        started = true;
      }
    }
    if (quoted || !started) {
      throw new PgException.Argument("42602", "invalid name syntax");
    }
    parts.add(part.toString());
    return parts;
  }

  /** The OID of a namespace named as in a {@code regnamespace} cast. */
  long namespaceOid(String name) {
    try {
      load();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    String n = name.trim().replace("\"", "");
    Long oid = n.equals("pg_catalog") ? (Long) PG_CATALOG_NAMESPACE : namespaceOids.get(n);
    if (oid == null) {
      throw new PgException.Argument("3F000", "schema \"" + name + "\" does not exist");
    }
    return oid;
  }

  /** The OID of a type named as in a {@code regtype} cast, by internal or SQL name. */
  static long typeOid(String name) {
    String n = name.trim().toLowerCase(Locale.ROOT);
    n = n.startsWith("pg_catalog.") ? n.substring("pg_catalog.".length()) : n;
    if (n.endsWith("[]") || n.startsWith("_")) {
      // integer[], or the array type's own name, _int4
      long element = typeOid(n.endsWith("[]") ? n.substring(0, n.length() - 2) : n.substring(1));
      if (ARRAY_OIDS.containsKey(element)) {
        return ARRAY_OIDS.get(element);
      }
      throw new PgException.Argument("42704", "type \"" + name + "\" does not exist");
    }
    if (n.equals("char")) {
      return 1042; // char is character(1); the one-byte type is spelled "char"
    }
    for (Map.Entry<Long, String> e : INTERNAL_TYPE_NAMES.entrySet()) {
      if (e.getValue().equals(n) || n.equals(TYPE_NAMES.get(e.getKey()))) {
        return e.getKey();
      }
    }
    for (Map.Entry<Long, String> e : TYPE_NAMES.entrySet()) {
      if (e.getValue().equals(n)) {
        return e.getKey(); // information_schema.sql_identifier
      }
    }
    throw new PgException.Argument("42704", "type \"" + name + "\" does not exist");
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

  /** obj_description: a table's key columns, or the internal language's comment. */
  @Nullable String description(long oid, String catalog) throws ExecutionException {
    load();
    return descriptions.get(catalog + " " + oid);
  }

  @Nullable String constraintDef(long oid, boolean pretty) throws ExecutionException {
    load();
    return pretty
        ? prettyConstraintDefs.getOrDefault(oid, constraintDefs.get(oid))
        : constraintDefs.get(oid);
  }

  /** The rows of a catalog table, with keys qualified as {@code qualifier.column}. */
  List<Map<String, Object>> rows(@Nullable String schema, String name, String qualifier)
      throws ExecutionException {
    load();
    List<Map<String, Object>> out = new ArrayList<>();
    String table = qualifiedName(schema, name);
    for (Map<String, Object> row :
        table.equals("pg_catalog.pg_settings")
            ? settings() // the session's, as it is now
            : Objects.requireNonNull(tables, "loaded above")
                .getOrDefault(table, Collections.emptyList())) {
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

  /** pg_settings: each setting as the session running on this thread has it. */
  private static List<Map<String, Object>> settings() {
    Evaluator.Settings session = Evaluator.settings();
    Map<String, List<Map<String, Object>>> t = new HashMap<>();
    for (String[] s : SETTINGS) {
      String value = s[1];
      String source = "default";
      String reset = s[1];
      if (session != null) {
        value = Objects.requireNonNullElse(session.get(s[0]), s[1]);
        source = session.source(s[0]);
        reset = session.resetValue(s[0]);
      }
      row(
          t,
          "pg_catalog.pg_settings",
          s[0],
          inUnit(s, value),
          s[4],
          s[2],
          s[3],
          source,
          s[1],
          inUnit(s, reset),
          s[5]);
    }
    return Objects.requireNonNull(t.get("pg_catalog.pg_settings"));
  }

  /** pg_settings.setting: a value in the setting's unit, as SHOW's "5s" is 5000 in ms. */
  private static String inUnit(String[] setting, String value) {
    // statement_timeout is the only setting with a unit
    return setting[4] == null ? value : String.valueOf(QueryProcessor.millis(value));
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
    String icu = Evaluator.icuCollationName();
    // the collations COLLATE accepts (Evaluator.checkCollation), with PostgreSQL 16's rows:
    // default, which is ScalarDB's, and byte order's three names; under ICU, default and the ICU
    // collation
    row(
        t,
        "pg_catalog.pg_collation",
        100L,
        "default",
        11L,
        "d",
        icu.isEmpty(),
        -1L,
        null,
        null,
        null,
        null,
        null);
    if (icu.isEmpty()) {
      for (Object[] c :
          new Object[][] {{950L, "C", -1L}, {951L, "POSIX", -1L}, {962L, "ucs_basic", 6L}}) {
        String locale = c[1].equals("POSIX") ? "POSIX" : "C";
        row(
            t,
            "pg_catalog.pg_collation",
            c[0],
            c[1],
            11L,
            "c",
            true,
            c[2],
            locale,
            locale,
            null,
            null,
            null);
      }
    } else {
      String locale = icu.substring(0, icu.length() - "-x-icu".length());
      row(
          t,
          "pg_catalog.pg_collation",
          ICU_COLLATION_OID,
          icu,
          11L, // pg_catalog
          "i",
          false, // ICU equality is many-to-one
          -1L,
          null,
          null,
          locale,
          locale,
          null);
    }
    row(t, "pg_catalog.pg_am", HEAP_AM, "heap", "t", "heap_tableam_handler");
    row(t, "pg_catalog.pg_am", BTREE_AM, "btree", "i", "bthandler");
    // the default btree operator class of each column type, with PostgreSQL 16's oids
    Set<Long> families = new HashSet<>();
    for (long[] c : OPCLASSES) {
      row(
          t,
          "pg_catalog.pg_opclass",
          c[1],
          BTREE_AM,
          OPCLASS_NAMES.get(c[1]),
          PG_CATALOG_NAMESPACE,
          OWNER_OID,
          c[2],
          c[0],
          true,
          0L);
      if (families.add(c[2])) {
        row(
            t,
            "pg_catalog.pg_opfamily",
            c[2],
            BTREE_AM,
            OPFAMILY_NAMES.get(c[2]),
            PG_CATALOG_NAMESPACE,
            OWNER_OID);
      }
    }
    // the language of the built-in functions; there is no procedural language
    row(t, "pg_catalog.pg_language", 12L, "internal", OWNER_OID, false, false, 0L, 0L, 2246L, null);
    row(t, "pg_catalog.pg_description", 12L, 2612L, 0L, "built-in functions");
    descriptions.put("pg_language 12", "built-in functions");
    row(t, "pg_catalog.pg_tablespace", DEFAULT_TABLESPACE, "pg_default", OWNER_OID, null, null);
    row(t, "pg_catalog.pg_tablespace", 1664L, "pg_global", OWNER_OID, null, null);
    // the schema of the built-in types, which drivers join pg_type with
    row(t, "pg_catalog.pg_namespace", PG_CATALOG_NAMESPACE, "pg_catalog", OWNER_OID, null);
    row(
        t,
        "pg_catalog.pg_namespace",
        INFORMATION_SCHEMA_OID,
        "information_schema",
        OWNER_OID,
        null);
    namespaceOids.put("information_schema", INFORMATION_SCHEMA_OID);
    row(t, "information_schema.schemata", database, "pg_catalog");
    row(t, "information_schema.schemata", database, "information_schema");
    catalogRelations(t);
    domains(t);
    if (!clusterUsers) {
      role(t, OWNER_OID, OWNER, true, true);
    }
    timezoneNames(t);
    for (Map.Entry<String, List<Map<String, String>>> b : BUILTIN_ROWS.entrySet()) {
      String table = b.getKey();
      String name = table.substring(table.indexOf('.') + 1);
      List<String> columns = Objects.requireNonNull(CatalogTypes.COLUMNS.get(table));
      for (Map<String, String> r : b.getValue()) {
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
          // information_schema's *_catalog columns name the database PostgreSQL 16 ran in; here,
          // this one
          String column = columns.get(i);
          values[i] =
              table.startsWith("information_schema.")
                      && column.endsWith("_catalog")
                      && r.get(column) != null
                  ? database
                  : catalogValue(table, column, r.get(column));
        }
        row(t, table, values);
        if (r.get("description") != null) { // pg_proc's and pg_operator's, which psql shows
          row(
              t,
              "pg_catalog.pg_description",
              Long.parseLong(Builtins.field(r, "oid")),
              CATALOG_OIDS.get(name),
              0L,
              r.get("description"));
          descriptions.put(name + " " + Builtins.field(r, "oid"), r.get("description"));
        }
      }
    }
    for (Map.Entry<Long, String> type : INTERNAL_TYPE_NAMES.entrySet()) {
      long oid = type.getKey();
      String name = type.getValue();
      long arrayOid = ARRAY_OIDS.getOrDefault(oid, 0L);
      boolean pseudo = PSEUDO_TYPES.contains(oid);
      // pg_node_tree and the pseudo-types name their I/O functions with an underscore
      String io = pseudo || oid == 194 ? name + "_" : name;
      // the polymorphic array types are aligned as any array may need
      boolean anyArray = oid == 2277 || oid == 5078;
      row(
          t,
          "pg_catalog.pg_type",
          oid,
          name,
          PG_CATALOG_NAMESPACE,
          typeCollation(oid),
          // typelem: int2vector and oidvector are subscripted as int2 and oid arrays
          oid == 22 ? 21L : oid == 30 ? 26L : 0L,
          ",",
          io + "in",
          pseudo ? "p" : "b",
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
          anyArray ? "d" : typeAlign(typeLength(oid)),
          typeLength(oid) > 0 ? "p" : "x",
          0L,
          false,
          io + "out",
          pseudo && !anyArray ? "-" : io + "recv",
          pseudo && !anyArray ? "-" : io + "send",
          "-",
          "-",
          "-",
          null);
      if (arrayOid != 0) {
        // the array type of each base type, which drivers look up by typelem
        arrayType(
            t, arrayOid, name, PG_CATALOG_NAMESPACE, typeCollation(oid), oid, typeLength(oid));
      }
    }
    Map<String, Boolean> grantees = acls ? grantees() : Collections.emptyMap();
    long oid = FIRST_USER_OID;
    for (String namespace : new TreeSet<>(admin.getNamespaceNames())) {
      if (namespace.equals("scalardb")
          || namespace.equals("coordinator")
          || namespace.equals(TypeStore.NAMESPACE)) {
        continue; // ScalarDB's own namespaces, and the frontend's
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
          icu.isEmpty() ? "c" : "i",
          "C",
          "C",
          icu.isEmpty() ? null : icu.substring(0, icu.length() - "-x-icu".length()),
          icu.isEmpty() ? null : icu.substring(0, icu.length() - "-x-icu".length()),
          null,
          null,
          DEFAULT_TABLESPACE,
          true,
          false,
          -1L);
      row(t, "information_schema.schemata", database, schema);
      boolean typed = typeStore.exists(); // asked once, not per table, while there is no table
      for (String table : new TreeSet<>(admin.getNamespaceTableNames(namespace))) {
        TableMetadata stored = admin.getTableMetadata(namespace, table);
        if (stored == null) {
          continue;
        }
        // columns with their declared types (numeric(10,2), not the bigint it is stored as)
        ColumnMap columnMap =
            ColumnMap.of(
                namespace,
                table,
                stored,
                typed ? typeStore.read(namespace, table, stored) : Collections.emptyMap());
        TableMetadata metadata = columnMap.metadata();
        long relOid = oid++;
        // as CREATE TABLE ... WITH (encrypted = '...') set them, for \d+ to show
        List<String> encrypted = new ArrayList<>();
        for (String column : metadata.getColumnNames()) {
          if (metadata.getEncryptedColumnNames().contains(column)) {
            encrypted.add(column);
          }
        }
        relation(
            t,
            relOid,
            table,
            namespaceOid,
            "r",
            HEAP_AM,
            true,
            grantees.isEmpty() ? null : acl(grantees, namespaceGrants, namespace, table),
            encrypted.isEmpty()
                ? null
                : Collections.singletonList("encrypted=" + String.join(",", encrypted)));
        relationOids.put(namespace + "." + table, relOid);
        relationNames.put(relOid, table);
        relationOids.put(schema + "." + table, relOid);
        row(t, "pg_catalog.pg_statio_all_tables", relOid, schema, table);
        row(t, "pg_catalog.pg_tables", schema, table, OWNER, null, true, false, false, false);
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
        Map<String, Object> columnCollations = new HashMap<>(); // for pg_index.indcollation
        Map<String, Object> columnOpclasses = new HashMap<>(); // for pg_index.indclass
        Map<String, Map<String, Object>> attributes = new HashMap<>(); // for an index's columns
        int attnum = 0;
        for (String column : metadata.getColumnNames()) {
          attnum++;
          DataType type = metadata.getColumnDataType(column);
          ColumnType declared = columnMap.type(column);
          // the declared type, as format_type shows it: numeric(10,2), not the bigint it is
          // stored as; character varying(20), not text
          boolean numeric = declared.isNumeric();
          boolean bounded = numeric && declared.precision != 0; // numeric alone has neither
          boolean characters =
              declared.kind == ColumnType.Kind.VARCHAR || declared.kind == ColumnType.Kind.CHAR;
          long typeOid = declared.encoded() ? declared.oid() : typeOid(type);
          long typmod = declared.encoded() ? declared.typmod() : -1L;
          boolean key = keys.contains(column);
          // the type's, or ScalarDB's ICU collation, which psql's \d shows as the column's
          long collation =
              typeCollation(typeOid) != 0 && !Evaluator.icuCollationName().isEmpty()
                  ? ICU_COLLATION_OID
                  : typeCollation(typeOid);
          columnCollations.put(column, collation);
          columnOpclasses.put(column, defaultOpclass(typeOid));
          attributes.put(
              column,
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
                  collation,
                  "",
                  "",
                  "p",
                  "",
                  -1L,
                  null,
                  typeLength(typeOid),
                  0L,
                  0L,
                  true));
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
              characters ? (Object) (long) declared.length : null,
              null,
              bounded ? (Object) (long) declared.precision : nativePrecision(typeOid),
              typeOid == 1700 ? (Object) 10L : nativePrecision(typeOid) != null ? 2L : null,
              bounded ? (Object) (long) declared.scale : nativeScale(typeOid),
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
        descriptions.put("pg_class " + relOid, description);
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
          row(
              t,
              "information_schema.constraint_column_usage",
              database,
              schema,
              table,
              keys.get(k),
              database,
              schema,
              table + "_pkey");
        }
        long pkeyOid = oid++;
        relation(t, pkeyOid, table + "_pkey", namespaceOid, "i", BTREE_AM, false, null, null);
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
            true,
            (long) keys.size(),
            (long) keys.size(),
            new OidVector(map(keys, columnCollations)),
            new OidVector(map(keys, columnOpclasses)),
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
        indexAttributes(t, pkeyOid, keys, attributes);
        relationOids.put(namespace + "." + table + "_pkey", pkeyOid);
        relationNames.put(pkeyOid, table + "_pkey");
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
        // pg_depend, as PostgreSQL records them: the table on its namespace, the key on its
        // columns, the key's index on the key, and a secondary index on its column
        row(
            t,
            "pg_catalog.pg_depend",
            PG_CLASS_OID,
            relOid,
            0L,
            PG_NAMESPACE_OID,
            namespaceOid,
            0L,
            "n");
        for (Object keyNumber : keyNumbers) {
          row(
              t,
              "pg_catalog.pg_depend",
              PG_CONSTRAINT_OID,
              constraintOid,
              0L,
              PG_CLASS_OID,
              relOid,
              keyNumber,
              "a");
        }
        row(
            t,
            "pg_catalog.pg_depend",
            PG_CLASS_OID,
            pkeyOid,
            0L,
            PG_CONSTRAINT_OID,
            constraintOid,
            0L,
            "i");
        for (String index : new TreeSet<>(metadata.getSecondaryIndexNames())) {
          long indexOid = oid++;
          String indexName = table + "_" + index + "_idx";
          relation(t, indexOid, indexName, namespaceOid, "i", BTREE_AM, false, null, null);
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
              true,
              1L,
              1L,
              new OidVector(map(Collections.singletonList(index), columnCollations)),
              new OidVector(map(Collections.singletonList(index), columnOpclasses)),
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
          indexAttributes(t, indexOid, Collections.singletonList(index), attributes);
          row(
              t,
              "pg_catalog.pg_depend",
              PG_CLASS_OID,
              indexOid,
              0L,
              PG_CLASS_OID,
              relOid,
              indexAttnum,
              "a");
          relationOids.put(namespace + "." + indexName, indexOid);
          relationNames.put(indexOid, indexName);
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
    if (login) {
      row(
          t,
          "pg_catalog.pg_user",
          name,
          oid,
          false,
          superuser,
          false,
          false,
          "********",
          null,
          null);
    }
  }

  /**
   * pg_timezone_names: the zones SET TimeZone takes by name, with their abbreviation and offset
   * now.
   */
  private static void timezoneNames(Map<String, List<Map<String, Object>>> t) {
    java.time.Instant now = java.time.Instant.now();
    for (String name : new TreeSet<>(java.time.ZoneId.getAvailableZoneIds())) {
      java.time.zone.ZoneRules rules = java.time.ZoneId.of(name).getRules();
      boolean dst = rules.isDaylightSavings(now);
      // CLDR's abbreviation, as Java names it (tzdata, which PostgreSQL reads, has dropped some
      // of them: ART where it says -03); one with no name is the offset, +0530 for GMT+05:30
      String abbrev =
          java.util.TimeZone.getTimeZone(name)
              .getDisplayName(dst, java.util.TimeZone.SHORT, Locale.US)
              .replaceFirst("^GMT([+-]\\d\\d):?(?:00|(\\d\\d))$", "$1$2");
      long seconds = rules.getOffset(now).getTotalSeconds();
      row(
          t,
          "pg_catalog.pg_timezone_names",
          name,
          abbrev,
          new Interval(0, 0, seconds * 1_000_000L),
          dst);
    }
  }

  /** An index's pg_attribute rows: its key columns' rows, renumbered, which allow NULL. */
  private static void indexAttributes(
      Map<String, List<Map<String, Object>>> t,
      long indexOid,
      List<String> columns,
      Map<String, Map<String, Object>> attributes) {
    for (int i = 0; i < columns.size(); i++) {
      Map<String, Object> attribute =
          new LinkedHashMap<>(Objects.requireNonNull(attributes.get(columns.get(i))));
      attribute.put("attrelid", indexOid);
      attribute.put("attnum", (long) i + 1);
      attribute.put("attnotnull", false);
      Objects.requireNonNull(t.get("pg_catalog.pg_attribute")).add(attribute);
    }
  }

  /**
   * The catalog relations, as the frontend declares them: pg_class rows with PostgreSQL 16's oids
   * and kinds, and pg_attribute rows for their columns, in the order SELECT * gives them. Where the
   * frontend declares fewer columns than PostgreSQL 16, as information_schema.columns, attnum
   * numbers the frontend's columns, not PostgreSQL's.
   */
  private void catalogRelations(Map<String, List<Map<String, Object>>> t) {
    for (String schema : Arrays.asList("pg_catalog", "information_schema")) {
      Map<String, List<String>> declared =
          schema.equals("pg_catalog") ? PG_CATALOG : INFORMATION_SCHEMA;
      long namespace = schema.equals("pg_catalog") ? PG_CATALOG_NAMESPACE : INFORMATION_SCHEMA_OID;
      for (Map.Entry<String, List<String>> d : declared.entrySet()) {
        String qualified = schema + "." + d.getKey();
        String[] relation = CatalogTypes.RELATIONS.get(qualified);
        if (relation == null) {
          continue; // a name PostgreSQL 16 does not have
        }
        long oid = Long.parseLong(relation[0]);
        String kind = relation[1];
        relation(
            t,
            oid,
            d.getKey(),
            namespace,
            kind,
            kind.equals("r") ? HEAP_AM : 0L,
            false,
            CATALOG_ACL,
            null);
        if (schema.equals("information_schema")) {
          relationOids.put(qualified, oid);
          relationNames.put(oid, d.getKey());
        }
        if (kind.equals("r")) {
          // tableoid, the system column the catalog tables have here; views have none
          row(
              t,
              "pg_catalog.pg_attribute",
              oid,
              -6L,
              TABLEOID,
              26L,
              -1L,
              true,
              false,
              false,
              0L,
              "",
              "",
              "p",
              "",
              -1L,
              null,
              4L,
              0L,
              0L,
              true);
          row(
              t,
              "pg_catalog.pg_tables",
              schema,
              d.getKey(),
              OWNER,
              null,
              false,
              false,
              false,
              false);
        } else {
          // ponytail: PostgreSQL's definition is not this frontend's, which is code; NULL says so
          row(t, "pg_catalog.pg_views", schema, d.getKey(), OWNER, null);
          row(
              t,
              "information_schema.views",
              database,
              schema,
              d.getKey(),
              null,
              "NONE",
              "NO",
              "NO",
              "NO",
              "NO",
              "NO");
        }
        row(
            t,
            "information_schema.tables",
            database,
            schema,
            d.getKey(),
            kind.equals("r") ? "BASE TABLE" : "VIEW");
        for (int i = 0; i < d.getValue().size(); i++) {
          String column = d.getValue().get(i);
          String[] pg = CatalogTypes.ATTRIBUTES.get(qualified + " " + column);
          long base = catalogColumnOid(qualified, column);
          long typeOid = base == 0 ? 25 : base;
          // PostgreSQL 16's attribute where its type is the one described or information_schema's
          // domain over it; else the type described, as for regproc's, which is text here
          long pgType = pg == null ? 0 : Long.parseLong(pg[0]);
          if (pg == null || pgType != typeOid && !DOMAIN_OIDS.contains(pgType)) {
            long length = typeLength(typeOid);
            pg =
                new String[] {
                  String.valueOf(typeOid),
                  "-1",
                  pg == null ? "f" : pg[2],
                  String.valueOf(typeCollation(typeOid)),
                  String.valueOf(length),
                  elementOid(typeOid) != 0 ? "1" : "0",
                  length > 0 ? "p" : "x"
                };
          }
          boolean notNull = "t".equals(pg[2]);
          row(
              t,
              "pg_catalog.pg_attribute",
              oid,
              (long) i + 1,
              column,
              Long.parseLong(pg[0]),
              Long.parseLong(pg[1]),
              notNull,
              false,
              false,
              Long.parseLong(pg[3]),
              "",
              "",
              pg[6],
              "",
              -1L,
              null,
              Long.parseLong(pg[4]),
              Long.parseLong(pg[5]),
              0L,
              true);
          row(
              t,
              "information_schema.columns",
              database,
              schema,
              d.getKey(),
              column,
              (long) i + 1,
              elementOid(typeOid) != 0 ? "ARRAY" : typeName(typeOid),
              notNull ? "NO" : "YES",
              INTERNAL_TYPE_NAMES.getOrDefault(
                  typeOid,
                  elementOid(typeOid) != 0
                      ? "_" + INTERNAL_TYPE_NAMES.get(elementOid(typeOid))
                      : null),
              null,
              null,
              nativePrecision(typeOid),
              nativePrecision(typeOid) != null ? 2L : null,
              nativeScale(typeOid),
              null);
        }
      }
    }
  }

  /** information_schema's domains, their array types and checks, as PostgreSQL 16 has them. */
  private void domains(Map<String, List<Map<String, Object>>> t) {
    for (String[] d : CatalogTypes.DOMAINS) {
      long oid = Long.parseLong(d[0]);
      long base = Long.parseLong(d[2]);
      long array = Long.parseLong(d[4]);
      long collation = Long.parseLong(d[6]);
      long length = typeLength(base);
      row(
          t,
          "pg_catalog.pg_type",
          oid,
          d[1],
          INFORMATION_SCHEMA_OID,
          collation,
          0L,
          ",",
          "domain_in",
          "d",
          base,
          array,
          length,
          typeCategory(base),
          0L,
          false,
          Long.parseLong(d[3]),
          OWNER_OID,
          d[7].isEmpty() ? null : d[7],
          length > 0,
          typeAlign(length),
          length > 0 ? "p" : "x",
          0L,
          false,
          INTERNAL_TYPE_NAMES.get(base) + "out",
          "domain_recv",
          INTERNAL_TYPE_NAMES.get(base) + "send",
          "-",
          "-",
          "-",
          null);
      arrayType(t, array, d[1], INFORMATION_SCHEMA_OID, collation, oid, length);
    }
    for (String[] c : CatalogTypes.CHECKS) {
      long oid = Long.parseLong(c[0]);
      row(
          t,
          "pg_catalog.pg_constraint",
          oid,
          c[1],
          "c",
          0L,
          0L,
          false,
          false,
          false,
          0L,
          0L,
          null,
          false,
          true,
          0L,
          true,
          Long.parseLong(c[2]),
          null,
          " ",
          " ",
          " ",
          INFORMATION_SCHEMA_OID);
      constraintDefs.put(oid, c[3]);
      prettyConstraintDefs.put(oid, c[4]);
    }
  }

  /** The pg_type row of the array type of an element type, aligned as the element's length. */
  private static void arrayType(
      Map<String, List<Map<String, Object>>> t,
      long oid,
      String elementName,
      long namespace,
      long collation,
      long element,
      long elementLength) {
    row(
        t,
        "pg_catalog.pg_type",
        oid,
        "_" + elementName,
        namespace,
        collation,
        element,
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
        typeAlign(elementLength),
        "x",
        0L,
        false,
        "array_out",
        "array_recv",
        "array_send",
        "-",
        "-",
        "array_typanalyze",
        null);
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
      @Nullable List<String> acl,
      @Nullable List<String> options) {
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
        options,
        null,
        -1.0,
        acl,
        0L,
        0L,
        false);
  }

  private static Map<String, Object> row(
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
    return row;
  }
}
