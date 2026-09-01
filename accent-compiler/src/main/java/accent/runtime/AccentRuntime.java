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
}
