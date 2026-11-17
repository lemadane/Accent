package accent.symbol;

import accent.ast.Ast.*;
import accent.symbol.SymbolTable.ResolvedType;

import java.util.List;
import java.util.function.Predicate;

/** Resolves extension signatures in the imports of their declaring source. */
public final class ExtensionResolver {
    private ExtensionResolver() {}

    public record Match(ExtensionDecl extension, MethodDecl method) {
        public boolean accessibleFrom(SymbolTable table, String packageName) {
            return method.modifiers().contains("public") || packageName.equals(table.extensionPackage(extension));
        }

        public ResolvedType returnType(SymbolTable table) {
            return table.resolveTypeInUnit(method.returnType(), table.extensionUnit(extension));
        }
    }

    public static Match find(SymbolTable table, ResolvedType receiver, String methodName,
                             List<Expression> arguments, List<ResolvedType> argumentTypes,
                             Predicate<Match> accessible) {
        Match best = null;
        int bestScore = -1;
        for (ExtensionDecl extension : table.extensions()) {
            CompilationUnit unit = table.extensionUnit(extension);
            ResolvedType target = table.resolveTypeInUnit(extension.targetType(), unit);
            if (!receiver.isCompatibleWith(target, table)) continue;
            for (Member member : extension.members()) {
                if (!(member instanceof MethodDecl method) || !method.name().equals(methodName)) continue;
                Match match = new Match(extension, method);
                if (!accessible.test(match)) continue;
                int score = argumentScore(table, unit, method.parameters(), arguments, argumentTypes);
                if (score < 0) continue;
                if (receiver.info().fullName().equals(target.info().fullName())) score += 100;
                if (score > bestScore) {
                    best = match;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static int argumentScore(SymbolTable table, CompilationUnit unit, List<Parameter> parameters,
                                     List<Expression> arguments, List<ResolvedType> argumentTypes) {
        boolean[] bound = new boolean[parameters.size()];
        int positional = 0;
        int score = 0;
        for (int i = 0; i < arguments.size(); i++) {
            Expression argument = arguments.get(i);
            int index = -1;
            if (argument instanceof NamedArgExpr named) {
                for (int p = 0; p < parameters.size(); p++) {
                    if (parameters.get(p).name().equals(named.name())) { index = p; break; }
                }
                if (index < 0 || bound[index]) return -1;
            } else {
                while (positional < parameters.size() && bound[positional]) positional++;
                index = positional;
                if (index >= parameters.size()) {
                    if (parameters.isEmpty() || !parameters.getLast().isVarargs()) return -1;
                    index = parameters.size() - 1;
                }
                if (!parameters.get(index).isVarargs()) positional++;
            }
            Parameter parameter = parameters.get(index);
            ResolvedType parameterType = table.resolveTypeInUnit(parameter.type(), unit);
            ResolvedType argumentType = i < argumentTypes.size() ? argumentTypes.get(i) : null;
            if (argumentType != null) {
                if (!table.isInvocationCompatible(argumentType, parameterType)) {
                    if (!parameter.isVarargs() || !table.isInvocationCompatible(argumentType,
                            new ResolvedType(parameterType.info(), false, parameterType.typeArguments(),
                                    parameterType.arrayDimensions() + 1))) return -1;
                }
                if (argumentType.info().fullName().equals(parameterType.info().fullName())) score += 10;
            }
            bound[index] = true;
        }
        for (int i = 0; i < parameters.size(); i++) {
            if (!bound[i] && parameters.get(i).defaultValue().isEmpty() && !parameters.get(i).isVarargs()) return -1;
            if (bound[i]) score++;
        }
        return score;
    }
}
