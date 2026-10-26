package com.scalar.db.frontend.postgres;

import com.scalar.db.api.AuthAdmin;
import com.scalar.db.exception.storage.ExecutionException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.sf.jsqlparser.schema.Table;

/**
 * CREATE, ALTER and DROP of users and roles, and GRANT and REVOKE, onto ScalarDB Cluster's {@link
 * AuthAdmin}. The statements run under the session's identity like any DDL, so Cluster decides who
 * may run them. Privileges mean what they mean in ScalarDB SQL ({@code DclStatementExecutor}), with
 * the same checks and messages, so one GRANT means the same in both products.
 *
 * <p>Statements here can hold a password: no message may quote a string literal or the statement.
 */
final class Dcl {
  /** What this class plans, matched on the trimmed statement before JSQLParser sees it. */
  static final Pattern STATEMENT =
      Pattern.compile("(?is)(GRANT|REVOKE|(CREATE|ALTER|DROP)\\s+(USER|ROLE))\\b.*");

  /**
   * Whether this class plans the trimmed statement. Leading comments are skipped, nested ones too:
   * a statement that slipped past here would reach JSQLParser, whose errors quote the statement,
   * password included. So any Unicode whitespace is skipped as well, more than PostgreSQL's six
   * characters: the lexer here then refuses it without quoting anything.
   */
  static boolean matches(String sql) {
    int i = QueryParser.skipSpace(sql, 0);
    while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
      i = QueryParser.skipSpace(sql, i + 1);
    }
    return STATEMENT.matcher(sql.substring(i)).matches();
  }

  /** The privileges as ScalarDB SQL names them; GRANT is the grant option. */
  enum Privilege {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    CREATE,
    DROP,
    TRUNCATE,
    ALTER,
    GRANT
  }

  private static final Set<Privilege> ALL = EnumSet.complementOf(EnumSet.of(Privilege.GRANT));

  private final QueryParser parser;
  private final AuthAdmin admin;
  private final Lexer in;

  private Dcl(QueryParser parser, AuthAdmin admin, String sql) {
    this.parser = parser;
    this.admin = admin;
    this.in = new Lexer(sql);
  }

  /** Plans a statement {@link #STATEMENT} matched. */
  static Plan plan(QueryParser parser, AuthAdmin admin, String sql) {
    return new Dcl(parser, admin, sql).plan();
  }

  private Plan plan() {
    String verb = in.word();
    switch (verb) {
      case "CREATE":
        return create();
      case "ALTER":
        return alter();
      case "DROP":
        return drop();
      default:
        return grantOrRevoke(verb.equals("GRANT"));
    }
  }

  // ---- users and roles ----

  /** The options of CREATE and ALTER: null where the statement does not say. */
  private static final class Options {
    @Nullable String password; // "" deletes it
    @Nullable Boolean superuser;
    @Nullable Boolean login;
    final List<String> notices = new ArrayList<>();

    AuthAdmin.UserOption[] userOptions() {
      return superuser == null
          ? new AuthAdmin.UserOption[0]
          : new AuthAdmin.UserOption[] {
            superuser ? AuthAdmin.UserOption.SUPERUSER : AuthAdmin.UserOption.NO_SUPERUSER
          };
    }
  }

  private Options options(String statement) {
    Options options = new Options();
    in.accept("WITH");
    while (!in.atEnd()) {
      String option = in.word();
      switch (option) {
        case "ENCRYPTED":
          in.expect("PASSWORD");
          options.password = password(options);
          break;
        case "PASSWORD":
          options.password = password(options);
          break;
        case "SUPERUSER":
        case "NOSUPERUSER":
          options.superuser = option.equals("SUPERUSER");
          break;
        case "LOGIN":
        case "NOLOGIN":
          options.login = option.equals("LOGIN");
          break;
        default:
          throw unsupported(statement + " option " + option);
      }
    }
    return options;
  }

  private String password(Options options) {
    if (in.accept("NULL")) {
      return "";
    }
    String password = in.string();
    if (password.isEmpty()) {
      options.notices.add("empty string is not a valid password, clearing password");
    } else if (password.startsWith("SCRAM-SHA-256$") || password.matches("md5[0-9a-f]{32}")) {
      // psql's \password and pgjdbc's alterUserPassword hash on the client; Cluster checks the
      // password itself, so it needs the password
      throw unsupported(
          "a pre-hashed password (psql's \\password sends one): use ALTER USER name PASSWORD"
              + " 'password', which the session's TLS protects,");
    }
    return password;
  }

  private Plan create() {
    boolean user = in.word().equals("USER");
    String name = in.name();
    Options options = options("CREATE ROLE");
    // CREATE USER is CREATE ROLE ... LOGIN; a role that logs in is a ScalarDB user, one that does
    // not is a ScalarDB role
    boolean login = options.login != null ? options.login : user;
    if (!login && (options.password != null || Boolean.TRUE.equals(options.superuser))) {
      throw unsupported(
          "a password or SUPERUSER for a role without LOGIN (a ScalarDB role cannot log in):"
              + " CREATE USER creates one that can");
    }
    String password =
        options.password == null || options.password.isEmpty() ? null : options.password;
    return ddl(
        "CREATE ROLE",
        options.notices,
        notices -> {
          if (login) {
            admin.createUser(name, password, options.userOptions());
          } else {
            admin.createRole(name);
          }
        });
  }

  private Plan alter() {
    in.word(); // USER or ROLE
    String name = role();
    if (in.atEnd()) {
      throw in.syntaxError();
    }
    Options options = options("ALTER ROLE");
    if (options.login != null) {
      throw unsupported("ALTER ROLE option " + (options.login ? "LOGIN" : "NOLOGIN"));
    }
    return ddl(
        "ALTER ROLE",
        options.notices,
        notices -> admin.alterUser(name, options.password, options.userOptions()));
  }

  private Plan drop() {
    in.word(); // USER or ROLE, which mean the same, as in PostgreSQL
    boolean ifExists = in.accept("IF");
    if (ifExists) {
      in.expect("EXISTS");
    }
    List<String> names = new ArrayList<>();
    do {
      names.add(in.name());
    } while (in.accept(","));
    in.end();
    return ddl(
        "DROP ROLE",
        new ArrayList<>(),
        notices -> {
          // ponytail: one at a time, not atomic as in PostgreSQL; Cluster has no batch
          for (String name : names) {
            if (admin.getUser(name).isPresent()) {
              admin.dropUser(name);
            } else if (admin.getRole(name).isPresent()) {
              admin.dropRole(name);
            } else if (ifExists) {
              notices.add("role \"" + name + "\" does not exist, skipping");
            } else {
              throw missingRole(name);
            }
          }
        });
  }

  // ---- GRANT and REVOKE ----

  private Plan grantOrRevoke(boolean grant) {
    String optionOnly = ""; // REVOKE GRANT OPTION FOR or ADMIN OPTION FOR: GRANT or ADMIN
    if (!grant && (in.peek("GRANT") || in.peek("ADMIN")) && in.peekAt(1, "OPTION")) {
      optionOnly = in.word();
      in.word();
      in.expect("FOR");
    }
    // Privileges before ON, or roles before TO or FROM
    List<Lexer.Token> items = new ArrayList<>();
    do {
      items.add(in.next());
      if (in.peek("PRIVILEGES")) {
        in.word(); // ALL PRIVILEGES
      }
      if (in.peek("(")) {
        throw unsupported("a column list in GRANT or REVOKE (ScalarDB grants whole tables)");
      }
    } while (in.accept(","));
    boolean on = in.accept("ON");
    if (!optionOnly.isEmpty() && optionOnly.equals("GRANT") != on) {
      throw unsupported(optionOnly + " OPTION FOR " + (on ? "privileges" : "roles"));
    }
    return on
        ? privileges(grant, !optionOnly.isEmpty(), items)
        : roles(grant, !optionOnly.isEmpty(), items);
  }

  /** A target of a privilege GRANT: a table, a namespace's tables, or a namespace itself. */
  private static final class Target {
    final char kind; // 't'able, 'a'll tables in schema, 's'chema
    @Nullable final String schema; // as written, quotes included
    final String name; // as written; for 'a' and 's', the schema

    Target(char kind, @Nullable String schema, String name) {
      this.kind = kind;
      this.schema = schema;
      this.name = name;
    }
  }

  private Plan privileges(boolean grant, boolean revokeOptionOnly, List<Lexer.Token> items) {
    char kind;
    if (in.accept("ALL")) {
      in.expect("TABLES");
      in.expect("IN");
      in.expect("SCHEMA");
      kind = 'a';
    } else if (in.accept("SCHEMA")) {
      kind = 's';
    } else {
      for (String other :
          new String[] {
            "SEQUENCE",
            "FUNCTION",
            "PROCEDURE",
            "ROUTINE",
            "DATABASE",
            "DOMAIN",
            "FOREIGN",
            "LANGUAGE",
            "LARGE",
            "TABLESPACE",
            "TYPE",
            "PARAMETER"
          }) {
        if (in.peek(other)) {
          throw unsupported("GRANT and REVOKE on " + other);
        }
      }
      in.accept("TABLE");
      kind = 't';
    }
    List<Target> targets = new ArrayList<>();
    do {
      String first = in.raw();
      if (kind == 't' && in.accept(".")) {
        targets.add(new Target(kind, first, in.raw()));
      } else {
        targets.add(new Target(kind, null, first));
      }
    } while (in.accept(","));

    Set<Privilege> privileges = EnumSet.noneOf(Privilege.class);
    boolean usage = false;
    for (Lexer.Token item : items) {
      String word = item.word();
      if (word.equals("ALL")) {
        privileges.addAll(kind == 's' ? EnumSet.of(Privilege.CREATE) : ALL);
      } else if (word.equals("USAGE") && kind == 's') {
        usage = true;
      } else if (word.equals("CREATE") && kind == 's') {
        privileges.add(Privilege.CREATE);
      } else if (kind == 's') {
        throw new PgException.Argument("0LP01", "invalid privilege type " + word + " for schema");
      } else if (isPrivilege(word) && !word.equals("GRANT")) {
        privileges.add(Privilege.valueOf(word));
      } else {
        throw unsupported("the privilege " + item.describe());
      }
    }
    if (revokeOptionOnly) {
      // ScalarDB has one grant option, not one per privilege: REVOKE GRANT OPTION FOR revokes it
      // and leaves the privileges
      privileges.clear();
      privileges.add(Privilege.GRANT);
    }

    List<String> grantees = grantees(grant);
    if (grant && in.accept("WITH")) {
      in.expect("GRANT");
      in.expect("OPTION");
      privileges.add(Privilege.GRANT);
    }
    endGrant(grant);
    boolean noticeUsage = usage;
    return ddl(
        grant ? "GRANT" : "REVOKE",
        new ArrayList<>(),
        notices -> {
          if (noticeUsage) {
            notices.add("USAGE has no ScalarDB counterpart (nothing needs it): ignored");
          }
          for (String grantee : grantees) {
            boolean user = isUser(grantee);
            for (Target target : targets) {
              String namespace;
              @Nullable String table = null;
              if (target.kind == 't') {
                QueryParser.Target resolved = parser.target(new Table(target.schema, target.name));
                namespace = resolved.namespace;
                table = resolved.table;
              } else {
                namespace = parser.namespace(target.name);
              }
              if (privileges.isEmpty()) {
                continue; // USAGE only
              }
              Set<Privilege> held =
                  revokeOptionOnly
                      ? EnumSet.noneOf(Privilege.class)
                      : sqlPrivileges(held(user, grantee, namespace, table));
              if (grant) {
                checkGrant(privileges, held);
              } else if (!revokeOptionOnly) {
                checkRevoke(privileges, held);
              }
              AuthAdmin.Privilege[] scalar = grant ? toGrant(privileges) : toRevoke(privileges);
              apply(grant, user, grantee, namespace, table, scalar);
            }
          }
        });
  }

  private static boolean isPrivilege(String word) {
    for (Privilege p : Privilege.values()) {
      if (p.name().equals(word)) {
        return true;
      }
    }
    return false;
  }

  private Set<AuthAdmin.Privilege> held(
      boolean user, String grantee, String namespace, @Nullable String table)
      throws ExecutionException {
    if (user) {
      return table == null
          ? admin.getPrivileges(grantee, namespace)
          : admin.getPrivileges(grantee, namespace, table);
    }
    return table == null
        ? admin.getRolePrivileges(grantee, namespace)
        : admin.getRolePrivileges(grantee, namespace, table);
  }

  private void apply(
      boolean grant,
      boolean user,
      String grantee,
      String namespace,
      @Nullable String table,
      AuthAdmin.Privilege[] privileges)
      throws ExecutionException {
    if (table == null) {
      if (user && grant) {
        admin.grant(grantee, namespace, privileges);
      } else if (user) {
        admin.revoke(grantee, namespace, privileges);
      } else if (grant) {
        admin.grantPrivilegeToRole(grantee, namespace, privileges);
      } else {
        admin.revokePrivilegeFromRole(grantee, namespace, privileges);
      }
    } else if (user && grant) {
      admin.grant(grantee, namespace, table, privileges);
    } else if (user) {
      admin.revoke(grantee, namespace, table, privileges);
    } else if (grant) {
      admin.grantPrivilegeToRole(grantee, namespace, table, privileges);
    } else {
      admin.revokePrivilegeFromRole(grantee, namespace, table, privileges);
    }
  }

  private Plan roles(boolean grant, boolean adminOptionOnly, List<Lexer.Token> items) {
    List<String> roles = new ArrayList<>();
    for (Lexer.Token item : items) {
      roles.add(item.name());
    }
    List<String> grantees = grantees(grant);
    boolean withAdmin = false;
    if (grant && in.accept("WITH")) {
      in.expect("ADMIN");
      if (!in.accept("OPTION")) {
        in.expect("TRUE");
      }
      withAdmin = true;
    }
    endGrant(grant);
    boolean adminOption = withAdmin;
    return ddl(
        grant ? "GRANT ROLE" : "REVOKE ROLE",
        new ArrayList<>(),
        notices -> {
          for (String grantee : grantees) {
            boolean user = isUser(grantee);
            for (String role : roles) {
              if (grant && user) {
                admin.grantRoleToUser(grantee, role, adminOption);
              } else if (grant) {
                admin.grantRoleToRole(grantee, role, adminOption);
              } else if (user && adminOptionOnly) {
                admin.revokeAdminOptionFromUser(grantee, role);
              } else if (user) {
                admin.revokeRoleFromUser(grantee, role);
              } else if (adminOptionOnly) {
                admin.revokeAdminOptionFromRole(grantee, role);
              } else {
                admin.revokeRoleFromRole(grantee, role);
              }
            }
          }
        });
  }

  private List<String> grantees(boolean grant) {
    in.expect(grant ? "TO" : "FROM");
    List<String> grantees = new ArrayList<>();
    do {
      in.accept("GROUP");
      if (in.peek("PUBLIC")) {
        throw unsupported("PUBLIC (ScalarDB grants to users and roles only)");
      }
      grantees.add(role());
    } while (in.accept(","));
    return grantees;
  }

  private void endGrant(boolean grant) {
    if (in.accept("GRANTED")) {
      throw unsupported("GRANTED BY");
    }
    if (!grant && in.peek("CASCADE")) {
      throw unsupported("CASCADE");
    }
    in.accept("RESTRICT");
    in.end();
  }

  /** A user or role name, where CURRENT_USER and the like mean the session's user. */
  private String role() {
    for (String self : new String[] {"CURRENT_USER", "SESSION_USER", "CURRENT_ROLE"}) {
      if (in.accept(self)) {
        return parser.sessionUser();
      }
    }
    return in.name();
  }

  private boolean isUser(String name) throws ExecutionException {
    if (admin.getUser(name).isPresent()) {
      return true;
    }
    if (admin.getRole(name).isPresent()) {
      return false;
    }
    throw missingRole(name);
  }

  // ---- ScalarDB SQL's rules (DclStatementExecutor), messages included ----

  static void checkGrant(Set<Privilege> granted, Set<Privilege> held) {
    // INSERT and UPDATE together, since both are ScalarDB's WRITE
    if (granted.contains(Privilege.INSERT) && !granted.contains(Privilege.UPDATE)) {
      throw invalidGrant(
          "Cannot grant the INSERT privilege if the user doesn't have the UPDATE privilege");
    }
    if (granted.contains(Privilege.UPDATE) && !granted.contains(Privilege.INSERT)) {
      throw invalidGrant(
          "Cannot grant the UPDATE privilege if the user doesn't have the INSERT privilege");
    }
    // UPDATE and DELETE read the rows first
    for (Privilege p : EnumSet.of(Privilege.UPDATE, Privilege.DELETE)) {
      if (granted.contains(p)
          && !held.contains(Privilege.SELECT)
          && !granted.contains(Privilege.SELECT)) {
        throw invalidGrant(
            "Cannot grant the " + p + " privilege if the user doesn't have the SELECT privilege");
      }
    }
  }

  static void checkRevoke(Set<Privilege> revoked, Set<Privilege> held) {
    if (revoked.contains(Privilege.SELECT)) {
      for (Privilege p : EnumSet.of(Privilege.UPDATE, Privilege.DELETE)) {
        if (held.contains(p) && !revoked.contains(p)) {
          throw invalidGrant(
              "Cannot revoke the SELECT privilege if the user has the " + p + " privilege");
        }
      }
    }
    if (revoked.contains(Privilege.INSERT) && !revoked.contains(Privilege.UPDATE)) {
      throw invalidGrant("Cannot revoke the INSERT privilege if the user has the UPDATE privilege");
    }
    if (revoked.contains(Privilege.UPDATE) && !revoked.contains(Privilege.INSERT)) {
      throw invalidGrant("Cannot revoke the UPDATE privilege if the user has the INSERT privilege");
    }
  }

  static AuthAdmin.Privilege[] toGrant(Set<Privilege> privileges) {
    Set<AuthAdmin.Privilege> out = EnumSet.noneOf(AuthAdmin.Privilege.class);
    for (Privilege p : privileges) {
      switch (p) {
        case SELECT:
          out.add(AuthAdmin.Privilege.READ);
          break;
        case INSERT:
          out.add(AuthAdmin.Privilege.WRITE);
          break;
        case UPDATE:
          out.add(AuthAdmin.Privilege.READ);
          out.add(AuthAdmin.Privilege.WRITE);
          break;
        case DELETE:
          out.add(AuthAdmin.Privilege.READ);
          out.add(AuthAdmin.Privilege.DELETE);
          break;
        default:
          out.add(AuthAdmin.Privilege.valueOf(p.name()));
      }
    }
    return out.toArray(new AuthAdmin.Privilege[0]);
  }

  static AuthAdmin.Privilege[] toRevoke(Set<Privilege> privileges) {
    Set<AuthAdmin.Privilege> out = EnumSet.noneOf(AuthAdmin.Privilege.class);
    for (Privilege p : privileges) {
      switch (p) {
        case SELECT:
          out.add(AuthAdmin.Privilege.READ);
          break;
        case INSERT:
        case UPDATE:
          out.add(AuthAdmin.Privilege.WRITE);
          break;
        default:
          out.add(AuthAdmin.Privilege.valueOf(p.name()));
      }
    }
    return out.toArray(new AuthAdmin.Privilege[0]);
  }

  static Set<Privilege> sqlPrivileges(Set<AuthAdmin.Privilege> privileges) {
    Set<Privilege> out = EnumSet.noneOf(Privilege.class);
    for (AuthAdmin.Privilege p : privileges) {
      if (p == AuthAdmin.Privilege.READ) {
        out.add(Privilege.SELECT);
      } else if (p == AuthAdmin.Privilege.WRITE) {
        out.add(Privilege.INSERT);
        out.add(Privilege.UPDATE);
      } else {
        out.add(Privilege.valueOf(p.name()));
      }
    }
    return out;
  }

  // ---- helpers ----

  /** What the plan runs; {@code notices} is the run's own list. */
  private interface Body {
    void run(List<String> notices) throws ExecutionException;
  }

  private static Plan ddl(String tag, List<String> planNotices, Body body) {
    Plan[] plan = new Plan[1];
    plan[0] =
        Plan.ddl(
            () -> {
              // a prepared statement runs again: its notices are this run's
              plan[0].notices.clear();
              plan[0].notices.addAll(planNotices);
              body.run(plan[0].notices);
            },
            tag);
    return plan[0];
  }

  private static PgException.Argument unsupported(String what) {
    return new PgException.Argument("0A000", what + " is not supported");
  }

  private static PgException.Argument invalidGrant(String message) {
    return new PgException.Argument("0LP01", message);
  }

  private static PgException.Argument missingRole(String name) {
    return new PgException.Argument("42704", "role \"" + name + "\" does not exist");
  }

  /** Splits a statement into words, quoted names, string literals and punctuation. */
  static final class Lexer {
    static final class Token {
      final char kind; // 'w'ord, 'q'uoted name, 's'tring, 'p'unctuation
      final String text; // as written for w, q and p; the value for s

      Token(char kind, String text) {
        this.kind = kind;
        this.text = text;
      }

      String word() {
        return kind == 'w' ? text.toUpperCase(Locale.ROOT) : "";
      }

      /** A name: unquoted ones fold to lower case, as in PostgreSQL. */
      String name() {
        if (kind != 'w' && kind != 'q') {
          throw new PgException.Argument("42601", "syntax error at or near " + describe());
        }
        return QueryParser.unquote(text);
      }

      /** The token for an error message: never a string literal's content. */
      String describe() {
        return kind == 's' ? "a string literal" : "\"" + text + "\"";
      }
    }

    private final List<Token> tokens = new ArrayList<>();
    private int at;

    Lexer(String sql) {
      int i = 0;
      int n = sql.length();
      while ((i = QueryParser.skipSpace(sql, i)) < n) {
        char c = sql.charAt(i);
        if (c == '\'' || c == '"') {
          StringBuilder value = new StringBuilder();
          int j = i + 1;
          while (true) {
            if (j >= n) {
              throw new PgException.Argument(
                  "42601",
                  c == '\'' ? "unterminated quoted string" : "unterminated quoted identifier");
            }
            char d = sql.charAt(j);
            if (d == c) {
              if (j + 1 < n && sql.charAt(j + 1) == c) {
                value.append(c);
                j += 2;
                continue;
              }
              break;
            }
            value.append(d);
            j++;
          }
          tokens.add(
              c == '\''
                  ? new Token('s', value.toString())
                  : new Token('q', sql.substring(i, j + 1)));
          i = j + 1;
        } else if (Character.isLetter(c) || c == '_') {
          int j = i + 1;
          while (j < n
              && (Character.isLetterOrDigit(sql.charAt(j))
                  || sql.charAt(j) == '_'
                  || sql.charAt(j) == '$')) {
            j++;
          }
          tokens.add(new Token('w', sql.substring(i, j)));
          i = j;
        } else {
          tokens.add(new Token('p', String.valueOf(c)));
          i++;
        }
      }
    }

    boolean atEnd() {
      return at >= tokens.size();
    }

    Token next() {
      if (atEnd()) {
        throw syntaxError();
      }
      return tokens.get(at++);
    }

    /** The next token as an upper-case word, or a syntax error. */
    String word() {
      Token t = next();
      if (t.kind != 'w') {
        at--;
        throw syntaxError();
      }
      return t.word();
    }

    String name() {
      return next().name();
    }

    /** A name as written, quotes included, for the parser's own resolution. */
    String raw() {
      Token t = next();
      t.name(); // a name or a syntax error
      return t.text;
    }

    String string() {
      Token t = next();
      if (t.kind != 's') {
        at--;
        throw syntaxError();
      }
      return t.text;
    }

    boolean peek(String word) {
      return peekAt(0, word);
    }

    boolean peekAt(int offset, String word) {
      if (at + offset >= tokens.size()) {
        return false;
      }
      Token t = tokens.get(at + offset);
      return t.kind == 'p' ? t.text.equals(word) : t.word().equals(word);
    }

    boolean accept(String word) {
      if (peek(word)) {
        at++;
        return true;
      }
      return false;
    }

    void expect(String word) {
      if (!accept(word)) {
        throw syntaxError();
      }
    }

    void end() {
      if (!atEnd()) {
        throw syntaxError();
      }
    }

    PgException.Argument syntaxError() {
      return new PgException.Argument(
          "42601",
          atEnd()
              ? "syntax error at end of input"
              : "syntax error at or near " + tokens.get(at).describe());
    }
  }
}
