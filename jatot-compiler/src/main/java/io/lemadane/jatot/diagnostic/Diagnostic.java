package io.lemadane.jatot.diagnostic;

import java.util.Objects;

public record Diagnostic(
        DiagnosticSeverity severity,
        String code,
        String message,
        int line,
        int column) {

    public Diagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");

        if (line < 1) {
            throw new IllegalArgumentException("line must be at least 1");
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be at least 1");
        }
    }

    public String format(String fileName) {
        return "%s:%d:%d: %s %s: %s".formatted(
                fileName,
                line,
                column,
                severity.name().toLowerCase(),
                code,
                message);
    }
}
