package accent.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Result of {@link #run(Callable)}, running on a virtual thread.
 * Accent {@code async expression} is compiler shorthand for
 * {@code Async.run(() -> expression)};
 * {@code await valueAsync} is shorthand for {@code valueAsync.await()}.
 * Call {@link #await()} to retrieve its result. Operations have independent lifetimes;
 * timeouts and interruption of an awaiter do not automatically cancel the operation.
 *
 * @param <T> result type, or {@link Void} for a void operation
 */
public final class Async<T> {
    private static final AtomicLong TASK_SEQUENCE = new AtomicLong();
    private final Future<T> delegate;

    /** A void operation that can throw a checked exception. */
    @FunctionalInterface
    public interface Action {
        /** @throws Exception if the operation fails */
        void run() throws Exception;
    }

    /**
     * Starts the operation immediately on a new virtual thread.
     * @param operation operation evaluated on the virtual thread
     * @param <T> result type
     * @return a handle for awaiting or cancelling the operation
     */
    public static <T> Async<T> run(Callable<T> operation) {
        Objects.requireNonNull(operation, "operation");
        FutureTask<T> task = new FutureTask<>(operation);
        Thread.ofVirtual().name("accent-task-", TASK_SEQUENCE.incrementAndGet()).start(task);
        return new Async<>(task);
    }

    /**
     * Starts a void operation, including one that throws checked exceptions.
     * @param operation operation evaluated on the virtual thread
     * @return a handle whose successful await returns null
     */
    public static Async<Void> run(Action operation) {
        Objects.requireNonNull(operation, "operation");
        return run(() -> {
            operation.run();
            return null;
        });
    }

    Async(Future<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Waits for and returns the result; multiple callers and repeated calls share it.
     * Runtime failures and errors are rethrown; checked failures are wrapped.
     *
     * @return the operation result
     * @throws CancellationException if cancelled or the awaiting thread is interrupted
     * @throws AccentExecutionException if the operation failed with a checked exception
     */
    public T await() {
        try {
            return delegate.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Awaiting thread was interrupted");
        } catch (ExecutionException exception) {
            throw rethrow(exception.getCause());
        }
    }

    /**
     * Waits for at most the supplied duration, without cancelling on timeout.
     *
     * @param timeout maximum wait duration
     * @return the operation result
     * @throws AccentExecutionException on timeout or a checked operation failure
     * @throws CancellationException if cancelled or the awaiting thread is interrupted
     */
    public T await(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            return delegate.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Awaiting thread was interrupted");
        } catch (ExecutionException exception) {
            throw rethrow(exception.getCause());
        } catch (TimeoutException exception) {
            throw new AccentExecutionException(exception);
        }
    }

    /**
     * Requests cancellation and interrupts running work, which must cooperate to stop.
     *
     * @return whether cancellation succeeded
     */
    public boolean cancel() {
        return delegate.cancel(true);
    }

    /** @return whether the operation succeeded, failed, or was cancelled */
    public boolean isDone() {
        return delegate.isDone();
    }

    /** @return whether the operation was cancelled */
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new AccentExecutionException(cause);
    }
}
