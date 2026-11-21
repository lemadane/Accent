package accent.compiler;

import accent.diagnostic.DiagnosticSeverity;
import accent.source.SourceFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NullabilityWarningTest {
    @Test
    void warnsForNullableMethodParametersAndResultsWithoutFailingChecks() {
        SourceFile source = new SourceFile(Path.of("NullabilityWarnings.accent"), """
                class NullabilityWarnings {
                    String lookup(String key?) { return key? else "" }
                    String optional()? { return null }
                    String first(List<String?> values) { return "" }
                    String regular(String value) { return value }
                }
                """);

        var result = new AccentCompiler().check(source);

        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertEquals(2, result.diagnostics().size(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.severity() == DiagnosticSeverity.WARNING));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ACCENT-W002")
                && d.message().equals("Parameter 'key' of method 'lookup()' accepts null.")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ACCENT-W003")
                && d.message().equals("Method 'optional()' may return null.")));
    }
}
