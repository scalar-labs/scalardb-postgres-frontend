package com.scalar.db.frontend.postgres;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * PostgreSQL's json and jsonb in memory. ScalarDB has no JSON column, so a json or jsonb column is
 * TEXT in storage; values exist as Jackson trees only while a statement runs: a cast, an operator
 * result, a function result. jsonb prints with sorted keys and {@code "k": v} separators, json with
 * its keys in order and {@code "k" : v} separators, or verbatim when it came from a literal.
 */
final class Json {
  private static final JsonNodeFactory NODES = JsonNodeFactory.withExactBigDecimals(true);
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) // 'true, false' is not json
          .setNodeFactory(NODES);

  private Json() {}

  /** A JSON value with its type: jsonb (binary) or json. */
  static final class Value {
    final JsonNode node;
    final boolean binary;
    @Nullable final String verbatim; // a json literal prints as it was written

    Value(JsonNode node, boolean binary, @Nullable String verbatim) {
      this.node = node;
      this.binary = binary;
      this.verbatim = verbatim;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Value && same(node, ((Value) o).node);
    }

    @Override
    public int hashCode() {
      return hash(node); // agrees with equals: [1] and [1.0] are one jsonb value
    }

    @Override
    public String toString() {
      return binary ? text(node, true) : verbatim != null ? verbatim : text(node, false);
    }
  }

  static JsonNode parse(String text) {
    try {
      JsonNode node = MAPPER.readTree(text);
      if (node == null || node.isMissingNode()) {
        throw new PgException.Argument("22P02", "invalid input syntax for type json");
      }
      return node;
    } catch (JsonProcessingException e) {
      throw new PgException.Argument(
          "22P02", "invalid input syntax for type json: " + e.getOriginalMessage());
    }
  }

  /** {@code 'text'::jsonb} or {@code 'text'::json}. */
  static Value cast(String text, boolean binary) {
    JsonNode node = parse(text);
    if (binary && hasNul(node)) {
      throw new PgException.Argument("22P05", "unsupported Unicode escape sequence");
    }
    return new Value(node, binary, binary ? null : text);
  }

  /** Whether a string or key holds \u0000, which jsonb cannot store. */
  private static boolean hasNul(JsonNode node) {
    if (node.isTextual()) {
      return node.textValue().indexOf('\0') >= 0;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> e = fields.next();
      if (e.getKey().indexOf('\0') >= 0 || hasNul(e.getValue())) {
        return true;
      }
    }
    for (JsonNode item : node.isArray() ? node : NODES.arrayNode()) {
      if (hasNul(item)) {
        return true;
      }
    }
    return false;
  }

  /**
   * An operand of a JSON operator: a JSON value, or the text of a json column (TEXT in storage,
   * read as jsonb).
   */
  static Value of(Object v) {
    if (v instanceof Value) {
      return (Value) v;
    }
    if (v instanceof String) {
      return new Value(parse((String) v), true, null);
    }
    throw new IllegalArgumentException("Not a JSON value: " + v);
  }

  /** {@code to_json(v)}: a SQL value as a JSON tree. */
  static JsonNode toNode(@Nullable Object v) {
    if (v == null) {
      return NODES.nullNode();
    }
    if (v instanceof Value) {
      return ((Value) v).node;
    }
    if (v instanceof Boolean) {
      return NODES.booleanNode((Boolean) v);
    }
    if (v instanceof Integer || v instanceof Long) {
      return NODES.numberNode(((Number) v).longValue());
    }
    if (v instanceof BigDecimal) {
      return NODES.numberNode((BigDecimal) v);
    }
    if (v instanceof Number) {
      return NODES.numberNode(new BigDecimal(Evaluator.text(v)));
    }
    if (v instanceof Evaluator.Row) {
      throw unsupportedRecord(); // PostgreSQL writes an object named by the record's fields
    }
    if (v instanceof Evaluator.Record) {
      com.fasterxml.jackson.databind.node.ObjectNode object = NODES.objectNode();
      ((Evaluator.Record) v).forEach((field, value) -> object.set(field, toNode(value)));
      return object;
    }
    if (v instanceof List) {
      ArrayNode array = NODES.arrayNode();
      for (Object item : (List<?>) v) {
        array.add(toNode(item));
      }
      return array;
    }
    // ISO 8601 as PostgreSQL writes it: seconds always, the fraction trimmed, and a timestamptz in
    // the session's zone with its offset as +HH:MM (todo 022)
    if (v instanceof LocalDateTime) {
      return NODES.textNode(Evaluator.text(v).replace(' ', 'T'));
    }
    if (v instanceof Instant) {
      ZoneOffset offset = Evaluator.zone().getRules().getOffset((Instant) v);
      LocalDateTime local = LocalDateTime.ofInstant((Instant) v, offset);
      return NODES.textNode(
          Evaluator.text(local).replace(' ', 'T')
              + (offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId()));
    }
    if (v instanceof LocalDate || v instanceof LocalTime || v instanceof ByteBuffer) {
      return NODES.textNode(Evaluator.text(v));
    }
    return NODES.textNode(Evaluator.text(v));
  }

  /** {@code j -> key}: a member by text key or an element by index (negative from the end). */
  @Nullable static JsonNode get(JsonNode node, @Nullable Object key) {
    if (key == null) {
      return null;
    }
    if (node.isObject()) {
      return key instanceof String ? node.get((String) key) : null;
    }
    if (node.isArray() && !(key instanceof String)) {
      int i = ((Number) key).intValue();
      if (i < 0) {
        i += node.size();
      }
      return i >= 0 && i < node.size() ? node.get(i) : null;
    }
    return null;
  }

  /** {@code j #> path}: members and elements in turn; array steps are taken from text. */
  @Nullable static JsonNode path(JsonNode node, List<?> path) {
    JsonNode cur = node;
    for (Object step : path) {
      if (cur == null || step == null) {
        return null;
      }
      String s = Evaluator.text(step);
      if (cur.isArray()) {
        try {
          cur = get(cur, Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
          return null;
        }
      } else {
        cur = get(cur, s);
      }
    }
    return cur;
  }

  /**
   * The result of {@code ->}, {@code #>} or json_extract_path (asText: {@code ->>} and the _text
   * forms): the node the steps led to, as a value of the given type. A json value keeps its text:
   * an object, array or number comes out as written, as in PostgreSQL, which re-escapes only
   * strings.
   */
  @Nullable static Object extracted(
      Value from, @Nullable JsonNode node, List<?> steps, boolean binary, boolean asText) {
    if (node == null) {
      return null;
    }
    String raw = null;
    if (!from.binary
        && !binary
        && from.verbatim != null
        && (node.isContainerNode() || node.isNumber())) {
      raw = from.verbatim;
      JsonNode cur = from.node;
      for (Object step : steps) {
        raw = member(raw, cur, step);
        cur = cur.isArray() ? cur.get(index(cur, step)) : cur.get(Evaluator.text(step));
      }
    }
    if (asText) {
      return raw != null ? raw : scalarText(node, binary);
    }
    return new Value(node, binary, raw);
  }

  private static int index(JsonNode array, Object step) {
    long i =
        step instanceof Number
            ? ((Number) step).longValue()
            : Long.parseLong(Evaluator.text(step).trim());
    return (int) (i < 0 ? i + array.size() : i);
  }

  /**
   * The text of an object's member (the last of that name, as PostgreSQL takes) or an array's
   * element, as written; the caller has found it in the parsed container.
   */
  private static String member(String text, JsonNode container, Object step) {
    boolean array = container.isArray();
    int index = array ? index(container, step) : -1;
    String name = array ? null : Evaluator.text(step);
    String found = null;
    try (JsonParser p = MAPPER.getFactory().createParser(text)) {
      p.nextToken();
      for (int i = 0; !p.nextToken().isStructEnd(); i++) {
        boolean match = array ? i == index : p.currentName().equals(name);
        if (!array) {
          p.nextToken(); // from the name to its value
        }
        int start = (int) p.currentTokenLocation().getCharOffset();
        p.skipChildren();
        if (match) {
          found =
              p.currentToken().isStructEnd()
                  ? text.substring(start, (int) p.currentTokenLocation().getCharOffset() + 1)
                  : p.getText();
        }
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e); // the text parsed once already
    }
    return java.util.Objects.requireNonNull(found);
  }

  /** {@code ->>}: a string without its quotes, a JSON null as SQL NULL, anything else as text. */
  @Nullable static String scalarText(@Nullable JsonNode node, boolean binary) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.isTextual() ? node.textValue() : text(node, binary);
  }

  /** jsonb containment, {@code a @> b}. */
  static boolean contains(JsonNode a, JsonNode b) {
    if (a.isObject() && b.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = b.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        JsonNode mine = a.get(e.getKey());
        if (mine == null || !contains(mine, e.getValue())) {
          return false;
        }
      }
      return true;
    }
    if (a.isArray() && b.isArray()) {
      for (JsonNode theirs : b) {
        boolean found = false;
        for (JsonNode mine : a) {
          if (theirs.isContainerNode()
              ? mine.getNodeType() == theirs.getNodeType() && contains(mine, theirs)
              : same(mine, theirs)) {
            found = true;
            break;
          }
        }
        if (!found) {
          return false;
        }
      }
      return true;
    }
    if (a.isArray()) {
      // a scalar is contained in an array that has it at the top level
      for (JsonNode mine : a) {
        if (same(mine, b)) {
          return true;
        }
      }
      return false;
    }
    return same(a, b);
  }

  /** A hash that equal values share: numbers by value, object members in any order. */
  private static int hash(JsonNode n) {
    if (n.isNumber()) {
      return n.decimalValue().stripTrailingZeros().hashCode();
    }
    int h = n.getNodeType().hashCode();
    if (n.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        h += e.getKey().hashCode() ^ hash(e.getValue());
      }
    } else if (n.isArray()) {
      for (JsonNode item : n) {
        h = 31 * h + hash(item);
      }
    } else {
      h = 31 * h + n.hashCode();
    }
    return h;
  }

  /** Equality as jsonb sees it: numbers by value, containers member by member. */
  static boolean same(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) {
      return a.decimalValue().compareTo(b.decimalValue()) == 0;
    }
    if (a.isObject() && b.isObject()) {
      if (a.size() != b.size()) {
        return false;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = a.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        JsonNode theirs = b.get(e.getKey());
        if (theirs == null || !same(e.getValue(), theirs)) {
          return false;
        }
      }
      return true;
    }
    if (a.isArray() && b.isArray()) {
      if (a.size() != b.size()) {
        return false;
      }
      for (int i = 0; i < a.size(); i++) {
        if (!same(a.get(i), b.get(i))) {
          return false;
        }
      }
      return true;
    }
    return a.equals(b);
  }

  /** {@code j ? key}: an object key, an array's string element, or the string itself. */
  static boolean exists(JsonNode node, String key) {
    if (node.isObject()) {
      return node.has(key);
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual() && item.textValue().equals(key)) {
          return true;
        }
      }
      return false;
    }
    return node.isTextual() && node.textValue().equals(key);
  }

  /** {@code a || b} for jsonb: objects merge, arrays append, anything else joins as an array. */
  static JsonNode concat(JsonNode a, JsonNode b) {
    if (a.isObject() && b.isObject()) {
      ObjectNode out = ((ObjectNode) a).deepCopy();
      out.setAll((ObjectNode) b);
      return out;
    }
    ArrayNode out = NODES.arrayNode();
    if (a.isArray()) {
      out.addAll((ArrayNode) a);
    } else {
      out.add(a);
    }
    if (b.isArray()) {
      out.addAll((ArrayNode) b);
    } else {
      out.add(b);
    }
    return out;
  }

  /** {@code j - key}: without an object member or the equal string elements; {@code j - n}. */
  static JsonNode delete(JsonNode node, Object key) {
    if (node.isObject()) {
      if (!(key instanceof String)) {
        throw new IllegalArgumentException("cannot delete from object using integer index");
      }
      ObjectNode out = ((ObjectNode) node).deepCopy();
      out.remove((String) key);
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = NODES.arrayNode();
      int index = key instanceof String ? Integer.MIN_VALUE : ((Number) key).intValue();
      if (index != Integer.MIN_VALUE && index < 0) {
        index += node.size();
      }
      for (int i = 0; i < node.size(); i++) {
        JsonNode item = node.get(i);
        boolean drop =
            key instanceof String ? item.isTextual() && item.textValue().equals(key) : i == index;
        if (!drop) {
          out.add(item);
        }
      }
      return out;
    }
    throw new IllegalArgumentException("cannot delete from scalar");
  }

  // jsonfuncs.c's setPath operations
  static final int PATH_CREATE = 0x1;
  static final int PATH_REPLACE = 0x4;
  static final int PATH_INSERT_BEFORE = 0x8;
  static final int PATH_INSERT_AFTER = 0x10;
  private static final int PATH_CREATE_OR_INSERT =
      PATH_CREATE | PATH_INSERT_BEFORE | PATH_INSERT_AFTER;

  /**
   * jsonb_set (create or replace) and jsonb_insert (insert before or after): PostgreSQL's setPath,
   * building a new tree. A scalar on the way leaves the value as it is.
   */
  static JsonNode setPath(JsonNode node, List<?> path, int level, JsonNode value, int op) {
    if (path.get(level) == null) {
      throw new PgException.Argument(
          "22004", "path element at position " + (level + 1) + " is null");
    }
    String step = String.valueOf(path.get(level));
    boolean last = level == path.size() - 1;
    if (node.isObject()) {
      ObjectNode out = ((ObjectNode) node).deepCopy();
      JsonNode member = node.get(step);
      if (member != null && last) {
        if ((op & (PATH_INSERT_BEFORE | PATH_INSERT_AFTER)) != 0) {
          throw new PgException.Argument("22023", "cannot replace existing key");
        }
        out.set(step, value);
      } else if (member != null) {
        out.set(step, setPath(member, path, level + 1, value, op));
      } else if (last && (op & PATH_CREATE_OR_INSERT) != 0) {
        out.set(step, value);
      }
      return out;
    }
    if (!node.isArray()) {
      return node;
    }
    int n = node.size();
    int index = pathIndex(step, level);
    if (index < 0) {
      index = -(long) index > n ? Integer.MIN_VALUE : n + index;
    }
    index = Math.min(index, n);
    ArrayNode out = NODES.arrayNode();
    boolean done = false;
    if ((index == Integer.MIN_VALUE || n == 0) && last && (op & PATH_CREATE_OR_INSERT) != 0) {
      out.add(value); // before the first element, or into an empty array
      done = true;
    }
    for (int i = 0; i < n; i++) {
      if (i != index) {
        out.add(node.get(i));
        continue;
      }
      done = true;
      if (!last) {
        out.add(setPath(node.get(i), path, level + 1, value, op));
        continue;
      }
      if ((op & (PATH_INSERT_BEFORE | PATH_CREATE)) != 0) {
        out.add(value);
      }
      if ((op & (PATH_INSERT_AFTER | PATH_INSERT_BEFORE)) != 0) {
        out.add(node.get(i));
      }
      if ((op & (PATH_INSERT_AFTER | PATH_REPLACE)) != 0) {
        out.add(value);
      }
    }
    if (!done && last && (op & PATH_CREATE_OR_INSERT) != 0) {
      out.add(value);
    }
    return out;
  }

  /** An array step of a path, read as C's strtol reads it: leading space and a sign allowed. */
  private static int pathIndex(String step, int level) {
    String digits = step.replaceFirst("^[ \\t\\n\\u000B\\f\\r]+", "");
    if (digits.matches("[+-]?[0-9]+")) {
      try {
        return Integer.parseInt(digits);
      } catch (NumberFormatException outOfRange) {
        // as strtoint's ERANGE
      }
    }
    throw new PgException.Argument(
        "22P02",
        "path element at position " + (level + 1) + " is not an integer: \"" + step + "\"");
  }

  /** jsonb_strip_nulls: without the object members whose value is null, at any depth. */
  static JsonNode stripNulls(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = NODES.objectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                if (!e.getValue().isNull()) {
                  out.set(e.getKey(), stripNulls(e.getValue()));
                }
              });
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = NODES.arrayNode();
      node.forEach(item -> out.add(stripNulls(item)));
      return out;
    }
    return node;
  }

  static String typeOf(JsonNode node) {
    if (node.isObject()) {
      return "object";
    }
    if (node.isArray()) {
      return "array";
    }
    if (node.isTextual()) {
      return "string";
    }
    if (node.isNumber()) {
      return "number";
    }
    if (node.isBoolean()) {
      return "boolean";
    }
    return "null";
  }

  static long arrayLength(JsonNode node) {
    if (!node.isArray()) {
      throw new PgException.Argument(
          "22023", "cannot get array length of a " + (node.isObject() ? "non-array" : "scalar"));
    }
    return node.size();
  }

  /** {@code json_build_object(k1, v1, ...)}: keys as text, in the order given. */
  static ObjectNode buildObject(List<Object> args) {
    ObjectNode out = NODES.objectNode();
    for (int i = 0; i < pairs(args); i += 2) {
      out.set(Evaluator.text(args.get(i)), toNode(args.get(i + 1)));
    }
    return out;
  }

  /** The number of arguments, checked as key and value pairs with scalar, non-null keys. */
  private static int pairs(List<Object> args) {
    if (args.size() % 2 != 0) {
      throw new PgException.Argument(
          "22023", "argument list must have even number of elements (key, value pairs)");
    }
    for (int i = 0; i < args.size(); i += 2) {
      Object key = args.get(i);
      if (key == null) {
        throw new IllegalArgumentException("argument " + (i + 1) + ": key must not be null");
      }
      if (key instanceof List || key instanceof Value) {
        throw new PgException.Argument(
            "22023", "key value must be scalar, not array, composite, or json");
      }
    }
    return args.size();
  }

  /**
   * A SQL value in json text, as PostgreSQL's datum_to_json writes it: a json value as written, a
   * jsonb one as printed, an array without spaces.
   */
  static String jsonText(@Nullable Object v) {
    if (v == null) {
      return "null";
    }
    if (v instanceof Value) {
      return v.toString();
    }
    if (v instanceof Evaluator.Row) {
      throw unsupportedRecord();
    }
    if (v instanceof Evaluator.Record) {
      // a named record, as row_to_json writes it: {"a":1,"b":"x"}
      StringJoiner fields = new StringJoiner(",", "{", "}");
      ((Evaluator.Record) v)
          .forEach((field, value) -> fields.add(jsonText(field) + ":" + jsonText(value)));
      return fields.toString();
    }
    if (v instanceof List) {
      StringJoiner items = new StringJoiner(",", "[", "]");
      ((List<?>) v).forEach(item -> items.add(jsonText(item)));
      return items.toString();
    }
    return text(toNode(v), false);
  }

  private static PgException.Argument unsupportedRecord() {
    return new PgException.Argument("0A000", "Unsupported: a record as json");
  }

  /** A json value built as text, as PostgreSQL builds it, and read back for the operators. */
  private static Value built(String text) {
    return new Value(parse(text), false, text);
  }

  /** json_build_object: {@code {"k" : v, ...}}, each value as written. */
  static Value buildObjectText(List<Object> args) {
    StringJoiner members = new StringJoiner(", ", "{", "}");
    for (int i = 0; i < pairs(args); i += 2) {
      members.add(jsonText(Evaluator.text(args.get(i))) + " : " + jsonText(args.get(i + 1)));
    }
    return built(members.toString());
  }

  /** json_object_agg: {@code { "k" : v, ... }}, spaced as PostgreSQL writes it. */
  static Value objectAggText(List<Object> pairs) {
    StringJoiner members = new StringJoiner(", ", "{ ", " }");
    for (int i = 0; i < pairs(pairs); i += 2) {
      members.add(jsonText(Evaluator.text(pairs.get(i))) + " : " + jsonText(pairs.get(i + 1)));
    }
    return built(members.toString());
  }

  /** json_build_array: {@code [v, ...]}; json_agg also starts an array or row on a new line. */
  static Value buildArrayText(List<?> items, boolean agg) {
    StringBuilder sb = new StringBuilder("[");
    for (Object item : items) {
      if (sb.length() > 1) {
        sb.append(agg && item instanceof List ? ", \n " : ", ");
      }
      sb.append(jsonText(item));
    }
    return built(sb.append(']').toString());
  }

  static ArrayNode buildArray(List<?> items) {
    ArrayNode out = NODES.arrayNode();
    for (Object item : items) {
      out.add(toNode(item));
    }
    return out;
  }

  /** The elements of an array, as values of the given type; a json value's as written. */
  static List<Object> elements(Value value, boolean binary, boolean asText) {
    JsonNode node = value.node;
    if (!node.isArray()) {
      throw new PgException.Argument(
          "22023", "cannot extract elements from " + (node.isObject() ? "an object" : "a scalar"));
    }
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < node.size(); i++) {
      out.add(extracted(value, node.get(i), List.of((long) i), binary, asText));
    }
    return out;
  }

  /** The text PostgreSQL prints: jsonb with sorted keys and {@code ": "}, json in order. */
  static String text(JsonNode node, boolean binary) {
    StringBuilder sb = new StringBuilder();
    write(node, binary, sb);
    return sb.toString();
  }

  private static void write(JsonNode node, boolean binary, StringBuilder sb) {
    if (node.isObject()) {
      sb.append('{');
      Iterable<Map.Entry<String, JsonNode>> entries = sortedIfBinary(node, binary);
      boolean first = true;
      for (Map.Entry<String, JsonNode> e : entries) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append('"').append(JsonStringEncoder.getInstance().quoteAsString(e.getKey()));
        sb.append(binary ? "\": " : "\" : ");
        write(e.getValue(), binary, sb);
      }
      sb.append('}');
    } else if (node.isArray()) {
      sb.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        write(node.get(i), binary, sb);
      }
      sb.append(']');
    } else if (node.isTextual()) {
      sb.append('"')
          .append(JsonStringEncoder.getInstance().quoteAsString(node.textValue()))
          .append('"');
    } else if (node.isNumber()) {
      sb.append(Evaluator.checkedNumeric(node.decimalValue()).toPlainString());
    } else {
      sb.append(node.asText()); // true, false, null
    }
  }

  /** jsonb orders keys by length, then bytewise; json keeps the order they were written in. */
  private static Iterable<Map.Entry<String, JsonNode>> sortedIfBinary(
      JsonNode object, boolean binary) {
    if (!binary) {
      List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
      object.fields().forEachRemaining(out::add);
      return out;
    }
    TreeMap<String, JsonNode> sorted =
        new TreeMap<>(
            (x, y) -> {
              byte[] a = x.getBytes(StandardCharsets.UTF_8);
              byte[] b = y.getBytes(StandardCharsets.UTF_8);
              if (a.length != b.length) {
                return Integer.compare(a.length, b.length);
              }
              for (int i = 0; i < a.length; i++) {
                int c = Integer.compare(a[i] & 0xff, b[i] & 0xff);
                if (c != 0) {
                  return c;
                }
              }
              return 0;
            });
    object.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
    return sorted.entrySet();
  }
}
