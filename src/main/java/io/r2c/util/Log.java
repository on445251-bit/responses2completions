package io.r2c.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal leveled logger.
 *
 * <p>Writes to stdout (which the container runtime or logcat collects) and
 * optionally forwards each formatted line to a {@link Sink}. The Android app uses
 * the sink to mirror the proxy log into its own UI and the floating window.
 */
public final class Log {

    public enum Level {
        ERROR(0), WARN(1), INFO(2), DEBUG(3);

        final int rank;

        Level(int rank) {
            this.rank = rank;
        }
    }

    /** Receives already formatted log lines. Must be thread safe. */
    public interface Sink {
        void onLog(String line);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile Level threshold = Level.INFO;
    private static volatile Sink sink;

    private Log() {}

    public static void init(Level level) {
        if (level != null) threshold = level;
    }

    /** Installs (or clears, with null) a UI sink. */
    public static void setSink(Sink s) {
        sink = s;
    }

    private static void write(Level level, String msg, Throwable t) {
        if (level.rank > threshold.rank) return;
        String line = LocalTime.now().format(TIME) + " " + level.name() + " " + msg;
        System.out.println(line);
        System.out.flush();
        Sink s = sink;
        if (s != null) {
            try {
                s.onLog(line);
            } catch (RuntimeException ignored) {
                // A broken UI sink must never break request handling.
            }
        }
        if (t != null && threshold.rank >= Level.DEBUG.rank) {
            t.printStackTrace(System.out);
        }
    }

    public static void error(String msg) {
        write(Level.ERROR, msg, null);
    }

    public static void error(String msg, Throwable t) {
        write(Level.ERROR, msg + " :: " + t, t);
    }

    public static void warn(String msg) {
        write(Level.WARN, msg, null);
    }

    public static void info(String msg) {
        write(Level.INFO, msg, null);
    }

    public static void debug(String msg) {
        write(Level.DEBUG, msg, null);
    }

    public static boolean debugEnabled() {
        return threshold.rank >= Level.DEBUG.rank;
    }
}