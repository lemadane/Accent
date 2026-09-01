package accent.tools.core;

import accent.ast.Ast.*;
import accent.semantic.SemanticAnalyzer;
import accent.symbol.SymbolTable.ResolvedType;
import accent.symbol.SymbolTable.TypeInfo;
import accent.lexer.Token;

public final class HoverProvider {

    public static String getHover(CompilationUnit unit, SemanticAnalyzer analyzer, int line, int column) {
        if (unit == null || analyzer == null) return null;

        Token token = AstNavigator.findTokenAt(unit, line, column);
        if (token == null) return null;

        // Check synthetic log field injected by @Logging
        if ("log".equals(token.lexeme())) {
            return "**(Synthetic Field)** `private static final accent.logging.Logger log`\n\nGenerated logger injected by `@Logging` annotation.";
        }

        Node node = AstNavigator.findNodeAt(unit, line, column);
        if (node instanceof Expression expr) {
            ResolvedType type = analyzer.resolvedTypes.get(expr);
            if (type != null) {
                StringBuilder sb = new StringBuilder();
                if (expr instanceof IdentifierExpr) {
                    sb.append("Local variable/symbol: `").append(token.lexeme()).append("`\n\n");
                } else if (expr instanceof MemberAccessExpr) {
                    sb.append("Field access: `").append(token.lexeme()).append("`\n\n");
                }
                sb.append("Type: `").append(type.toString()).append("`");
                return sb.toString();
            }
        }

        return "Symbol: `" + token.lexeme() + "`";
    }
}
