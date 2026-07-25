package io.lemadane.jatot.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runtime value produced by a future Jatot {@code async expression} lowering.
 */
public final class JatotFuture<T> {
    private final Future<T> delegate;

    JatotFuture(Future<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

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
            throw new JatotExecutionException(exception);
        }
    }

    public boolean cancel() {
        return delegate.cancel(true);
    }

    public boolean isDone() {
        return delegate.isDone();
    }

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
        return new JatotExecutionException(cause);
    }
}
