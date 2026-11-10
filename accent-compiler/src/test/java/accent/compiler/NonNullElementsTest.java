package accent.compiler;

import accent.source.SourceFile;
import accent.runtime.ElementContracts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NonNullElementsTest {
    @TempDir Path temp;
    private CompilationResult check(String body) {
        return new AccentCompiler().check(List.of(new SourceFile(Path.of("Sample.accent"),
                "import java.util.List; import java.util.ArrayList; class Sample { " + body + " }")));
    }
    private void accepts(String body) {
        var result = check(body);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }
    private void rejects(String body) {
        var result = check(body);
        assertFalse(result.successful(), body);
    }
    @Test void declarationsAndUsesCarryBothContracts() {
        accepts("void run() { List<String> names!! = new ArrayList<String>(); names!!.add(\"John\"); String name! = names!!.get!(0); }");
        rejects("void run() { List<String> names!!; }");
        rejects("void run(List<String> names!!) { names!.clear(); }");
        rejects("void run(List<String> names!) { names!!.clear(); }");
        rejects("void run() { String name!! = \"John\"; }");
    }
    @Test void mutationsRequireProvenNonNullElements() {
        rejects("void run(List<String> names!!) { names!!.add(null); }");
        rejects("void run(List<String> names!!, String user) { names!!.add(user); }");
        rejects("void run(List<String> names!!) { names!!.set!(0, null); }");
        rejects("void run(List<String> names!!, List<String> other!) { names!!.addAll(other!); }");
        accepts("void run(List<String> names!!, String user) { if (user != null) { names!!.add(user); } }");
        accepts("void run(List<String> names!!, String user!) { names!!.add(user!); }");
    }
    @Test void arrayElementsAreCheckedAtInitializationAndAssignment() {
        accepts("void run() { String[] names!! = {\"John\", \"Jane\"}; String name! = names![0]!; names![0]! = \"Jim\"; }");
        rejects("void run() { String[] names!! = {\"John\", null}; }");
        rejects("void run() { String[] names!! = new String[2]; }");
        rejects("void run(String[] names!!) { names![0]! = null; }");
        rejects("void run(String[] names!!) { String value = names![0]; }");
        rejects("void run(String[] names!) { String value! = names![0]!; }");
    }
    @Test void aliasesCannotEraseElementContracts() {
        rejects("void run(List<String> names!!) { List<String> alias! = names!!; }");
        rejects("void run(String[] names!!) { String[] alias! = names!!; }");
        accepts("void run(List<String> names!!) { List<String> alias!! = names!!; alias!!.add(\"John\"); }");
    }
    @Test void fieldsParametersAndGenericAliasesKeepContracts() {
        accepts("List<String> names!! = new ArrayList<String>(); void run(String user!) { this.names!!.add(user!); }");
        rejects("void run(List<String> names!!) { List<Object> alias!! = names!!; }");
        rejects("void run(ArrayList<String> source!) { List<String> names!! = source!; }");
        rejects("List<String> run(List<String> names!!) { return names!!; }");
        rejects("Sample(List<String> names!!) {} static void run(List<String> source!) { final item = new Sample(source!); }");
        rejects("void run(String[][] names!!) {}");
        rejects("void run(List<String!> names!) {}");
    }
    @Test void factoriesAndInferredBindings() {
        accepts("void run() { List<String> names!! = List.of(\"John\"); final other!! = names!!; String value! = other!!.get!(0); }");
        accepts("void run() { List<String> names!! = new ArrayList<>(); }");
        rejects("void run() { List<String> names!! = List.of(null); }");
        rejects("void run(List<String> names!!) { names!!.replaceAll(value -> null); }");
    }
    @Test void compiledLibrariesKeepElementContracts() throws Exception {
        Path source = temp.resolve("Library.accent");
        Files.writeString(source, "import java.util.List; class Library { static String first!(List<String> names!!) { return names!!.get!(0); } }");
        Path library = temp.resolve("library");
        var libraryResult = new AccentCompiler().compile(List.of(source), library, List.of("build/classes/java/main"), temp.resolve("library-java"), true);
        assertTrue(libraryResult.successful(), () -> libraryResult.diagnostics().toString());
        Path consumer = temp.resolve("Consumer.accent");
        Files.writeString(consumer, "import java.util.List; class Consumer { String run!(List<String> names!!) { return Library.first!(names!!); } }");
        var result = new AccentCompiler().compile(List.of(consumer), temp.resolve("consumer"), List.of("build/classes/java/main", library.toString()), temp.resolve("consumer-java"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        Files.writeString(consumer, "import java.util.List; class Consumer { String run!(List<String> names!) { return Library.first!(names!); } }");
        var invalid = new AccentCompiler().compile(List.of(consumer), temp.resolve("invalid"), List.of("build/classes/java/main", library.toString()), temp.resolve("invalid-java"), true);
        assertFalse(invalid.successful());
    }
    @Test void documentedExamplesCompile() throws Exception {
        String section = Files.readString(Path.of("../README.md")).split("#### Non-null array and list elements", 2)[1]
                .split("#### Non-null method results", 2)[0];
        var matcher = java.util.regex.Pattern.compile("```accent\\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(section);
        assertTrue(matcher.find());
        var result = new AccentCompiler().check(List.of(new SourceFile(Path.of("Names.accent"), matcher.group(1))));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }
    @Test void checkedListsRejectJavaMutationPaths() {
        List<String> original = new ArrayList<>(List.of("John"));
        List<String> names = ElementContracts.protect(original);
        original.add(null);
        assertEquals(List.of("John"), names);
        assertThrows(NullPointerException.class, () -> names.add(null));
        assertThrows(NullPointerException.class, () -> names.set(0, null));
        assertThrows(NullPointerException.class, () -> names.addAll(Arrays.asList("Jane", null)));
        assertEquals(List.of("John"), names);
        var iterator = names.listIterator(); iterator.next();
        assertThrows(NullPointerException.class, () -> iterator.set(null));
        assertThrows(NullPointerException.class, () -> names.subList(0, 1).add(null));
        assertThrows(NullPointerException.class, () -> names.replaceAll(value -> null));
        assertThrows(NullPointerException.class, () -> ElementContracts.protect(new String[]{null}));
    }
    @Test void compiledCodeProtectsJavaBoundariesAndArrayReads() throws Exception {
        Path source = temp.resolve("Sample.accent");
        Files.writeString(source, """
                import java.util.List;
                class Sample {
                    static String first!(List<String> names!!) { return names!!.get!(0); }
                    static java.util.function.Supplier<String> capture!(List<String> names!!) {
                        java.util.function.Supplier<String> result! = () -> names!!.get!(0);
                        return result!;
                    }
                    static String array!() {
                        String[] names!! = {"John", "Jane"};
                        names!! = {"Jim", "Jane"};
                        names![0]! = "Jim";
                        return names![0]!;
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            var sample = loader.loadClass("Sample");
            assertEquals("Jim", sample.getMethod("array").invoke(null));
            assertEquals("John", sample.getMethod("first", List.class).invoke(null, List.of("John")));
            var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> sample.getMethod("first", List.class).invoke(null, Arrays.asList((String) null)));
            assertInstanceOf(NullPointerException.class, ex.getCause());
        }
    }
}
