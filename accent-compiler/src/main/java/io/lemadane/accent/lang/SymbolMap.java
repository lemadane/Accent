package io.lemadane.accent.lang;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class SymbolMap {
    private final Map<Symbol<?>, Object> values = new HashMap<>();

    public <T> void put(Symbol<T> symbol, T value) {
        Objects.requireNonNull(symbol, "symbol");
        values.put(symbol, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Symbol<T> symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return (T) values.get(symbol);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(Symbol<T> symbol, T defaultValue) {
        Objects.requireNonNull(symbol, "symbol");
        return values.containsKey(symbol) ? (T) values.get(symbol) : defaultValue;
    }

    public boolean contains(Symbol<?> symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return values.containsKey(symbol);
    }

    @SuppressWarnings("unchecked")
    public <T> T remove(Symbol<T> symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return (T) values.remove(symbol);
    }

    public void clear() {
        values.clear();
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
