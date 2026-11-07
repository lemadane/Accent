package accent.compiler;

import accent.ast.Ast.CompilationUnit;
import accent.ast.Ast.ExtensionDecl;
import accent.ast.Ast.TypeDeclaration;
import accent.diagnostic.Diagnostic;
import accent.diagnostic.DiagnosticSeverity;
import accent.lexer.AccentLexer;
import accent.lexer.LexResult;
import accent.parser.AccentParser;
import accent.semantic.SemanticAnalyzer;
import accent.lowering.AccentLowerer;
import accent.emitter.JavaEmitter;
import accent.symbol.SymbolTable;
import accent.source.SourceFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Entry point for the Accent compiler pipeline. */
public final class AccentCompiler {

    public CompilationResult check(Path path) {
        Objects.requireNonNull(path, "path");
        Path normalizedPath = path.toAbsolutePath().normalize();

        if (!normalizedPath.getFileName().toString().endsWith(".accent")) {
            Diagnostic diagnostic = new Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "ACCENT-C001",
                    "Accent source files must use the .accent extension.",
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
                    "ACCENT-C002",
                    "Unable to read source file: " + exception.getMessage(),
                    1,
                    1);
            return new CompilationResult(Optional.empty(), List.of(diagnostic));
        }
    }

    public CompilationResult check(SourceFile sourceFile) {
        Objects.requireNonNull(sourceFile, "sourceFile");
        LexResult lexResult = new AccentLexer(sourceFile).lex();

        if (!lexResult.successful()) {
            return new CompilationResult(Optional.empty(), lexResult.diagnostics());
        }

        AccentParser parser = new AccentParser(sourceFile, lexResult.tokens());
        CompilationUnit compilationUnit = parser.parse();

        List<Diagnostic> diagnostics = new ArrayList<>(lexResult.diagnostics());
        diagnostics.addAll(parser.diagnostics());

        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        if (!registerTypeNames(compilationUnit, new LinkedHashMap<>(), diagnostics)) {
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

    /**
     * Checks several in-memory sources together (one top-level type per file), so that
     * they can reference each other.
     */
    public CompilationResult check(List<SourceFile> sourceFiles) {
        MultiCheck outcome = checkAll(sourceFiles);
        List<Diagnostic> diagnostics = outcome.perFile().values().stream().flatMap(List::stream).toList();
        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }
        return new CompilationResult(outcome.lastUnit(), diagnostics);
    }

    /**
     * Like {@link #check(List)}, but keeps each diagnostic attributed to the file it came from.
     * Files without diagnostics map to an empty list; iteration order matches {@code sourceFiles}.
     */
    public Map<SourceFile, List<Diagnostic>> checkEach(List<SourceFile> sourceFiles) {
        return checkAll(sourceFiles).perFile();
    }

    private record MultiCheck(Map<SourceFile, List<Diagnostic>> perFile, Optional<CompilationUnit> lastUnit) {}

    private MultiCheck checkAll(List<SourceFile> sourceFiles) {
        Objects.requireNonNull(sourceFiles, "sourceFiles");
        Map<SourceFile, List<Diagnostic>> perFile = new LinkedHashMap<>();
        Map<SourceFile, CompilationUnit> units = new LinkedHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            List<Diagnostic> diagnostics = new ArrayList<>();
            perFile.put(sourceFile, diagnostics);
            LexResult lexResult = new AccentLexer(sourceFile).lex();
            diagnostics.addAll(lexResult.diagnostics());
            if (!lexResult.successful()) continue;
            AccentParser parser = new AccentParser(sourceFile, lexResult.tokens());
            units.put(sourceFile, parser.parse());
            diagnostics.addAll(parser.diagnostics());
        }
        boolean syntaxErrors = perFile.values().stream().flatMap(List::stream)
                .anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR);
        if (syntaxErrors) return new MultiCheck(perFile, Optional.empty());
        SymbolTable table = new SymbolTable();
        Map<String, DeclarationSite> declaredTypes = new LinkedHashMap<>();
        for (var entry : units.entrySet()) {
            if (registerTypeNames(entry.getValue(), declaredTypes, perFile.get(entry.getKey()))) {
                table.addCompilationUnit(entry.getValue());
            }
        }
        if (perFile.values().stream().flatMap(List::stream).anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new MultiCheck(perFile, Optional.empty());
        }
        CompilationUnit last = null;
        for (var entry : units.entrySet()) {
            SemanticAnalyzer analyzer = new SemanticAnalyzer(table);
            analyzer.analyze(entry.getValue());
            perFile.get(entry.getKey()).addAll(analyzer.diagnostics());
            last = entry.getValue();
        }
        return new MultiCheck(perFile, Optional.ofNullable(last));
    }

    private record DeclarationSite(SourceFile sourceFile, TypeDeclaration declaration) {}

    private static boolean registerTypeNames(CompilationUnit unit, Map<String, DeclarationSite> declaredTypes,
                                             List<Diagnostic> diagnostics) {
        boolean unique = true;
        for (var declaration : unit.declarations()) {
            String fullName = unit.packageName().map(pkg -> pkg + ".").orElse("") + declaration.name();
            DeclarationSite previous = declaredTypes.putIfAbsent(fullName, new DeclarationSite(unit.sourceFile(), declaration));
            if (previous != null) {
                boolean extension = declaration instanceof ExtensionDecl || previous.declaration() instanceof ExtensionDecl;
                if (declaration instanceof ExtensionDecl current && previous.declaration() instanceof ExtensionDecl earlier
                        && unit.sourceFile().equals(previous.sourceFile()) && current.targetType().equals(earlier.targetType())) {
                    continue; // Blocks in one source share an import scope and can become one helper.
                }
                String message = extension ? "Duplicate extension helper/type '" : "Duplicate type '";
                message += fullName + "' in " + unit.sourceFile().path() + "; already declared in " + previous.sourceFile().path();
                if (extension) message += ". Keep extensions for the same target in one source file and avoid names used by extension helpers.";
                diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, extension ? "ACCENT-C007" : "ACCENT-C006",
                        message, 1, 1));
                unique = false;
            }
        }
        return unique;
    }

    public CompilationResult compile(List<Path> sourcePaths, Path outputDir, List<String> classpath, Path generatedSourceDir, boolean saveJava) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<Path> allSourceFiles = new LinkedHashSet<>();

        // Find all source files recursively
        for (Path srcPath : sourcePaths) {
            if (Files.isDirectory(srcPath)) {
                try (Stream<Path> walk = Files.walk(srcPath)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> !p.toAbsolutePath().normalize().startsWith(outputDir.toAbsolutePath().normalize()))
                        .filter(p -> generatedSourceDir == null || !p.toAbsolutePath().normalize().startsWith(generatedSourceDir.toAbsolutePath().normalize()))
                        .filter(p -> p.toString().endsWith(".accent") || p.toString().endsWith(".java"))
                        .map(p -> p.toAbsolutePath().normalize())
                        .sorted()
                        .forEach(allSourceFiles::add);
                } catch (IOException e) {
                    diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "ACCENT-C002", "Failed to scan directory: " + srcPath + ". Error: " + e.getMessage(), 1, 1));
                    return new CompilationResult(Optional.empty(), diagnostics);
                }
            } else if (Files.isRegularFile(srcPath)) {
                if (srcPath.toString().endsWith(".accent") || srcPath.toString().endsWith(".java")) {
                    allSourceFiles.add(srcPath.toAbsolutePath().normalize());
                } else {
                    diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "ACCENT-C001", "Source files must use .accent or .java extension.", 1, 1));
                    return new CompilationResult(Optional.empty(), diagnostics);
                }
            } else {
                diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "ACCENT-C002",
                        "Source path does not exist or is not a regular file or directory: " + srcPath, 1, 1));
                return new CompilationResult(Optional.empty(), diagnostics);
            }
        }

        ClassLoader customClassLoader = ClassLoader.getSystemClassLoader();
        if (classpath != null && !classpath.isEmpty()) {
            List<java.net.URL> urls = new ArrayList<>();
            for (String cp : classpath) {
                try {
                    urls.add(Path.of(cp).toUri().toURL());
                } catch (Exception e) {
                    // Ignore invalid path
                }
            }
            if (!urls.isEmpty()) {
                customClassLoader = new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]), ClassLoader.getSystemClassLoader());
            }
        }
        SymbolTable symbolTable = new SymbolTable(customClassLoader);
        Map<String, DeclarationSite> declaredTypes = new LinkedHashMap<>();
        List<CompilationUnit> accentUnits = new ArrayList<>();
        List<Path> originalJavaFiles = new ArrayList<>();

        // Parse files
        for (Path file : allSourceFiles) {
            boolean isJava = file.toString().endsWith(".java");
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                SourceFile source = new SourceFile(file, content);
                LexResult lexResult = new AccentLexer(source).lex();
                diagnostics.addAll(lexResult.diagnostics());

                if (lexResult.successful()) {
                    AccentParser parser = new AccentParser(source, lexResult.tokens());
                    CompilationUnit unit = parser.parse();
                    diagnostics.addAll(parser.diagnostics());

                    if (!registerTypeNames(unit, declaredTypes, diagnostics)) continue;
                    symbolTable.addCompilationUnit(unit);
                    if (!isJava) {
                        accentUnits.add(unit);
                    } else {
                        originalJavaFiles.add(file);
                    }
                }
            } catch (IOException e) {
                diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "ACCENT-C002", "Failed to read file: " + file + ". Error: " + e.getMessage(), 1, 1));
            }
        }

        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Semantic analysis
        SemanticAnalyzer analyzer = new SemanticAnalyzer(symbolTable);
        for (CompilationUnit unit : accentUnits) {
            analyzer.analyze(unit);
        }
        diagnostics.addAll(analyzer.diagnostics());

        if (diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Lower and emit Java code
        AccentLowerer lowerer = new AccentLowerer(symbolTable, analyzer.resolvedTypes);
        JavaEmitter emitter = new JavaEmitter();
        List<Path> compiledJavaFiles = new ArrayList<>(originalJavaFiles);

        try {
            Files.createDirectories(outputDir);
            Files.createDirectories(generatedSourceDir);

            for (CompilationUnit unit : accentUnits) {
                CompilationUnit lowered = lowerer.lower(unit);

                // Determine output path based on package
                Path packagePath = generatedSourceDir;
                if (unit.packageName().isPresent()) {
                    String pkg = unit.packageName().get().replace('.', '/');
                    packagePath = generatedSourceDir.resolve(pkg);
                }
                Files.createDirectories(packagePath);

                // Repeated extension blocks in a source share a helper and the source's import scope.
                Map<String, TypeDeclaration> declarations = new LinkedHashMap<>();
                for (var declaration : lowered.declarations()) {
                    TypeDeclaration previous = declarations.get(declaration.name());
                    if (declaration instanceof ExtensionDecl extension && previous instanceof ExtensionDecl earlier) {
                        var members = new ArrayList<>(earlier.members());
                        members.addAll(extension.members());
                        declarations.put(declaration.name(), new ExtensionDecl(extension.targetType(), members));
                    } else {
                        declarations.put(declaration.name(), declaration);
                    }
                }

                // Public extension helpers need their own Java files, including when they accompany a type.
                for (var declaration : declarations.values()) {
                    CompilationUnit generatedUnit = new CompilationUnit(lowered.sourceFile(), lowered.packageName(),
                            lowered.imports(), List.of(declaration), lowered.tokens());
                    Path genFile = packagePath.resolve(declaration.name() + ".java");
                    Files.writeString(genFile, emitter.emit(generatedUnit), StandardCharsets.UTF_8);
                    compiledJavaFiles.add(genFile);
                }
            }
        } catch (IOException e) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "ACCENT-C004", "Failed during code generation: " + e.getMessage(), 1, 1));
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        if (compiledJavaFiles.isEmpty()) {
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Invoke javac
        List<String> javacArgs = new ArrayList<>();
        javacArgs.add("-parameters");
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
            diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "ACCENT-C005", "System Java compiler (javac) is not available in this runtime environment.", 1, 1));
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        int exitCode = compiler.run(null, null, null, javacArgs.toArray(new String[0]));
        if (exitCode != 0) {
            diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "ACCENT-C003", "Java compilation failed with exit code " + exitCode, 1, 1));
            return new CompilationResult(Optional.empty(), diagnostics);
        }

        // Cleanup generated sources if saveJava is false
        if (!saveJava) {
            // Delete temp generated java files or directories
        }

        return new CompilationResult(Optional.empty(), diagnostics);
    }
}
