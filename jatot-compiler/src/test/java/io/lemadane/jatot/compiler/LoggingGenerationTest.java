package io.lemadane.jatot.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingGenerationTest {
    
    @TempDir
    Path tempDir;

    @Test
    void testLoggingAnnotationGeneratesLoggerField() throws Exception {
        Path sourceFile = tempDir.resolve("CustomerService.jatot");
        Files.writeString(sourceFile, """
            import io.lemadane.jatot.logging.Logging;
            
            @Logging
            public class CustomerService {
                public void login() {
                    log.info("Test");
                }
            }
            """);

        Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);

        JatotCompiler compiler = new JatotCompiler();
        var result = compiler.compile(
            List.of(sourceFile),
            outDir,
            List.of(),
            outDir,
            true // saveJava
        );

        // result.successful() will be false because io.lemadane.jatot.logging is not in the test classpath for javac.
        // We only care that the Java source was generated correctly.

        Path generatedJava = outDir.resolve("CustomerService.java");
        assertTrue(Files.exists(generatedJava), "Generated Java file should exist");

        String javaContent = Files.readString(generatedJava);
        assertTrue(javaContent.contains("private static final io.lemadane.jatot.logging.Logger log = io.lemadane.jatot.logging.LogManager.getLogger(CustomerService.class);"), 
                   "Should generate logger field");
    }
}
