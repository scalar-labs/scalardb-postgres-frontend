package com.scalar.db.frontend.postgres;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The functions, operators and casts the frontend implements, as PostgreSQL 16 catalogs them:
 * builtins.txt (pg_proc), aggregates.txt, operators.txt and casts.txt, written by
 * difftest/catalog/builtins.py, which keeps a row only when the frontend runs a call of it. A
 * call's result type comes from the signature it matches, as PostgreSQL resolves it.
 */
final class Builtins {
  /** One pg_proc row. */
  static final class Function {
    final Map<String, String> row;
    final long oid;
    final String name;
    final long result;
    final long[] args;
    final boolean variadic;

    Function(Map<String, String> row) {
      this.row = row;
      oid = Long.parseLong(field(row, "oid"));
      name = field(row, "proname");
      result = Long.parseLong(field(row, "prorettype"));
      String types = field(row, "proargtypes");
      args =
          types.isEmpty()
              ? new long[0]
              : Arrays.stream(types.split(" ")).mapToLong(Long::parseLong).toArray();
      variadic = !"0".equals(row.get("provariadic"));
    }
  }

  static final List<Map<String, String>> PROCS = table("builtins.txt");
  private static final Map<String, List<Function>> BY_NAME = new HashMap<>();
  private static final Map<Long, Function> BY_OID = new HashMap<>();
  static final List<Map<String, String>> AGGREGATES = table("aggregates.txt");
  static final List<Map<String, String>> OPERATORS = table("operators.txt");
  static final List<Map<String, String>> CASTS = table("casts.txt");
  // implicit casts, source -> targets, which a call's arguments may take to match a signature
  private static final Map<Long, List<Long>> IMPLICIT = new HashMap<>();
  // the polymorphic pseudo-types: "any", anyelement and anycompatible take any value, anynonarray
  // any but an array, anyarray and anycompatiblearray any array
  private static final Set<Long> ANY_ELEMENT = Set.of(2276L, 2283L, 5077L);
  private static final long ANY_NONARRAY = 2776;
  private static final Set<Long> ANY_ARRAY = Set.of(2277L, 5078L);
  private static final long ANY_COMPATIBLE = 5077;
  private static final long ANY_COMPATIBLE_ARRAY = 5078;
  // PostgreSQL's preferred types, which win a tie: text among strings, float8 among numbers
  private static final Set<Long> PREFERRED = Set.of(25L, 701L);

  static {
    for (Map<String, String> row : PROCS) {
      Function f = new Function(row);
      BY_NAME.computeIfAbsent(f.name.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(f);
      BY_OID.put(f.oid, f);
    }
    for (Map<String, String> c : CASTS) {
      if (field(c, "castcontext").equals("i")) {
        IMPLICIT
            .computeIfAbsent(Long.parseLong(field(c, "castsource")), k -> new ArrayList<>())
            .add(Long.parseLong(field(c, "casttarget")));
      }
    }
  }

  private Builtins() {}

  /** A column that is never NULL. */
  static String field(Map<String, String> row, String column) {
    return Objects.requireNonNull(row.get(column), column);
  }

  /** A tab-separated resource with a header line; {@code \N} is NULL, as COPY writes it. */
  static List<Map<String, String>> table(String resource) {
    List<Map<String, String>> rows = new ArrayList<>();
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(Builtins.class.getResourceAsStream(resource), resource),
                StandardCharsets.UTF_8))) {
      String[] header = null;
      for (String line = in.readLine(); line != null; line = in.readLine()) {
        if (line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\t", -1);
        if (header == null) {
          header = fields;
          continue;
        }
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
          row.put(header[i], fields[i].equals("\\N") ? null : unescape(fields[i]));
        }
        rows.add(row);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }

  private static String unescape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char n = s.charAt(++i);
        sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** The signatures of a function name, in any case; empty if the frontend has none. */
  static List<Function> overloads(String name) {
    return BY_NAME.getOrDefault(name.toUpperCase(Locale.ROOT), Collections.emptyList());
  }

  /** Whether a signature of the name takes this many arguments. */
  static boolean accepts(String name, int arity) {
    return overloads(name).stream().anyMatch(f -> takes(f, arity));
  }

  private static boolean takes(Function f, int arity) {
    return f.variadic ? arity >= f.args.length : arity == f.args.length;
  }

  /** The pg_proc row of an OID, or null. */
  @Nullable static Function function(long oid) {
    return BY_OID.get(oid);
  }

  /**
   * The OID a {@code regproc} ({@code lower}) or {@code regprocedure} ({@code lower(text)}) names,
   * failing as PostgreSQL does for none or, for regproc, more than one.
   */
  static long procOid(String text, boolean withArguments) {
    String t = text.trim();
    t = t.startsWith("pg_catalog.") ? t.substring("pg_catalog.".length()) : t;
    int open = t.indexOf('(');
    String name = withArguments && open > 0 ? t.substring(0, open).trim() : t;
    List<Function> found = new ArrayList<>(overloads(name));
    if (withArguments) {
      if (open < 0 || !t.endsWith(")")) {
        throw new PgException.Argument("22P02", "expected a left parenthesis");
      }
      String list = t.substring(open + 1, t.length() - 1).trim();
      long[] args =
          list.isEmpty()
              ? new long[0]
              : Arrays.stream(list.split(",")).mapToLong(Catalog::typeOid).toArray();
      found.removeIf(f -> !Arrays.equals(f.args, args));
      if (found.isEmpty()) {
        throw new PgException.Argument("42883", "function " + t + " does not exist");
      }
    } else if (found.size() > 1) {
      throw new PgException.Argument("42725", "more than one function named \"" + t + "\"");
    }
    if (found.isEmpty()) {
      throw new PgException.Argument("42883", "function \"" + t + "\" does not exist");
    }
    return found.get(0).oid;
  }

  /**
   * The result type of a call with arguments of these types (0 for unknown), from the signature
   * PostgreSQL would choose (see {@link #score}); 0 when no signature matches.
   */
  static long resultOid(String name, long[] args) {
    args = vectorsAsArrays(args);
    Function best = null;
    int bestScore = -1;
    for (Function f : overloads(name)) {
      int score = takes(f, args.length) ? score(f.args, args, f.variadic) : -1;
      if (score > bestScore) {
        best = f;
        bestScore = score;
      }
    }
    return best == null ? 0 : resolved(best.result, best.args, args);
  }

  /** The result type of {@code left op right} (left 0 for a prefix operator), or 0. */
  static long operatorOid(String op, long left, long right) {
    long found = 0;
    int bestScore = -1;
    for (Map<String, String> o : OPERATORS) {
      if (!field(o, "oprname").equals(op)) {
        continue;
      }
      long l = Long.parseLong(field(o, "oprleft"));
      long r = Long.parseLong(field(o, "oprright"));
      if ((l == 0) != (left == 0)) {
        continue;
      }
      long[] params = l == 0 ? new long[] {r} : new long[] {l, r};
      long[] args = vectorsAsArrays(l == 0 ? new long[] {right} : new long[] {left, right});
      int score = score(params, args, false);
      if (score > bestScore) {
        bestScore = score;
        found = resolved(Long.parseLong(field(o, "oprresult")), params, args);
      }
    }
    return found;
  }

  /**
   * The arguments with int2vector and oidvector as int2[] and oid[], as PostgreSQL's type
   * resolution takes them (pg_index.indkey to generate_subscripts and unnest).
   */
  private static long[] vectorsAsArrays(long[] args) {
    long[] out = args.clone();
    for (int i = 0; i < out.length; i++) {
      out[i] = out[i] == 22 ? 1005 : out[i] == 30 ? 1028 : out[i];
    }
    return out;
  }

  /**
   * How well arguments fit parameters, -1 if one cannot: 100 for each exact match, and 1 for each
   * argument an implicit cast takes to a preferred type, an unknown literal to text, or an array to
   * anyarray, which are PostgreSQL's tie-breaks (ceil(int) is ceil(float8), length('a') is
   * length(text)).
   */
  private static int score(long[] params, long[] args, boolean variadic) {
    int score = 0;
    for (int i = 0; i < args.length; i++) {
      long p = params[Math.min(i, params.length - 1)];
      if (variadic && i >= params.length - 1 && p != 2276) {
        p = Catalog.elementOid(p); // f(a, VARIADIC text[]) takes text arguments
      }
      long a = args[i];
      if (a == p) {
        score += 100;
      } else if (a == 0) {
        score += p == 25 ? 1 : 0;
      } else if (IMPLICIT.getOrDefault(a, List.of()).contains(p)) {
        score += PREFERRED.contains(p) ? 1 : 0;
      } else if (!polymorphic(p, a)) {
        return -1;
      } else if (ANY_ARRAY.contains(p)) {
        score +=
            1; // an array fits anyarray closer than anyelement: ARRAY[1] || ARRAY[2] is array_cat
      }
    }
    return score;
  }

  private static boolean polymorphic(long param, long arg) {
    boolean array = Catalog.elementOid(arg) != 0;
    return ANY_ELEMENT.contains(param)
        || param == ANY_NONARRAY && !array
        || ANY_ARRAY.contains(param) && array;
  }

  /**
   * The type both convert to: the one the other casts to implicitly, as int to numeric and numeric
   * to float8 do.
   *
   * <p>ponytail: types with no implicit cast either way keep the first; PostgreSQL fails the call
   * (text[] || integer)
   */
  private static long common(long a, long b) {
    return IMPLICIT.getOrDefault(a, List.of()).contains(b)
            && !IMPLICIT.getOrDefault(b, List.of()).contains(a)
        ? b
        : a;
  }

  /** A polymorphic result type as the arguments decide it. */
  private static long resolved(long result, long[] params, long[] args) {
    if (!ANY_ELEMENT.contains(result) && result != ANY_NONARRAY && !ANY_ARRAY.contains(result)) {
      return result;
    }
    // anyelement's type is the first polymorphic argument's; anycompatible's the common type of
    // them all (ARRAY[1] || 2.5 is numeric[])
    boolean compatible = result == ANY_COMPATIBLE || result == ANY_COMPATIBLE_ARRAY;
    long element = 0;
    for (int i = 0; i < args.length && i < params.length; i++) {
      long p = params[i];
      long e =
          ANY_ARRAY.contains(p)
              ? Catalog.elementOid(args[i])
              : ANY_ELEMENT.contains(p) || p == ANY_NONARRAY ? args[i] : 0;
      if (e != 0 && element == 0) {
        element = e;
      } else if (e != 0 && compatible) {
        element = common(element, e);
      } else if (e != 0) {
        break;
      }
    }
    if (element == 0) {
      return 0;
    }
    return ANY_ARRAY.contains(result) ? Catalog.arrayOid(element) : element;
  }
}
