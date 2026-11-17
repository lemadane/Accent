package accent.compiler;

import accent.source.SourceFile;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies language examples in the owning repository, without a ServerSide checkout. */
class LanguageDocumentationTest {
    @TempDir Path directory;

    private void accepts(String source) {
        var result = new AccentCompiler().check(TestSources.sources(source));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test void documentedExamplesCompile() throws Exception {
        String section = Files.readString(Path.of("../README.md")).split("#### Non-null array and list elements", 2)[1]
                .split("#### Non-null method results", 2)[0];
        var matcher = java.util.regex.Pattern.compile("```accent\\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(section);
        assertTrue(matcher.find());
        var result = new AccentCompiler().check(List.of(new SourceFile(Path.of("Names.accent"), matcher.group(1))));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }
    @Test void documentedMethodExamplesCompile() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String section = readme.split("#### Non-null method results", 2)[1].split("### 2.", 2)[0];
        var snippets = java.util.regex.Pattern.compile("```accent\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(section);
        StringBuilder source = new StringBuilder();
        while (snippets.find()) source.append(snippets.group(1)).append("\n");
        assertFalse(source.isEmpty());
        accepts(source.toString());
    }

    @Test
    void documentedInheritanceDemoCompilesAndExecutes() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String readme = Files.readString(root.resolve("README.md"));
        Path demo = Files.createDirectories(directory.resolve("inheritance"));
        for (String name : List.of("Counter", "FastCounter")) {
            String section = readme.split("\\*\\*" + name + "[.]accent\\*\\*", 2)[1];
            String example = section.split("```accent\\n", 2)[1].split("```", 2)[0];
            Files.writeString(demo.resolve(name + ".accent"), example);
        }
        var result = new AccentCompiler().compile(List.of(demo), directory.resolve("classes"),
                List.of(System.getProperty("accent.test.runtimeClasspath")), directory.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.resolve("classes").toUri().toURL()})) {
            Class<?> type = loader.loadClass("FastCounter");
            Object instance = type.getConstructor().newInstance();
            type.getMethod("increment").invoke(instance);
            assertEquals(3, type.getField("total").get(instance));
            assertEquals("fast / counter", type.getMethod("summary").invoke(instance));
        }
    }

}
