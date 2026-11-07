package accent.tools.core;

import accent.ast.Ast.CompilationUnit;
import accent.diagnostic.Diagnostic;
import accent.parser.AccentParser;
import accent.lexer.AccentLexer;
import accent.lexer.LexResult;
import accent.semantic.SemanticAnalyzer;
import accent.symbol.SymbolTable;
import accent.source.SourceFile;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class AccentLanguageService {

    public static final class ProjectContext {
        private final List<Path> classpath = new ArrayList<>();
        private final Map<Path, SourceFile> files = new ConcurrentHashMap<>();
        private final Map<Path, CompilationUnit> units = new ConcurrentHashMap<>();
        private final Map<Path, SemanticAnalyzer> analyzers = new ConcurrentHashMap<>();
        private final Map<Path, SymbolTable> symbolTables = new ConcurrentHashMap<>();
        private ClassLoader classLoader;

        public ProjectContext(List<Path> classpath) {
            this.classpath.addAll(classpath);
            updateClassLoader();
        }

        public void updateClasspath(List<Path> newClasspath) {
            this.classpath.clear();
            this.classpath.addAll(newClasspath);
            updateClassLoader();
        }

        private void updateClassLoader() {
            try {
                URL[] urls = new URL[classpath.size()];
                for (int i = 0; i < classpath.size(); i++) {
                    urls[i] = classpath.get(i).toUri().toURL();
                }
                this.classLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
            } catch (Exception e) {
                this.classLoader = ClassLoader.getSystemClassLoader();
            }
        }

        public ClassLoader getClassLoader() {
            return classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        }

        public void putFile(Path path, String content) {
            Path normalized = normalize(path);
            files.put(normalized, new SourceFile(normalized, content));
        }

        public void removeFile(Path path) {
            Path normalized = normalize(path);
            files.remove(normalized);
            units.remove(normalized);
            analyzers.remove(normalized);
            symbolTables.remove(normalized);
        }

        public synchronized CompilationResult analyze(Path path, boolean errorTolerant) {
            Path normalized = normalize(path);
            SourceFile sourceFile = files.get(normalized);
            if (sourceFile == null) {
                return new CompilationResult(null, List.of(), null);
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            CompilationUnit unit = parse(sourceFile, errorTolerant, diagnostics);
            Map<Path, SourceFile> sources = discoverSources(unit);
            // Open buffers override disk contents, including unsaved edits in dependencies.
            sources.putAll(files);
            Map<Path, CompilationUnit> projectUnits = new LinkedHashMap<>();
            for (var entry : sources.entrySet()) {
                CompilationUnit projectUnit = entry.getKey().equals(normalized) ? unit
                        : parse(entry.getValue(), true, new ArrayList<>());
                projectUnits.put(entry.getKey(), projectUnit);
            }

            SymbolTable symbolTable = new SymbolTable(getClassLoader());
            projectUnits.values().forEach(symbolTable::addCompilationUnit);

            SemanticAnalyzer analyzer = new SemanticAnalyzer(symbolTable);
            analyzer.analyze(unit);
            units.put(normalized, unit);
            analyzers.put(normalized, analyzer);
            symbolTables.put(normalized, symbolTable);
            diagnostics.addAll(analyzer.diagnostics());

            return new CompilationResult(unit, diagnostics, analyzer);
        }

        private static CompilationUnit parse(SourceFile sourceFile, boolean errorTolerant, List<Diagnostic> diagnostics) {
            LexResult lexResult = new AccentLexer(sourceFile).lex();
            AccentParser parser = new AccentParser(sourceFile, lexResult.tokens());
            parser.setErrorTolerant(errorTolerant);
            CompilationUnit unit = parser.parse();
            diagnostics.addAll(lexResult.diagnostics());
            diagnostics.addAll(parser.diagnostics());
            return unit;
        }

        private static Map<Path, SourceFile> discoverSources(CompilationUnit unit) {
            Map<Path, SourceFile> sources = new LinkedHashMap<>();
            Path sourcePath = normalize(unit.sourceFile().path());
            // Synthetic editor/test buffers must not trigger a scan of the working directory.
            if (!Files.isRegularFile(sourcePath)) return sources;
            Path parent = sourcePath.getParent();
            Path sourceRoot = parent;
            boolean packageRoot = false;
            if (unit.packageName().isPresent()) {
                String[] segments = unit.packageName().orElseThrow().split("\\.");
                int remaining = segments.length;
                while (remaining > 0 && sourceRoot != null && sourceRoot.getFileName() != null
                        && sourceRoot.getFileName().toString().equals(segments[remaining - 1])) {
                    sourceRoot = sourceRoot.getParent();
                    remaining--;
                }
                packageRoot = remaining == 0 && sourceRoot != null && sourceRoot.getParent() != null;
                if (!packageRoot) sourceRoot = parent;
            }
            Path conventionalRoot = conventionalSourceRoot(parent);
            if (conventionalRoot != null) {
                sourceRoot = conventionalRoot;
                packageRoot = true;
            }
            // A package path or conventional source directory identifies a root; otherwise use immediate siblings.
            try {
                List<Path> sourcePaths = new ArrayList<>();
                if (packageRoot) {
                    Path root = sourceRoot;
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                            if (!path.equals(root) && Set.of("build", "target", "out", "node_modules", "jdk",
                                    ".git", ".gradle").contains(path.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                            if (attributes.isRegularFile() && path.toString().endsWith(".accent")) sourcePaths.add(path);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path path, IOException exception) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else {
                    try (Stream<Path> paths = Files.list(sourceRoot)) {
                        sourcePaths.addAll(paths.filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".accent")).toList());
                    }
                }
                sourcePaths.sort(Comparator.naturalOrder());
                for (Path path : sourcePaths) {
                    Path normalized = normalize(path);
                    try {
                        sources.put(normalized, new SourceFile(normalized, Files.readString(path, StandardCharsets.UTF_8)));
                    } catch (IOException ignored) {
                        // Other readable sources and open buffers can still be analyzed.
                    }
                }
            } catch (IOException ignored) {
                // Keep available buffers usable when a sibling source cannot be read.
            }
            return sources;
        }

        private static Path conventionalSourceRoot(Path directory) {
            for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
                Path sourceSet = candidate.getParent();
                Path src = sourceSet == null ? null : sourceSet.getParent();
                if (candidate.getFileName() != null && candidate.getFileName().toString().equals("accent")
                        && sourceSet != null && sourceSet.getFileName() != null
                        && Set.of("main", "test").contains(sourceSet.getFileName().toString())
                        && src != null && src.getFileName() != null && src.getFileName().toString().equals("src")) {
                    return candidate;
                }
            }
            return null;
        }

        private static Path normalize(Path path) {
            return path.toAbsolutePath().normalize();
        }

        public CompilationUnit getUnit(Path path) {
            return units.get(normalize(path));
        }

        public SemanticAnalyzer getAnalyzer(Path path) {
            return analyzers.get(normalize(path));
        }

        public String getFileContent(Path path) {
            SourceFile sf = files.get(normalize(path));
            return sf != null ? sf.content() : "";
        }

        public synchronized String getGeneratedJava(Path path) {
            try {
                CompilationResult result = analyze(path, true);
                if (result.unit() == null) return "// Source file not parsed or empty AST.";
                SymbolTable symbolTable = symbolTables.get(normalize(path));
                accent.lowering.AccentLowerer lowerer = new accent.lowering.AccentLowerer(symbolTable,
                        result.analyzer().resolvedTypes);
                CompilationUnit lowered = lowerer.lower(result.unit());
                return new accent.emitter.JavaEmitter().emit(lowered);
            } catch (Exception exception) {
                return "// Error generating Java: " + exception.getMessage();
            }
        }
    }

    public static final class CompilationResult {
        private final CompilationUnit unit;
        private final List<Diagnostic> diagnostics;
        private final SemanticAnalyzer analyzer;

        public CompilationResult(CompilationUnit unit, List<Diagnostic> diagnostics, SemanticAnalyzer analyzer) {
            this.unit = unit;
            this.diagnostics = diagnostics;
            this.analyzer = analyzer;
        }

        public CompilationUnit unit() { return unit; }
        public List<Diagnostic> diagnostics() { return diagnostics; }
        public SemanticAnalyzer analyzer() { return analyzer; }
    }

    public static String getGeneratedJava(CompilationUnit unit, ClassLoader loader) {
        if (unit == null) return "";
        ProjectContext context = new ProjectContext(List.of());
        context.classLoader = loader;
        context.putFile(unit.sourceFile().path(), unit.sourceFile().content());
        return context.getGeneratedJava(unit.sourceFile().path());
    }
}
