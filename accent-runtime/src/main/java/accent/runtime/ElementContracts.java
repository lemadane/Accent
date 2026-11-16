package accent.runtime;

import java.util.*;
import java.util.function.UnaryOperator;

/** Runtime boundaries for Accent's non-null element contracts. */
public final class ElementContracts {
    private ElementContracts() {}

    public static <E> List<E> protect(List<E> source) {
        Objects.requireNonNull(source, "Non-null list");
        if (source instanceof GuardedList<?>) return source;
        return new GuardedList<>(source);
    }

    public static <E> E[] protect(E[] source) {
        E[] copy = Objects.requireNonNull(source, "Non-null array").clone();
        for (E value : copy) Objects.requireNonNull(value, "Non-null array element");
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static <T> T protectNullable(T source) {
        if (source == null) return null;
        if (source instanceof List<?> list) return (T) protect(list);
        if (source instanceof Object[] array) return (T) protect(array);
        throw new IllegalArgumentException("Element contracts require a reference array or List");
    }

    private static final class GuardedList<E> extends AbstractList<E> implements RandomAccess {
        private final ArrayList<E> values;
        GuardedList(Collection<? extends E> source) { values = checkedCopy(source); }
        private static <E> ArrayList<E> checkedCopy(Collection<? extends E> source) {
            ArrayList<E> copy = new ArrayList<>(source);
            copy.forEach(value -> Objects.requireNonNull(value, "Non-null list element"));
            return copy;
        }
        @Override public E get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
        @Override public E set(int index, E value) { return values.set(index, Objects.requireNonNull(value, "Non-null list element")); }
        @Override public void add(int index, E value) {
            values.add(index, Objects.requireNonNull(value, "Non-null list element")); modCount++;
        }
        @Override public boolean addAll(Collection<? extends E> source) { return addAll(size(), source); }
        @Override public boolean addAll(int index, Collection<? extends E> source) {
            boolean changed = values.addAll(index, checkedCopy(source));
            if (changed) modCount++;
            return changed;
        }
        @Override public E remove(int index) { E old = values.remove(index); modCount++; return old; }
        @Override public void replaceAll(UnaryOperator<E> operator) {
            Objects.requireNonNull(operator);
            ArrayList<E> replacement = new ArrayList<>(values.size());
            for (E value : values) replacement.add(Objects.requireNonNull(operator.apply(value), "Non-null list element"));
            for (int i = 0; i < replacement.size(); i++) values.set(i, replacement.get(i));
        }
    }
}
