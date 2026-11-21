package accent.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeOfRuntimeTest {
    @Test void normalizesEveryBoxedPrimitive() {
        assertSame(boolean.class, AccentRuntime.typeOf(true));
        assertSame(byte.class, AccentRuntime.typeOf((byte) 1));
        assertSame(short.class, AccentRuntime.typeOf((short) 1));
        assertSame(int.class, AccentRuntime.typeOf(1));
        assertSame(long.class, AccentRuntime.typeOf(1L));
        assertSame(float.class, AccentRuntime.typeOf(1.0f));
        assertSame(double.class, AccentRuntime.typeOf(1.0));
        assertSame(char.class, AccentRuntime.typeOf('a'));
    }

    @Test void preservesReferenceAndArrayClasses() {
        assertSame(String.class, AccentRuntime.typeOf("Ada"));
        assertSame(int[].class, AccentRuntime.typeOf(new int[0]));
        assertSame(Integer[].class, AccentRuntime.typeOf(new Integer[0]));
        assertSame(java.util.ArrayList.class, AccentRuntime.typeOf(new java.util.ArrayList<String>()));
        assertSame(Class.class, AccentRuntime.typeOf(int.class));
    }

    @Test void rejectsNull() {
        assertThrows(NullPointerException.class, () -> AccentRuntime.typeOf(null));
    }
}
