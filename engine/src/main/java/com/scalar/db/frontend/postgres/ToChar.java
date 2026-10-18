package com.scalar.db.frontend.postgres;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * to_char(value, template) for timestamps, dates and numbers, ported from PostgreSQL 16's
 * formatting.c (DCH_to_char, NUM_processor), with the C locale's names, signs and separators. Not
 * done: intervals, and the number patterns RN, EEEE and L, which fail with 0A000.
 */
final class ToChar {
  private ToChar() {}

  // keywords as formatting.c lists them, in its search order: name=id
  private static final Map<String, String> DATETIME =
      keywords(
          "A.D.=A_D A.M.=A_M AD=AD AM=AM B.C.=B_C BC=BC CC=CC DAY=DAY DDD=DDD DD=DD DY=DY Day=Day "
              + "Dy=Dy D=D FF1=FF1 FF2=FF2 FF3=FF3 FF4=FF4 FF5=FF5 FF6=FF6 FX=FX HH24=HH24 HH12=HH12 "
              + "HH=HH IDDD=IDDD ID=ID IW=IW IYYY=IYYY IYY=IYY IY=IY I=I J=J MI=MI MM=MM MONTH=MONTH "
              + "MON=MON MS=MS Month=Month Mon=Mon OF=OF P.M.=P_M PM=PM Q=Q RM=RM SSSSS=SSSS "
              + "SSSS=SSSS SS=SS TZH=TZH TZM=TZM TZ=TZ US=US WW=WW W=W Y,YYY=Y_YYY YYYY=YYYY YYY=YYY "
              + "YY=YY Y=Y a.d.=a_d a.m.=a_m ad=ad am=am b.c.=b_c bc=bc cc=CC day=day ddd=DDD dd=DD "
              + "dy=dy d=D ff1=FF1 ff2=FF2 ff3=FF3 ff4=FF4 ff5=FF5 ff6=FF6 fx=FX hh24=HH24 hh12=HH12 "
              + "hh=HH iddd=IDDD id=ID iw=IW iyyy=IYYY iyy=IYY iy=IY i=I j=J mi=MI mm=MM month=month "
              + "mon=mon ms=MS of=OF p.m.=p_m pm=pm q=Q rm=rm sssss=SSSS ssss=SSSS ss=SS tzh=TZH "
              + "tzm=TZM tz=tz us=US ww=WW w=W y,yyy=Y_YYY yyyy=YYYY yyy=YYY yy=YY y=Y");

  private static final Map<String, String> NUMBER =
      keywords(
          ",=, .=. 0=0 9=9 B=B C=C D=D EEEE=E FM=FM G=G L=L MI=MI PL=PL PR=PR RN=RN SG=SG SP=SP "
              + "S=S TH=TH V=V b=B c=C d=D eeee=E fm=FM g=G l=L mi=MI pl=PL pr=PR rn=RN sg=SG sp=SP "
              + "s=S th=th v=V");

  private static Map<String, String> keywords(String list) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String pair : list.split(" ", -1)) {
      int eq = pair.lastIndexOf('=');
      map.put(pair.substring(0, eq), pair.substring(eq + 1));
    }
    return Collections.unmodifiableMap(map);
  }

  private static final int FM = 1;
  private static final int TM = 2;
  private static final int TH_UPPER = 4;
  private static final int TH_LOWER = 8;

  /** A keyword with its FM/TM prefix and TH/th/SP postfix, or one literal character. */
  private static final class Node {
    @Nullable final String key;
    final int suffix;
    final String text;

    Node(@Nullable String key, int suffix, String text) {
      this.key = key;
      this.suffix = suffix;
      this.text = text;
    }
  }

  /** formatting.c's parse_format: prefixes and postfixes only for date-time templates. */
  private static List<Node> parse(String fmt, Map<String, String> keywords, boolean datetime) {
    List<Node> nodes = new ArrayList<>();
    int i = 0;
    while (i < fmt.length()) {
      int suffix = 0;
      if (datetime && (fmt.startsWith("FM", i) || fmt.startsWith("fm", i))) {
        suffix = FM;
        i += 2;
      } else if (datetime && (fmt.startsWith("TM", i) || fmt.startsWith("tm", i))) {
        suffix = TM;
        i += 2;
      }
      String key = null;
      for (Map.Entry<String, String> k : keywords.entrySet()) {
        if (fmt.startsWith(k.getKey(), i)) {
          key = k.getValue();
          i += k.getKey().length();
          break;
        }
      }
      if (key != null) {
        if (datetime && fmt.startsWith("TH", i)) {
          suffix |= TH_UPPER;
          i += 2;
        } else if (datetime && fmt.startsWith("th", i)) {
          suffix |= TH_LOWER;
          i += 2;
        } else if (datetime && fmt.startsWith("SP", i)) {
          i += 2; // spell mode, which PostgreSQL ignores
        }
        nodes.add(new Node(key, suffix, ""));
      } else if (i < fmt.length() && fmt.charAt(i) == '"') {
        for (i++; i < fmt.length(); ) {
          if (fmt.charAt(i) == '"') {
            i++;
            break;
          }
          if (fmt.charAt(i) == '\\' && i + 1 < fmt.length()) {
            i++; // backslash quotes the next character
          }
          i = literal(fmt, i, nodes);
        }
      } else if (i < fmt.length()) {
        if (fmt.startsWith("\\\"", i)) {
          i++; // outside quotes, a backslash is only special before a double quote
        }
        i = literal(fmt, i, nodes);
      }
    }
    return nodes;
  }

  private static int literal(String fmt, int i, List<Node> nodes) {
    int end = fmt.offsetByCodePoints(i, 1);
    nodes.add(new Node(null, 0, fmt.substring(i, end)));
    return end;
  }

  /**
   * to_char of a timestamp, timestamptz, date or number; null for a date-time and empty template.
   */
  @Nullable static String toChar(Object value, String template, ZoneId zone) {
    if (value instanceof Number) {
      return number(parse(template, NUMBER, false), (Number) value);
    }
    if (template.isEmpty()) {
      return null;
    }
    ZonedDateTime t;
    String tz;
    if (value instanceof LocalDateTime) {
      t = ((LocalDateTime) value).atZone(ZoneOffset.UTC); // a timestamp has no zone: TZ is empty
      tz = null;
    } else if (value instanceof Instant || value instanceof LocalDate) {
      // a date is formatted as the timestamptz of its midnight, as PostgreSQL picks
      // to_char(timestamptz)
      t =
          value instanceof Instant
              ? ((Instant) value).atZone(zone)
              : ((LocalDate) value).atStartOfDay(zone);
      tz = abbreviation(t);
    } else {
      throw new PgException.Argument("0A000", "to_char of this type is not supported");
    }
    return datetime(parse(template, DATETIME, true), t, tz);
  }

  // ponytail: Java's English short zone names, which match the tz database's for the common zones;
  // a zone Java names by offset (GMT+03:00) shows PostgreSQL's numeric form (+03)
  private static String abbreviation(ZonedDateTime t) {
    if (t.getZone() instanceof ZoneOffset) {
      return t.getOffset().getTotalSeconds() == 0 ? "UTC" : Evaluator.offsetText(t.getOffset());
    }
    String name =
        java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US).format(t); // EST or EDT
    return name.startsWith("GMT") && !t.getZone().getId().equals("GMT")
        ? Evaluator.offsetText(t.getOffset())
        : name;
  }

  private static final String[] MONTHS = {
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December"
  };
  private static final String[] ROMAN_MONTHS = {
    "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"
  };

  private static String datetime(List<Node> nodes, ZonedDateTime t, @Nullable String tz) {
    StringBuilder out = new StringBuilder();
    int year = t.getYear();
    int adjusted = year <= 0 ? -(year - 1) : year; // 1 BC is year 0
    int isoYear = t.get(IsoFields.WEEK_BASED_YEAR);
    int isoAdjusted = isoYear <= 0 ? -(isoYear - 1) : isoYear;
    int hour = t.getHour();
    int micros = t.getNano() / 1000;
    int wday = t.getDayOfWeek().getValue() % 7; // Sunday is 0
    int offset = t.getOffset().getTotalSeconds();
    String month = MONTHS[t.getMonthValue() - 1];
    String day = t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
    for (Node n : nodes) {
      if (n.key == null) {
        out.append(n.text);
        continue;
      }
      boolean fm = (n.suffix & FM) != 0;
      boolean bare = fm || (n.suffix & TM) != 0; // TM's localized names are not padded
      String s;
      switch (n.key) {
        case "A_M":
        case "P_M":
          s = hour >= 12 ? "P.M." : "A.M.";
          break;
        case "AM":
        case "PM":
          s = hour >= 12 ? "PM" : "AM";
          break;
        case "a_m":
        case "p_m":
          s = hour >= 12 ? "p.m." : "a.m.";
          break;
        case "am":
        case "pm":
          s = hour >= 12 ? "pm" : "am";
          break;
        case "HH":
        case "HH12":
          s = padded(hour % 12 == 0 ? 12 : hour % 12, fm ? 0 : 2);
          break;
        case "HH24":
          s = padded(hour, fm ? 0 : 2);
          break;
        case "MI":
          s = padded(t.getMinute(), fm ? 0 : 2);
          break;
        case "SS":
          s = padded(t.getSecond(), fm ? 0 : 2);
          break;
        case "FF1":
          s = padded(micros / 100000, 1);
          break;
        case "FF2":
          s = padded(micros / 10000, 2);
          break;
        case "FF3":
        case "MS":
          s = padded(micros / 1000, 3);
          break;
        case "FF4":
          s = padded(micros / 100, 4);
          break;
        case "FF5":
          s = padded(micros / 10, 5);
          break;
        case "FF6":
        case "US":
          s = padded(micros, 6);
          break;
        case "SSSS":
          s = Integer.toString(t.toLocalTime().toSecondOfDay());
          break;
        case "tz":
          s = tz == null ? "" : tz.toLowerCase(Locale.ROOT);
          break;
        case "TZ":
          s = tz == null ? "" : tz;
          break;
        case "TZH":
          s = (offset >= 0 ? "+" : "-") + padded(Math.abs(offset) / 3600, 2);
          break;
        case "TZM":
          s = padded(Math.abs(offset) % 3600 / 60, 2);
          break;
        case "OF":
          s = (offset >= 0 ? "+" : "-") + padded(Math.abs(offset) / 3600, fm ? 0 : 2);
          if (Math.abs(offset) % 3600 != 0) {
            s += ":" + padded(Math.abs(offset) % 3600 / 60, 2);
          }
          break;
        case "A_D":
        case "B_C":
          s = year <= 0 ? "B.C." : "A.D.";
          break;
        case "AD":
        case "BC":
          s = year <= 0 ? "BC" : "AD";
          break;
        case "a_d":
        case "b_c":
          s = year <= 0 ? "b.c." : "a.d.";
          break;
        case "ad":
        case "bc":
          s = year <= 0 ? "bc" : "ad";
          break;
        case "MONTH":
          s = name(month.toUpperCase(Locale.ROOT), bare, 9);
          break;
        case "Month":
          s = name(month, bare, 9);
          break;
        case "month":
          s = name(month.toLowerCase(Locale.ROOT), bare, 9);
          break;
        case "MON":
          s = month.substring(0, 3).toUpperCase(Locale.ROOT);
          break;
        case "Mon":
          s = month.substring(0, 3);
          break;
        case "mon":
          s = month.substring(0, 3).toLowerCase(Locale.ROOT);
          break;
        case "MM":
          s = padded(t.getMonthValue(), fm ? 0 : 2);
          break;
        case "DAY":
          s = name(day.toUpperCase(Locale.ROOT), bare, 9);
          break;
        case "Day":
          s = name(day, bare, 9);
          break;
        case "day":
          s = name(day.toLowerCase(Locale.ROOT), bare, 9);
          break;
        case "DY":
          s = day.substring(0, 3).toUpperCase(Locale.ROOT);
          break;
        case "Dy":
          s = day.substring(0, 3);
          break;
        case "dy":
          s = day.substring(0, 3).toLowerCase(Locale.ROOT);
          break;
        case "DDD":
          s = padded(t.getDayOfYear(), fm ? 0 : 3);
          break;
        case "IDDD":
          s =
              padded(
                  (t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) - 1) * 7 + t.getDayOfWeek().getValue(),
                  fm ? 0 : 3);
          break;
        case "DD":
          s = padded(t.getDayOfMonth(), fm ? 0 : 2);
          break;
        case "D":
          s = Integer.toString(wday + 1);
          break;
        case "ID":
          s = Integer.toString(t.getDayOfWeek() == DayOfWeek.SUNDAY ? 7 : wday);
          break;
        case "WW":
          s = padded((t.getDayOfYear() - 1) / 7 + 1, fm ? 0 : 2);
          break;
        case "IW":
          s = padded(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), fm ? 0 : 2);
          break;
        case "Q":
          s = Integer.toString((t.getMonthValue() - 1) / 3 + 1);
          break;
        case "CC":
          {
            int c = year > 0 ? (year - 1) / 100 + 1 : year / 100 - 1;
            s = c <= 99 && c >= -99 ? padded(c, fm ? 0 : c >= 0 ? 2 : 3) : Integer.toString(c);
            break;
          }
        case "Y_YYY":
          s = adjusted / 1000 + "," + padded(adjusted % 1000, 3);
          break;
        case "YYYY":
          s = padded(adjusted, fm ? 0 : 4);
          break;
        case "IYYY":
          s = padded(isoAdjusted, fm ? 0 : 4);
          break;
        case "YYY":
          s = padded(adjusted % 1000, fm ? 0 : 3);
          break;
        case "IYY":
          s = padded(isoAdjusted % 1000, fm ? 0 : 3);
          break;
        case "YY":
          s = padded(adjusted % 100, fm ? 0 : 2);
          break;
        case "IY":
          s = padded(isoAdjusted % 100, fm ? 0 : 2);
          break;
        case "Y":
          s = Integer.toString(adjusted % 10);
          break;
        case "I":
          s = Integer.toString(isoAdjusted % 10);
          break;
        case "RM":
          s = name(ROMAN_MONTHS[t.getMonthValue() - 1], fm, 4);
          break;
        case "rm":
          s = name(ROMAN_MONTHS[t.getMonthValue() - 1].toLowerCase(Locale.ROOT), fm, 4);
          break;
        case "W":
          s = Integer.toString((t.getDayOfMonth() - 1) / 7 + 1);
          break;
        case "J":
          s = Long.toString(t.toLocalDate().getLong(JulianFields.JULIAN_DAY));
          break;
        default: // FX
          s = "";
      }
      out.append(s);
      if ((n.suffix & (TH_UPPER | TH_LOWER)) != 0 && !s.isEmpty() && isNumbered(n.key)) {
        out.append(ordinal(s, (n.suffix & TH_UPPER) != 0));
      }
    }
    return out.toString();
  }

  private static final Set<String> NAMED =
      Set.of(
          "a_m", "p_m", "am", "pm", "a_d", "b_c", "ad", "bc", "tz", "tzh", "tzm", "of", "month",
          "mon", "day", "dy", "rm", "fx");

  // the keywords whose value takes an ordinal suffix (formatting.c's str_numth calls)
  private static boolean isNumbered(String key) {
    return !NAMED.contains(key.toLowerCase(Locale.ROOT));
  }

  private static String padded(int value, int width) {
    String digits = Integer.toString(Math.abs(value));
    StringBuilder s = new StringBuilder(value < 0 ? "-" : "");
    for (int i = digits.length() + s.length(); i < width; i++) {
      s.append('0');
    }
    return s.append(digits).toString();
  }

  private static String name(String name, boolean fm, int width) {
    StringBuilder s = new StringBuilder(name);
    while (!fm && s.length() < width) {
      s.append(' ');
    }
    return s.toString();
  }

  /** formatting.c's get_th: st, nd, rd or th, the teens all th. */
  private static String ordinal(String number, boolean upper) {
    char last = number.charAt(number.length() - 1);
    if (number.length() > 1 && number.charAt(number.length() - 2) == '1') {
      last = '0';
    }
    String th = last == '1' ? "st" : last == '2' ? "nd" : last == '3' ? "rd" : "th";
    return upper ? th.toUpperCase(Locale.ROOT) : th;
  }

  /** formatting.c's NUMDesc: what the template's number patterns ask for. */
  private static final class Format {
    int pre;
    int post;
    int multi;
    boolean decimal;
    boolean zero;
    boolean fill;
    boolean bracket;
    boolean minus;
    boolean plus;
    int lsignAt; // 0 none, 1 before the digits, 2 after
    int preLsign;
    int zeroStart;
    int zeroEnd;
    boolean vSeen;

    /** NUMDesc_prepare, for one keyword. */
    void add(String key) {
      switch (key) {
        case "9":
          if (bracket) {
            throw syntax("\"9\" must be ahead of \"PR\"");
          }
          if (vSeen) {
            ++multi;
          } else if (decimal) {
            ++post;
          } else {
            ++pre;
          }
          break;
        case "0":
          if (bracket) {
            throw syntax("\"0\" must be ahead of \"PR\"");
          }
          if (!zero && !decimal) {
            zero = true;
            zeroStart = pre + 1;
          }
          if (decimal) {
            ++post;
          } else {
            ++pre;
          }
          zeroEnd = pre + post;
          break;
        case "D":
        case ".":
          if (decimal) {
            throw syntax("multiple decimal points");
          }
          if (vSeen) {
            throw syntax("cannot use \"V\" and decimal point together");
          }
          decimal = true;
          break;
        case "FM":
          fill = true;
          break;
        case "S":
          if (lsignAt != 0) {
            throw syntax("cannot use \"S\" twice");
          }
          if (plus || minus || bracket) {
            throw syntax("cannot use \"S\" and \"PL\"/\"MI\"/\"SG\"/\"PR\" together");
          }
          if (!decimal) {
            lsignAt = 1;
            preLsign = pre;
          } else {
            lsignAt = 2;
          }
          break;
        case "MI":
          if (lsignAt != 0) {
            throw syntax("cannot use \"S\" and \"MI\" together");
          }
          minus = true;
          break;
        case "PL":
          if (lsignAt != 0) {
            throw syntax("cannot use \"S\" and \"PL\" together");
          }
          plus = true;
          break;
        case "SG":
          if (lsignAt != 0) {
            throw syntax("cannot use \"S\" and \"SG\" together");
          }
          minus = true;
          plus = true;
          break;
        case "PR":
          if (lsignAt != 0 || plus || minus) {
            throw syntax("cannot use \"PR\" and \"S\"/\"PL\"/\"MI\"/\"SG\" together");
          }
          bracket = true;
          break;
        case "V":
          if (decimal) {
            throw syntax("cannot use \"V\" and decimal point together");
          }
          vSeen = true;
          break;
        case "RN":
        case "E":
        case "L":
          throw new PgException.Argument(
              "0A000", "to_char's RN, EEEE and L number patterns are not supported");
        default: // , G B C SP TH th: written, or not, as they come
      }
    }
  }

  private static PgException.Argument syntax(String message) {
    return new PgException.Argument("42601", message);
  }

  /**
   * to_char of a number: the digit string as int4_to_char and friends make it, then NUM_processor.
   */
  private static String number(List<Node> nodes, Number value) {
    Format f = new Format();
    for (Node n : nodes) {
      if (n.key != null) {
        f.add(n.key);
      }
    }
    String digits;
    boolean negative;
    if (value instanceof BigDecimal) {
      BigDecimal v = ((BigDecimal) value).movePointRight(f.multi);
      v = v.setScale(f.post, RoundingMode.HALF_UP); // numeric_round: half away from zero
      negative = v.signum() < 0;
      digits = v.abs().toPlainString();
    } else if (value instanceof Double || value instanceof Float) {
      // float8_to_char: printf's %.*f, at most DBL_DIG (FLT_DIG) significant digits
      double v = value.doubleValue();
      if (Double.isNaN(v) || Double.isInfinite(v)) {
        throw new PgException.Argument("0A000", "to_char of NaN or Infinity is not supported");
      }
      if (f.multi > 0) {
        v =
            value instanceof Float
                ? value.floatValue() * (float) Math.pow(10, f.multi) // a float multiplier
                : v * Math.pow(10, f.multi);
      }
      int dig = value instanceof Float ? 6 : 15;
      int preLen =
          new BigDecimal(Math.abs(v)).setScale(0, RoundingMode.HALF_EVEN).toPlainString().length();
      if (preLen >= dig) {
        f.post = 0;
      } else if (preLen + f.post > dig) {
        f.post = dig - preLen;
      }
      negative = v < 0 || (v == 0 && 1 / v < 0); // printf writes -0.00
      digits = new BigDecimal(Math.abs(v)).setScale(f.post, RoundingMode.HALF_EVEN).toPlainString();
    } else {
      long v = value.longValue();
      try {
        if (value instanceof Long && f.multi >= 19) { // dtoi8(10^multi) fails whatever the value
          throw new ArithmeticException();
        }
        for (int i = 0; i < f.multi; i++) {
          v = value instanceof Long ? Math.multiplyExact(v, 10L) : Math.multiplyExact((int) v, 10);
        }
      } catch (ArithmeticException e) {
        throw new PgException.Argument(
            "22003", (value instanceof Long ? "bigint" : "integer") + " out of range");
      }
      negative = v < 0;
      digits = negative ? Long.toString(v).substring(1) : Long.toString(v);
      if (f.post > 0) {
        digits += "." + "0".repeat(f.post);
      }
    }
    f.pre += f.multi;
    int point = digits.indexOf('.');
    int preLen = point >= 0 ? point : digits.length();
    int outPreSpaces = 0;
    if (preLen < f.pre) {
      outPreSpaces = f.pre - preLen;
    } else if (preLen > f.pre) {
      digits = "#".repeat(f.pre) + "." + "#".repeat(f.post);
    }
    return new Processor(f, digits, outPreSpaces, negative ? '-' : '+').run(nodes);
  }

  /** formatting.c's NUM_processor and NUM_numpart_to_char, for to_char. */
  private static final class Processor {
    final Format f;
    final String number;
    final int outPreSpaces;
    final char sign;
    final StringBuilder out = new StringBuilder();
    int numberP;
    int lastRelevant = -1;
    boolean signWrote;
    int numCount;
    int numCurr;
    boolean numIn;
    int cut = -1; // where printf's string would end: a digit read past the number writes a NUL

    Processor(Format f, String number, int outPreSpaces, char sign) {
      this.f = f;
      this.number = number;
      this.outPreSpaces = outPreSpaces;
      this.sign = sign;
    }

    char at(int i) {
      return i >= 0 && i < number.length() ? number.charAt(i) : '\0';
    }

    String run(List<Node> nodes) {
      if (f.zeroStart > 0) {
        --f.zeroStart;
      }
      if (f.plus || f.minus) {
        signWrote = !(f.plus && !f.minus); // MI/PL/SG write the sign themselves
      } else {
        if (sign != '-' && f.fill) {
          f.bracket = false;
        }
        signWrote = sign == '+' && f.fill && f.lsignAt == 0;
        if (f.lsignAt == 1 && f.pre == f.preLsign) {
          f.lsignAt = 2;
        }
      }
      numCount = f.post + f.pre - 1;
      if (f.fill && f.decimal) {
        int p = number.indexOf('.');
        if (p >= 0) {
          lastRelevant = p;
          for (int i = p + 1; i < number.length(); i++) {
            if (number.charAt(i) != '0') {
              lastRelevant = i;
            }
          }
          if (f.zeroEnd > outPreSpaces) {
            int lastZero = Math.min(number.length() - 1, f.zeroEnd - outPreSpaces);
            lastRelevant = Math.max(lastRelevant, lastZero);
          }
        }
      }
      if (!signWrote && outPreSpaces == 0) {
        ++numCount;
      }
      for (Node n : nodes) {
        if (n.key == null) {
          out.append(n.text);
          continue;
        }
        switch (n.key) {
          case "9":
          case "0":
          case ".":
          case "D":
            part(n.key);
            break;
          case ",":
          case "G":
            if (numIn) {
              out.append(',');
            } else if (!f.fill) {
              out.append(' ');
            }
            break;
          case "TH":
          case "th":
            if (number.charAt(0) != '#' && sign != '-' && !f.decimal) {
              out.append(ordinal(number, n.key.equals("TH")));
            }
            break;
          case "MI":
            if (sign == '-') {
              out.append('-');
            } else if (!f.fill) {
              out.append(' ');
            }
            break;
          case "PL":
            if (sign == '+') {
              out.append('+');
            } else if (!f.fill) {
              out.append(' ');
            }
            break;
          case "SG":
            out.append(sign);
            break;
          default: // S and PR write with the digits; B, C, FM, SP and V write nothing
        }
      }
      return cut >= 0 ? out.substring(0, cut) : out.toString();
    }

    boolean predecSpace() {
      return !f.zero && numberP == 0 && at(0) == '0' && f.post != 0;
    }

    void part(String id) {
      numIn = false;
      if (!signWrote
          && (numCurr >= outPreSpaces || (f.zero && f.zeroStart == numCurr))
          && (!predecSpace() || (lastRelevant >= 0 && at(lastRelevant) == '.'))) {
        if (f.lsignAt != 0) {
          if (f.lsignAt == 1) {
            out.append(sign);
            signWrote = true;
          }
        } else if (f.bracket) {
          out.append(sign == '+' ? ' ' : '<');
          signWrote = true;
        } else if (sign == '+') {
          if (!f.fill) {
            out.append(' ');
          }
          signWrote = true;
        } else {
          out.append('-');
          signWrote = true;
        }
      }
      if (numCurr < outPreSpaces && (f.zeroStart > numCurr || !f.zero)) {
        if (!f.fill) {
          out.append(' ');
        }
      } else if (f.zero && numCurr < outPreSpaces && f.zeroStart <= numCurr) {
        out.append('0');
        numIn = true;
      } else {
        if (at(numberP) == '.') {
          if (lastRelevant < 0 || at(lastRelevant) != '.' || f.fill) {
            out.append('.');
          }
        } else if (lastRelevant >= 0 && numberP > lastRelevant && !id.equals("0")) {
          // FM: a trailing zero not asked for by a 0
        } else if (predecSpace()) {
          if (!f.fill) {
            out.append(' ');
          } else if (lastRelevant >= 0 && at(lastRelevant) == '.') {
            out.append('0'); // 0 in FM9.9 is 0.
          }
        } else {
          if (at(numberP) == '\0' && cut < 0) {
            cut = out.length();
          }
          out.append(at(numberP));
          numIn = true;
        }
        if (numberP < number.length()) {
          ++numberP;
        }
      }
      int end = numCount + (outPreSpaces != 0 ? 1 : 0) + (f.decimal ? 1 : 0);
      if (lastRelevant >= 0 && lastRelevant == numberP) {
        end = numCurr;
      }
      if (numCurr + 1 == end) {
        if (signWrote && f.bracket) {
          out.append(sign == '+' ? ' ' : '>');
        } else if (f.lsignAt == 2) {
          out.append(sign);
        }
      }
      ++numCurr;
    }
  }
}
