package accent.compiler;

import static org.junit.jupiter.api.Assertions.*;

import accent.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for JS-style truthy and falsy condition evaluation.
 * Tests booleans, numbers, strings, null, objects, control flow (if, while, do-while, ternary, if-expr),
 * and logical operators (and, or, not, nand, nor, xor, xnor, &&, ||, !).
 */
class TruthyFalsyEndToEndTest {

    private Path createTempDir() throws Exception {
        Path temp = Files.createTempDirectory("accent-truthy-temp");
        temp.toFile().deleteOnExit();
        return temp;
    }

    private CompilationResult compile(Path srcDir, Path binDir, Path genDir) {
        AccentCompiler compiler = new AccentCompiler();
        List<String> cp = List.of("build/classes/java/main", "build/classes/java/test");
        return compiler.compile(List.of(srcDir), binDir, cp, genDir, true);
    }

    private void runClass(Path binDir, String mainClassName) throws Exception {
        java.net.URLClassLoader classLoader = new java.net.URLClassLoader(
            new java.net.URL[] {
                binDir.toUri().toURL(),
                Path.of("build/classes/java/main").toUri().toURL()
            },
            ClassLoader.getSystemClassLoader()
        );
        try {
            Class<?> clazz = classLoader.loadClass(mainClassName);
            java.lang.reflect.Method mainMethod = clazz.getMethod("main", String[].class);
            mainMethod.setAccessible(true);
            mainMethod.invoke(null, (Object) new String[0]);
        } finally {
            classLoader.close();
        }
    }

    @Test
    void testIfStatementTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "public class IfMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // Strings\n" +
            "        final strVal = \"hello\";\n" +
            "        final emptyStr = \"\";\n" +
            "        boolean strRan = false;\n" +
            "        if (strVal) { strRan = true; }\n" +
            "        if (!strRan) throw new RuntimeException(\"non-empty string should be truthy\");\n" +
            "\n" +
            "        if (emptyStr) { throw new RuntimeException(\"empty string should be falsy\"); }\n" +
            "\n" +
            "        // Numbers\n" +
            "        final numVal = 42;\n" +
            "        final zeroVal = 0;\n" +
            "        final doubleZero = 0.0;\n" +
            "        final doubleVal = 3.14;\n" +
            "        boolean numRan = false;\n" +
            "        if (numVal) { numRan = true; }\n" +
            "        if (!numRan) throw new RuntimeException(\"non-zero int should be truthy\");\n" +
            "\n" +
            "        if (zeroVal) { throw new RuntimeException(\"zero int should be falsy\"); }\n" +
            "        if (doubleZero) { throw new RuntimeException(\"double zero should be falsy\"); }\n" +
            "\n" +
            "        boolean dblRan = false;\n" +
            "        if (doubleVal) { dblRan = true; }\n" +
            "        if (!dblRan) throw new RuntimeException(\"non-zero double should be truthy\");\n" +
            "\n" +
            "        // Null and objects\n" +
            "        final Object nullObj = null;\n" +
            "        final Object realObj = new Object();\n" +
            "        if (nullObj) { throw new RuntimeException(\"null should be falsy\"); }\n" +
            "\n" +
            "        boolean objRan = false;\n" +
            "        if (realObj) { objRan = true; }\n" +
            "        if (!objRan) throw new RuntimeException(\"object reference should be truthy\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("IfMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.IfMain");
    }

    @Test
    void testLoopsAndTernaryTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "public class LoopsTernaryMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // While loop\n" +
            "        var count = 3;\n" +
            "        var iterations = 0;\n" +
            "        while (count) {\n" +
            "            iterations++;\n" +
            "            count--;\n" +
            "        }\n" +
            "        if (iterations != 3) throw new RuntimeException(\"while loop failed, expected 3 got \" + iterations);\n" +
            "\n" +
            "        // Do-while loop\n" +
            "        var doCount = 1;\n" +
            "        var doRuns = 0;\n" +
            "        do {\n" +
            "            doRuns++;\n" +
            "            doCount--;\n" +
            "        } while (doCount);\n" +
            "        if (doRuns != 1) throw new RuntimeException(\"do-while loop failed, expected 1 got \" + doRuns);\n" +
            "\n" +
            "        // Ternary operator\n" +
            "        final strRes = \"yes\" ? \"ok\" : \"no\";\n" +
            "        if (!strRes.equals(\"ok\")) throw new RuntimeException(\"ternary string failed\");\n" +
            "\n" +
            "        final zeroRes = 0 ? \"bad\" : \"good\";\n" +
            "        if (!zeroRes.equals(\"good\")) throw new RuntimeException(\"ternary zero failed\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("LoopsTernaryMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.LoopsTernaryMain");
    }

    @Test
    void testLogicalOperatorsTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "public class LogicalOpsMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // not / !\n" +
            "        if (not \"hello\") throw new RuntimeException(\"not 'hello' should be false\");\n" +
            "        if (!\"world\")   throw new RuntimeException(\"! 'world' should be false\");\n" +
            "        if (!not 0)     throw new RuntimeException(\"! not 0 should be false\");\n" +
            "\n" +
            "        // and / &&\n" +
            "        if (!(\"abc\" and 100)) throw new RuntimeException(\"'abc' and 100 should be true\");\n" +
            "        if (\"abc\" and 0)     throw new RuntimeException(\"'abc' and 0 should be false\");\n" +
            "        if (!(\"abc\" && 5))   throw new RuntimeException(\"'abc' && 5 should be true\");\n" +
            "\n" +
            "        // or / ||\n" +
            "        if (!(\"\" or 5))      throw new RuntimeException(\"'' or 5 should be true\");\n" +
            "        if (0 or \"\")         throw new RuntimeException(\"0 or '' should be false\");\n" +
            "        if (!(0 || \"foo\"))   throw new RuntimeException(\"0 || 'foo' should be true\");\n" +
            "\n" +
            "        // nand / nor / xor / xnor\n" +
            "        if (\"a\" nand \"b\")   throw new RuntimeException(\"'a' nand 'b' should be false\");\n" +
            "        if (!(\"\" nand \"b\")) throw new RuntimeException(\"'' nand 'b' should be true\");\n" +
            "\n" +
            "        if (\"a\" nor \"b\")    throw new RuntimeException(\"'a' nor 'b' should be false\");\n" +
            "        if (!(0 nor \"\"))    throw new RuntimeException(\"0 nor '' should be true\");\n" +
            "\n" +
            "        if (!(\"a\" xor 0))   throw new RuntimeException(\"'a' xor 0 should be true\");\n" +
            "        if (\"a\" xor \"b\")    throw new RuntimeException(\"'a' xor 'b' should be false\");\n" +
            "\n" +
            "        if (!(\"a\" xnor \"b\")) throw new RuntimeException(\"'a' xnor 'b' should be true\");\n" +
            "        if (\"a\" xnor 0)     throw new RuntimeException(\"'a' xnor 0 should be false\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("LogicalOpsMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.LogicalOpsMain");
    }
}
