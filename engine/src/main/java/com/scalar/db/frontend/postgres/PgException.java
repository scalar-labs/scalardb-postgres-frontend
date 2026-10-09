package com.scalar.db.frontend.postgres;

/**
 * An error of the frontend's own that knows its PostgreSQL SQLSTATE, so the code a client sees does
 * not depend on how the message is worded. The two kinds keep the Java types the engine's catch
 * blocks rely on: a bad statement or value, and a wrong transaction state.
 */
interface PgException {
  /** The five-character SQLSTATE sent to the client. */
  String sqlState();

  /** A statement or value PostgreSQL would refuse. */
  final class Argument extends IllegalArgumentException implements PgException {
    private static final long serialVersionUID = 1L;
    private final String sqlState;

    Argument(String sqlState, String message) {
      super(message);
      this.sqlState = sqlState;
    }

    Argument(String sqlState, String message, Throwable cause) {
      super(message, cause);
      this.sqlState = sqlState;
    }

    @Override
    public String sqlState() {
      return sqlState;
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
}
