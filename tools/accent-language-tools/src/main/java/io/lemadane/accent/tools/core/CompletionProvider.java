package io.lemadane.accent.tools.core;

import io.lemadane.accent.ast.Ast.*;
import io.lemadane.accent.semantic.SemanticAnalyzer;
import io.lemadane.accent.symbol.SymbolTable.ResolvedType;
import io.lemadane.accent.symbol.SymbolTable.TypeInfo;
import io.lemadane.accent.symbol.SymbolTable.MethodInfo;
import io.lemadane.accent.symbol.SymbolTable.FieldInfo;
import io.lemadane.accent.lexer.Token;
import io.lemadane.accent.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

public final class CompletionProvider {

    public static final class CompletionItem {
        private final String label;
        private final String kind; // "Keyword", "Variable", "Method", "Field", "Class"
        private final String detail;

        public CompletionItem(String label, String kind, String detail) {
            this.label = label;
            this.kind = kind;
            this.detail = detail;
        }

        public String label() { return label; }
        public String kind() { return kind; }
        public String detail() { return detail; }
    }

    public static List<CompletionItem> getCompletions(CompilationUnit unit, SemanticAnalyzer analyzer, int line, int column) {
        List<CompletionItem> items = new ArrayList<>();
        if (unit == null) return items;

        // 1. Check if the cursor is right after a dot '.' to trigger member completion
        Token token = AstNavigator.findTokenAt(unit, line, column);
        Token prevToken = null;
        if (token == null && unit.tokens() != null) {
            // Find closest preceding token
            for (Token t : unit.tokens()) {
                if (t.line() == line && t.column() + t.lexeme().length() == column - 1) {
                    prevToken = t;
                    break;
                }
            }
        } else {
            prevToken = token;
        }

        if (prevToken != null && prevToken.type() == TokenType.DOT) {
            // Find the member access receiver
            Node node = AstNavigator.findNodeAt(unit, line, prevToken.column() - 1);
            if (node instanceof Expression expr) {
                ResolvedType type = analyzer != null ? analyzer.resolvedTypes.get(expr) : null;
                if (type != null && type.info() != null) {
                    TypeInfo info = type.info();
                    // Add methods
                    for (MethodInfo m : info.methods()) {
                        items.add(new CompletionItem(m.name(), "Method", m.returnType().toString()));
                    }
                    // Add fields
                    for (FieldInfo f : info.fields()) {
                        items.add(new CompletionItem(f.name(), "Field", f.type().toString()));
                    }
                    return items;
                }
            }
        }

        // Special handling for log. inside @Logging context
        if (prevToken != null && prevToken.lexeme().equals("log")) {
            items.add(new CompletionItem("info", "Method", "void"));
            items.add(new CompletionItem("debug", "Method", "void"));
            items.add(new CompletionItem("warn", "Method", "void"));
            items.add(new CompletionItem("error", "Method", "void"));
            items.add(new CompletionItem("trace", "Method", "void"));
            return items;
        }

        // 2. Default Keyword completions
        String[] keywords = {
            "class", "interface", "record", "enum", "extension",
            "public", "private", "protected", "static", "final",
            "void", "return", "if", "else", "for", "while", "do",
            "try", "catch", "finally", "throw", "yield", "break",
            "continue", "async", "await", "generator", "emit"
        };
        for (String kw : keywords) {
            items.add(new CompletionItem(kw, "Keyword", "Accent keyword"));
        }

        // 3. Local variable completions (simplified search)
        if (unit.declarations() != null) {
            for (TypeDeclaration td : unit.declarations()) {
                items.add(new CompletionItem(td.name(), "Class", td.name()));
                for (Member m : td.members()) {
                    if (m instanceof FieldDecl fd) {
                        items.add(new CompletionItem(fd.name(), "Field", fd.type().toString()));
                    } else if (m instanceof MethodDecl md) {
                        items.add(new CompletionItem(md.name(), "Method", md.returnType().toString()));
                    }
                }
            }
        }

        return items;
    }
}
