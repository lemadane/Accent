package accent.runtime;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/** Prototype runtime support for Accent async/await lowering. */
public final class AccentRuntime {
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong();

    private AccentRuntime() {
    }

    public static <T> AccentFuture<T> async(Callable<T> operation) {
        Objects.requireNonNull(operation, "operation");

        FutureTask<T> task = new FutureTask<>(operation);
        Thread.ofVirtual()
                .name("accent-task-", TASK_SEQUENCE.incrementAndGet())
                .start(task);

        return new AccentFuture<>(task);
    }

    public static AccentFuture<Void> async(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        return async(() -> {
            operation.run();
            return null;
        });
    }

    /** Evaluates Javascript-style truthiness for any Object or reference value. */
    public static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            if (value instanceof Double d) {
                return d != 0.0 && !d.isNaN();
            }
            if (value instanceof Float f) {
                return f != 0.0f && !f.isNaN();
            }
            return n.longValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Character c) {
            return c != '\0';
        }
        return true;
    }

    public static boolean isTruthy(boolean value) {
        return value;
    }

    public static boolean isTruthy(int value) {
        return value != 0;
    }

    public static boolean isTruthy(long value) {
        return value != 0L;
    }

    public static boolean isTruthy(double value) {
        return value != 0.0 && !Double.isNaN(value);
    }

    public static boolean isTruthy(float value) {
        return value != 0.0f && !Float.isNaN(value);
    }

    public static boolean isFalsy(Object value) {
        return !isTruthy(value);
    }

    public static boolean isFalsy(boolean value) {
        return !value;
    }

    public static boolean isFalsy(int value) {
        return value == 0;
    }

    public static boolean isFalsy(long value) {
        return value == 0L;
    }

    public static boolean isFalsy(double value) {
        return value == 0.0 || Double.isNaN(value);
    }

    public static boolean isFalsy(float value) {
        return value == 0.0f || Float.isNaN(value);
    }
}

