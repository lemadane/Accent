package accent.tools.core;

import accent.diagnostic.DiagnosticSeverity;
import accent.tools.core.AccentLanguageService.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EditorProjectRegressionTest {
    @TempDir
    Path directory;

    @Test
    void openingOneFileResolvesUnopenedSiblingSources() throws Exception {
        write("demo/Answer.accent", "package demo; class Answer { int value() { return 42; } }");
        String caller = "package demo; class Caller { int call() { return new Answer().value(); } }";
        Path path = write("demo/Caller.accent", caller);
        ProjectContext context = new ProjectContext(List.of());
        context.putFile(path, caller);

        var result = context.analyze(path, false);

        assertTrue(result.diagnostics().stream().noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR),
                () -> "Unopened sibling should resolve: " + result.diagnostics());
    }

    @Test
    void resolvesImportsUnderConventionalSourceRootsWithNonmatchingPackagePrefixes() throws Exception {
        for (String sourceSet : List.of("main", "test")) {
            String root = "src/" + sourceSet + "/accent/";
            write(root + "User.accent", "package demo; class User { String! name() { return \"Alice\"; } }");
            String page = """
                    package demo.routes;
                    import demo.User;
                    class Page { String! title() { return new User().name(); } }
                    """;
            Path path = write(root + "routes/Page.accent", page);
            ProjectContext context = new ProjectContext(List.of());
            context.putFile(path, page);

            var result = context.analyze(path, false);

            assertTrue(result.diagnostics().stream().noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR),
                    () -> "Source root " + sourceSet + " should resolve cross-package imports: " + result.diagnostics());
        }
    }

    @Test
    void dependentAnalysisUsesTheLatestUnsavedSiblingContents() {
        Path answer = directory.resolve("Answer.accent");
        Path caller = directory.resolve("Caller.accent");
        ProjectContext context = new ProjectContext(List.of());
        context.putFile(answer, "class Answer { int oldValue() { return 1; } }");
        assertTrue(context.analyze(answer, false).diagnostics().isEmpty());
        context.putFile(answer, "class Answer { int newValue() { return 42; } }");
        context.putFile(caller, "class Caller { int call() { return new Answer().newValue(); } }");

        var result = context.analyze(caller, false);

        assertTrue(result.diagnostics().stream().noneMatch(d -> d.severity() == DiagnosticSeverity.ERROR),
                () -> "Dependent analysis should see unsaved sibling edits: " + result.diagnostics());
    }

    @Test
    void generatedJavaPreviewRetainsSiblingExtensionResolution() throws Exception {
        String extension = """
                package shared;
                class Text {}
                extension String! {
                    String! decorate() { return "<" + this + ">"; }
                }
                """;
        Path text = write("shared/Text.accent", extension);
        String caller = """
                package client;
                import shared.Text;
                class Caller {
                    static String! run() { return "x".decorate(); }
                }
                """;
        Path path = write("client/Caller.accent", caller);
        ProjectContext context = new ProjectContext(List.of());
        context.putFile(text, extension);
        context.analyze(text, false);
        context.putFile(path, caller);
        var result = context.analyze(path, false);
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());

        String generated = AccentLanguageService.getGeneratedJava(result.unit(), context.getClassLoader());

        assertTrue(generated.contains("shared.Extension_String_.decorate(\"x\")"), generated);
        assertFalse(generated.contains("\"x\".decorate()"), generated);
    }

    @Test
    void projectPreviewUsesUnsavedSiblingExtensions() {
        ProjectContext context = new ProjectContext(List.of());
        Path text = directory.resolve("Text.accent");
        context.putFile(text, "package shared; class Text {} extension String! { int oldValue() { return 1; } }");
        context.analyze(text, false);
        context.putFile(text, "package shared; class Text {} extension String! { int newValue() { return 42; } }");
        Path caller = directory.resolve("Caller.accent");
        context.putFile(caller, "package client; class Caller { int call() { return \"x\".newValue(); } }");

        String generated = context.getGeneratedJava(caller);

        assertTrue(generated.contains("shared.Extension_String_.newValue(\"x\")"), generated);
        assertFalse(generated.contains("\"x\".newValue()"), generated);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = directory.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }
}
