package accent.compiler;

import accent.lexer.AccentLexer;
import accent.parser.AccentParser;
import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BodylessDataDeclarationTest {
    @TempDir Path temp;

    @Test void bodylessDeclarationsCompileAndRetainGeneratedBehavior() throws Exception {
        Path sources = Files.createDirectory(temp.resolve("sources"));
        Files.writeString(sources.resolve("Address.accent"), "record Address(String city)");
        Files.writeString(sources.resolve("Profile.accent"), "model Profile(String name, Address address)");
        Files.writeString(sources.resolve("Box.accent"), "record Box<T>(T value) implements java.io.Serializable");
        Files.writeString(sources.resolve("Defaults.accent"), "singleton record Defaults(int count = 1)");
        Files.writeString(sources.resolve("Settings.accent"), "singleton model Settings(int count = 2)");
        Files.writeString(sources.resolve("Label.accent"), "record Label(String value) { String text() { return this.value; } }");
        Files.writeString(sources.resolve("Named.accent"), "model Named(String value) { String text() { return this.value; } }");
        Files.writeString(sources.resolve("Example.accent"), """
                class Example {
                    static String run() {
                        final profile = new Profile("John", new Address("Manila"));
                        profile.name("Jane");
                        return profile.name() + ":" + profile.address().city();
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(sources), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            assertEquals("Jane:Manila", loader.loadClass("Example").getMethod("run").invoke(null));
        }
    }

    @Test void rejectsEmptyAndCommentOnlyBodiesAtParseTime() {
        for (String kind : List.of("record", "model", "singleton record", "singleton model")) {
            for (String body : List.of("{}", "{ /* documentation */ }", "{\n// documentation\n}")) {
                var source = new SourceFile(Path.of("Empty.accent"), kind + " Empty() " + body);
                var parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
                parser.parse();
                assertTrue(parser.diagnostics().stream().anyMatch(d -> d.message().contains("bodies are not allowed")),
                        kind + body + parser.diagnostics());
            }
        }
    }

    @Test void javaRecordsStillRequireAndAllowEmptyBodies() {
        for (String declaration : List.of("public record JavaRecord(String value) {}",
                "public record JavaRecord(String value)")) {
            var source = new SourceFile(Path.of("JavaRecord.java"), declaration);
            var parser = new AccentParser(source, new AccentLexer(source).lex().tokens());
            parser.parse();
            assertEquals(!declaration.endsWith("{}"), !parser.diagnostics().isEmpty());
        }
    }
}
