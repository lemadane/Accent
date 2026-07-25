package io.lemadane.jatot.tools.core;

import io.lemadane.jatot.ast.Ast.*;
import io.lemadane.jatot.lexer.Token;
import io.lemadane.jatot.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

public final class AstNavigator {

    public static Token findTokenAt(CompilationUnit unit, int line, int column) {
        if (unit == null || unit.tokens() == null) return null;
        for (Token t : unit.tokens()) {
            if (t.line() == line && column >= t.column() && column <= t.column() + t.lexeme().length()) {
                return t;
            }
        }
        return null;
    }

    public static Node findNodeAt(CompilationUnit unit, int line, int column) {
        List<Node> path = new ArrayList<>();
        traverse(unit, line, column, path);
        if (path.isEmpty()) return null;
        // Return the deepest node
        return path.get(path.size() - 1);
    }

    private static void traverse(Node node, int line, int col, List<Node> path) {
        if (node == null) return;

        boolean matches = false;
        if (node instanceof Expression expr) {
            Token t = expr.token();
            if (t != null && t.line() == line && col >= t.column() && col <= t.column() + t.lexeme().length()) {
                matches = true;
            }
        }

        if (matches) {
            path.add(node);
        }

        if (node instanceof CompilationUnit cu) {
            for (TypeDeclaration decl : cu.declarations()) {
                traverse(decl, line, col, path);
            }
        } else if (node instanceof ClassDecl cd) {
            for (Member m : cd.members()) {
                traverse(m, line, col, path);
            }
        } else if (node instanceof RecordDecl rd) {
            for (Member m : rd.members()) {
                traverse(m, line, col, path);
            }
        } else if (node instanceof InterfaceDecl id) {
            for (Member m : id.members()) {
                traverse(m, line, col, path);
            }
        } else if (node instanceof EnumDecl ed) {
            for (Member m : ed.members()) {
                traverse(m, line, col, path);
            }
        } else if (node instanceof MethodDecl md) {
            md.body().ifPresent(b -> traverse(b, line, col, path));
        } else if (node instanceof ConstructorDecl cd) {
            traverse(cd.body(), line, col, path);
        } else if (node instanceof BlockStmt bs) {
            for (Statement s : bs.statements()) {
                traverse(s, line, col, path);
            }
        } else if (node instanceof LocalVarDeclStmt lvd) {
            lvd.initializer().ifPresent(i -> traverse(i, line, col, path));
        } else if (node instanceof IfStmt is) {
            traverse(is.condition(), line, col, path);
            traverse(is.thenBranch(), line, col, path);
            is.elseBranch().ifPresent(e -> traverse(e, line, col, path));
        } else if (node instanceof ForStmt fs) {
            fs.init().ifPresent(i -> traverse(i, line, col, path));
            fs.condition().ifPresent(c -> traverse(c, line, col, path));
            fs.update().ifPresent(u -> traverse(u, line, col, path));
            traverse(fs.body(), line, col, path);
        } else if (node instanceof ForEachStmt fes) {
            traverse(fes.iterable(), line, col, path);
            traverse(fes.body(), line, col, path);
        } else if (node instanceof WhileStmt ws) {
            traverse(ws.condition(), line, col, path);
            traverse(ws.body(), line, col, path);
        } else if (node instanceof DoWhileStmt dws) {
            traverse(dws.body(), line, col, path);
            traverse(dws.condition(), line, col, path);
        } else if (node instanceof TryStmt ts) {
            traverse(ts.body(), line, col, path);
            for (CatchClause cc : ts.catchClauses()) {
                traverse(cc.body(), line, col, path);
            }
            ts.finallyBlock().ifPresent(f -> traverse(f, line, col, path));
        } else if (node instanceof ReturnStmt rs) {
            rs.expression().ifPresent(e -> traverse(e, line, col, path));
        } else if (node instanceof YieldStmt ys) {
            traverse(ys.expression(), line, col, path);
        } else if (node instanceof ExprStmt es) {
            traverse(es.expression(), line, col, path);
        } else if (node instanceof ThrowStmt ts) {
            traverse(ts.expression(), line, col, path);
        } else if (node instanceof EmitStmt es) {
            traverse(es.expression(), line, col, path);
        } else if (node instanceof BinaryExpr be) {
            traverse(be.left(), line, col, path);
            traverse(be.right(), line, col, path);
        } else if (node instanceof UnaryExpr ue) {
            traverse(ue.expression(), line, col, path);
        } else if (node instanceof MemberAccessExpr mae) {
            traverse(mae.receiver(), line, col, path);
        } else if (node instanceof MethodCallExpr mce) {
            if (mce.receiver() != null) traverse(mce.receiver(), line, col, path);
            for (Expression arg : mce.arguments()) {
                traverse(arg, line, col, path);
            }
        } else if (node instanceof NewObjectExpr noe) {
            for (Expression arg : noe.arguments()) {
                traverse(arg, line, col, path);
            }
        }
    }
}
