package com.scalar.db.frontend.postgres;

/**
 * An error of the frontend's own that knows its PostgreSQL SQLSTATE, so the code a client sees does
 * not depend on how the message is worded. The two kinds keep the Java types the engine's catch
 * blocks rely on: a bad statement or value, and a wrong transaction state.
 */
interface PgException {
  /** The five-character SQLSTATE sent to the client. */
  String sqlState();

  /** PostgreSQL's DETAIL line for this error, or null when it has none. */
  @javax.annotation.Nullable default String detail() {
    return null;
  }

  /** A statement or value PostgreSQL would refuse. */
  final class Argument extends IllegalArgumentException implements PgException {
    private static final long serialVersionUID = 1L;
    private final String sqlState;
    @javax.annotation.Nullable private final String detail;

    Argument(String sqlState, String message) {
      this(sqlState, message, (String) null);
    }

    Argument(String sqlState, String message, @javax.annotation.Nullable String detail) {
      super(message);
      this.sqlState = sqlState;
      this.detail = detail;
    }

    Argument(String sqlState, String message, Throwable cause) {
      super(message, cause);
      this.sqlState = sqlState;
      this.detail = null;
    }

    @Override
    public String sqlState() {
      return sqlState;
    }

    @Override
    @javax.annotation.Nullable public String detail() {
      return detail;
    }
  }

  /** A statement not allowed in the session's transaction state. */
  final class State extends IllegalStateException implements PgException {
    private static final long serialVersionUID = 1L;
    private final String sqlState;

    State(String sqlState, String message) {
      super(message);
      this.sqlState = sqlState;
    }

    @Override
    public String sqlState() {
      return sqlState;
    }
  }

  /**
   * A value wholly above or below a column's range ({@code 40000} for a smallint): a comparison
   * with it is decided by its sign alone, so a predicate falls back to that constant.
   */
  final class OutOfRange extends IllegalArgumentException implements PgException {
    private static final long serialVersionUID = 1L;
    private final boolean above;

    OutOfRange(String message, boolean above) {
      super(message);
      this.above = above;
    }

    /** True when the value lies above the column's range, false when below. */
    boolean above() {
      return above;
    }

    @Override
    public String sqlState() {
      return "22003"; // numeric_value_out_of_range
    }
  }

  /**
   * A value within a column's range that has no exact stored form ({@code 1.005} for a
   * numeric(10,2), a string longer than a varchar(n)): equality matches nothing, and an ordering
   * comparison is evaluated in memory, never folded into a constant.
   */
  final class NotRepresentable extends IllegalArgumentException implements PgException {
    private static final long serialVersionUID = 1L;
    private final String sqlState;

    NotRepresentable(String sqlState, String message) {
      super(message);
      this.sqlState = sqlState;
    }

    @Override
    public String sqlState() {
      return sqlState;
    }
  }
}
