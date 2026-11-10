package accent.compiler;

import accent.logging.LogConfiguration;
import accent.logging.LogLevel;
import accent.logging.LogManager;
import accent.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeIoTest {
    @TempDir Path temp;

    @Test void printsAndLogsWithContextWithoutLoggerImportsOrAnnotations() throws Exception {
        Path source = temp.resolve("NativeIo.accent");
        Files.writeString(source, """
                package example;
                import java.util.Map;
                class NativeIo {
                    static int calls = 0;
                    static String value() { static.calls++; return "world"; }
                    static String context() { return log.get("requestId") ?: "missing"; }
                    static int run() {
                        final log = new Logger();
                        Logger logger = log;
                        print("Hello ");
                        println(static.value());
                        println(42);
                        String missing? = null;
                        println(missing?);
                        println();
                        log.put("requestId", "R1");
                        try {
                            log("created");
                            Logger.log("direct");
                            log.remove("unused");
                            logger.warn("warning");
                            final task = async static.context();
                            final requestId = await task;
                            log.info(requestId);
                            log("failed", new IllegalStateException("detail"));
                            log.withContext(Map.of("scope", "inner"), () -> {
                                log.info("scoped");
                            });
                        } finally {
                            log.clear();
                        }
                        log.info("clean");
                        return static.calls;
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("classes"),
                List.of("build/classes/java/main"), temp.resolve("generated"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        String java = Files.readString(temp.resolve("generated/example/NativeIo.java"));
        assertTrue(java.contains("java.lang.System.out.println"));
        assertTrue(java.contains("accent.logging.Logger.log(\"created\")"), java);
        assertTrue(java.contains("accent.logging.LogManager.getLogger(\"example.NativeIo\")"));
        LogConfiguration previous = LogManager.configuration();
        LogConfiguration config = new LogConfiguration();
        config.rootLevel = LogLevel.TRACE;
        config.consoleEnabled = false;
        config.fileEnabled = true;
        config.filePath = temp.resolve("native.log").toString();
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8);
             var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("classes").toUri().toURL()})) {
            LogManager.configure(config);
            System.setOut(capture);
            assertEquals(1, loader.loadClass("example.NativeIo").getMethod("run").invoke(null));
        } finally {
            System.setOut(original);
            LogManager.getLogger("test").clear();
            LogManager.configure(previous);
        }
        assertEquals(String.join(System.lineSeparator(), "Hello world", "42", "null", "", ""),
                output.toString(StandardCharsets.UTF_8));
        String logs = Files.readString(temp.resolve("native.log"));
        assertTrue(logs.contains("example.NativeIo - created {requestId=R1}"), logs);
        assertTrue(logs.contains("example.NativeIo - direct {requestId=R1}"), logs);
        assertTrue(logs.contains("warning {requestId=R1}"), logs);
        assertTrue(logs.contains("scoped {requestId=R1, scope=inner}"), logs);
        assertTrue(logs.contains("IllegalStateException: detail"), logs);
        assertTrue(logs.lines().anyMatch(line -> line.endsWith("example.NativeIo - clean")), logs);
    }

    @Test void loggerWorksInRecordsInterfacesAndExtensions() throws Exception {
        Path record = temp.resolve("RecordLog.accent");
        Path contract = temp.resolve("InterfaceLog.accent");
        Path extension = temp.resolve("StringLog.accent");
        Path caller = temp.resolve("Caller.accent");
        Files.writeString(record, "record RecordLog() { String category() { return log.name() ?: \"\"; } }");
        Files.writeString(contract, "interface InterfaceLog { static String category() { return log.name() ?: \"\"; } }");
        Files.writeString(extension, "extension String { String category() { return log.name() ?: \"\"; } }");
        Files.writeString(caller, "class Caller { static String category() { return \"x\".category(); } }");
        var result = new AccentCompiler().compile(List.of(record, contract, extension, caller), temp.resolve("kinds"),
                List.of("build/classes/java/main"), temp.resolve("kinds-java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.resolve("kinds").toUri().toURL()})) {
            Class<?> type = loader.loadClass("RecordLog");
            assertEquals("RecordLog", type.getMethod("category").invoke(type.getConstructor().newInstance()));
            assertEquals("InterfaceLog", loader.loadClass("InterfaceLog").getMethod("category").invoke(null));
            assertEquals("Extension_String", loader.loadClass("Caller").getMethod("category").invoke(null));
        }
    }

    @Test void validatesBuiltinArgumentsAndKeepsLoggerReadOnly() {
        for (String body : List.of("println(1, 2);", "log();", "log(42);", "log = log;")) {
            var result = new AccentCompiler().check(new SourceFile(Path.of("Bad.accent"),
                    "class Bad { void run() { " + body + " } }"));
            assertFalse(result.successful(), body);
        }
    }

    @Test void explicitMethodsAndLocalLoggerBindingsRemainAvailable() throws Exception {
        Path source = temp.resolve("Custom.accent");
        Files.writeString(source, """
                class Custom {
                    static int println() { return 7; }
                    static int run(Logger log) {
                        log.info("explicit parameter");
                        log("static shorthand");
                        return static.println();
                    }
                }
                """);
        var result = new AccentCompiler().compile(List.of(source), temp.resolve("custom"),
                List.of("build/classes/java/main"), temp.resolve("custom-java"), true);
        assertTrue(result.successful(), result.diagnostics().toString());
        String generated = Files.readString(temp.resolve("custom-java/Custom.java"));
        assertTrue(generated.contains("accent.logging.Logger.log(\"static shorthand\")"), generated);
    }
}
