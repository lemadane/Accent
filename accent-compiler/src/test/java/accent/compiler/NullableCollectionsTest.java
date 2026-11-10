package accent.compiler;

import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NullableCollectionsTest {
    @TempDir Path temp;

    @Test void executesDocumentedCombinationsAndSkipsIndicesForNullReceivers() throws Exception {
        var result = new AccentCompiler().compile(
                List.of(Path.of("src/e2e/examples/NullableCollectionsDemo.accent")), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("generated"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            assertEquals("John:missing:skipped:empty:John",
                    loader.loadClass("demo.NullableCollectionsDemo").getMethod("run").invoke(null));
        }
    }

    @Test void rejectsMissingElementMarkersAndNullableValuesInNonNullBindings() {
        for (String body : List.of(
                "void run(java.util.List<String?> names) { String value? = names.get(0); }",
                "void run(java.util.List<String> names?) { String value = names?.get(0); }",
                "void run(String[] names?) { String value = names?[0]; }",
                "void run(String?[] names) { String value? = names[0]; }",
                "void run(String name) { String value = name ?? \"fallback\"; }")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Sample.accent"), "class Sample { " + body + " }"));
            assertFalse(result.successful(), body);
        }
    }

    @Test void preservesNullableReturnMetadataAcrossSeparateCompilation() throws Exception {
        Path library = temp.resolve("Library.accent");
        Files.writeString(library, "class Library { static String name?() { return null; } }");
        Path classes = temp.resolve("library");
        var result = new AccentCompiler().compile(List.of(library), classes,
                List.of("build/classes/java/main"), temp.resolve("library-java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        Path consumer = temp.resolve("Consumer.accent");
        Files.writeString(consumer, "class Consumer { String value?() { return Library.name(); } }");
        result = new AccentCompiler().compile(List.of(consumer), temp.resolve("consumer"),
                List.of("build/classes/java/main", classes.toString()), temp.resolve("consumer-java"), true);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Nullable result requires")));
        Files.writeString(consumer, "class Consumer { String value() { return Library.name()? ?: \"fallback\"; } }");
        result = new AccentCompiler().compile(List.of(consumer), temp.resolve("consumer"),
                List.of("build/classes/java/main", classes.toString()), temp.resolve("consumer-java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
    }
}
