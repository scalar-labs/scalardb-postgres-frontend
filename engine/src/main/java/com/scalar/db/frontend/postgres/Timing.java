package com.scalar.db.frontend.postgres;

import com.scalar.db.api.Result;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits the time sessions spend handling protocol messages into the part spent waiting on ScalarDB
 * and the frontend's own part (parsing, planning, evaluating, encoding), for benchmarks. Off by
 * default; {@link #start} turns it on, wraps the manager and admin in a timing proxy and logs both
 * totals every interval. Times are summed over sessions, so with several sessions they exceed the
 * wall-clock interval; the share is what to read.
 */
public final class Timing {
  private static final Logger logger = LoggerFactory.getLogger(Timing.class);

  static volatile boolean enabled;
  static final LongAdder busy = new LongAdder(); // nanoseconds handling messages
  static final LongAdder scalardb = new LongAdder(); // nanoseconds inside ScalarDB calls
  static final LongAdder statements = new LongAdder(); // Query and Execute messages
  static final LongAdder calls = new LongAdder(); // ScalarDB calls

  private Timing() {}

  /**
   * Turns timing on and logs the totals of each interval.
   *
   * @param intervalSeconds how often to log
   */
  public static void start(long intervalSeconds) {
    enabled = true;
    ScheduledExecutorService log =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread t = new Thread(runnable, "postgres-frontend-timing");
              t.setDaemon(true);
              return t;
            });
    long[] last = new long[4];
    log.scheduleAtFixedRate(
        () -> {
          long[] now = {busy.sum(), scalardb.sum(), statements.sum(), calls.sum()};
          long b = now[0] - last[0];
          long s = now[1] - last[1];
          if (now[2] != last[2]) {
            logger.info(
                "last {} s: {} statements, {} ScalarDB calls; busy {} ms, in ScalarDB {} ms ({}%),"
                    + " frontend {} ms",
                intervalSeconds,
                now[2] - last[2],
                now[3] - last[3],
                b / 1_000_000,
                s / 1_000_000,
                b == 0 ? 0 : s * 100 / b,
                (b - s) / 1_000_000);
          }
          System.arraycopy(now, 0, last, 0, 4);
        },
        intervalSeconds,
        intervalSeconds,
        TimeUnit.SECONDS);
  }

  /** Counts the nanoseconds since {@code start} as message handling. */
  static void handled(long start, boolean statement) {
    busy.add(System.nanoTime() - start);
    if (statement) {
      statements.increment();
    }
  }

  /**
   * Wraps a ScalarDB API object so that every call on it, and on the transactions and scanners it
   * hands out, counts as time in ScalarDB.
   *
   * @param type the interface to expose
   * @param target the object to wrap
   * @param <T> the interface
   * @return the wrapped object
   */
  public static <T> T wrap(Class<T> type, T target) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              long start = System.nanoTime();
              Object out;
              try {
                out = method.invoke(target, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              } finally {
                scalardb.add(System.nanoTime() - start);
                calls.increment();
              }
              Class<?> r = method.getReturnType();
              // ponytail: only ScalarDB's own interfaces are followed; a Result is plain data
              return out != null
                      && r.isInterface()
                      && r.getName().startsWith("com.scalar.db.api.")
                      && r != Result.class
                  ? wrapAs(r, out)
                  : out;
            }));
  }

  private static <T> T wrapAs(Class<T> type, Object target) {
    return wrap(type, type.cast(target));
  }
}
