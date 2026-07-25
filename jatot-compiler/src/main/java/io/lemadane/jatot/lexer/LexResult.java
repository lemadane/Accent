package io.lemadane.jatot.lexer;

import io.lemadane.jatot.diagnostic.Diagnostic;
import java.util.List;
import java.util.Objects;

public record LexResult(List<Token> tokens, List<Diagnostic> diagnostics) {
    public LexResult {
        tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean successful() {
        return diagnostics.stream().noneMatch(diagnostic ->
                diagnostic.severity() == io.lemadane.jatot.diagnostic.DiagnosticSeverity.ERROR);
    }
}
