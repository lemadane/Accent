package io.lemadane.accent.compiler;

import static org.junit.jupiter.api.Assertions.*;
import io.lemadane.accent.lexer.AccentLexer;
import io.lemadane.accent.lexer.LexResult;
import io.lemadane.accent.lexer.TokenType;
import io.lemadane.accent.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for textual boolean operators: not, and, or, nand, nor, xor, xnor.
 *
 * Covers:
 *  - Lexer: each operator tokenized as its own type, not inside longer identifiers
 *  - End-to-end: truth tables, short-circuit, mixed symbolic+textual, type errors
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
    void lexerRecognizesNandKeyword() {
        LexResult r = lex("a nand b");
        assertEquals(TokenType.NAND, r.tokens().get(1).type());
    }

    @Test
    void lexerRecognizesNorKeyword() {
        LexResult r = lex("a nor b");
        assertEquals(TokenType.NOR, r.tokens().get(1).type());
    }

    @Test
    void lexerRecognizesXorKeyword() {
        LexResult r = lex("a xor b");
        assertEquals(TokenType.XOR, r.tokens().get(1).type());
    }

    @Test
    void lexerRecognizesXnorKeyword() {
        LexResult r = lex("a xnor b");
        assertEquals(TokenType.XNOR, r.tokens().get(1).type());
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
    void lexerPreservesSymbolicOperators() {
        LexResult r = lex("!a && b || c");
        assertEquals(TokenType.BANG,    r.tokens().get(0).type());
        assertEquals(TokenType.AND_AND, r.tokens().get(2).type());
        assertEquals(TokenType.OR_OR,   r.tokens().get(4).type());
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
        // Tests AND, OR, NAND, NOR, XOR, XNOR truth tables via transpiled Java
        String code =
            "package test;\n" +
            "public class TruthTableMain {\n" +
            "    public static void main(String[] args) {\n" +
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
            "        // NAND\n" +
            "        ff = false nand false; if (ff != true)  throw new RuntimeException(\"NAND ff\");\n" +
            "        ft = false nand true;  if (ft != true)  throw new RuntimeException(\"NAND ft\");\n" +
            "        tf = true  nand false; if (tf != true)  throw new RuntimeException(\"NAND tf\");\n" +
            "        tt = true  nand true;  if (tt != false) throw new RuntimeException(\"NAND tt\");\n" +
            "\n" +
            "        // NOR\n" +
            "        ff = false nor false; if (ff != true)  throw new RuntimeException(\"NOR ff\");\n" +
            "        ft = false nor true;  if (ft != false) throw new RuntimeException(\"NOR ft\");\n" +
            "        tf = true  nor false; if (tf != false) throw new RuntimeException(\"NOR tf\");\n" +
            "        tt = true  nor true;  if (tt != false) throw new RuntimeException(\"NOR tt\");\n" +
            "\n" +
            "        // XOR\n" +
            "        ff = false xor false; if (ff != false) throw new RuntimeException(\"XOR ff\");\n" +
            "        ft = false xor true;  if (ft != true)  throw new RuntimeException(\"XOR ft\");\n" +
            "        tf = true  xor false; if (tf != true)  throw new RuntimeException(\"XOR tf\");\n" +
            "        tt = true  xor true;  if (tt != false) throw new RuntimeException(\"XOR tt\");\n" +
            "\n" +
            "        // XNOR\n" +
            "        ff = false xnor false; if (ff != true)  throw new RuntimeException(\"XNOR ff\");\n" +
            "        ft = false xnor true;  if (ft != false) throw new RuntimeException(\"XNOR ft\");\n" +
            "        tf = true  xnor false; if (tf != false) throw new RuntimeException(\"XNOR tf\");\n" +
            "        tt = true  xnor true;  if (tt != true)  throw new RuntimeException(\"XNOR tt\");\n" +
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
    void testSymbolicOperatorsStillWork() throws Exception {
        String code =
            "package test;\n" +
            "public class SymbolicMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final a = !true;           if (a != false) throw new RuntimeException(\"!true\");\n" +
            "        final b = true && false;   if (b != false) throw new RuntimeException(\"&&\");\n" +
            "        final c = false || true;   if (c != true)  throw new RuntimeException(\"||\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("SymbolicMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.SymbolicMain");
    }

    @Test
    void testMixedTextualAndSymbolicOperators() throws Exception {
        String code =
            "package test;\n" +
            "public class MixedMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        boolean active = true;\n" +
            "        boolean suspended = false;\n" +
            "        boolean hidden = false;\n" +
            "        boolean enabled = true;\n" +
            "        // active && not suspended  ->  true && !false  ->  true\n" +
            "        final allowed = active && not suspended;\n" +
            "        if (!allowed) throw new RuntimeException(\"mixed 1\");\n" +
            "        // enabled and !hidden  ->  true && !false  ->  true\n" +
            "        final visible = enabled and !hidden;\n" +
            "        if (!visible) throw new RuntimeException(\"mixed 2\");\n" +
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
            "public class PrecedenceMain {\n" +
            "    public static void main(String[] args) {\n" +
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
            "public class RealisticMain {\n" +
            "    public static boolean canAccess(\n" +
            "            boolean authenticated, boolean suspended, boolean administrator) {\n" +
            "        return authenticated and not suspended or administrator;\n" +
            "    }\n" +
            "    public static boolean exactlyOneSelected(boolean emailSelected, boolean smsSelected) {\n" +
            "        return emailSelected xor smsSelected;\n" +
            "    }\n" +
            "    public static boolean haveSameStatus(boolean firstActive, boolean secondActive) {\n" +
            "        return firstActive xnor secondActive;\n" +
            "    }\n" +
            "    public static void main(String[] args) {\n" +
            "        if (!canAccess(true, false, false)) throw new RuntimeException(\"canAccess 1\");\n" +
            "        if (canAccess(true, true, false))   throw new RuntimeException(\"canAccess 2\");\n" +
            "        if (!canAccess(false, true, true))  throw new RuntimeException(\"canAccess 3\");\n" +
            "        if (!exactlyOneSelected(true, false)) throw new RuntimeException(\"xor 1\");\n" +
            "        if (exactlyOneSelected(true, true))   throw new RuntimeException(\"xor 2\");\n" +
            "        if (!haveSameStatus(true, true))   throw new RuntimeException(\"xnor 1\");\n" +
            "        if (haveSameStatus(true, false))   throw new RuntimeException(\"xnor 2\");\n" +
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
    // TYPE ERROR TESTS
    // =====================================================================

    @Test
    void testTypeErrorIntAnd() throws Exception {
        String code =
            "package test;\n" +
            "public class TypeErrMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        boolean r = 1 and 2;\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("TypeErrMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertFalse(result.successful(), "Expected type error for int and int");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
            d.message().contains("and") && d.message().contains("int")),
            "Expected diagnostic mentioning 'and' and 'int'");
    }

    @Test
    void testTypeErrorNotOnInt() throws Exception {
        String code =
            "package test;\n" +
            "public class TypeErrNot {\n" +
            "    public static void main(String[] args) {\n" +
            "        boolean r = not 42;\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("TypeErrNot.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertFalse(result.successful(), "Expected type error for not int");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
            d.message().contains("not")),
            "Expected diagnostic mentioning 'not'");
    }
}
