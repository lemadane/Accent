package accent.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccentCliExecutionTest {
    @TempDir
    Path directory;

    @Test
    void runsTheMainTypeWhenAnExtensionAppearsFirst() throws Exception {
        Path source = directory.resolve("Main.accent");
        Files.writeString(source, """
                package demo;
                extension String! {
                    int answer() { return 42; }
                }
                class Main {
                    static void main(String[] args) {
                        if ("test".answer() != 42) {
                            throw new IllegalStateException("Extension call failed");
                        }
                    }
                }
                """);

        int exitCode = AccentCli.run(new String[]{"run", source.toString(),
                "-d", directory.resolve("classes").toString(),
                "-cp", System.getProperty("accent.test.runtimeClasspath"),
                "--save-java", directory.resolve("generated").toString()});

        assertEquals(0, exitCode);
    }
}
