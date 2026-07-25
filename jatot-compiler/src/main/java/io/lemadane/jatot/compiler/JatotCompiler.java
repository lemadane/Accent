package io.lemadane.jatot.compiler;

import io.lemadane.jatot.ast.Ast.CompilationUnit;
import io.lemadane.jatot.diagnostic.Diagnostic;
import io.lemadane.jatot.diagnostic.DiagnosticSeverity;
import io.lemadane.jatot.lexer.JatotLexer;
import io.lemadane.jatot.lexer.LexResult;
import io.lemadane.jatot.parser.JatotParser;
import io.lemadane.jatot.semantic.SemanticAnalyzer;
import io.lemadane.jatot.lowering.JatotLowerer;
import io.lemadane.jatot.emitter.JavaEmitter;
import io.lemadane.jatot.symbol.SymbolTable;
import io.lemadane.jatot.source.SourceFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Entry point for the Jatot compiler pipeline. */
public final class JatotCompiler {

    public CompilationResult check(Path path) {
        Objects.requireNonNull(path, "path");
        Path normalizedPath = path.toAbsolutePath().normalize();

        if (!normalizedPath.getFileName().toString().endsWith(".jatot")) {
            Diagnostic diagnostic = new Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "JATOT-C001",
                    "Jatot source files must use the .jatot extension.",
                    1,
                    1);
            return new CompilationResult(Optional.empty(), List.of(diagnostic));
        }

        try {
            String content = Files.readString(normalizedPath, StandardCharsets.UTF_8);
            return check(new SourceFile(normalizedPath, content));
        } catch (IOException exception) {
            Diagnostic diagnostic = new Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "JATOT-C002",
                    "Unable to read source file: " + exception.getMessage(),
                    1,
                    1);
            return new CompilationResult(Optional.empty(), List.of(diagnostic));
        }
    }

    public CompilationResult check(SourceFile sourceFile) {
        Objects.requireNonNull(sourceFile, "sourceFile");
        LexResult lexResult = new JatotLexer(sourceFile).lex();

        if (!lexResult.successful()) {
            return new CompilationResult(Optional.empty(), lexResult.diagnostics());
        }

        JatotParser parser = new JatotParser(sourceFile, lexResult.tokens());
        CompilationUnit compilationUnit = parser.parse();

        List<Diagnostic> diagnostics = new ArrayList<>(lexResult.diagnostics());
        diagnostics.addAll(parser.diagnostics());

        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Perform semantic checks just for validation
        SymbolTable table = new SymbolTable();
        table.addCompilationUnit(compilationUnit);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(table);
        analyzer.analyze(compilationUnit);
        diagnostics.addAll(analyzer.diagnostics());

        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        return new CompilationResult(
                Optional.of(compilationUnit),
                diagnostics);
    }

    public CompilationResult compile(List<Path> sourcePaths, Path outputDir, List<String> classpath, Path generatedSourceDir, boolean saveJava) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Path> allSourceFiles = new ArrayList<>();

        // Find all source files recursively
        for (Path srcPath : sourcePaths) {
            if (Files.isDirectory(srcPath)) {
                try (Stream<Path> walk = Files.walk(srcPath)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".jatot") || p.toString().endsWith(".java"))
                        .forEach(allSourceFiles::add);
                } catch (IOException e) {
                    diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "JATOT-C002", "Failed to scan directory: " + srcPath + ". Error: " + e.getMessage(), 1, 1));
                    return new CompilationResult(Optional.empty(), diagnostics);
                }
            } else if (Files.isRegularFile(srcPath)) {
                if (srcPath.toString().endsWith(".jatot") || srcPath.toString().endsWith(".java")) {
                    allSourceFiles.add(srcPath);
                } else {
                    diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "JATOT-C001", "Source files must use .jatot or .java extension.", 1, 1));
                    return new CompilationResult(Optional.empty(), diagnostics);
                }
            }
        }

        SymbolTable symbolTable = new SymbolTable();
        List<CompilationUnit> jatotUnits = new ArrayList<>();
        List<Path> originalJavaFiles = new ArrayList<>();

        // Parse files
        for (Path file : allSourceFiles) {
            boolean isJava = file.toString().endsWith(".java");
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                SourceFile source = new SourceFile(file, content);
                LexResult lexResult = new JatotLexer(source).lex();
                diagnostics.addAll(lexResult.diagnostics());

                if (lexResult.successful()) {
                    JatotParser parser = new JatotParser(source, lexResult.tokens());
                    CompilationUnit unit = parser.parse();
                    diagnostics.addAll(parser.diagnostics());

                    symbolTable.addCompilationUnit(unit);
                    if (!isJava) {
                        jatotUnits.add(unit);
                    } else {
                        originalJavaFiles.add(file);
                    }
                }
            } catch (IOException e) {
                diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "JATOT-C002", "Failed to read file: " + file + ". Error: " + e.getMessage(), 1, 1));
            }
        }

        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Semantic analysis
        SemanticAnalyzer analyzer = new SemanticAnalyzer(symbolTable);
        for (CompilationUnit unit : jatotUnits) {
            analyzer.analyze(unit);
        }
        diagnostics.addAll(analyzer.diagnostics());

        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Lower and emit Java code
        JatotLowerer lowerer = new JatotLowerer(symbolTable);
        JavaEmitter emitter = new JavaEmitter();
        List<Path> compiledJavaFiles = new ArrayList<>(originalJavaFiles);

        try {
            Files.createDirectories(outputDir);
            Files.createDirectories(generatedSourceDir);

            for (CompilationUnit unit : jatotUnits) {
                CompilationUnit lowered = lowerer.lower(unit);
                String javaSource = emitter.emit(lowered);

                // Determine output path based on package
                Path packagePath = generatedSourceDir;
                if (unit.packageName().isPresent()) {
                    String pkg = unit.packageName().get().replace('.', '/');
                    packagePath = generatedSourceDir.resolve(pkg);
                }
                Files.createDirectories(packagePath);

                // Assuming first declaration name is the class/filename
                String filename = unit.declarations().isEmpty() ? "Module" : unit.declarations().get(0).name();
                Path genFile = packagePath.resolve(filename + ".java");
                Files.writeString(genFile, javaSource, StandardCharsets.UTF_8);

                compiledJavaFiles.add(genFile);
            }
        } catch (IOException e) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "JATOT-C004", "Failed during code generation: " + e.getMessage(), 1, 1));
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        if (compiledJavaFiles.isEmpty()) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Invoke javac
        List<String> javacArgs = new ArrayList<>();
        javacArgs.add("-d");
        javacArgs.add(outputDir.toString());

        if (!classpath.isEmpty()) {
            javacArgs.add("-cp");
            javacArgs.add(String.join(System.getProperty("path.separator"), classpath));
        }

        for (Path f : compiledJavaFiles) {
            javacArgs.add(f.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "JATOT-C005", "System Java compiler (javac) is not available in this runtime environment.", 1, 1));
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        int exitCode = compiler.run(null, null, null, javacArgs.toArray(new String[0]));
        if (exitCode != 0) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "JATOT-C003", "Java compilation failed with exit code " + exitCode, 1, 1));
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Cleanup generated sources if saveJava is false
        if (!saveJava) {
            // Delete temp generated java files or directories
        }

        return new CompilationResult(Optional.empty(), diagnostics);
    }
}
