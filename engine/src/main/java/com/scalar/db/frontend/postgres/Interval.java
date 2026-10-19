package com.scalar.db.frontend.postgres;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL's interval: months, days and microseconds kept apart, because a month or a day is not
 * a fixed number of seconds when added to a timestamp. ScalarDB has no interval column, so an
 * interval only lives in memory: a literal, a cast, or the difference of two timestamps.
 */
final class Interval implements Comparable<Interval> {
  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND;

  final int months;
  final int days;
  final long micros;

  Interval(long months, long days, long micros) {
    this.months = Math.toIntExact(months);
    this.days = Math.toIntExact(days);
    this.micros = micros;
  }

  // A time of day, or an amount with an optional unit, or anything else (an error)
  private static final Pattern TOKEN =
      Pattern.compile(
          "([+-]?\\d++:\\d{1,2}(?::\\d{1,2}(?:\\.\\d++)?)?)|([+-]?\\d++(?:\\.\\d++)?)\\s*+([a-zA-Z]*+)|(\\S++)");
  private static final Pattern ISO =
      Pattern.compile(
          "(?i)P(?:(-?\\d++)Y)?(?:(-?\\d++)M)?(?:(-?\\d++)W)?(?:(-?\\d++)D)?"
              + "(?:T(?:(-?\\d++)H)?(?:(-?\\d++)M)?(?:(-?\\d++(?:\\.\\d++)?)S)?)?");

  /**
   * Parses PostgreSQL's input forms: {@code 1 year 2 mons 3 days 04:05:06.5}, unit words in their
   * long and short spellings, fractional amounts (1.5 days is 1 day 12:00:00), a bare number of
   * seconds, a leading {@code @}, a trailing {@code ago}, and ISO 8601 {@code P1Y2M3DT4H5M6S}.
   */
  static Interval parse(String text) {
    try {
      return read(text);
    } catch (ArithmeticException e) {
      throw outOfRange(); // a field past its range, as PostgreSQL's 22015
    }
  }

  private static Interval read(String text) {
    String s = text.trim();
    if (s.startsWith("@")) {
      s = s.substring(1).trim();
    }
    boolean ago = false;
    if (s.toLowerCase(Locale.ROOT).endsWith(" ago")) {
      ago = true;
      s = s.substring(0, s.length() - 4).trim();
    }
    long months = 0;
    long days = 0;
    BigDecimal micros = BigDecimal.ZERO;
    Matcher iso = ISO.matcher(s);
    if (iso.matches() && s.length() > 1) {
      months = num(iso.group(1)) * 12 + num(iso.group(2));
      days = num(iso.group(3)) * 7 + num(iso.group(4));
      micros =
          seconds(num(iso.group(5)) * 3600 + num(iso.group(6)) * 60)
              .add(
                  iso.group(7) == null
                      ? BigDecimal.ZERO
                      : seconds(Evaluator.checkedNumeric(new BigDecimal(iso.group(7)))));
    } else {
      Matcher m = TOKEN.matcher(s);
      int seen = 0; // the fields given so far: PostgreSQL rejects one given twice
      boolean any = false;
      while (m.find()) {
        any = true;
        if (m.group(1) != null) {
          seen = field(seen, 0b11111 << 4, text); // a time is hours, minutes and all seconds
          String[] parts = m.group(1).split(":");
          boolean negative = parts[0].startsWith("-");
          BigDecimal secs =
              seconds(Math.abs(Long.parseLong(parts[0])) * 3600 + Long.parseLong(parts[1]) * 60)
                  .add(
                      parts.length > 2
                          ? seconds(Evaluator.checkedNumeric(new BigDecimal(parts[2])))
                          : BigDecimal.ZERO);
          micros = micros.add(negative ? secs.negate() : secs);
          continue;
        }
        if (m.group(2) == null) {
          throw invalid(text);
        }
        BigDecimal amount = Evaluator.checkedNumeric(new BigDecimal(m.group(2)));
        String unit = m.group(3).toLowerCase(Locale.ROOT);
        if (unit.isEmpty()) {
          // a bare number is seconds alone, or days before a time ('1 2:03:04'); PostgreSQL's
          // other readings ('1-2' as years and months) are not taken
          boolean beforeTime =
              m.end() < s.length() && TIME_NEXT.matcher(s.substring(m.end())).lookingAt();
          if (!beforeTime && (seen != 0 || m.end() < s.trim().length())) {
            throw invalid(text);
          }
          unit = beforeTime ? "day" : "second";
        }
        if (unit.matches("s|sec|secs|second|seconds")) {
          // fractional seconds also claim the milliseconds and microseconds, as in PostgreSQL
          seen =
              field(
                  seen,
                  amount.scale() > 0 && fraction(amount).signum() != 0 ? 0b111 << 6 : 1 << 6,
                  text);
          micros = micros.add(seconds(amount));
        } else if (unit.matches("y|yr|yrs|year|years")) {
          seen = field(seen, 1, text);
          // a fraction of a year is whole months, rounded as PostgreSQL's rint: 0.7 years is 8 mons
          months =
              Math.addExact(
                  months,
                  whole(
                      amount.multiply(BigDecimal.valueOf(12)).setScale(0, RoundingMode.HALF_EVEN)));
        } else if (unit.matches("mon|mons|month|months")) {
          seen = field(seen, 1 << 1, text);
          months = Math.addExact(months, whole(amount));
          // a fraction of a month is 30-day days, and their fraction time: 0.01 months is 07:12:00
          BigDecimal d = fraction(amount).multiply(BigDecimal.valueOf(30));
          days = Math.addExact(days, whole(d));
          micros = micros.add(fraction(d).multiply(BigDecimal.valueOf(MICROS_PER_DAY)));
        } else if (unit.matches("w|week|weeks") || unit.matches("d|day|days")) {
          seen = field(seen, unit.startsWith("w") ? 1 << 2 : 1 << 3, text);
          BigDecimal d = unit.startsWith("w") ? amount.multiply(BigDecimal.valueOf(7)) : amount;
          days = Math.addExact(days, whole(d));
          micros = micros.add(fraction(d).multiply(BigDecimal.valueOf(MICROS_PER_DAY)));
        } else if (unit.matches("h|hr|hrs|hour|hours")) {
          seen = field(seen, 1 << 4, text);
          micros = micros.add(seconds(amount.multiply(BigDecimal.valueOf(3600))));
        } else if (unit.matches("m|min|mins|minute|minutes")) {
          seen = field(seen, 1 << 5, text);
          micros = micros.add(seconds(amount.multiply(BigDecimal.valueOf(60))));
        } else if (unit.matches("ms|millisecond|milliseconds")) {
          seen = field(seen, 1 << 7, text);
          micros = micros.add(amount.multiply(BigDecimal.valueOf(1000)));
        } else if (unit.matches("us|microsecond|microseconds")) {
          seen = field(seen, 1 << 8, text);
          micros = micros.add(amount);
        } else {
          throw invalid(text);
        }
      }
      if (!any) {
        throw invalid(text); // '' is not an interval
      }
    }
    long time;
    try {
      time = micros.setScale(0, RoundingMode.HALF_UP).longValueExact();
    } catch (ArithmeticException e) {
      throw outOfRange();
    }
    if (ago) {
      months = -months;
      days = -days;
      time = Math.negateExact(time);
    }
    if (months != (int) months || days != (int) days) {
      throw outOfRange(); // PostgreSQL keeps months and days as integers
    }
    return new Interval(months, days, time);
  }

  private static final Pattern TIME_NEXT = Pattern.compile("\\s*[+-]?\\d+:");

  /** {@code seen} with {@code bits} added; a field given twice is invalid input. */
  private static int field(int seen, int bits, String text) {
    if ((seen & bits) != 0) {
      throw invalid(text);
    }
    return seen | bits;
  }

  private static IllegalArgumentException outOfRange() {
    return new PgException.Argument("22015", "interval out of range");
  }

  private static IllegalArgumentException invalid(String text) {
    return new PgException.Argument(
        "22007", "invalid input syntax for type interval: \"" + text + "\"");
  }

  private static long num(String group) {
    return group == null ? 0 : Long.parseLong(group);
  }

  private static BigDecimal seconds(long s) {
    return BigDecimal.valueOf(s * MICROS_PER_SECOND);
  }

  private static BigDecimal seconds(BigDecimal s) {
    return s.multiply(BigDecimal.valueOf(MICROS_PER_SECOND));
  }

  private static long whole(BigDecimal v) {
    try {
      return v.setScale(0, RoundingMode.DOWN).longValueExact();
    } catch (ArithmeticException e) {
      throw outOfRange();
    }
  }

  private static BigDecimal fraction(BigDecimal v) {
    return v.subtract(v.setScale(0, RoundingMode.DOWN));
  }

  Interval plus(Interval o) {
    return new Interval(months + o.months, days + o.days, micros + o.micros);
  }

  Interval negate() {
    return new Interval(-months, -days, -micros);
  }

  /**
   * Multiplies as PostgreSQL does: fractional months spill into days, fractional days into time.
   */
  Interval times(double factor) {
    BigDecimal f = new BigDecimal(Double.toString(factor));
    BigDecimal m = BigDecimal.valueOf(months).multiply(f);
    BigDecimal d =
        BigDecimal.valueOf(days).multiply(f).add(fraction(m).multiply(BigDecimal.valueOf(30)));
    BigDecimal us =
        BigDecimal.valueOf(micros)
            .multiply(f)
            .add(fraction(d).multiply(BigDecimal.valueOf(MICROS_PER_DAY)));
    return new Interval(whole(m), whole(d), us.setScale(0, RoundingMode.HALF_UP).longValue());
  }

  LocalDateTime addTo(LocalDateTime t) {
    return t.plusMonths(months).plusDays(days).plus(micros, ChronoUnit.MICROS);
  }

  Instant addTo(Instant t) {
    // As PostgreSQL: months and days move the wall clock in the session's zone, so a day across a
    // DST change keeps the time of day; the time part is elapsed time (todo 018)
    return t.atZone(Evaluator.zone())
        .plusMonths(months)
        .plusDays(days)
        .toInstant()
        .plus(micros, ChronoUnit.MICROS);
  }

  LocalTime addTo(LocalTime t) {
    return t.plus(micros, ChronoUnit.MICROS);
  }

  /** {@code a - b} for timestamps: days and time, never months, as PostgreSQL computes it. */
  static Interval between(LocalDateTime b, LocalDateTime a) {
    long days = ChronoUnit.DAYS.between(b, a);
    return new Interval(0, days, ChronoUnit.MICROS.between(b.plusDays(days), a));
  }

  /**
   * {@code age(a, b)} as PostgreSQL computes it: the difference of each field, with a negative
   * field borrowing from the next one, and a short day count borrowing the length of the earlier
   * date's month.
   */
  static Interval age(LocalDateTime b, LocalDateTime a) {
    boolean negative = a.isBefore(b);
    LocalDateTime from = negative ? a : b;
    LocalDateTime to = negative ? b : a;
    long years = to.getYear() - from.getYear();
    long months = to.getMonthValue() - from.getMonthValue();
    long days = to.getDayOfMonth() - from.getDayOfMonth();
    long micros = (to.toLocalTime().toNanoOfDay() - from.toLocalTime().toNanoOfDay()) / 1000;
    if (micros < 0) {
      micros += MICROS_PER_DAY;
      days--;
    }
    if (days < 0) {
      days += java.time.YearMonth.from(from).lengthOfMonth();
      months--;
    }
    if (months < 0) {
      months += 12;
      years--;
    }
    Interval v = new Interval(years * 12 + months, days, micros);
    return negative ? v.negate() : v;
  }

  /** EXTRACT fields; epoch counts a month as 30 days and a year as 365.25 days, as PostgreSQL. */
  Object extract(String field) {
    long seconds = micros / MICROS_PER_SECOND;
    switch (field.toUpperCase(Locale.ROOT)) {
      case "YEAR":
        return (long) (months / 12);
      case "MONTH":
        return (long) (months % 12);
      case "DAY":
        return (long) days;
      case "HOUR":
        return seconds / 3600;
      case "MINUTE":
        return (seconds / 60) % 60;
      case "SECOND":
        return BigDecimal.valueOf(seconds % 60)
            .add(BigDecimal.valueOf(micros % MICROS_PER_SECOND, 6));
      case "EPOCH":
        {
          // seconds with six decimals: years of 365.25 days, months of 30 days
          BigDecimal secs =
              BigDecimal.valueOf((months / 12) * 31_557_600L + (months % 12) * 2_592_000L)
                  .add(BigDecimal.valueOf(days * 86_400L));
          return secs.add(BigDecimal.valueOf(micros, 6));
        }
      default:
        throw new PgException.Argument("0A000", "Unsupported EXTRACT field for interval: " + field);
    }
  }

  /** Months as 30 days and days as 24 hours: how PostgreSQL compares intervals. */
  private long normalized() {
    return (months * 30L + days) * MICROS_PER_DAY + micros;
  }

  @Override
  public int compareTo(Interval o) {
    return Long.compare(normalized(), o.normalized());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Interval && normalized() == ((Interval) o).normalized();
  }

  @Override
  public int hashCode() {
    return Long.hashCode(normalized());
  }

  /** PostgreSQL's default output, such as {@code 1 year 2 mons 3 days 04:05:06.5}. */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    // as PostgreSQL's EncodeInterval: a positive field after a negative one is signed, -1 days
    // +02:00
    boolean before = part(sb, months / 12, "year", false);
    before = part(sb, months % 12, "mon", before);
    before = part(sb, days, "day", before);
    if (micros != 0 || sb.length() == 0) {
      long seconds =
          Math.abs(micros / MICROS_PER_SECOND); // Math.abs(micros) fails at Long.MIN_VALUE
      long fraction = Math.abs(micros % MICROS_PER_SECOND);
      String time =
          String.format("%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
      if (fraction != 0) {
        time += String.format(".%06d", fraction).replaceAll("0+$", "");
      }
      sb.append(sb.length() > 0 ? " " : "")
          .append(micros < 0 ? "-" : before ? "+" : "")
          .append(time);
    }
    return sb.toString();
  }

  /** Appends a nonzero field; whether the last field written is negative. */
  private static boolean part(StringBuilder sb, long value, String unit, boolean before) {
    if (value == 0) {
      return before;
    }
    sb.append(sb.length() > 0 ? " " : "").append(before && value > 0 ? "+" : "").append(value);
    sb.append(' ').append(unit);
    if (value != 1) {
      sb.append('s'); // PostgreSQL pluralizes everything but exactly 1
    }
    return value < 0;
  }
}
