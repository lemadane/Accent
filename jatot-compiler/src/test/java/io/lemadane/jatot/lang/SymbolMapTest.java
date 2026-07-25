package io.lemadane.jatot.lang;

import org.junit.jupiter.api.Test;

import io.lemadane.jatot.lang.Symbol;
import io.lemadane.jatot.lang.SymbolMap;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class SymbolMapTest {

    @Test
    public void symbolMapTypedRetrieval() {
        Symbol<UUID> userId = Symbol.create("userId");
        Symbol<String> status = Symbol.create("status");

        UUID expectedId = UUID.randomUUID();

        SymbolMap values = new SymbolMap();
        values.put(userId, expectedId);
        values.put(status, "ACTIVE");

        assertEquals(expectedId, values.get(userId));
        assertEquals("ACTIVE", values.get(status));
    }

    @Test
    public void sameDescriptionInSymbolMap() {
        Symbol<String> first = Symbol.create("name");
        Symbol<String> second = Symbol.create("name");

        SymbolMap values = new SymbolMap();
        values.put(first, "Lemuel");
        values.put(second, "Jatot");

        assertEquals("Lemuel", values.get(first));
        assertEquals("Jatot", values.get(second));
    }

    @Test
    public void nullValues() {
        Symbol<String> note = Symbol.create("note");

        SymbolMap values = new SymbolMap();
        values.put(note, null);

        assertTrue(values.contains(note));
        assertNull(values.get(note));
    }

    @Test
    public void removal() {
        Symbol<String> status = Symbol.create("status");

        SymbolMap values = new SymbolMap();
        values.put(status, "ACTIVE");

        String removed = values.remove(status);

        assertEquals("ACTIVE", removed);
        assertFalse(values.contains(status));
    }

    @Test
    public void nullValidation() {
        SymbolMap values = new SymbolMap();

        assertThrows(NullPointerException.class, () -> values.put(null, "value"));
        assertThrows(NullPointerException.class, () -> values.get(null));
        assertThrows(NullPointerException.class, () -> values.getOrDefault(null, "default"));
        assertThrows(NullPointerException.class, () -> values.contains(null));
        assertThrows(NullPointerException.class, () -> values.remove(null));
    }
}
