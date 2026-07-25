package io.lemadane.jatot.tools.core;

import java.util.ArrayList;
import java.util.List;
import io.lemadane.jatot.ast.Ast.*;
import io.lemadane.jatot.semantic.SemanticAnalyzer;
import io.lemadane.jatot.lexer.Token;

public final class DefinitionProvider {

    public static final class DefinitionLocation {
        private final String uri;
        private final int line;
        private final int column;

        public DefinitionLocation(String uri, int line, int column) {
            this.uri = uri;
            this.line = line;
            this.column = column;
        }

        public String uri() { return uri; }
        public int line() { return line; }
        public int column() { return column; }
    }

    public static DefinitionLocation getDefinition(CompilationUnit unit, SemanticAnalyzer analyzer, int line, int column) {
        if (unit == null) return null;

        Token token = AstNavigator.findTokenAt(unit, line, column);
        if (token == null) return null;

        String name = token.lexeme();
        String uri = unit.sourceFile().path().toUri().toString();

        // 1. Search fields and methods in the class
        for (TypeDeclaration decl : unit.declarations()) {
            if (decl.name().equals(name)) {
                Token t = unit.tokens().stream().filter(tok -> tok.lexeme().equals(decl.name())).findFirst().orElse(token);
                return new DefinitionLocation(uri, t.line(), t.column());
            }
            for (Member member : decl.members()) {
                if (member instanceof FieldDecl fd && fd.name().equals(name)) {
                    Token t = unit.tokens().stream().filter(tok -> tok.lexeme().equals(fd.name())).findFirst().orElse(token);
                    return new DefinitionLocation(uri, t.line(), t.column());
                } else if (member instanceof MethodDecl md && md.name().equals(name)) {
                    Token t = unit.tokens().stream().filter(tok -> tok.lexeme().equals(md.name())).findFirst().orElse(token);
                    return new DefinitionLocation(uri, t.line(), t.column());
                }
            }
        }

        // 2. Search local variables and parameters inside the AST
        List<Token> declTokens = new ArrayList<>();
        findLocalDeclarations(unit, name, declTokens);
        if (!declTokens.isEmpty()) {
            // Find closest preceding declaration or just the first declaration
            Token best = declTokens.get(0);
            return new DefinitionLocation(uri, best.line(), best.column());
        }

        return null;
    }

    private static void findLocalDeclarations(Node node, String name, List<Token> results) {
        if (node == null) return;

        if (node instanceof LocalVarDeclStmt lvd && lvd.name().equals(name)) {
            // Find the identifier token for the local variable
            results.add(new Token(io.lemadane.jatot.lexer.TokenType.IDENTIFIER, name, 1, 1)); // Placeholder or exact matching below
        } else if (node instanceof Parameter param && param.name().equals(name)) {
            results.add(new Token(io.lemadane.jatot.lexer.TokenType.IDENTIFIER, name, 1, 1));
        }

        if (node instanceof CompilationUnit cu) {
            for (TypeDeclaration decl : cu.declarations()) {
                findLocalDeclarations(decl, name, results);
            }
        } else if (node instanceof ClassDecl cd) {
            for (Member m : cd.members()) findLocalDeclarations(m, name, results);
        } else if (node instanceof MethodDecl md) {
            for (Parameter p : md.parameters()) findLocalDeclarations(p, name, results);
            md.body().ifPresent(b -> findLocalDeclarations(b, name, results));
        } else if (node instanceof BlockStmt bs) {
            for (Statement s : bs.statements()) findLocalDeclarations(s, name, results);
        } else if (node instanceof LocalVarDeclStmt lvd) {
            lvd.initializer().ifPresent(i -> findLocalDeclarations(i, name, results));
        } else if (node instanceof IfStmt is) {
            findLocalDeclarations(is.thenBranch(), name, results);
            is.elseBranch().ifPresent(e -> findLocalDeclarations(e, name, results));
        } else if (node instanceof ForStmt fs) {
            fs.init().ifPresent(i -> findLocalDeclarations(i, name, results));
            findLocalDeclarations(fs.body(), name, results);
        } else if (node instanceof ForEachStmt fes) {
            findLocalDeclarations(fes.parameter(), name, results);
            findLocalDeclarations(fes.body(), name, results);
        } else if (node instanceof TryStmt ts) {
            findLocalDeclarations(ts.body(), name, results);
            for (CatchClause cc : ts.catchClauses()) {
                findLocalDeclarations(cc.parameter(), name, results);
                findLocalDeclarations(cc.body(), name, results);
            }
            ts.finallyBlock().ifPresent(f -> findLocalDeclarations(f, name, results));
        }
    }
}
