package io.lemadane.accent.lang;

import org.junit.jupiter.api.Test;

import io.lemadane.accent.lang.Symbol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

public class SymbolTest {

    @Test
    public void uniqueIdentity() {
        Symbol<String> first = Symbol.create("id");
        Symbol<String> second = Symbol.create("id");

        assertNotSame(first, second);
        assertNotEquals(first, second);
    }

    @Test
    public void sameDescription() {
        Symbol<String> first = Symbol.create("id");
        Symbol<String> second = Symbol.create("id");

        assertEquals(first.description(), second.description());
    }

    @Test
    public void anonymousSymbols() {
        Symbol<String> symbol = Symbol.create();

        assertNull(symbol.description());
        assertEquals("Symbol()", symbol.toString());
    }

    @Test
    public void stringRepresentation() {
        Symbol<String> symbol = Symbol.create("userId");

        assertEquals("Symbol(userId)", symbol.toString());
    }

    @Test
    public void hashMapBehavior() {
        Symbol<String> first = Symbol.create("name");
        Symbol<String> second = Symbol.create("name");

        Map<Symbol<?>, Object> values = new HashMap<>();

        values.put(first, "First");
        values.put(second, "Second");

        assertEquals(2, values.size());
        assertEquals("First", values.get(first));
        assertEquals("Second", values.get(second));
    }

    @Test
    public void globalRegistryIdentity() {
        Symbol<Object> first = Symbol.forKey("application.user");
        Symbol<Object> second = Symbol.forKey("application.user");

        assertSame(first, second);
        assertEquals(first, second);
    }

    @Test
    public void differentRegistryKeys() {
        assertNotSame(Symbol.forKey("first"), Symbol.forKey("second"));
    }

    @Test
    public void registryKeyLookup() {
        Symbol<Object> registered = Symbol.forKey("application.user");
        Symbol<String> unique = Symbol.create("application.user");

        assertEquals("application.user", Symbol.keyFor(registered));
        assertNull(Symbol.keyFor(unique));
    }

    @Test
    public void nullValidation() {
        assertThrows(NullPointerException.class, () -> Symbol.forKey(null));
        assertThrows(NullPointerException.class, () -> Symbol.keyFor(null));
    }

    @Test
    public void concurrentUniqueCreation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<Symbol<String>>> futures = new ArrayList<>();

        for (int index = 0; index < 10_000; index++) {
            futures.add(executor.submit(() -> Symbol.create("shared")));
        }

        Map<Symbol<?>, Boolean> map = new HashMap<>();
        for (Future<Symbol<String>> future : futures) {
            Symbol<String> symbol = future.get();
            assertFalse(map.containsKey(symbol));
            map.put(symbol, true);
        }
        executor.shutdown();
    }

    @Test
    public void concurrentRegistryAccess() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<Symbol<Object>>> futures = new ArrayList<>();

        for (int index = 0; index < 1_000; index++) {
            futures.add(executor.submit(() -> Symbol.forKey("shared.key")));
        }

        Symbol<Object> expected = futures.get(0).get();

        for (Future<Symbol<Object>> future : futures) {
            assertSame(expected, future.get());
        }
        executor.shutdown();
    }
}
