package accent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AccentRuntimeTest {
    @Test
    void runsOperationOnVirtualThread() {
        AccentFuture<Boolean> future = AccentRuntime.async(() ->
                Thread.currentThread().isVirtual());

        assertTrue(future.await());
    }

    @Test
    void startsOperationsBeforeTheyAreAwaited() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        AccentFuture<String> first = AccentRuntime.async(() -> {
            bothStarted.countDown();
            release.await();
            return "first";
        });

        AccentFuture<String> second = AccentRuntime.async(() -> {
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
        AccentFuture<String> future = AccentRuntime.async(() -> {
            throw new IllegalStateException("failure");
        });

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, future::await);

        assertEquals("failure", exception.getMessage());
    }
}
