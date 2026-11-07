package accent.compiler;

import accent.diagnostic.Diagnostic;
import accent.diagnostic.DiagnosticSeverity;
import accent.source.SourceFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticVisibilityTest {
    private static final SourceFile COUNTER = source("vis/Counter.accent", """
            package vis;
            class Counter {
                int total = 0;
                int _cache = 0;
                int __step = 1;
                static int __shared = 0;
                void inc() {}
                void _reset() {}
                void __grow() {}
                static void __touch() {}
            }
            """);

    private static SourceFile source(String path, String content) {
        return new SourceFile(Path.of(path), content);
    }

    private static void assertAccepted(SourceFile... sources) {
        AccentCompiler compiler = new AccentCompiler();
        CompilationResult result = compiler.check(List.of(sources));
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(compiler.checkEach(List.of(sources)).values().stream().flatMap(List::stream)
                .noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }

    private static void assertRejected(List<SourceFile> sources, SourceFile offending, String... fragments) {
        AccentCompiler compiler = new AccentCompiler();
        CompilationResult result = compiler.check(sources);
        assertFalse(result.successful(), "Semantic checking must reject inaccessible members before javac runs");
        var perFile = compiler.checkEach(sources);
        for (String fragment : fragments) {
            assertTrue(result.diagnostics().stream().map(Diagnostic::message).anyMatch(m -> m.contains(fragment)),
                    () -> "Missing diagnostic for " + fragment + ": " + result.diagnostics());
            assertTrue(perFile.get(offending).stream().map(Diagnostic::message).anyMatch(m -> m.contains(fragment)),
                    () -> "Diagnostic must belong to " + offending.displayName() + ": " + perFile);
        }
        for (SourceFile source : sources) {
            if (!source.equals(offending)) assertTrue(perFile.get(source).isEmpty(), source.displayName());
        }
    }

    @Test
    void rejectsPackageAndProtectedMembersInUnrelatedPackages() {
        SourceFile snooper = source("other/Snooper.accent", """
                package other;
                import vis.Counter;
                class Snooper {
                    int peek(Counter! counter) {
                        counter._reset();
                        counter.__grow();
                        return counter._cache + counter.__step;
                    }
                }
                """);
        assertRejected(List.of(COUNTER, snooper), snooper,
                "field '_cache'", "field '__step'", "method '_reset'", "method '__grow'");
    }

    @Test
    void acceptsSamePackageAndPublicMembers() {
        SourceFile neighbor = source("vis/Neighbor.accent", """
                package vis;
                class Neighbor {
                    int peek(Counter! counter) {
                        counter._reset(); counter.__grow();
                        return counter._cache + counter.__step;
                    }
                }
                """);
        SourceFile visitor = source("other/Visitor.accent", """
                package other;
                import vis.Counter;
                class Visitor {
                    int peek(Counter! counter) { counter.inc(); return counter.total; }
                }
                """);
        assertAccepted(COUNTER, neighbor, visitor);
    }

    @Test
    void acceptsProtectedSubclassReceiversSuperAndStaticAccess() {
        SourceFile child = source("other/Child.accent", """
                package other;
                import vis.Counter;
                class Child extends Counter {
                    int peek(Child! sibling) {
                        this.__grow(); super.__grow(); sibling.__grow();
                        return this.__step + super.__step + sibling.__step;
                    }
                    static int shared() { Counter.__touch(); return Counter.__shared; }
                }
                """);
        assertAccepted(COUNTER, child);
    }

    @Test
    void rejectsProtectedBaseReceiversEvenInsideCrossPackageSubclass() {
        SourceFile child = source("other/Child.accent", """
                package other;
                import vis.Counter;
                class Child extends Counter {
                    int peek(Counter! counter) { counter.__grow(); return counter.__step; }
                }
                """);
        assertRejected(List.of(COUNTER, child), child, "field '__step'", "method '__grow'");
    }

    @Test
    void usesDeclaringPackageForInheritedMembers() {
        SourceFile child = source("other/Child.accent",
                "package other; import vis.Counter; class Child extends Counter {}");
        SourceFile neighbor = source("other/Neighbor.accent", """
                package other;
                class Neighbor {
                    int peek(Child! child) { child._reset(); return child._cache; }
                }
                """);
        assertRejected(List.of(COUNTER, child, neighbor), neighbor,
                "field '_cache' declared in 'vis.Counter'", "method '_reset' declared in 'vis.Counter'");
    }

    @Test
    void unqualifiedInheritedAccessCannotBypassVisibilityChecks() {
        SourceFile child = source("other/Child.accent", """
                package other;
                import vis.Counter;
                class Child extends Counter {
                    int peek() { _reset(); return _cache; }
                }
                """);
        assertRejected(List.of(COUNTER, child), child, "field '_cache'", "method '_reset'");
    }

    @Test
    void packageMembersAreNotInheritedAcrossPackageBoundaries() {
        SourceFile child = source("other/Child.accent",
                "package other; import vis.Counter; class Child extends Counter {}");
        SourceFile neighbor = source("vis/Neighbor.accent", """
                package vis;
                import other.Child;
                class Neighbor {
                    int peek(Child! child) { child._reset(); return child._cache; }
                }
                """);
        assertRejected(List.of(COUNTER, child, neighbor), neighbor, "field '_cache'", "method '_reset'");
    }

    @Test
    void generatedRecordFieldsRemainPrivateButAccessorsArePublic() {
        SourceFile point = source("vis/Point.accent", """
                package vis;
                record Point(int x) { int ownField() { return this.x; } }
                """);
        SourceFile valid = source("other/Valid.accent", """
                package other; import vis.Point;
                class Valid { int read(Point! point) { return point.x(); } }
                """);
        assertAccepted(point, valid);
        SourceFile invalid = source("vis/Invalid.accent", """
                package vis;
                class Invalid { int read(Point! point) { return point.x; } }
                """);
        assertRejected(List.of(point, invalid), invalid, "field 'x' declared in 'vis.Point'");
    }

    @Test
    void interfaceMembersAreImplicitlyPublicAcrossPackagesAndThroughImplementations() {
        SourceFile shape = source("vis/Shape.accent", """
                package vis;
                interface Shape {
                    int VALUE = 1;
                    int area();
                    static int initial() { return 1; }
                }
                """);
        SourceFile circle = source("vis/Circle.accent", """
                package vis;
                class Circle implements Shape { int area() { return 2; } }
                """);
        SourceFile visitor = source("other/Visitor.accent", """
                package other;
                import vis.Shape;
                import vis.Circle;
                class Visitor {
                    int read(Shape! shape, Circle! circle) {
                        return shape.area() + Shape.VALUE + Shape.initial() + circle.VALUE;
                    }
                }
                """);
        assertAccepted(shape, circle, visitor);
    }

    @Test
    void staticInterfaceMethodsAreNotInheritedByImplementations() {
        SourceFile api = source("vis/Api.accent", """
                package vis;
                interface Api { static int initial() { return 1; } }
                """);
        SourceFile implementation = source("vis/Implementation.accent",
                "package vis; class Implementation implements Api {}");
        SourceFile caller = source("vis/Caller.accent", """
                package vis;
                class Caller { int read(Implementation! value) { return value.initial(); } }
                """);
        assertRejected(List.of(api, implementation, caller), caller, "method 'initial'");
    }

    @Test
    void rejectsReflectedPrivatePackageAndInheritedProtectedMembers() {
        SourceFile snooper = source("other/JavaSnooper.accent", """
                package other;
                import java.util.ArrayList;
                class JavaSnooper {
                    int privateField(ArrayList! list) { return list.size; }
                    Object[] packageField(ArrayList! list) { return list.elementData; }
                    int inheritedProtectedField(ArrayList! list) { return list.modCount; }
                    void protectedMethod(ArrayList! list) { list.removeRange(0, 0); }
                    Object[] privateMethod(ArrayList! list) { return list.grow(); }
                    int packageMethod(String! text) { return text.coder(); }
                }
                """);
        assertRejected(List.of(snooper), snooper,
                "field 'size' declared in 'java.util.ArrayList'",
                "field 'elementData' declared in 'java.util.ArrayList'",
                "field 'modCount' declared in 'java.util.AbstractList'",
                "method 'removeRange' declared in 'java.util.ArrayList'",
                "method 'grow' declared in 'java.util.ArrayList'",
                "method 'coder' declared in 'java.lang.String'");
    }

    @Test
    void acceptsReflectedProtectedMembersOnValidSubclassReceivers() {
        SourceFile child = source("other/JavaChild.accent", """
                package other;
                import java.util.ArrayList;
                class JavaChild extends ArrayList {
                    int read(JavaChild! sibling) {
                        this.removeRange(0, 0); super.removeRange(0, 0); sibling.removeRange(0, 0);
                        return this.modCount + super.modCount + sibling.modCount;
                    }
                }
                """);
        assertAccepted(child);
    }

    @Test
    void publicOverridesRemainAccessibleWhenInheritedMethodsAreProtected() {
        SourceFile source = source("other/CloneList.accent", """
                package other;
                import java.util.ArrayList;
                class CloneList { Object copy(ArrayList! list) { return list.clone(); } }
                """);
        assertAccepted(source);
    }

    @Test
    void inaccessibleApplicableOverloadsCannotBeMaskedByAccessibleIncompatibleOnes() {
        for (String publicParameter : List.of("", "String text")) {
            SourceFile api = source("vis/Api.java", """
                    package vis;
                    public class Api {
                        public int read(%s) { return 0; }
                        private int read(int count) { return count; }
                    }
                    """.formatted(publicParameter));
            SourceFile caller = source("other/Caller.accent", """
                    package other;
                    import vis.Api;
                    class Caller { int call(Api! api) { return api.read(1); } }
                    """);
            assertRejected(List.of(api, caller), caller, "method 'read' declared in 'vis.Api'");
        }
    }

    @Test
    void newlyConstructedReceiversMatchNonNullExtensionsButNullableInputsDoNot() {
        SourceFile extension = source("vis/Builder.accent", """
                package vis;
                extension StringBuilder! { int extent() { return this.length(); } }
                """);
        SourceFile constructor = source("vis/Constructed.accent", """
                package vis;
                class Constructed { int size() { return new StringBuilder("text").extent(); } }
                """);
        assertAccepted(extension, constructor);
        SourceFile nullable = source("vis/Nullable.accent", """
                package vis;
                class Nullable { int size(StringBuilder builder) { return builder.extent(); } }
                """);
        assertRejected(List.of(extension, nullable), nullable, "Cannot resolve method 'extent'");
    }

    @Test
    void extensionPrefixesUseTheDeclarationPackageForAccess() {
        SourceFile extension = source("vis/Text.accent", """
                package vis;
                extension String {
                    int _hidden() { return 1; }
                    int __guarded() { return 2; }
                    int exposed() { return 3; }
                }
                """);
        SourceFile neighbor = source("vis/Neighbor.accent", """
                package vis;
                class Neighbor { int read(String! text) { return text._hidden() + text.__guarded(); } }
                """);
        SourceFile visitor = source("other/Visitor.accent", """
                package other;
                class Visitor { int read(String! text) { return text.exposed(); } }
                """);
        assertAccepted(extension, neighbor, visitor);
        SourceFile snooper = source("other/Snooper.accent", """
                package other;
                class Snooper { int read(String! text) { return text._hidden() + text.__guarded(); } }
                """);
        assertRejected(List.of(extension, snooper), snooper,
                "extension method '_hidden'", "extension method '__guarded'");
    }
}
