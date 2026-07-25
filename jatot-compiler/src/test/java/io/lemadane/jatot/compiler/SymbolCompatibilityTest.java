package io.lemadane.jatot.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SymbolCompatibilityTest {

    private String symbol = "value";

    public String symbol() {
        return symbol;
    }

    @Test
    public void testSymbolKeywordCompatibility() {
        SymbolCompatibilityTest test = new SymbolCompatibilityTest();
        assertEquals("value", test.symbol());
        
        // This valid Java must remain valid Jatot
        String symbol = "local_value";
        assertEquals("local_value", symbol);
    }
}
