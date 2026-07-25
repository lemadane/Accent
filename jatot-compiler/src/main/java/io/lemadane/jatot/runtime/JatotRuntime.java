package io.lemadane.jatot.runtime;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/** Prototype runtime support for Jatot async/await lowering. */
public final class JatotRuntime {
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong();

    private JatotRuntime() {
    }

    public static <T> JatotFuture<T> async(Callable<T> operation) {
        Objects.requireNonNull(operation, "operation");

        FutureTask<T> task = new FutureTask<>(operation);
        Thread.ofVirtual()
                .name("jatot-task-", TASK_SEQUENCE.incrementAndGet())
                .start(task);

        return new JatotFuture<>(task);
    }

    public static JatotFuture<Void> async(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        return async(() -> {
            operation.run();
            return null;
        });
    }
}
