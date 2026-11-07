package accent.logging;

import accent.runtime.Async;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LoggerContextTest {
    private static final Logger log = LogManager.getLogger("context-test");

    @AfterEach void clear() { log.clearContext(); }

    @Test void supportsMutationRemovalAndClear() {
        assertNull(log.get("requestId"));
        assertEquals(Map.of(), log.contextMap());
        log.put("requestId", "first");
        log.put("requestId", "second");
        log.put("customerId", "C1");
        assertEquals("second", log.get("requestId"));
        log.remove("requestId");
        assertNull(log.get("requestId"));
        assertEquals("C1", log.get("customerId"));
        log.clearContext();
        assertTrue(log.contextMap().isEmpty());
        log.remove("missing");
    }

    @Test void snapshotsAndSetContextAreDefensive() {
        var input = new HashMap<>(Map.of("requestId", "one"));
        log.setContextMap(input);
        input.put("requestId", "two");
        var snapshot = log.contextMap();
        log.put("requestId", "three");
        assertEquals(Map.of("requestId", "one"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", "y"));
        log.setContextMap(Map.of());
        assertNull(log.get("requestId"));
    }

    @Test void rejectsNullInputsWithoutLosingExistingContext() {
        log.put("id", "valid");
        assertThrows(NullPointerException.class, () -> log.put(null, "value"));
        assertThrows(NullPointerException.class, () -> log.put("key", null));
        assertThrows(NullPointerException.class, () -> log.get(null));
        assertThrows(NullPointerException.class, () -> log.remove(null));
        assertThrows(NullPointerException.class, () -> log.setContextMap(null));
        var invalid = new HashMap<String, String>();
        invalid.put("id", null);
        assertThrows(NullPointerException.class, () -> log.setContextMap(invalid));
        assertEquals("valid", log.get("id"));
    }

    @Test void eventsCaptureImmutableContextAndFormatDeterministically() {
        log.put("requestId", "R1");
        log.put("customerId", "C1");
        var event = new LogEvent(Instant.EPOCH, LogLevel.INFO, "service", "thread", "hello", null);
        log.clearContext();
        assertEquals(Map.of("requestId", "R1", "customerId", "C1"), event.mdc());
        assertThrows(UnsupportedOperationException.class, () -> event.mdc().clear());
        assertTrue(new DefaultLogFormatter().format(event).endsWith("hello {customerId=C1, requestId=R1}"));
        var empty = new LogEvent(Instant.EPOCH, LogLevel.INFO, "service", "thread", "hello", null);
        assertTrue(new DefaultLogFormatter().format(empty).endsWith("service - hello"));
        var input = new HashMap<>(Map.of("id", "one"));
        var explicit = new LogEvent(Instant.EPOCH, LogLevel.INFO, "s", "t", "m", null, input);
        input.clear();
        assertEquals(Map.of("id", "one"), explicit.mdc());
    }

    @Test void asyncCapturesAtSubmissionAndIsolatesParentSiblingsAndNestedTasks() throws Exception {
        log.put("id", "parent");
        var release = new CountDownLatch(1);
        var first = Async.run(() -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            assertEquals("parent", log.get("id"));
            log.put("id", "child");
            var nested = Async.run(() -> {
                assertEquals("child", log.get("id"));
                log.put("id", "grandchild");
                return log.get("id");
            });
            assertEquals("grandchild", nested.await());
            return log.get("id");
        });
        log.put("id", "changed");
        var sibling = Async.run(() -> log.get("id"));
        release.countDown();
        assertEquals("child", first.await());
        assertEquals("changed", sibling.await());
        assertEquals("changed", log.get("id"));
        log.clearContext();
        assertNull(Async.run(() -> log.get("id")).await());
    }

    @Test void voidTasksAndFailuresDoNotChangeParentContext() {
        log.put("id", "parent");
        Async.run((Async.Action) () -> {
            assertEquals("parent", log.get("id"));
            log.clearContext();
        }).await();
        var failed = Async.run((Async.Action) () -> {
            log.put("id", "failure");
            throw new IllegalStateException("expected");
        });
        assertThrows(IllegalStateException.class, failed::await);
        assertEquals("parent", log.get("id"));
    }

    @Test void withContextAddsFieldsAndRestoresPreviousContext() {
        log.put("id", "outer");
        String inner = log.withContext(Map.of("id", "inner", "extra", "x"), () -> log.get("id") + log.get("extra"));
        assertEquals("innerx", inner);
        assertEquals(Map.of("id", "outer"), log.contextMap());
        assertThrows(IllegalStateException.class, () -> log.withContext(Map.of("extra", "y"), (Runnable) () -> {
            throw new IllegalStateException("expected");
        }));
        assertEquals(Map.of("id", "outer"), log.contextMap());
    }

    @Test void contextIsSharedAcrossLoggersOnSameThread() {
        log.put("id", "shared");
        assertEquals("shared", LogManager.getLogger("other").get("id"));
    }
}
