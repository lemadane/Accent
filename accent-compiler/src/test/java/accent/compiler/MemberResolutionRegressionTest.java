package accent.compiler;

import accent.diagnostic.DiagnosticSeverity;
import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemberResolutionRegressionTest {
    @TempDir
    Path tempDir;

    private Path write(String relative, String text) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private Path compileJavaApi(String methods) throws Exception {
        Path source = write("java/api/Calls.java", """
                package api;
                public class Calls {
                    %s
                }
                """.formatted(methods));
        Path library = tempDir.resolve("library");
        Files.createDirectories(library);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", library.toString(), source.toString()));
        return library;
    }

    private Object compileAndRun(String mainClass, List<Path> libraries) throws Exception {
        List<String> classpath = new ArrayList<>();
        classpath.add("build/classes/java/main");
        libraries.stream().map(Path::toString).forEach(classpath::add);
        CompilationResult result = new AccentCompiler().compile(List.of(tempDir.resolve("src")),
                tempDir.resolve("classes"), classpath, tempDir.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        List<java.net.URL> urls = new ArrayList<>();
        urls.add(tempDir.resolve("classes").toUri().toURL());
        for (Path library : libraries) urls.add(library.toUri().toURL());
        try (var loader = new URLClassLoader(urls.toArray(java.net.URL[]::new), getClass().getClassLoader())) {
            return loader.loadClass(mainClass).getMethod("run").invoke(null);
        }
    }

    @Test
    void reflectedPublicVarargsRemainCallableDespitePrivateFixedArityOverload() throws Exception {
        Path library = compileJavaApi("""
                public static String collect(String prefix, Object... values) { return prefix + values.length; }
                private static String collect(String prefix, int value) { return "private"; }
                """);
        write("src/client/Caller.accent", """
                package client;
                import api.Calls;
                class Caller { static String run() { return Calls.collect("count:", 1) ?: ""; } }
                """);

        assertEquals("count:1", compileAndRun("client.Caller", List.of(library)));
    }

    @Test
    void reflectedPublicBoxedOverloadRemainsCallableDespitePrivatePrimitiveOverload() throws Exception {
        Path library = compileJavaApi("""
                public static String boxed(Integer value) { return "boxed:" + value; }
                private static String boxed(int value) { return "private"; }
                """);
        write("src/client/Caller.accent", """
                package client;
                import api.Calls;
                class Caller { static String run() { return Calls.boxed(1) ?: ""; } }
                """);

        assertEquals("boxed:1", compileAndRun("client.Caller", List.of(library)));
    }

    @Test
    void importedFieldAndMethodTypesResolveInTheDeclaringUnit() throws Exception {
        Path token = write("src/domain/Token.accent", """
                package domain;
                class Token { int number() { return 7; } }
                """);
        Path service = write("src/library/Service.accent", """
                package library;
                import domain.Token;
                class Service {
                    Token token = new Token();
                    Token value() { return this.token; }
                }
                """);
        Path caller = write("src/client/Caller.accent", """
                package client;
                import library.Service;
                class Caller {
                    static int run() {
                        var service = new Service();
                        return service.token.number() + service.value().number();
                    }
                }
                """);
        List<SourceFile> sources = List.of(new SourceFile(token, Files.readString(token)),
                new SourceFile(service, Files.readString(service)), new SourceFile(caller, Files.readString(caller)));
        AccentCompiler compiler = new AccentCompiler();

        CompilationResult result = compiler.check(sources);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(compiler.checkEach(sources).values().stream().flatMap(List::stream)
                .noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
        assertEquals(14, compileAndRun("client.Caller", List.of()));
    }
}
