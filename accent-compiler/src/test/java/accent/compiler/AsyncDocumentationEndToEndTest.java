package accent.compiler;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles and executes actual documentation snippets so syntax changes cannot leave them stale. */
@Timeout(20)
class AsyncDocumentationEndToEndTest {
    @TempDir Path directory;

    @Test
    void readmeAndEditorGuideExamplesCompileAndExecute() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String readme = Files.readString(root.resolve("README.md"));
        String section = readme.split("(?m)^### [0-9]+[.] Concurrency with virtual threads", 2)[1].split("(?m)^### ", 2)[0];
        String editor = Files.readString(root.resolve("tools/accent-vscode/README.md"));
        Path sources = Files.createDirectories(directory.resolve("source"));
        Files.writeString(sources.resolve("Customer.accent"), "record Customer(String name)");
        Files.writeString(sources.resolve("Service.accent"), """
                class Service {
                    Customer findRequired(int id) { return new Customer("Alice"); }
                    Customer findForCustomer(int id) { return new Customer("Alice"); }
                }
                """);
        var blocks = Pattern.compile("```accent\\n(.*?)```", Pattern.DOTALL).matcher(section + "\n" + editor);
        int count = 0;
        while (blocks.find()) {
            String snippet = blocks.group(1);
            String imports = String.join("\n", snippet.lines().filter(line -> line.startsWith("import ")).toList());
            String body = String.join("\n", snippet.lines().filter(line -> !line.startsWith("import ")).toList());
            String name = "Example" + count++;
            Files.writeString(sources.resolve(name + ".accent"), imports + "\nclass " + name + " {\n"
                    + "Service customerService = new Service();\n"
                    + "Service orderService = new Service();\n"
                    + "Service service = new Service();\n"
                    + "void example(int id) {\n" + body + "\n}\n}");
        }
        assertTrue(count >= 6, "Both guides must contain compilable concurrency examples");
        Path output = directory.resolve("classes");
        var result = new AccentCompiler().compile(List.of(sources), output,
                List.of("build/classes/java/main"), directory.resolve("generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
            for (int i = 0; i < count; i++) {
                Class<?> example = loader.loadClass("Example" + i);
                example.getMethod("example", int.class).invoke(example.getConstructor().newInstance(), 1);
            }
        }
    }
}
