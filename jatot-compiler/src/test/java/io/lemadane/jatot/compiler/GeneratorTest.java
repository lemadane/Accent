package io.lemadane.jatot.compiler;

import io.lemadane.jatot.lexer.*;
import io.lemadane.jatot.source.SourceFile;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code generator} / {@code emit} feature.
 */
class GeneratorTest {

    private LexResult lex(String src) {
        SourceFile sf = new SourceFile(Path.of("Test.jatot"), src);
        return new JatotLexer(sf).lex();
    }

    // ─── Lexer ─────────────────────────────────────────────

    @Test
    void lexerRecognizesGeneratorKeyword() {
        LexResult r = lex("generator int numbers()");
        assertEquals(TokenType.GENERATOR, r.tokens().get(0).type());
    }

    @Test
    void lexerRecognizesEmitKeyword() {
        LexResult r = lex("emit value");
        assertEquals(TokenType.EMIT, r.tokens().get(0).type());
    }

    @Test
    void lexerDoesNotTokenizeSubstringsAsKeywords() {
        LexResult r = lex("generators emitting");
        assertEquals(TokenType.IDENTIFIER, r.tokens().get(0).type());
        assertEquals("generators", r.tokens().get(0).lexeme());
        assertEquals(TokenType.IDENTIFIER, r.tokens().get(1).type());
        assertEquals("emitting", r.tokens().get(1).lexeme());
    }

    // ─── End-to-end compilation & execution ─────────────────

    /**
     * Compiles a Jatot source that uses generator/emit, runs it via
     * reflection and checks the generated Iterable produces the expected
     * values.
     */
    @Test
    void generatorProducesLazyIterable() throws Exception {
        String jatotSrc =
            "package test;\n" +
            "public class Gen {\n" +
            "    public generator int numbers(int limit) {\n" +
            "        for (var i = 0; i < limit; i++) {\n" +
            "            emit i;\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public static void main(String[] args) {\n" +
            "        var gen = new Gen();\n" +
            "        var result = new java.util.ArrayList<Integer>();\n" +
            "        for (Integer n : gen.numbers(5)) {\n" +
            "            result.add(n);\n" +
            "        }\n" +
            "        if (result.size() != 5) throw new RuntimeException(\"Expected 5, got \" + result.size());\n" +
            "        for (var i = 0; i < 5; i++) {\n" +
            "            if (!result.get(i).equals(i)) throw new RuntimeException(\"Expected \" + i + \" at index \" + i + \", got \" + result.get(i));\n" +
            "        }\n" +
            "    }\n" +
            "}\n";

        CompilationResult cr = new JatotCompiler().check(
            new SourceFile(Path.of("Gen.jatot"), jatotSrc));
        assertTrue(cr.successful(), "Compilation failed: " + cr.diagnostics());
    }

    @Test
    void generatorEmitsStrings() throws Exception {
        String jatotSrc =
            "package test;\n" +
            "public class StringGen {\n" +
            "    public generator String greetings(String! name) {\n" +
            "        emit \"Hello, \" + name + \"!\";\n" +
            "        emit \"Welcome, \" + name + \".\";\n" +
            "        emit \"Goodbye, \" + name + \".\";\n" +
            "    }\n" +
            "}\n";

        CompilationResult cr = new JatotCompiler().check(
            new SourceFile(Path.of("StringGen.jatot"), jatotSrc));
        assertTrue(cr.successful(), "Compilation failed: " + cr.diagnostics());
    }

    @Test
    void generatorWithConditionalEmit() throws Exception {
        String jatotSrc =
            "package test;\n" +
            "public class ConditionalGen {\n" +
            "    public generator int evenNumbers(int limit) {\n" +
            "        for (var i = 0; i < limit; i++) {\n" +
            "            if (i % 2 == 0) {\n" +
            "                emit i;\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "}\n";

        CompilationResult cr = new JatotCompiler().check(
            new SourceFile(Path.of("ConditionalGen.jatot"), jatotSrc));
        assertTrue(cr.successful(), "Compilation failed: " + cr.diagnostics());
    }

    @Test
    void generatorReturnTypeIsIterableInEmittedJava() throws Exception {
        String jatotSrc =
            "package test;\n" +
            "public class ReturnCheck {\n" +
            "    public generator int numbers(int limit) {\n" +
            "        for (var i = 0; i < limit; i++) {\n" +
            "            emit i;\n" +
            "        }\n" +
            "    }\n" +
            "}\n";

        CompilationResult cr = new JatotCompiler().check(
            new SourceFile(Path.of("ReturnCheck.jatot"), jatotSrc));
        assertTrue(cr.successful(), "Compilation failed: " + cr.diagnostics());

        // Verify emitted Java source contains Iterable<Integer>
        io.lemadane.jatot.emitter.JavaEmitter emitter = new io.lemadane.jatot.emitter.JavaEmitter();
        io.lemadane.jatot.lowering.JatotLowerer lowerer = new io.lemadane.jatot.lowering.JatotLowerer(new io.lemadane.jatot.symbol.SymbolTable());
        String javaSource = emitter.emit(lowerer.lower(cr.compilationUnit().get()));
        assertTrue(javaSource.contains("Iterable<Integer>"),
            "Expected Iterable<Integer> in emitted Java:\n" + javaSource);
        assertTrue(javaSource.contains("JatotGenerator.of"),
            "Expected JatotGenerator.of in emitted Java:\n" + javaSource);
        assertTrue(javaSource.contains("__emit.accept"),
            "Expected __emit.accept in emitted Java:\n" + javaSource);
    }

    @Test
    void generatorOnFieldIsError() {
        String jatotSrc =
            "package test;\n" +
            "public class BadField {\n" +
            "    generator int field;\n" +
            "}\n";

        CompilationResult cr = new JatotCompiler().check(
            new SourceFile(Path.of("BadField.jatot"), jatotSrc));
        assertFalse(cr.successful(), "Generator on a field should fail");
    }

    @Test
    void emptyGenerator() throws Exception {
        String jatotSrc =
            "package test;\n" +
            "public class EmptyGen {\n" +
            "    public generator int nothing() {\n" +
            "    }\n" +
            "}\n";

        CompilationResult cr = new JatotCompiler().check(
            new SourceFile(Path.of("EmptyGen.jatot"), jatotSrc));
        assertTrue(cr.successful(), "Empty generator should compile: " + cr.diagnostics());
    }

    @Test
    void generatorBoxesPrimitiveTypes() throws Exception {
        io.lemadane.jatot.emitter.JavaEmitter emitter = new io.lemadane.jatot.emitter.JavaEmitter();
        io.lemadane.jatot.lowering.JatotLowerer lowerer = new io.lemadane.jatot.lowering.JatotLowerer(new io.lemadane.jatot.symbol.SymbolTable());

        // Test all primitive types
        Map<String, String> primitiveToBoxed = Map.of(
            "int", "Integer",
            "long", "Long",
            "double", "Double",
            "float", "Float",
            "boolean", "Boolean",
            "char", "Character",
            "byte", "Byte",
            "short", "Short"
        );

        for (var entry : primitiveToBoxed.entrySet()) {
            String prim = entry.getKey();
            String boxed = entry.getValue();
            String src =
                "package test;\n" +
                "public class BoxTest {\n" +
                "    public generator " + prim + " values() {\n" +
                "    }\n" +
                "}\n";

            CompilationResult cr = new JatotCompiler().check(
                new SourceFile(Path.of("BoxTest.jatot"), src));
            assertTrue(cr.successful(), prim + " generator failed: " + cr.diagnostics());

            String java = emitter.emit(lowerer.lower(cr.compilationUnit().get()));
            assertTrue(java.contains("Iterable<" + boxed + ">"),
                "Expected Iterable<" + boxed + "> for " + prim + " generator, got:\n" + java);
        }
    }
}
