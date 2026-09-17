package accent.compiler;

import static org.junit.jupiter.api.Assertions.*;
import accent.diagnostic.DiagnosticSeverity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccentEndToEndTest {

    private Path createTempDir() throws Exception {
        Path temp = Files.createTempDirectory("accent-e2e-temp");
        temp.toFile().deleteOnExit();
        return temp;
    }

    private CompilationResult compile(Path srcDir, Path binDir, Path genDir) {
        AccentCompiler compiler = new AccentCompiler();
        List<String> cp = List.of(
            "build/classes/java/main",
            "build/classes/java/test"
        );
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
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        } finally {
            classLoader.close();
        }
    }

    @Test
    void testBlockExpressionsAndYield() throws Exception {
        String code = 
            "package test;\n" +
            "public class BlockExprMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final condition = true;\n" +
            "        final val = if (condition) {\n" +
            "            yield 100;\n" +
            "        } else {\n" +
            "            yield 200;\n" +
            "        };\n" +
            "        if (val != 100) { throw new RuntimeException(\"Value should be 100\"); }\n" +
            "\n" +
            "        var sum = 0;\n" +
            "        final list = for (var i = 0; i < 5; i++) {\n" +
            "            yield i;\n" +
            "        };\n" +
            "        for (var n : list) { sum = sum + n; }\n" +
            "        if (sum != 10) { throw new RuntimeException(\"Sum should be 10\"); }\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("BlockExprMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());

        runClass(binDir, "test.BlockExprMain");
    }

    @Test
    void testImmutableLocalsAndReassignment() throws Exception {
        String code = 
            "package test;\n" +
            "public class ImmutableMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final x = 10;\n" +
            "        x = 20;\n" + // Reassignment of final
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("ImmutableMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertFalse(result.successful(), "Expected compilation failure for reassignment of final.");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }

    @Test
    void testParameterReassignment() throws Exception {
        String code = 
            "package test;\n" +
            "public class ParamMain {\n" +
            "    public void run(int val) {\n" +
            "        val = 20;\n" + // Reassignment of parameter
            "    }\n" +
            "    public static void main(String[] args) {}\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("ParamMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertFalse(result.successful(), "Expected compilation failure for reassignment of parameter.");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }

    @Test
    void testMandatoryThisCheck() throws Exception {
        String code = 
            "package test;\n" +
            "public class ThisCheck {\n" +
            "    private int count = 0;\n" +
            "    public void inc() {\n" +
            "        count = count + 1;\n" + // Missing this.
            "    }\n" +
            "    public static void main(String[] args) {}\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("ThisCheck.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertFalse(result.successful(), "Expected compilation failure for missing this. qualification.");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }

    @Test
    void testNullCoalescingAndChaining() throws Exception {
        String code = 
            "package test;\n" +
            "public class NullMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final String name = null;\n" +
            "        final fallback = name ?? \"Default\";\n" +
            "        if (!fallback.equals(\"Default\")) { throw new RuntimeException(\"Null coalescing failed\"); }\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("NullMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());

        runClass(binDir, "test.NullMain");
    }

    @Test
    void testClassExtensions() throws Exception {
        String code = 
            "package test;\n" +
            "extension String! {\n" +
            "    public String! shout() {\n" +
            "        return this.toUpperCase();\n" +
            "    }\n" +
            "}\n" +
            "class ExtMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final text = \"hello\";\n" +
            "        final result = text.shout();\n" +
            "        if (!result.equals(\"HELLO\")) { throw new RuntimeException(\"Extension method failed\"); }\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("ExtMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());

        runClass(binDir, "test.ExtMain");
    }

    @Test
    void testAsyncAwaitConcurrency() throws Exception {
        String code = 
            "package test;\n" +
            "public class AsyncMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final fut1 = async 42;\n" +
            "        final val = await fut1;\n" +
            "        if (val != 42) { throw new RuntimeException(\"Async/await failed\"); }\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("AsyncMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());

        runClass(binDir, "test.AsyncMain");
    }

    @Test
    void testAnnotationPreservation() throws Exception {
        String code = 
            "package test;\n" +
            "@Deprecated\n" +
            "public class AnnotationMain {\n" +
            "    @SuppressWarnings(\"unchecked\")\n" +
            "    public static void main(String[] args) {\n" +
            "        final val = 100;\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("AnnotationMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());

        runClass(binDir, "test.AnnotationMain");

        String genJava = Files.readString(genDir.resolve("test/AnnotationMain.java"), StandardCharsets.UTF_8);
        assertTrue(genJava.contains("@Deprecated"), "Generated Java missing @Deprecated class annotation");
        assertTrue(genJava.contains("@SuppressWarnings(\"unchecked\")"), "Generated Java missing @SuppressWarnings member annotation");
    }

    @Test
    void testDefaultParamsAndNamedArgs() throws Exception {
        String code = 
            "package test;\n" +
            "public class DefaultParamsMain {\n" +
            "    public static int calculate(int base, int multiplier = 2, int offset = 1) {\n" +
            "        return base * multiplier + offset;\n" +
            "    }\n" +
            "    public static void main(String[] args) {\n" +
            "        // 1. Positional only with defaults\n" +
            "        final res1 = calculate(10);\n" +
            "        if (res1 != 21) { throw new RuntimeException(\"Test 1 failed: \" + res1); }\n" +
            "\n" +
            "        // 2. Named arguments out of order\n" +
            "        final res2 = calculate(offset: 5, base: 10);\n" +
            "        if (res2 != 25) { throw new RuntimeException(\"Test 2 failed: \" + res2); }\n" +
            "\n" +
            "        // 3. Mixed positional and named arguments\n" +
            "        final res3 = calculate(10, multiplier: 3);\n" +
            "        if (res3 != 31) { throw new RuntimeException(\"Test 3 failed: \" + res3); }\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("DefaultParamsMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());

        runClass(binDir, "test.DefaultParamsMain");
    }

    @Test
    void testTernaryExpression() throws Exception {
        String code = 
            "package test;\n" +
            "public class TernaryMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final condition = true;\n" +
            "        final val = condition ? \"Zack\" : \"Guest\";\n" +
            "        if (!val.equals(\"Zack\")) { throw new RuntimeException(\"Expected Zack\"); }\n" +
            "\n" +
            "        final nested = condition ? (false ? 1 : 2) : 3;\n" +
            "        if (nested != 2) { throw new RuntimeException(\"Expected 2\"); }\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("TernaryMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());

        runClass(binDir, "test.TernaryMain");
    }

    @Test
    void testSqlExpression() throws Exception {
        String code = 
            "package test;\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "public class SqlMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final id = 42;\n" +
            "        final query = sql`SELECT * FROM users WHERE id = {id}`;\n" +
            "    }\n" +
            "}\n";

        String dbCode =
            "package accent.sql;\n" +
            "import java.util.List;\n" +
            "public class Sql {\n" +
            "    public static Object execute(String sql, List params, Class resultClass) {\n" +
            "        return null;\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.createDirectories(srcDir.resolve("io/accent/sql"));

        Files.writeString(srcDir.resolve("SqlMain.accent"), code, StandardCharsets.UTF_8);
        Files.writeString(srcDir.resolve("io/accent/sql/Sql.java"), dbCode, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
    }

    @Test
    void testAsyncSqlExpression() throws Exception {
        String code = 
            "package test;\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "public class AsyncSqlMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final id = 42;\n" +
            "        final queryFuture = async sql`SELECT * FROM users WHERE id = {id}`;\n" +
            "        final query = await queryFuture;\n" +
            "    }\n" +
            "}\n";

        String dbCode =
            "package accent.sql;\n" +
            "import java.util.List;\n" +
            "public class Sql {\n" +
            "    public static Object execute(String sql, List params, Class resultClass) {\n" +
            "        return null;\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.createDirectories(srcDir.resolve("io/accent/sql"));

        Files.writeString(srcDir.resolve("AsyncSqlMain.accent"), code, StandardCharsets.UTF_8);
        Files.writeString(srcDir.resolve("io/accent/sql/Sql.java"), dbCode, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
    }

    @Test
    void testSqlSyntaxErrors() throws Exception {
        String code = 
            "package test;\n" +
            "public class SqlErrorMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final bad1 = sql`SELECT FROM users`;\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("SqlErrorMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("SQL Syntax Error") || d.message().contains("Expected select expressions")));
    }

    @Test
    void testStringInterpolation() throws Exception {
        String code = 
            "package test;\n" +
            "import java.util.List;\n" +
            "import java.util.ArrayList;\n" +
            "public class InterpMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // 1. Basic variables and primitive values\n" +
            "        final name = \"Lemuel\";\n" +
            "        final age = 42;\n" +
            "        final result = $\"Hello, {name}! You are {age} years old.\";\n" +
            "        if (!result.equals(\"Hello, Lemuel! You are 42 years old.\")) {\n" +
            "            throw new RuntimeException(\"Fail basic: \" + result);\n" +
            "        }\n" +
            "\n" +
            "        // 2. Arithmetic, ternary, and nested expressions\n" +
            "        final active = true;\n" +
            "        final math = $\"Total: {10 * 5}, Status: {active ? \"Active\" : \"Inactive\"}\";\n" +
            "        if (!math.equals(\"Total: 50, Status: Active\")) {\n" +
            "            throw new RuntimeException(\"Fail expressions: \" + math);\n" +
            "        }\n" +
            "\n" +
            "        // 3. Double brace literal braces\n" +
            "        final braces = $\"{{name: \\\"{name}\\\", age: {age}}}\";\n" +
            "        final quote = Character.toString(34);\n" +
            "        final expectedBraces = \"{name: \" + quote + \"Lemuel\" + quote + \", age: 42}\";\n" +
            "        if (!braces.equals(expectedBraces)) {\n" +
            "            throw new RuntimeException(\"Fail braces: \" + braces);\n" +
            "        }\n" +
            "\n" +
            "        // 4. Multiline string interpolation\n" +
            "        final multiline = $\"First line: {name}\n" +
            "Second line\n" +
            "Third line: {age}\";\n" +
            "        final expectedMultiline = $\"First line: Lemuel\n" +
            "Second line\n" +
            "Third line: 42\";\n" +
            "        if (!multiline.equals(expectedMultiline)) {\n" +
            "            throw new RuntimeException(\"Fail multiline: \\\"\" + multiline + \"\\\"\");\n" +
            "        }\n" +
            "\n" +
            "        // 5. Null handling\n" +
            "        final Object nullVal = null;\n" +
            "        final nullTest = $\"Value: {nullVal}\";\n" +
            "        if (!nullTest.equals(\"Value: null\")) {\n" +
            "            throw new RuntimeException(\"Fail null handling: \" + nullTest);\n" +
            "        }\n" +
            "\n" +
            "        // 6. Evaluation order check\n" +
            "        final List<Integer> order = new ArrayList<Integer>();\n" +
            "        final orderTest = $\"{recordCall(order, 1)}-{recordCall(order, 2)}-{recordCall(order, 3)}\";\n" +
            "        if (!orderTest.equals(\"10-20-30\")) {\n" +
            "            throw new RuntimeException(\"Fail order value: \" + orderTest);\n" +
            "        }\n" +
            "        if (order.size() != 3 || order.get(0) != 1 || order.get(1) != 2 || order.get(2) != 3) {\n" +
            "            throw new RuntimeException(\"Fail evaluation order side effects: \" + order);\n" +
            "        }\n" +
            "\n" +
            "        // 7. Normal Java compat checks\n" +
            "        final String $value = \"Hello\";\n" +
            "        final String user$name = \"Lemuel\";\n" +
            "        final ordinary = \"Hello, {name}\";\n" +
            "        if (!ordinary.equals(\"Hello, {name}\")) {\n" +
            "            throw new RuntimeException(\"Fail normal string braces: \" + ordinary);\n" +
            "        }\n" +
            "\n" +
            "        final textBlock = \"\"\"\n" +
            "            Hello, {name}\n" +
            "            \"\"\";\n" +
            "        if (!textBlock.contains(\"{name}\")) {\n" +
            "            throw new RuntimeException(\"Fail normal textblock braces: \" + textBlock);\n" +
            "        }\n" +
            "\n" +
            "        // 8. C#-style {expr} string interpolation with $\"-\", $`...`, and multiline $\"\"\"...\"\"\" text blocks\n" +
            "        final interpolated1 = $\"Hello {name}, next year age will be {age + 1}!\";\n" +
            "        final interpolated2 = $`Hello {name}, next year age will be {age + 1}!`;\n" +
            "        if (!interpolated1.equals(\"Hello Lemuel, next year age will be 43!\")) {\n" +
            "            throw new RuntimeException(\"Fail C# style string interpolation 1: \" + interpolated1);\n" +
            "        }\n" +
            "        if (!interpolated2.equals(\"Hello Lemuel, next year age will be 43!\")) {\n" +
            "            throw new RuntimeException(\"Fail C# style string interpolation 2: \" + interpolated2);\n" +
            "        }\n" +
            "\n" +
            "        final interpolatedMultiBlock = $\"\"\"\n" +
            "            Name: {name}\n" +
            "            Age: {age}\n" +
            "            \"\"\";\n" +
            "        if (!interpolatedMultiBlock.contains(\"Name: Lemuel\") || !interpolatedMultiBlock.contains(\"Age: 42\")) {\n" +
            "            throw new RuntimeException(\"Fail multiline text block interpolation: \" + interpolatedMultiBlock);\n" +
            "        }\n" +
            "\n" +
            "        // 9. Singleton class testing (.instance() accessor)\n" +
            "        final cfg1 = AppConfig.instance();\n" +
            "        final cfg2 = AppConfig.instance();\n" +
            "        if (cfg1 != cfg2) {\n" +
            "            throw new RuntimeException(\"Fail singleton instance equality check!\");\n" +
            "        }\n" +
            "        if (!cfg1.appName().equals(\"JatotApp\")) {\n" +
            "            throw new RuntimeException(\"Fail singleton method call: \" + cfg1.appName());\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    private static int recordCall(List<Integer> list, int id) {\n" +
            "        list.add(id);\n" +
            "        return id * 10;\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "singleton AppConfig {\n" +
            "    public String! appName() {\n" +
            "        return \"JatotApp\";\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("InterpMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.InterpMain");
    }

    @Test
    void testStringInterpolationValidationErrors() throws Exception {
        // Empty brace check
        String code1 = 
            "package test;\n" +
            "public class ErrorMain1 {\n" +
            "    public static void main(String[] args) {\n" +
            "        final bad = $\"Hello, {}\";\n" +
            "    }\n" +
            "}\n";

        Path tempDir1 = createTempDir();
        Path srcDir1 = tempDir1.resolve("src");
        Files.createDirectories(srcDir1);
        Files.writeString(srcDir1.resolve("ErrorMain1.accent"), code1, StandardCharsets.UTF_8);
        CompilationResult res1 = compile(srcDir1, tempDir1.resolve("bin"), tempDir1.resolve("gen"));
        assertFalse(res1.successful());
        assertTrue(res1.diagnostics().stream().anyMatch(d -> d.message().contains("Interpolation expression cannot be empty.")));

        // Void expression check
        String code2 = 
            "package test;\n" +
            "public class ErrorMain2 {\n" +
            "    public static void main(String[] args) {\n" +
            "        final bad = $\"Result: {print()}\";\n" +
            "    }\n" +
            "    private static void print() {}\n" +
            "}\n";

        Path tempDir2 = createTempDir();
        Path srcDir2 = tempDir2.resolve("src");
        Files.createDirectories(srcDir2);
        Files.writeString(srcDir2.resolve("ErrorMain2.accent"), code2, StandardCharsets.UTF_8);
        CompilationResult res2 = compile(srcDir2, tempDir2.resolve("bin"), tempDir2.resolve("gen"));
        assertFalse(res2.successful());
        assertTrue(res2.diagnostics().stream().anyMatch(d -> d.message().contains("Interpolation expression cannot have type void.")));
    }

    @Test
    void testSingletonClassDeclaration() throws Exception {
        String code = 
            "package test;\n" +
            "public class SingletonMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final s1 = StateTracker.instance();\n" +
            "        final s2 = StateTracker.instance();\n" +
            "        if (s1 != s2) {\n" +
            "            throw new RuntimeException(\"Singleton identity check failed!\");\n" +
            "        }\n" +
            "        s1.increment();\n" +
            "        s2.increment();\n" +
            "        if (s1.count() != 2) {\n" +
            "            throw new RuntimeException(\"Singleton state persistence failed: \" + s1.count());\n" +
            "        }\n" +
            "        final c1 = CacheManager.instance();\n" +
            "        final c2 = CacheManager.instance();\n" +
            "        if (c1 != c2 || !c1.getApp().equals(\"Jatot\")) {\n" +
            "            throw new RuntimeException(\"Singleton class syntax check failed!\");\n" +
            "        }\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "singleton StateTracker {\n" +
            "    private int counter = 0;\n" +
            "    public void increment() {\n" +
            "        this.counter = this.counter + 1;\n" +
            "    }\n" +
            "    public int count() {\n" +
            "        return this.counter;\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "public singleton class CacheManager {\n" +
            "    public String! getApp() {\n" +
            "        return \"Jatot\";\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("SingletonMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Singleton compilation failed: " + result.diagnostics());
        runClass(binDir, "test.SingletonMain");

        // Negative check: Attempting to call 'new StateTracker()' should fail compilation
        String badCode = 
            "package test;\n" +
            "public class BadSingleton {\n" +
            "    public static void main(String[] args) {\n" +
            "        final s = new StateTracker();\n" +
            "    }\n" +
            "}\n" +
            "singleton StateTracker {}\n";
        Path badDir = createTempDir();
        Path badSrc = badDir.resolve("src");
        Files.createDirectories(badSrc);
        Files.writeString(badSrc.resolve("BadSingleton.accent"), badCode, StandardCharsets.UTF_8);
        CompilationResult badResult = compile(badSrc, badDir.resolve("bin"), badDir.resolve("gen"));
        assertFalse(badResult.successful(), "Should fail when attempting to instantiate singleton with 'new'");
    }

    @Test
    void testModelDeclaration() throws Exception {
        String code = 
            "package test;\n" +
            "public class ModelMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final user = new User(\"U101\", \"Lemuel\", 42);\n" +
            "        if (!user.id().equals(\"U101\") || !user.name().equals(\"Lemuel\") || user.age() != 42) {\n" +
            "            throw new RuntimeException(\"Fail model getter check: \" + user);\n" +
            "        }\n" +
            "        user.setName(\"Lemuel A.\");\n" +
            "        user.setAge(43);\n" +
            "        if (!user.name().equals(\"Lemuel A.\") || user.age() != 43) {\n" +
            "            throw new RuntimeException(\"Fail model setter check: \" + user);\n" +
            "        }\n" +
            "        final str = user.toString();\n" +
            "        if (!str.equals(\"User[id=U101, name=Lemuel A., age=43]\")) {\n" +
            "            throw new RuntimeException(\"Fail model toString check: \" + str);\n" +
            "        }\n" +
            "        final userCopy = user.copy(\"U101\", \"Lemuel A.\", 43);\n" +
            "        if (!user.equals(userCopy) || user.hashCode() != userCopy.hashCode()) {\n" +
            "            throw new RuntimeException(\"Fail model equals/hashCode/copy check!\");\n" +
            "        }\n" +
            "        if (!(user instanceof java.io.Serializable)) {\n" +
            "            throw new RuntimeException(\"Fail model Serializable check!\");\n" +
            "        }\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "public model User(final String! id, String! name, int age) {}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("ModelMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Model compilation failed: " + result.diagnostics());
        runClass(binDir, "test.ModelMain");
    }

    @Test
    void testFinalModelFieldCannotHaveSetter() throws Exception {
        String badCode = 
            "package test;\n" +
            "public class BadModel {\n" +
            "    public static void main(String[] args) {\n" +
            "        final user = new User(\"U101\", \"Lemuel\");\n" +
            "        user.setId(\"U102\");\n" +
            "    }\n" +
            "}\n" +
            "model User(final String! id, var String! name) {}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("BadModel.accent"), badCode, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, tempDir.resolve("bin"), tempDir.resolve("gen"));
        assertFalse(result.successful(), "Compilation should fail when calling setter on final field 'id'");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("is final and cannot have a setter")));
    }
}
