package accent.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UnionTypesTest {
    @TempDir Path root;

    private CompilationResult compile(String body) throws Exception {
        Path source = root.resolve("UnionDemo.accent");
        Files.writeString(source, "public class UnionDemo { " + body + " }");
        return new AccentCompiler().compile(List.of(source), root.resolve("classes"),
                List.of(), root.resolve("generated"), true);
    }

    @Test void unionsCompileAndRunWithPrimitiveAndReferenceNarrowing() throws Exception {
        var result = compile("""
            static String|int choose(boolean text) {
                return text then "Ada" else 10
            }
            public static String run(boolean text) {
                String|int value = static.choose(text)
                int age = value is int then value else 0
                String name = value is String then value else "Guest"
                if (value is String) {
                    return name + ":" + value.length()
                } else {
                    int next = value + 1
                    return name + ":" + age + ":" + next
                }
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader())) {
            var method = loader.loadClass("UnionDemo").getMethod("run", boolean.class);
            assertEquals("Ada:3", method.invoke(null, true));
            assertEquals("Guest:10:11", method.invoke(null, false));
        }
    }

    @Test void namedIsPatternsCompileAndRun() throws Exception {
        var result = compile("""
            static int calls = 0
            static String|int next(boolean text) {
                static.calls = static.calls + 1
                return text then "Ada" else 10
            }
            public static int run(boolean text) {
                static.calls = 0
                if (static.next(text) is String name) {
                    return name.length() + static.calls
                } else {
                    String|int nameAge = 10
                    return (nameAge is int age then age else 0) + static.calls
                }
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader())) {
            var method = loader.loadClass("UnionDemo").getMethod("run", boolean.class);
            assertEquals(4, method.invoke(null, true));
            assertEquals(11, method.invoke(null, false));
        }
    }

    @Test void namedPatternIsUnavailableInElseOrAfterIf() throws Exception {
        var result = compile("""
            static void run(String|int value) {
                if (value is String name) { println(name) }
                else { println(name) }
                println(name)
            }
            """);
        assertFalse(result.successful());
        assertEquals(2, result.diagnostics().stream()
                .filter(d -> d.message().contains("Cannot resolve symbol 'name'")).count());
    }

    @Test void namedPatternCannotShadowParameter() throws Exception {
        var result = compile("""
            static void run(String|int value, String name) {
                if (value is String name) { println(name) }
            }
            """);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("conflicts with an existing")));
    }

    @Test void instanceofIsRejectedWithMigrationDiagnostic() throws Exception {
        var result = compile("""
            static void run(String|int value) {
                if (value instanceof String) { println(value) }
            }
            """);
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Use 'is' instead of 'instanceof'")));
    }

    @Test void tryExpressionReturnsUnion() throws Exception {
        var result = compile("""
            static String|int parse(String text) {
                return try { yield Integer.parseInt(text) }
                catch (NumberFormatException e) { yield "invalid" }
            }
            public static int run() {
                String|int value = static.parse("12")
                return value is int then value else 0
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test void jdbcResultCanBeUsedInsideLoopAfterNarrowing() throws Exception {
        var result = compile("""
            static java.sql.ResultSet|String getOrders(java.sql.Statement database, String query) {
                return try { yield database.executeQuery(query) else "No result" }
                catch (java.sql.SQLException e) { yield e.getMessage() else "Database error" }
            }
            public static void run(java.sql.Statement database) throws java.sql.SQLException {
                java.sql.ResultSet|String results = static.getOrders(database, "SELECT * FROM orders")
                if (results is java.sql.ResultSet) {
                    while (results.next()) { println(results.getString("customer_name")) }
                } else { println("Database error: " + results) }
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()}, getClass().getClassLoader())) {
            var method = loader.loadClass("UnionDemo").getDeclaredMethod("getOrders", java.sql.Statement.class, String.class);
            method.setAccessible(true);
            var database = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Statement.class}, (proxy, called, args) -> {
                        if (args[0].equals("error")) throw new java.sql.SQLException();
                        return null;
                    });
            assertEquals("No result", method.invoke(null, database, "empty"));
            assertEquals("Database error", method.invoke(null, database, "error"));
        }
    }

    @Test void reassignmentInvalidatesNarrowing() throws Exception {
        assertFalse(compile("""
            static int run() {
                String|int value = 1
                if (value is int) {
                    value = "changed"
                    int age = value
                    return age
                }
                return 0
            }
            """).successful());
    }

    @Test void unionParametersAndFieldsPreserveAlternatives() throws Exception {
        var result = compile("""
            String|int value = 10
            static String|int identity(String|int input) { return input }
            public static int run() {
                String|int result = static.identity(10)
                return result is int then result else 0
            }
            """);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
    }

    @Test void rejectsValuesOutsideUnion() throws Exception {
        assertFalse(compile("static void run() { String|int value = true }").successful());
    }

    @Test void rejectsVoidAlternative() throws Exception {
        assertFalse(compile("static void run() { String|void value = \"bad\" }").successful());
    }

    @Test void rejectsUnrelatedUnionAssignment() throws Exception {
        assertFalse(compile("""
            static void run(String|boolean input) {
                String|int output = input
            }
            """).successful());
    }

    @Test void rejectsInvalidNullableUnionReturn() throws Exception {
        assertFalse(compile("static String?|int run() { return true }").successful());
    }

    @Test void rejectsNarrowingOutsideBranch() throws Exception {
        assertFalse(compile("""
            static int run(String|int value) {
                if (value is int) { int age = value }
                return value
            }
            """).successful());
    }

    @Test void rejectsMixedConditionalAssignedToString() throws Exception {
        assertFalse(compile("""
            static void run(String|int value) {
                String name = value is int then value else "Guest"
            }
            """).successful());
    }
}
