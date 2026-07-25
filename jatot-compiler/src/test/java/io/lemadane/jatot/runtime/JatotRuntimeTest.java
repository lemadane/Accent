package io.lemadane.jatot.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JatotRuntimeTest {
    @Test
    void runsOperationOnVirtualThread() {
        JatotFuture<Boolean> future = JatotRuntime.async(() ->
                Thread.currentThread().isVirtual());

        assertTrue(future.await());
    }

    @Test
    void startsOperationsBeforeTheyAreAwaited() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        JatotFuture<String> first = JatotRuntime.async(() -> {
            bothStarted.countDown();
            release.await();
            return "first";
        });

        JatotFuture<String> second = JatotRuntime.async(() -> {
            bothStarted.countDown();
            release.await();
            return "second";
        });

        assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
        release.countDown();

        assertEquals("first", first.await());
        assertEquals("second", second.await());
    }

    @Test
    void rethrowsRuntimeFailureAtAwait() {
        JatotFuture<String> future = JatotRuntime.async(() -> {
            throw new IllegalStateException("failure");
        });

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, future::await);

        assertEquals("failure", exception.getMessage());
    }
}
