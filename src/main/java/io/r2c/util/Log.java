package io.r2c.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Minimal leveled logger writing to stdout, which is what container runtimes collect. */
public final class Log {

    public enum Level {
        ERROR(0), WARN(1), INFO(2), DEBUG(3);

        final int rank;

        Level(int rank) {
            this.rank = rank;
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile Level threshold = Level.INFO;

    private Log() {}

    public static void init(Level level) {
        if (level != null) threshold = level;
    }

    private static void write(Level level, String msg) {
        if (level.rank > threshold.rank) return;
        System.out.println(LocalTime.now().format(TIME) + " " + level.name() + " " + msg);
        System.out.flush();
    }

    public static void error(String msg) {
        write(Level.ERROR, msg);
    }

    public static void error(String msg, Throwable t) {
        write(Level.ERROR, msg + " :: " + t);
        if (threshold.rank >= Level.DEBUG.rank) t.printStackTrace(System.out);
    }

    public static void warn(String msg) {
        write(Level.WARN, msg);
    }

    public static void info(String msg) {
        write(Level.INFO, msg);
    }

    public static void debug(String msg) {
        write(Level.DEBUG, msg);
    }

    public static boolean debugEnabled() {
        return threshold.rank >= Level.DEBUG.rank;
    }
}