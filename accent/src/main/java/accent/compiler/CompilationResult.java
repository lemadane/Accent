package accent.compiler;

import accent.ast.Ast.CompilationUnit;
import accent.diagnostic.Diagnostic;
import accent.diagnostic.DiagnosticSeverity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CompilationResult(
        Optional<CompilationUnit> compilationUnit,
        List<Diagnostic> diagnostics) {

    public CompilationResult {
        compilationUnit = Objects.requireNonNull(compilationUnit, "compilationUnit");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean successful() {
        return diagnostics.stream()
                .noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }
}
