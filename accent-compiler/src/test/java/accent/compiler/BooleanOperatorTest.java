package accent.compiler;

import static org.junit.jupiter.api.Assertions.*;
import accent.lexer.AccentLexer;
import accent.lexer.LexResult;
import accent.lexer.TokenType;
import accent.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for textual boolean operators: not, and, or.
 *
 * Covers:
 *  - Lexer: each operator tokenized as its own type, not inside longer identifiers
 *  - End-to-end: truth tables, short-circuit, combined textual operators, type errors
 */
class BooleanOperatorTest {

    // ---- Helpers ----

    private Path createTempDir() throws Exception {
        Path temp = Files.createTempDirectory("accent-bool-temp");
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

    private LexResult lex(String src) {
        SourceFile sf = new SourceFile(Path.of("Test.accent"), src);
        return new AccentLexer(sf).lex();
    }

    // =====================================================================
    // LEXER TESTS
    // =====================================================================

    @Test
    void lexerRecognizesNotKeyword() {
        LexResult r = lex("not a");
        assertEquals(TokenType.NOT, r.tokens().get(0).type());
        assertEquals("not", r.tokens().get(0).lexeme());
    }

    @Test
    void lexerRecognizesAndKeyword() {
        LexResult r = lex("a and b");
        assertEquals(TokenType.AND, r.tokens().get(1).type());
    }

    @Test
    void lexerRecognizesOrKeyword() {
        LexResult r = lex("a or b");
        assertEquals(TokenType.OR, r.tokens().get(1).type());
    }

    @Test
    void rejectsSymbolicLogicalOperatorsWithReplacementHints() {
        String[][] cases = {
                {"true && false", "and"},
                {"true || false", "or"},
                {"!true", "not"},
                {"!(true and false)", "not"},
                {"not !false", "not"}
        };
        for (String[] testCase : cases) {
            var source = new SourceFile(Path.of("Test.accent"),
                    "class Test { boolean run() { return " + testCase[0] + "; } }");
            var result = new AccentCompiler().check(source);
            assertFalse(result.successful(), testCase[0]);
            assertTrue(result.diagnostics().stream()
                    .anyMatch(d -> d.message().contains("Use '" + testCase[1] + "' instead")),
                    () -> result.diagnostics().toString());
        }
    }

    @Test
    void preservesNonNullTypesInequalityAndLiteralSymbols() {
        var source = new SourceFile(Path.of("Test.accent"), """
                class Test {
                    boolean run(String value!, String[] values!) {
                        final symbols = "! && ||";
                        // ! && || in comments are harmless.
                        return value! != null and not false or true;
                    }
                }
                """);
        var result = new AccentCompiler().check(source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test
    void textualOperatorsShortCircuit() throws Exception {
        Path tempDir = createTempDir();
        Path source = tempDir.resolve("ShortCircuit.accent");
        Files.writeString(source, """
                class ShortCircuit {
                    static boolean fail() {
                        throw new RuntimeException("Right operand must not run");
                    }
                    static void main(String[] args) {
                        if (false and fail()) throw new RuntimeException("and failed");
                        if (not (true or fail())) throw new RuntimeException("or failed");
                    }
                }
                """);
        Path binDir = tempDir.resolve("bin");
        var result = compile(source, binDir, tempDir.resolve("gen"));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        runClass(binDir, "ShortCircuit");
    }

    @Test
    void removedOperatorsAreOrdinaryIdentifiers() throws Exception {
        for (String name : List.of("nand", "nor", "xor", "xnor")) {
            assertEquals(TokenType.IDENTIFIER, lex(name).tokens().get(0).type());
            SourceFile source = new SourceFile(Path.of("Test.accent"),
                    "class Test { boolean run() { return true " + name + " false; } }");
            assertFalse(new AccentCompiler().check(source).successful(),
                    name + " must not be accepted as an operator");
        }
        Path tempDir = createTempDir();
        Path source = tempDir.resolve("Identifiers.accent");
        Files.writeString(source, """
                class Identifiers {
                    static void main(String[] args) {
                        boolean nand = true;
                        boolean nor = false;
                        boolean xor = true;
                        boolean xnor = false;
                        if (not (nand and not nor and xor and not xnor)) {
                            throw new RuntimeException("Former operator identifiers failed");
                        }
                    }
                }
                """);
        Path binDir = tempDir.resolve("bin");
        CompilationResult result = compile(source, binDir, tempDir.resolve("gen"));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        runClass(binDir, "Identifiers");
    }

    @Test
    void lexerDoesNotTokenizeKeywordsInsideLongerIdentifiers() {
        // 'notification' must be IDENTIFIER, not NOT + something
        LexResult r = lex("notification android ordinary xorValue northern");
        for (int i = 0; i < r.tokens().size() - 1 /* skip EOF */; i++) {
            assertEquals(TokenType.IDENTIFIER, r.tokens().get(i).type(),
                "Expected IDENTIFIER but got " + r.tokens().get(i).type() + " for: " + r.tokens().get(i).lexeme());
        }
    }

    @Test
    void lexerHandlesOperatorsAcrossNewlines() {
        LexResult r = lex("active\nand\nverified");
        assertEquals(TokenType.IDENTIFIER, r.tokens().get(0).type());
        assertEquals(TokenType.AND,        r.tokens().get(1).type());
        assertEquals(TokenType.IDENTIFIER, r.tokens().get(2).type());
    }

    @Test
    void lexerHandlesOperatorWithBlockComment() {
        LexResult r = lex("active /* explanation */ and verified");
        assertEquals(TokenType.AND, r.tokens().get(1).type());
    }

    // =====================================================================
    // TRUTH TABLE END-TO-END TESTS
    // =====================================================================

    @Test
    void testTruthTablesAllOperators() throws Exception {
        // Tests AND, OR, NOT truth tables via transpiled Java
        String code =
            "package test;\n" +
            "class TruthTableMain {\n" +
            "    static void main(String[] args) {\n" +
            "        var ff = false;\n" +
            "        var ft = false;\n" +
            "        var tf = false;\n" +
            "        var tt = false;\n" +
            "\n" +
            "        // AND\n" +
            "        ff = false and false; if (ff != false) throw new RuntimeException(\"AND ff\");\n" +
            "        ft = false and true;  if (ft != false) throw new RuntimeException(\"AND ft\");\n" +
            "        tf = true  and false; if (tf != false) throw new RuntimeException(\"AND tf\");\n" +
            "        tt = true  and true;  if (tt != true)  throw new RuntimeException(\"AND tt\");\n" +
            "\n" +
            "        // OR\n" +
            "        ff = false or false; if (ff != false) throw new RuntimeException(\"OR ff\");\n" +
            "        ft = false or true;  if (ft != true)  throw new RuntimeException(\"OR ft\");\n" +
            "        tf = true  or false; if (tf != true)  throw new RuntimeException(\"OR tf\");\n" +
            "        tt = true  or true;  if (tt != true)  throw new RuntimeException(\"OR tt\");\n" +
            "\n" +
            "        // NOT\n" +
            "        final a = not true;  if (a != false) throw new RuntimeException(\"NOT true\");\n" +
            "        final b = not false; if (b != true)  throw new RuntimeException(\"NOT false\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("TruthTableMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.TruthTableMain");
    }

    @Test
    void testCombinedTextualOperators() throws Exception {
        String code =
            "package test;\n" +
            "class MixedMain {\n" +
            "    static void main(String[] args) {\n" +
            "        boolean active = true;\n" +
            "        boolean suspended = false;\n" +
            "        boolean hidden = false;\n" +
            "        boolean enabled = true;\n" +
            "        // active and not suspended  ->  true and not false  ->  true\n" +
            "        final allowed = active and not suspended;\n" +
            "        if (not allowed) throw new RuntimeException(\"mixed 1\");\n" +
            "        // enabled and not hidden  ->  true and not false  ->  true\n" +
            "        final visible = enabled and not hidden;\n" +
            "        if (not visible) throw new RuntimeException(\"mixed 2\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("MixedMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.MixedMain");
    }

    @Test
    void testPrecedenceNotAndBeforeOr() throws Exception {
        // a or b and c  =>  a or (b and c)
        // false or (true and false)  =>  false or false  =>  false
        String code =
            "package test;\n" +
            "class PrecedenceMain {\n" +
            "    static void main(String[] args) {\n" +
            "        final r1 = false or true and false;\n" +
            "        if (r1 != false) throw new RuntimeException(\"or-and precedence\");\n" +
            "        // not a and b  =>  (not a) and b\n" +
            "        // (not true) and true  =>  false and true  =>  false\n" +
            "        final r2 = not true and true;\n" +
            "        if (r2 != false) throw new RuntimeException(\"not-and precedence\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("PrecedenceMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.PrecedenceMain");
    }

    @Test
    void testRealisticExamples() throws Exception {
        String code =
            "package test;\n" +
            "class RealisticMain {\n" +
            "    static boolean canAccess(\n" +
            "            boolean authenticated, boolean suspended, boolean administrator) {\n" +
            "        return authenticated and not suspended or administrator;\n" +
            "    }\n" +
            "    static boolean exactlyOneSelected(boolean emailSelected, boolean smsSelected) {\n" +
            "        return emailSelected != smsSelected;\n" +
            "    }\n" +
            "    static boolean haveSameStatus(boolean firstActive, boolean secondActive) {\n" +
            "        return firstActive == secondActive;\n" +
            "    }\n" +
            "    static void main(String[] args) {\n" +
            "        if (not canAccess(true, false, false)) throw new RuntimeException(\"canAccess 1\");\n" +
            "        if (canAccess(true, true, false))   throw new RuntimeException(\"canAccess 2\");\n" +
            "        if (not canAccess(false, true, true))  throw new RuntimeException(\"canAccess 3\");\n" +
            "        if (not exactlyOneSelected(true, false)) throw new RuntimeException(\"exclusive selection 1\");\n" +
            "        if (exactlyOneSelected(true, true))   throw new RuntimeException(\"exclusive selection 2\");\n" +
            "        if (not haveSameStatus(true, true))   throw new RuntimeException(\"same status 1\");\n" +
            "        if (haveSameStatus(true, false))   throw new RuntimeException(\"same status 2\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("RealisticMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.RealisticMain");
    }

    // =====================================================================
    // TRUTHY / FALSY OPERAND TESTS
    // =====================================================================

    @Test
    void testTruthyIntAnd() throws Exception {
        String code =
            "package test;\n" +
            "class TruthyIntAndMain {\n" +
            "    static void main(String[] args) {\n" +
            "        boolean r = 1 and 2;\n" +
            "        if (not r) throw new RuntimeException(\"1 and 2 should be true\");\n" +
            "        boolean r2 = 0 and 2;\n" +
            "        if (r2) throw new RuntimeException(\"0 and 2 should be false\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("TruthyIntAndMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.TruthyIntAndMain");
    }

    @Test
    void testTruthyNotOnInt() throws Exception {
        String code =
            "package test;\n" +
            "class TruthyNotMain {\n" +
            "    static void main(String[] args) {\n" +
            "        boolean r1 = not 42;\n" +
            "        if (r1) throw new RuntimeException(\"not 42 should be false\");\n" +
            "        boolean r2 = not 0;\n" +
            "        if (not r2) throw new RuntimeException(\"not 0 should be true\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("TruthyNotMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.TruthyNotMain");
    }
}

