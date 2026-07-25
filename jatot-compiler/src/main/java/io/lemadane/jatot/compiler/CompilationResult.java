package io.lemadane.jatot.compiler;

import io.lemadane.jatot.ast.Ast.CompilationUnit;
import io.lemadane.jatot.diagnostic.Diagnostic;
import io.lemadane.jatot.diagnostic.DiagnosticSeverity;
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
