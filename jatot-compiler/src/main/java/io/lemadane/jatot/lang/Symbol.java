package io.lemadane.jatot.lang;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Symbol<T> {
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private static final ConcurrentMap<String, Symbol<Object>> REGISTRY = new ConcurrentHashMap<>();

    private final long identity;
    private final String description;
    private final String registryKey;

    private Symbol(String description, String registryKey) {
        this.identity = NEXT_ID.incrementAndGet();
        this.description = description;
        this.registryKey = registryKey;
    }

    public static <T> Symbol<T> create() {
        return new Symbol<>(null, null);
    }

    public static <T> Symbol<T> create(String description) {
        return new Symbol<>(description, null);
    }

    public static Symbol<Object> forKey(String key) {
        Objects.requireNonNull(key, "key");
        return REGISTRY.computeIfAbsent(
                key,
                value -> new Symbol<>(value, value)
        );
    }

    public static String keyFor(Symbol<?> symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return symbol.registryKey;
    }

    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(identity);
    }

    @Override
    public String toString() {
        return description == null
                ? "Symbol()"
                : "Symbol(" + description + ")";
    }
}
