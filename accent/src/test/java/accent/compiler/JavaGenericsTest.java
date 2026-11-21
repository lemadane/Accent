package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JavaGenericsTest {
    @TempDir Path root;

    private CompilationResult compile(String source) throws Exception {
        Path file = root.resolve("Generics.accent");
        Files.writeString(file, "import java.util.*\n" + source);
        return new AccentCompiler().compile(List.of(file), root.resolve("classes"), List.of(), root.resolve("java"), true);
    }

    private String succeeds(String source) throws Exception {
        var result = compile(source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        return Files.readString(root.resolve("java/Generics.java"));
    }

    @Test void wildcardsBoundsAndNestedArguments() throws Exception {
        String java = succeeds("""
            public class Generics<T extends Number & Comparable<T>> {
                static void copy(List<? extends Number> source, List<? super Number> target) {}
                static void nested(Map<String, List<? extends Number[]>> values) {}
                static void any(List<?> values) {}
                static <U extends Number & Comparable<U>> U identity(U value) { return value }
                static <U extends Number & Comparable<U>> int compare(U left, U right) { return left.compareTo(right) }
            }
            """);
        assertTrue(java.contains("T extends Number & Comparable<T>"));
        assertTrue(java.contains("List<? super Number>"));
        assertTrue(java.contains("Map<String, List<? extends Number[]>>"));
    }

    @Test void diamondIsPreserved() throws Exception {
        String java = succeeds("""
            public class Generics {
                static Map<String, List<Integer>> create() { return new HashMap<>() }
            }
            """);
        assertTrue(java.contains("new HashMap<>()"));
    }

    @Test void genericMethodsAndInheritedMembersRetainResultTypes() throws Exception {
        succeeds("""
            public class Generics {
                static int length(Map<String, String> values) { return values.get("a").length() }
                static int inherited(TreeMap<String, String> values) { return values.get("a").length() }
                static int explicit() { return Collections.<String>singletonList("hello").get(0).length() }
                static int inferred() { return Collections.singletonList("hello").get(0).length() }
                static int varargs(String[] values) { return Arrays.asList(values).get(0).length() }
            }
            """);
    }

    @Test void parameterizedOwnerTypes() throws Exception {
        String java = succeeds("""
            public class Generics {
                static void entry(Map.Entry<String, Integer> entry) {}
                static void inner(java.util.concurrent.ConcurrentHashMap.KeySetView<String, Integer> view) {}
            }
            """);
        assertTrue(java.contains("Map.Entry<String, Integer>"));
    }

    @Test void genericSourceDeclarationsExecute() throws Exception {
        succeeds("""
            public class Generics<T extends Number> {
                <U extends T> Generics(U value) {}
                static <U extends Number> int number(U value) { return value.intValue() }
                static <U> U identity(U value) { return value }
                static int run() { return static.number(42) + static.identity("hello").length() }
                static Generics<Number> construct() { return new <Integer> Generics<Number>(42) }
                static int boxed() { return static.identity(42).intValue() }
                static <U> U[] array(U[] values) { return values }
            }
            """);
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader())) {
            assertEquals(47, loader.loadClass("Generics").getMethod("run").invoke(null));
        }
    }

    @Test void parameterizedInnerClassPreservesBothOwnersAndMembers() throws Exception {
        Path fixture = root.resolve("GenericOuter.java");
        Files.writeString(fixture, """
            package fixture;
            public class GenericOuter<T> {
                public class Inner<U> {
                    public T outerValue;
                    public U innerValue;
                    public T outer() { return outerValue; }
                    public U inner() { return innerValue; }
                }
            }
            """);
        Path library = root.resolve("library");
        Files.createDirectories(library);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", library.toString(), fixture.toString()));
        Path source = root.resolve("Generics.accent");
        Files.writeString(source, """
            import fixture.GenericOuter
            public class Generics {
                static int outer(GenericOuter<String>.Inner<Integer> value) { return value.outer().length() }
                static int inner(GenericOuter<String>.Inner<Integer> value) { return value.inner().intValue() }
                static int field(GenericOuter<String>.Inner<Integer> value) { return value.outerValue.length() }
            }
            """);
        var result = new AccentCompiler().compile(List.of(source), root.resolve("classes"),
                List.of(library.toString(), System.getProperty("accent.test.runtimeClasspath")), root.resolve("java"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(Files.readString(root.resolve("java/Generics.java")).contains("GenericOuter<String>.Inner<Integer>"));
    }

    @Test void boundedRecordMembersAndSourceInheritance() throws Exception {
        Path record = root.resolve("Box.accent");
        Files.writeString(record, """
            record Box<T extends Number>(T value) {
                int integer() { return this.value().intValue() }
            }
            """);
        Path parent = root.resolve("Parent.accent");
        Files.writeString(parent, """
            class Parent<T> {
                T echo(T value) { return value }
            }
            """);
        Path child = root.resolve("Generics.accent");
        Files.writeString(child, """
            class Generics extends Parent<String> {
                int length() { return super.echo("hello").length() }
                static int recordValue() { return new Box<Integer>(42).integer() }
            }
            """);
        var result = new AccentCompiler().compile(List.of(record, parent, child), root.resolve("classes"),
                List.of(), root.resolve("java"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test void invalidGenericArgumentsAreRejected() throws Exception {
        assertFalse(compile("class Generics { static void test(List<int> values) {} }").successful());
        assertFalse(compile("class Generics { static void test(List<? extends int> values) {} }").successful());
        assertFalse(compile("class Generics { static void test(List<String, Integer> values) {} }").successful());
        assertFalse(compile("class Generics<T extends Number> { static void test(Generics<String> value) {} }").successful());
        assertFalse(compile("class Generics { static void test(List<? extends Number> values) { values.add(1) } }").successful());
        assertFalse(compile("class Generics { static List<String> test() { return new ArrayList<Integer>() } }").successful());
    }
}
