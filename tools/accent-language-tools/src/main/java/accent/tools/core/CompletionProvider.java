package accent.tools.core;

import accent.ast.Ast.*;
import accent.semantic.SemanticAnalyzer;
import accent.symbol.SymbolTable.ResolvedType;
import accent.symbol.SymbolTable.TypeInfo;
import accent.symbol.SymbolTable.MethodInfo;
import accent.symbol.SymbolTable.FieldInfo;
import accent.lexer.Token;
import accent.lexer.TokenType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private static String bindingName(String name, TypeNode type) {
        return name + (!type.isNonNull() && !(type instanceof PrimitiveTypeNode) ? "?" : "");
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
                    // Include inherited members using the same access rules as semantic checks.
                    Set<String> methodDetails = new HashSet<>();
                    for (MethodInfo m : analyzer.accessibleMethods(expr)) {
                        String resultType = info.fullName().equals("accent.runtime.Async")
                                && m.name().equals("await") && !type.typeArguments().isEmpty()
                                ? type.typeArguments().getFirst().toString()
                                : m.returnType().toString();
                        if (methodDetails.add(m.name() + ":" + resultType)) {
                            boolean nonNullElementResult = type.nonNullElements() && info.fullName().equals("java.util.List")
                                    && Set.of("get", "getFirst", "getLast", "set", "removeFirst", "removeLast").contains(m.name());
                            boolean nonNullAsyncResult = info.fullName().equals("accent.runtime.Async")
                                    && m.name().equals("await") && !type.typeArguments().isEmpty()
                                    && type.typeArguments().getFirst().isNonNull();
                            items.add(new CompletionItem(nonNullAsyncResult || nonNullElementResult || m.returnType().isNonNull() || m.returnType() instanceof PrimitiveTypeNode ? m.name() : m.name() + "()?", "Method", resultType));
                        }
                    }
                    for (FieldInfo f : analyzer.accessibleFields(expr)) {
                        items.add(new CompletionItem(bindingName(f.name(), f.type()), "Field", f.type().toString()));
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
            "class", "interface", "record", "model", "singleton", "enum", "extension",
            "public", "protected", "private", "static", "final",
            "this", "super", "void", "return", "if", "else", "for", "while", "do",
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
                        items.add(new CompletionItem(bindingName(fd.name(), fd.type()), "Field", fd.type().toString()));
                    } else if (m instanceof MethodDecl md) {
                        items.add(new CompletionItem((md.returnType().isNonNull() || md.returnType() instanceof PrimitiveTypeNode ? md.name() : md.name() + "()?"), "Method", md.returnType().toString()));
                    }
                }
            }
        }

        return items;
    }
}
