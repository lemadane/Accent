package io.lemadane.jatot.tools.core;

import io.lemadane.jatot.ast.Ast.CompilationUnit;
import io.lemadane.jatot.diagnostic.Diagnostic;
import io.lemadane.jatot.parser.JatotParser;
import io.lemadane.jatot.lexer.JatotLexer;
import io.lemadane.jatot.lexer.LexResult;
import io.lemadane.jatot.semantic.SemanticAnalyzer;
import io.lemadane.jatot.symbol.SymbolTable;
import io.lemadane.jatot.source.SourceFile;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class JotatLanguageService {

    public static final class ProjectContext {
        private final List<Path> classpath = new ArrayList<>();
        private final Map<Path, SourceFile> files = new ConcurrentHashMap<>();
        private final Map<Path, CompilationUnit> units = new ConcurrentHashMap<>();
        private final Map<Path, SemanticAnalyzer> analyzers = new ConcurrentHashMap<>();
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
            files.put(path, new SourceFile(path, content));
        }

        public void removeFile(Path path) {
            files.remove(path);
            units.remove(path);
            analyzers.remove(path);
        }

        public CompilationResult analyze(Path path, boolean errorTolerant) {
            SourceFile sourceFile = files.get(path);
            if (sourceFile == null) {
                return new CompilationResult(null, List.of(), null);
            }

            LexResult lexResult = new JatotLexer(sourceFile).lex();
            JatotParser parser = new JatotParser(sourceFile, lexResult.tokens());
            parser.setErrorTolerant(errorTolerant);

            CompilationUnit unit = parser.parse();
            units.put(path, unit);

            List<Diagnostic> diagnostics = new ArrayList<>(lexResult.diagnostics());
            diagnostics.addAll(parser.diagnostics());

            // Build symbol table
            SymbolTable symbolTable = new SymbolTable(getClassLoader());
            // Add all current compilation units to symbol table for cross-file references
            for (CompilationUnit u : units.values()) {
                symbolTable.addCompilationUnit(u);
            }

            SemanticAnalyzer analyzer = new SemanticAnalyzer(symbolTable);
            analyzer.analyze(unit);
            analyzers.put(path, analyzer);
            diagnostics.addAll(analyzer.diagnostics());

            return new CompilationResult(unit, diagnostics, analyzer);
        }

        public CompilationUnit getUnit(Path path) {
            return units.get(path);
        }

        public SemanticAnalyzer getAnalyzer(Path path) {
            return analyzers.get(path);
        }

        public String getFileContent(Path path) {
            SourceFile sf = files.get(path);
            return sf != null ? sf.content() : "";
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
        try {
            SymbolTable symbolTable = new SymbolTable(loader);
            symbolTable.addCompilationUnit(unit);
            io.lemadane.jatot.lowering.JatotLowerer lowerer = new io.lemadane.jatot.lowering.JatotLowerer(symbolTable);
            io.lemadane.jatot.emitter.JavaEmitter emitter = new io.lemadane.jatot.emitter.JavaEmitter();
            CompilationUnit lowered = lowerer.lower(unit);
            return emitter.emit(lowered);
        } catch (Exception e) {
            return "// Error generating Java: " + e.getMessage();
        }
    }
}
