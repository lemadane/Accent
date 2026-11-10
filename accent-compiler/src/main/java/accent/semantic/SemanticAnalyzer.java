package accent.semantic;

import accent.ast.Ast.*;
import accent.diagnostic.Diagnostic;
import accent.diagnostic.DiagnosticSeverity;
import accent.lexer.Token;
import accent.symbol.SymbolTable;
import accent.symbol.ExtensionResolver;
import accent.symbol.SymbolTable.ResolvedType;
import accent.symbol.SymbolTable.TypeInfo;
import accent.symbol.SymbolTable.ConstructorInfo;
import accent.symbol.SymbolTable.ImportResolver;
import java.util.*;

public final class SemanticAnalyzer implements ImportResolver {
    private final SymbolTable symbolTable;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    // State for current traversal
    private CompilationUnit currentUnit;
    private TypeInfo currentClass;
    private boolean isStaticContext;
    
    // Scopes
    private final List<Map<String, LocalVar>> scopes = new ArrayList<>();
    
    private final Deque<List<ResolvedType>> lambdaReturns = new ArrayDeque<>();
    private final Map<LambdaExpr, List<ResolvedType>> lambdaParameterTypes = new IdentityHashMap<>();
    private final Map<LambdaExpr, ResolvedType> lambdaResults = new IdentityHashMap<>();
    private final Map<LambdaExpr, ResolvedType> lambdaTargets = new IdentityHashMap<>();

    // Stack of expression contexts for yield checks
    private final Deque<ExpressionContext> expressionContexts = new ArrayDeque<>();

    private boolean checkingArrayReceiver;
    private ResolvedType requiredReturnType;
    private ResolvedType declaredReturnType;
    private String currentMethodName;

    private static class LocalVar {
        final String name;
        final ResolvedType type;
        final boolean isFinal;
        final boolean isParameter;
        boolean isAssigned;
        boolean isRefinedNonNull;

        LocalVar(String name, ResolvedType type, boolean isFinal, boolean isParameter) {
            this.name = name;
            this.type = type;
            this.isFinal = isFinal;
            this.isParameter = isParameter;
            this.isAssigned = isParameter; // Parameters are pre-assigned
            this.isRefinedNonNull = false;
        }
    }

    private static class ExpressionContext {
        final String kind; // "if", "try", "loop"
        final List<ResolvedType> yieldedTypes = new ArrayList<>();
        boolean yieldsInAllPaths = false;

        ExpressionContext(String kind) {
            this.kind = kind;
        }
    }

    public SemanticAnalyzer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    @Override
    public TypeInfo resolve(String simpleName) {
        return symbolTable.resolveInUnit(simpleName, currentUnit);
    }

    private String nativeLoggerName;

    public void analyze(CompilationUnit unit) {
        this.currentUnit = unit;
        validateStaticImports();
        for (TypeDeclaration decl : unit.declarations()) {
            nativeLoggerName = unit.packageName().map(pkg -> pkg + ".").orElse("") + decl.name();
            if (decl instanceof ClassDecl cd) {
                analyzeClass(cd);
            } else if (decl instanceof ModelDecl md) {
                analyzeModel(md);
            } else if (decl instanceof RecordDecl rd) {
                analyzeRecord(rd);
            } else if (decl instanceof InterfaceDecl id) {
                analyzeInterface(id);
            } else if (decl instanceof EnumDecl ed) {
                analyzeEnum(ed);
            } else if (decl instanceof ExtensionDecl ed) {
                analyzeExtension(ed);
            }
        }
        this.currentUnit = null;
        this.nativeLoggerName = null;
    }

    private boolean hasLoggingAnnotation(List<String> modifiers) {
        if (modifiers == null) return false;
        if (modifiers.contains("@accent.logging.Logging")) {
            return true;
        }
        if (modifiers.contains("@Logging")) {
            if (currentUnit == null) return false;
            return currentUnit.imports().contains("accent.logging.Logging") || currentUnit.imports().contains("accent.logging.*");
        }
        return false;
    }

    private void injectLoggerScope(List<String> modifiers, List<Member> members, Token errorToken) {
        if (hasLoggingAnnotation(modifiers)) {
            if (members.stream().anyMatch(m -> m instanceof FieldDecl fd && fd.name().equals("log"))) {
                error(errorToken, "Cannot generate logging field 'log' because the type already declares a field named 'log'.");
            }
            TypeInfo loggerInfo = symbolTable.getType("accent.logging.Logger");
            if (loggerInfo == null) {
                BaseTypeNode voidType = new BaseTypeNode("void", List.of(), false);
                BaseTypeNode stringType = new BaseTypeNode("java.lang.String", List.of(), false);
                BaseTypeNode throwableType = new BaseTypeNode("java.lang.Throwable", List.of(), false);
                Parameter strParam = new Parameter(stringType, "msg", false, Optional.empty());
                Parameter errParam = new Parameter(throwableType, "err", false, Optional.empty());
                
                SymbolTable.MethodInfo traceMethod = new SymbolTable.MethodInfo("trace", List.of("public"), voidType, List.of(strParam));
                SymbolTable.MethodInfo traceErrMethod = new SymbolTable.MethodInfo("trace", List.of("public"), voidType, List.of(strParam, errParam));
                SymbolTable.MethodInfo debugMethod = new SymbolTable.MethodInfo("debug", List.of("public"), voidType, List.of(strParam));
                SymbolTable.MethodInfo debugErrMethod = new SymbolTable.MethodInfo("debug", List.of("public"), voidType, List.of(strParam, errParam));
                SymbolTable.MethodInfo infoMethod = new SymbolTable.MethodInfo("info", List.of("public"), voidType, List.of(strParam));
                SymbolTable.MethodInfo infoErrMethod = new SymbolTable.MethodInfo("info", List.of("public"), voidType, List.of(strParam, errParam));
                SymbolTable.MethodInfo warnMethod = new SymbolTable.MethodInfo("warn", List.of("public"), voidType, List.of(strParam));
                SymbolTable.MethodInfo warnErrMethod = new SymbolTable.MethodInfo("warn", List.of("public"), voidType, List.of(strParam, errParam));
                SymbolTable.MethodInfo errorMethod = new SymbolTable.MethodInfo("error", List.of("public"), voidType, List.of(strParam));
                SymbolTable.MethodInfo errorErrMethod = new SymbolTable.MethodInfo("error", List.of("public"), voidType, List.of(strParam, errParam));
                
                loggerInfo = new TypeInfo(
                    "accent.logging.Logger", false, false, false, false, Optional.empty(), List.of(),
                    List.of(
                        traceMethod, traceErrMethod,
                        debugMethod, debugErrMethod,
                        infoMethod, infoErrMethod,
                        warnMethod, warnErrMethod,
                        errorMethod, errorErrMethod
                    ),
                    List.of(), List.of()
                );
            }
            ResolvedType logType = new ResolvedType(loggerInfo, true, List.of(), 0);
            scopes.getFirst().put("log", new LocalVar("log", logType, true, false));
        }
    }

    private void validateSingleton(TypeDeclaration decl) {
        if (!decl.isSingleton()) return;
        List<TypeParameter> typeParameters = decl instanceof ClassDecl cd ? cd.typeParameters()
                : decl instanceof ModelDecl md ? md.typeParameters() : ((RecordDecl) decl).typeParameters();
        if (!typeParameters.isEmpty()) error(null, "Singleton declarations cannot have type parameters.");
        List<Parameter> components = decl instanceof ModelDecl md ? md.components()
                : decl instanceof RecordDecl rd ? rd.components() : List.of();
        boolean savedStatic = isStaticContext;
        isStaticContext = true;
        for (Parameter component : components) {
            if (Set.of("instance", "copy", "clone", "readResolve", "$accentCreateSingleton").contains(component.name())) {
                error(null, "Reserved singleton component name: " + component.name());
            }
            if (component.isVarargs()) error(null, "Singleton components cannot be varargs.");
            if (component.defaultValue().isEmpty()) {
                error(null, "Singleton component '" + component.name() + "' requires a default value.");
            } else {
                ResolvedType actual = checkExpression(component.defaultValue().get());
                ResolvedType expected = symbolTable.resolveTypeNode(component.type(), this);
                if (actual != null && !symbolTable.isInvocationCompatible(actual, expected)) {
                    error(null, "Incompatible default value for singleton component '" + component.name() + "'.");
                }
            }
        }
        isStaticContext = savedStatic;
        for (Member member : decl.members()) {
            if (decl instanceof RecordDecl && member instanceof FieldDecl field && !field.modifiers().contains("static")) {
                error(null, "Singleton records cannot declare additional instance fields.");
            }
            if (member instanceof ConstructorDecl) {
                error(null, "Singletons cannot declare constructors; the compiler generates a private one. "
                        + "Initialize state with field initializers instead.");
            }
            if (member instanceof MethodDecl method && Set.of("instance", "copy", "clone", "readResolve", "$accentCreateSingleton").contains(method.name())) {
                error(null, "Reserved singleton method: " + method.name());
            }
        }
    }

    private void analyzeClass(ClassDecl cd) {
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? cd.name() : pkg + "." + cd.name();
        this.currentClass = symbolTable.getType(fullName);
        
        pushScope();
        injectLoggerScope(cd.modifiers(), cd.members(), null);
        validateSingleton(cd);

        for (Member member : cd.members()) {
            analyzeMember(member);
        }
        
        popScope();
        this.currentClass = null;
    }

    private void analyzeRecord(RecordDecl rd) {
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? rd.name() : pkg + "." + rd.name();
        this.currentClass = symbolTable.getType(fullName);

        pushScope();
        injectLoggerScope(rd.modifiers(), rd.members(), null);
        validateSingleton(rd);
        rd.components().forEach(p -> checkParameterDefault(p, symbolTable.resolveTypeNode(p.type(), this)));

        for (Member member : rd.members()) {
            analyzeMember(member);
        }
        
        popScope();
        this.currentClass = null;
    }

    private void analyzeModel(ModelDecl md) {
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? md.name() : pkg + "." + md.name();
        this.currentClass = symbolTable.getType(fullName);

        pushScope();
        injectLoggerScope(md.modifiers(), md.members(), null);
        validateSingleton(md);
        md.components().forEach(p -> checkParameterDefault(p, symbolTable.resolveTypeNode(p.type(), this)));

        for (Member member : md.members()) {
            analyzeMember(member);
        }

        popScope();
        this.currentClass = null;
    }

    private void analyzeInterface(InterfaceDecl id) {
        if (hasLoggingAnnotation(id.modifiers())) {
            error(null, "@Logging is not supported on interfaces.");
        }

        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? id.name() : pkg + "." + id.name();
        this.currentClass = symbolTable.getType(fullName);

        pushScope(); // Interfaces don't get loggers, but we push scope for consistency if needed

        for (Member member : id.members()) {
            analyzeMember(member);
        }
        
        popScope();
        this.currentClass = null;
    }

    private void analyzeEnum(EnumDecl ed) {
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? ed.name() : pkg + "." + ed.name();
        this.currentClass = symbolTable.getType(fullName);

        pushScope();
        injectLoggerScope(ed.modifiers(), ed.members(), null);

        for (Member member : ed.members()) {
            analyzeMember(member);
        }
        
        popScope();
        this.currentClass = null;
    }

    private void analyzeExtension(ExtensionDecl ed) {
        this.currentClass = null; // Extension methods act like static context for member access on outside classes, but they define a special 'this' receiver.
        ResolvedType targetType = symbolTable.resolveTypeNode(ed.targetType(), this);

        for (Member member : ed.members()) {
            if (member instanceof MethodDecl md) {
                // Extension methods are static in generated code but have 'this' receiver parameter in Accent
                isStaticContext = false;
                
                pushScope();
                // Inside extension body, "this" is of type targetType! and is non-null
                ResolvedType thisType = targetType.withNonNull(true);
                scopes.getFirst().put("this", new LocalVar("this", thisType, true, true));

                for (Parameter param : md.parameters()) {
                    ResolvedType pType = symbolTable.resolveTypeNode(param.type(), this);
                    checkParameterDefault(param, pType);
                    scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true)); // All parameters are immutable
                }

                checkMethodBody(md);
                popScope();
            }
        }
    }

    private void checkMethodBody(MethodDecl method) {
        ResolvedType savedDeclaredReturn = declaredReturnType;
        declaredReturnType = symbolTable.resolveTypeNode(method.returnType(), this);
        ResolvedType savedReturn = requiredReturnType;
        String savedName = currentMethodName;
        requiredReturnType = !method.isGenerator() && method.returnType().isNonNull()
                && !(method.returnType() instanceof PrimitiveTypeNode)
                ? symbolTable.resolveTypeNode(method.returnType(), this) : null;
        currentMethodName = method.name();
        if (requiredReturnType != null && requiredReturnType.isPrimitive()) {
            error(null, "Primitive and void methods cannot have nullable return contracts.");
            requiredReturnType = null;
        }
        validateReturnOverride(method);
        method.body().ifPresent(body -> {
            checkStatement(body);
            if (requiredReturnType != null && canCompleteNormally(body)) {
                error(null, "Non-null method '" + method.name() + "()' must return a value on every completing path.");
            }
        });
        requiredReturnType = savedReturn;
        declaredReturnType = savedDeclaredReturn;
        currentMethodName = savedName;
    }

    private void validateReturnOverride(MethodDecl method) {
        if (currentClass == null || method.modifiers().contains("static")) return;
        ResolvedType result = symbolTable.resolveTypeNode(method.returnType(), this);
        for (TypeInfo parent : symbolTable.hierarchy(currentClass)) {
            if (parent == currentClass) continue;
            for (SymbolTable.MethodInfo inherited : parent.methods()) {
                if (!inherited.name().equals(method.name()) || inherited.modifiers().contains("static")
                        || inherited.parameters().size() != method.parameters().size()) continue;
                if (!symbolTable.isMemberAccessible(inherited, currentClass, currentClass,
                        currentUnit.packageName().orElse(""), false)) continue;
                boolean sameSignature = true;
                for (int i = 0; i < method.parameters().size(); i++) {
                    ResolvedType own = symbolTable.resolveTypeNode(method.parameters().get(i).type(), this);
                    ResolvedType other = symbolTable.resolveParameterType(inherited, i);
                    if (!own.info().fullName().equals(other.info().fullName())
                            || own.arrayDimensions() != other.arrayDimensions()) sameSignature = false;
                }
                ResolvedType inheritedResult = symbolTable.resolveReturnType(inherited);
                if (sameSignature && inheritedResult.nonNullElements() && !result.nonNullElements())
                    error(null, "Override must preserve the inherited non-null element return contract.");
                if (sameSignature && inheritedResult.isNonNull() && !inheritedResult.isPrimitive()
                        && !result.isNonNull()) {
                    error(null, "Override '" + method.name() + "()' must preserve the inherited non-null return contract; use '"
                            + method.name() + "()'.");
                }
            }
        }
    }

    /** Conservative fall-through analysis; throw and non-terminating loops need no return. */
    private boolean canCompleteNormally(Statement statement) {
        if (statement instanceof ReturnStmt || statement instanceof ThrowStmt) return false;
        if (statement instanceof BlockStmt block) {
            for (Statement child : block.statements()) if (!canCompleteNormally(child)) return false;
            return true;
        }
        if (statement instanceof IfStmt branch) {
            return canCompleteNormally(branch.thenBranch())
                    || branch.elseBranch().isEmpty() || canCompleteNormally(branch.elseBranch().get());
        }
        if (statement instanceof TryStmt attempt) {
            if (attempt.finallyBlock().isPresent() && !canCompleteNormally(attempt.finallyBlock().get())) return false;
            return canCompleteNormally(attempt.body())
                    || attempt.catchClauses().stream().anyMatch(c -> canCompleteNormally(c.body()));
        }
        if (statement instanceof WhileStmt loop) {
            return !isAlwaysTrue(loop.condition()) || containsBreak(loop.body());
        }
        if (statement instanceof ForStmt loop) {
            return (loop.condition().isPresent() && !isAlwaysTrue(loop.condition().get())) || containsBreak(loop.body());
        }
        if (statement instanceof DoWhileStmt loop) {
            return containsBreak(loop.body()) || ((canCompleteNormally(loop.body()) || containsContinue(loop.body())) && !isAlwaysTrue(loop.condition()));
        }
        return true;
    }

    private boolean isAlwaysTrue(Expression expression) {
        return expression instanceof LiteralExpr literal && Boolean.TRUE.equals(literal.value());
    }

    private boolean containsContinue(Statement statement) {
        if (statement instanceof ContinueStmt) return true;
        if (statement instanceof BlockStmt block) return block.statements().stream().anyMatch(this::containsContinue);
        if (statement instanceof IfStmt branch) return containsContinue(branch.thenBranch())
                || branch.elseBranch().map(this::containsContinue).orElse(false);
        if (statement instanceof TryStmt attempt) return containsContinue(attempt.body())
                || attempt.catchClauses().stream().anyMatch(c -> containsContinue(c.body()))
                || attempt.finallyBlock().map(this::containsContinue).orElse(false);
        return false;
    }

    private boolean containsBreak(Statement statement) {
        if (statement instanceof BreakStmt) return true;
        if (statement instanceof BlockStmt block) return block.statements().stream().anyMatch(this::containsBreak);
        if (statement instanceof IfStmt branch) return containsBreak(branch.thenBranch())
                || branch.elseBranch().map(this::containsBreak).orElse(false);
        if (statement instanceof TryStmt attempt) return containsBreak(attempt.body())
                || attempt.catchClauses().stream().anyMatch(c -> containsBreak(c.body()))
                || attempt.finallyBlock().map(this::containsBreak).orElse(false);
        // A break inside a nested loop targets that loop.
        return false;
    }

    private final Set<Expression> nullableUses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Expression> optionalReceivers = Collections.newSetFromMap(new IdentityHashMap<>());

    private ResolvedType nullableResult(ResolvedType type) {
        if (!type.isPrimitive() || type.info().fullName().equals("void")) return type.withNonNull(false);
        String boxed = switch (type.info().fullName()) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "char" -> "java.lang.Character";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            default -> "java.lang.Object";
        };
        return new ResolvedType(symbolTable.getType(boxed), false, List.of(), 0);
    }

    private void checkMethodMarker(MethodCallExpr call, ResolvedType declaredResult) {
        if (optionalReceivers.contains(call)) return;
        // The marker describes the method result before optional receiver propagation.
        boolean nullable = !declaredResult.isNonNull() && !declaredResult.isPrimitive();
        if (nullableUses.contains(call) != nullable) {
            error(call.token(), nullable
                    ? "Nullable result requires '?': use '" + call.methodName() + "(...)?'."
                    : "Non-null result must not use a nullable '?' marker.");
        }
    }

    private Map<LocalVar, Boolean> refinementSnapshot() {
        Map<LocalVar, Boolean> facts = new IdentityHashMap<>();
        for (var scope : scopes) for (LocalVar variable : scope.values()) facts.put(variable, variable.isRefinedNonNull);
        return facts;
    }

    private void restoreRefinements(Map<LocalVar, Boolean> facts) {
        facts.forEach((variable, nonNull) -> variable.isRefinedNonNull = nonNull);
    }

    private void clearMutableRefinements() {
        for (var scope : scopes) for (LocalVar variable : scope.values()) {
            if (!variable.isFinal && !variable.isParameter) variable.isRefinedNonNull = false;
        }
    }

    private void checkParameterDefault(Parameter parameter, ResolvedType expected) {
        parameter.defaultValue().ifPresent(value -> {
            ResolvedType actual = checkExpression(value);
            if (actual != null && !symbolTable.isInvocationCompatible(actual, expected)) {
                error(null, "Incompatible types for default value of '" + parameter.name() + "'.");
            }
        });
    }

    private boolean isList(ResolvedType type) {
        return type != null && type.arrayDimensions() == 0 && type.info().fullName().equals("java.util.List");
    }

    private ResolvedType checkCollectionInitializer(Expression expression, ResolvedType expected, ResolvedType actual) {
        ArrayInitializerExpr contextualArray = expression instanceof ArrayInitializerExpr array ? array
                : expression instanceof NewArrayExpr array ? array.initializer().orElse(null) : null;
        if (expected != null && expected.arrayDimensions() > 0 && contextualArray != null) {
            for (Expression item : contextualArray.expressions()) {
                ResolvedType value = resolvedTypes.get(item);
                if (value == null || !symbolTable.isInvocationCompatible(value, expected.elementType(symbolTable)))
                    error(item.token(), "Array element does not satisfy the declared element contract.");
            }
            ResolvedType initialized = expected.withNonNull(true);
            resolvedTypes.put(expression, initialized);
            return initialized;
        }
        if (isList(expected) && expression instanceof MethodCallExpr call
                && call.receiver() instanceof IdentifierExpr id && Set.of("List", "java.util.List").contains(id.name())
                && Set.of("of", "copyOf").contains(call.methodName())) {
            if (expected.nonNullElements() && call.methodName().equals("of")) {
                for (Expression item : call.arguments()) {
                    ResolvedType value = resolvedTypes.get(item);
                    if (value != null && !symbolTable.isInvocationCompatible(value, expected.elementType(symbolTable)))
                        error(item.token(), "Non-null elements require a proven non-null value of the element type.");
                }
            }
            return expected.withNonNull(true);
        }
        if (expected == null || !expected.nonNullElements()) return actual;
        ArrayInitializerExpr initializer = expression instanceof ArrayInitializerExpr a ? a
                : expression instanceof NewArrayExpr a ? a.initializer().orElse(null) : null;
        if (initializer != null) {
            for (Expression item : initializer.expressions()) {
                ResolvedType value = resolvedTypes.get(item);
                if (value == null || !symbolTable.isInvocationCompatible(value, expected.elementType(symbolTable)))
                    error(item.token(), "Non-null elements require a proven non-null value of the element type.");
            }
            actual = expected.withNonNull(true);
        } else if (expression instanceof NewArrayExpr array) {
            if (!array.dimensions().isEmpty() && array.dimensions().getFirst() instanceof LiteralExpr literal
                    && literal.value() instanceof Number size && size.doubleValue() == 0) {
                actual = expected.withNonNull(true);
            } else {
                error(expression.token(), "Non-null element arrays require an initializer; sized reference arrays start with null elements.");
            }
        } else if (isList(expected) && expression instanceof NewObjectExpr object
                && object.type() instanceof BaseTypeNode b
                && Set.of("ArrayList", "java.util.ArrayList", "LinkedList", "java.util.LinkedList").contains(b.name())
                && object.arguments().isEmpty()) {
            actual = expected.withNonNull(true);
        }
        if (actual != null) resolvedTypes.put(expression, actual);
        return actual;
    }

    private void checkElementMarker(String name, boolean marked, ResolvedType type, Token token) {
        // Element nullability is declared in the element type, not the collection name.
    }

    private void checkBindingMarker(String name, Expression expression, ResolvedType type, Token token) {
        if (optionalReceivers.contains(expression)) return;
        boolean nullable = !type.isNonNull() && !type.isPrimitive();
        if (nullableUses.contains(expression) != nullable) {
            error(token, nullable ? "Nullable binding '" + name + "' requires '?' at every use."
                    : "Non-null binding '" + name + "' must not use '?'.");
        }
    }


    private void analyzeMember(Member member) {
        if (member instanceof FieldDecl fd) {
            isStaticContext = fd.modifiers().contains("static");
            fd.initializer().ifPresent(init -> {
                ResolvedType actual = checkExpression(init);
                ResolvedType expected = symbolTable.resolveFieldType(currentClass.findField(fd.name(), symbolTable));
                actual = checkCollectionInitializer(init, expected, actual);
                if (fd.type().isNonNull() && !(fd.type() instanceof PrimitiveTypeNode)
                        && expected.isPrimitive()) {
                    error(null, "Primitive bindings cannot be nullable.");
                }
                if (actual != null && expected != null && !symbolTable.isInvocationCompatible(actual, expected)) {
                    error(null, "Incompatible types for field '" + fd.name() + "': expected " + expected + ", actual " + actual);
                }
            });
        } else if (member instanceof ConstructorDecl cd) {
            isStaticContext = false;
            pushScope();
            for (Parameter param : cd.parameters()) {
                ResolvedType pType = symbolTable.resolveTypeNode(param.type(), this);
                checkParameterDefault(param, pType);
                scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true));
            }
            checkStatement(cd.body());
            popScope();
        } else if (member instanceof MethodDecl md) {
            isStaticContext = md.modifiers().contains("static");
            pushScope();
            for (Parameter param : md.parameters()) {
                ResolvedType pType = symbolTable.resolveTypeNode(param.type(), this);
                checkParameterDefault(param, pType);
                scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true));
            }
            checkMethodBody(md);
            popScope();
        }
    }

    // --- Scope Operations ---

    private void pushScope() {
        scopes.addFirst(new HashMap<>());
    }

    private void popScope() {
        scopes.removeFirst();
    }

    private LocalVar lookupVar(String name) {
        for (Map<String, LocalVar> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

    // --- Statement Analysis ---

    private void checkStatement(Statement stmt) {
        if (stmt instanceof BlockStmt bs) {
            pushScope();
            for (Statement s : bs.statements()) {
                checkStatement(s);
            }
            popScope();
        } else if (stmt instanceof ForInitStmt init) {
            IdentifierExpr name = (IdentifierExpr) init.assignment().left();
            boolean bound = lookupVar(name.name()) != null
                    || currentClass != null && currentClass.findField(name.name(), symbolTable) != null
                    || !staticImportTypes(name.name(), false).isEmpty()
                    || name.name().equals("log");
            if (bound) checkExpression(init.assignment());
            else checkStatement(init.declaration());
        } else if (stmt instanceof LocalVarDeclStmt varDecl) {
            ResolvedType type = null;
            if (varDecl.type().isPresent()) {
                type = symbolTable.resolveTypeNode(varDecl.type().get(), this);
            }

            ResolvedType initType = null;
            if (varDecl.initializer().isPresent()) {
                if (varDecl.initializer().get() instanceof LambdaExpr lambda && type != null) contextualizeLambda(lambda, type);
                initType = checkExpression(varDecl.initializer().get());
                if (type == null) {
                    type = initType == null ? null : initType.withNonNull(initType.isPrimitive() || varDecl.nonNullBinding());
                    if (type != null && varDecl.nonNullElements()) {
                        if (!isList(type) && type.arrayDimensions() == 0) error(null, "Inferred '!!' requires an array or List<T>.");
                        type = type.withNonNullElements(true);
                    }
                }
            }

            if (type == null) {
                error(null, "Cannot infer type of variable '" + varDecl.name() + "' without initializer.");
                TypeInfo objInfo = symbolTable.getType("java.lang.Object");
                type = new ResolvedType(objInfo, false, List.of(), 0);
            }

            if (varDecl.initializer().isPresent() && initType != null) {
                initType = checkCollectionInitializer(varDecl.initializer().get(), type, initType);
                if (!initType.isCompatibleWith(type, symbolTable)) {
                    error(null, "Incompatible types: expected " + type + ", actual " + initType);
                }
            }

            // Shadows check
            LocalVar local = new LocalVar(varDecl.name(), type, varDecl.isFinal(), false);
            local.isRefinedNonNull = initType != null && initType.isNonNull();
            scopes.getFirst().put(varDecl.name(), local);
        } else if (stmt instanceof IfStmt ifs) {
            ResolvedType condType = checkExpression(ifs.condition());
            verifyCondition(condType, ifs.condition().token());

            Map<LocalVar, Boolean> before = refinementSnapshot();
            // Null checking flow refinement
            boolean refinedNonNull = false;
            String refinedVarName = "";
            
            // Check direct null comparison: x != null
            if (ifs.condition() instanceof BinaryExpr bin && bin.operator().type() == accent.lexer.TokenType.BANG_EQUAL) {
                if (unwrapNullable(bin.left()) instanceof IdentifierExpr id && bin.right() instanceof LiteralExpr lit && lit.value() == null) {
                    refinedVarName = id.name();
                    refinedNonNull = true;
                } else if (bin.right() instanceof IdentifierExpr id && bin.left() instanceof LiteralExpr lit && lit.value() == null) {
                    refinedVarName = id.name();
                    refinedNonNull = true;
                }
            }

            if (refinedNonNull) {
                LocalVar variable = lookupVar(refinedVarName);
                if (variable != null) variable.isRefinedNonNull = true;
            }
            checkStatement(ifs.thenBranch());
            Map<LocalVar, Boolean> thenFacts = refinementSnapshot();
            restoreRefinements(before);
            ifs.elseBranch().ifPresent(this::checkStatement);
            boolean thenCompletes = canCompleteNormally(ifs.thenBranch());
            boolean elseCompletes = ifs.elseBranch().map(this::canCompleteNormally).orElse(true);
            for (LocalVar variable : before.keySet()) {
                boolean thenFact = thenFacts.getOrDefault(variable, false);
                boolean elseFact = variable.isRefinedNonNull;
                variable.isRefinedNonNull = (!thenCompletes || thenFact) && (!elseCompletes || elseFact);
            }
        } else if (stmt instanceof ForStmt fs) {
            clearMutableRefinements();
            pushScope();
            fs.init().ifPresent(this::checkStatement);
            fs.condition().ifPresent(this::checkExpression);
            fs.update().ifPresent(this::checkExpression);
            checkStatement(fs.body());
            popScope();
            fs.elseBranch().ifPresent(this::checkStatement);
            clearMutableRefinements();
        } else if (stmt instanceof ForEachStmt fes) {
            clearMutableRefinements();
            ResolvedType iterType = checkExpression(fes.iterable());
            ResolvedType elemType;
            if (fes.parameter().type() instanceof BaseTypeNode base && base.name().equals("var")) {
                if (iterType != null && iterType.info().fullName().equals("java.util.List") && !iterType.typeArguments().isEmpty()) {
                    elemType = iterType.typeArguments().get(0);
                } else if (iterType != null && iterType.arrayDimensions() > 0) {
                    elemType = iterType.elementType(symbolTable);
                } else {
                    elemType = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
                }
            } else {
                elemType = symbolTable.resolveTypeNode(fes.parameter().type(), this);
            }

            elemType = elemType.withNonNull(elemType.isPrimitive() || fes.parameter().type().isNonNull());
            pushScope();
            // Enhance parameter is implicitly final and parameter-like (immutable)
            scopes.getFirst().put(fes.parameter().name(), new LocalVar(fes.parameter().name(), elemType, true, true));
            checkStatement(fes.body());
            popScope();
            fes.elseBranch().ifPresent(this::checkStatement);
            clearMutableRefinements();
        } else if (stmt instanceof WhileStmt ws) {
            clearMutableRefinements();
            checkExpression(ws.condition());
            checkStatement(ws.body());
            clearMutableRefinements();
            ws.elseBranch().ifPresent(this::checkStatement);
            clearMutableRefinements();
        } else if (stmt instanceof DoWhileStmt dws) {
            clearMutableRefinements();
            checkStatement(dws.body());
            checkExpression(dws.condition());
            clearMutableRefinements();
            dws.elseBranch().ifPresent(this::checkStatement);
            clearMutableRefinements();
        } else if (stmt instanceof TryStmt ts) {
            clearMutableRefinements();
            checkStatement(ts.body());
            for (CatchClause cc : ts.catchClauses()) {
                clearMutableRefinements();
                pushScope();
                ResolvedType exType = symbolTable.resolveTypeNode(cc.parameter().type(), this);
                scopes.getFirst().put(cc.parameter().name(), new LocalVar(cc.parameter().name(), exType, true, true));
                checkStatement(cc.body());
                popScope();
            }
            clearMutableRefinements();
            ts.finallyBlock().ifPresent(this::checkStatement);
        } else if (stmt instanceof ReturnStmt rs) {
            ResolvedType result = rs.expression().map(this::checkExpression)
                    .orElse(new ResolvedType(symbolTable.getType("void"), false, List.of(), 0));
            if (lambdaReturns.isEmpty() && declaredReturnType != null && result != null
                    && (declaredReturnType.nonNullElements() || result.nonNullElements())
                    && (declaredReturnType.arrayDimensions() > 0 || isList(declaredReturnType))
                    && !symbolTable.isInvocationCompatible(result, declaredReturnType))
                error(null, "Return value must preserve the declared non-null element contract.");
            if (!lambdaReturns.isEmpty() && result != null) lambdaReturns.peek().add(result);
            else if (requiredReturnType != null) {
                if (rs.expression().isEmpty() || result == null
                        || !symbolTable.isInvocationCompatible(result, requiredReturnType)) {
                    error(rs.expression().map(Expression::token).orElse(null),
                            "Non-null method '" + currentMethodName + "()' must return a non-null value compatible with "
                                    + requiredReturnType + ".");
                }
            }
        } else if (stmt instanceof YieldStmt ys) {
            ResolvedType type = checkExpression(ys.expression());
            if (expressionContexts.isEmpty()) {
                error(ys.expression().token(), "Yield statement outside of expression context.");
            } else {
                expressionContexts.peek().yieldedTypes.add(type);
            }
        } else if (stmt instanceof BreakStmt || stmt instanceof ContinueStmt) {
            // Valid loop controls
        } else if (stmt instanceof ThrowStmt ts) {
            checkExpression(ts.expression());
        } else if (stmt instanceof EmitStmt es) {
            checkExpression(es.expression());
        } else if (stmt instanceof ExprStmt es) {
            ResolvedType type = checkExpression(es.expression());
            // Warning for ignored Async result
            if (type != null && type.info().fullName().equals("accent.runtime.Async")) {
                warning(es.expression().token(), "ACCENT-W001", "Unobserved or ignored Async result. Consider assigning it to a variable or calling .await().");
            }
        }
    }

    private boolean contextualizeLambda(LambdaExpr lambda, ResolvedType target) {
        if (target == null || target.info() == null) return false;
        var methods = target.info().methods().stream()
                .filter(method -> !method.modifiers().contains("static"))
                .toList();
        var abstractMethods = methods.stream().filter(method -> method.modifiers().contains("abstract")).toList();
        if (!abstractMethods.isEmpty()) methods = abstractMethods;
        if (methods.size() != 1 || methods.getFirst().parameters().size() != lambda.parameters().size()) return false;
        SymbolTable.MethodInfo method = methods.getFirst();
        List<ResolvedType> parameters = new ArrayList<>();
        for (int i = 0; i < method.parameters().size(); i++) parameters.add(symbolTable.resolveParameterType(method, i));
        lambdaParameterTypes.put(lambda, parameters);
        lambdaTargets.put(lambda, target.withNonNull(true));
        return true;
    }

    private boolean isAccessible(SymbolTable.FieldInfo field, ResolvedType receiver, Expression receiverExpression) {
        return symbolTable.isMemberAccessible(field, receiver.info(), currentClass,
                currentUnit.packageName().orElse(""), receiverExpression instanceof SuperExpr);
    }

    private boolean isAccessible(SymbolTable.MethodInfo method, ResolvedType receiver, Expression receiverExpression) {
        return symbolTable.isMemberAccessible(method, receiver.info(), currentClass,
                currentUnit.packageName().orElse(""), receiverExpression instanceof SuperExpr);
    }

    private static boolean requiresSuper(Expression receiver, List<String> modifiers,
                                         SymbolTable.MemberOwner owner, TypeInfo accessingClass) {
        return receiver instanceof ThisExpr && !modifiers.contains("static")
                && owner != null && accessingClass != null
                && !owner.typeName().equals(accessingClass.fullName());
    }

    private void verifyMemberReceiver(Expression receiver, List<String> modifiers,
                                      SymbolTable.MemberOwner owner, String name, Token token) {
        if (modifiers.contains("static")) {
            boolean own = owner != null && currentClass != null && owner.typeName().equals(currentClass.fullName());
            if (own && !(receiver instanceof StaticExpr)) {
                error(token, "Static member '" + name + "' of the current class must be accessed with 'static." + name + "'.");
            } else if (!own && (receiver == null || receiver instanceof StaticExpr
                    || receiver instanceof ThisExpr || receiver instanceof SuperExpr || !isTypeReceiver(receiver)
                    || owner != null && !resolvedTypes.get(receiver).info().fullName().equals(owner.typeName()))) {
                error(token, "Static member '" + name + "' must be accessed with its declaring class name '"
                        + (owner == null ? "ClassName" : owner.typeName()) + "." + name + "'.");
            }
        } else if (receiver instanceof StaticExpr) {
            error(token, "Instance member '" + name + "' cannot be accessed with 'static.'; use '"
                    + requiredQualifier(owner) + name + "'.");
        }
        if (requiresSuper(receiver, modifiers, owner, currentClass)) {
            error(token, "Inherited instance member '" + name + "' declared in '" + owner.typeName()
                    + "' must be accessed with 'super." + name + "', not 'this." + name + "'.");
        }
        if (receiver instanceof SuperExpr && modifiers.contains("abstract")) {
            error(token, "Cannot call abstract parent method '" + name + "' with 'super'; override it before calling it with 'this'.");
        }
    }

    private void validateStaticImports() {
        for (String entry : currentUnit.imports()) {
            if (!entry.startsWith("static ")) continue;
            String path = entry.substring(7);
            int dot = path.lastIndexOf('.');
            TypeInfo type = dot < 0 ? null : symbolTable.getType(path.substring(0, dot));
            Token token = currentUnit.tokens().getFirst();
            if (type == null) {
                error(token, "Cannot resolve declaring type of static import '" + path + "'.");
                continue;
            }
            String name = path.substring(dot + 1);
            if (name.equals("*")) continue;
            String pkg = currentUnit.packageName().orElse("");
            var field = type.findField(name, symbolTable);
            boolean valid = field != null && field.modifiers().contains("static")
                    && symbolTable.isMemberAccessible(field, type, null, pkg, false);
            valid |= type.findAllMethods(name, symbolTable).stream().anyMatch(method ->
                    method.modifiers().contains("static")
                    && symbolTable.isMemberAccessible(method, type, null, pkg, false));
            // Static imports can also name nested types, which normal type resolution handles.
            if (!valid && symbolTable.getType(path) == null)
                error(token, "Static import '" + path + "' must name an accessible static member.");
        }
    }

    /** Explicit imports take precedence over wildcard imports, separately for fields and methods. */
    private List<TypeInfo> staticImportTypes(String name, boolean method) {
        List<TypeInfo> explicit = new ArrayList<>();
        List<TypeInfo> wildcard = new ArrayList<>();
        for (String entry : currentUnit.imports()) {
            if (!entry.startsWith("static ")) continue;
            String path = entry.substring(7);
            int dot = path.lastIndexOf('.');
            if (dot < 0) continue;
            String member = path.substring(dot + 1);
            if (!member.equals(name) && !member.equals("*")) continue;
            TypeInfo type = symbolTable.getType(path.substring(0, dot));
            if (type == null) continue;
            boolean exists = method
                    ? type.findAllMethods(name, symbolTable).stream().anyMatch(m -> m.modifiers().contains("static"))
                    : type.findField(name, symbolTable) != null
                        && type.findField(name, symbolTable).modifiers().contains("static");
            if (exists) (member.equals("*") ? wildcard : explicit).add(type);
        }
        return explicit.isEmpty() ? wildcard : explicit;
    }

    private Expression importedReceiver(SymbolTable.MemberOwner owner, Token token) {
        return currentClass != null && owner.typeName().equals(currentClass.fullName())
                ? new StaticExpr(token) : new IdentifierExpr(owner.typeName(), token);
    }

    private ResolvedType checkImportedExpression(Expression original, Expression expanded) {
        builtinExpressions.put(original, expanded);
        if (nullableUses.contains(original)) nullableUses.add(expanded);
        return checkExpression(expanded);
    }

    private ResolvedType importedField(IdentifierExpr id, List<TypeInfo> types) {
        Map<String, SymbolTable.FieldInfo> fields = new LinkedHashMap<>();
        for (TypeInfo type : types) {
            var field = type.findField(id.name(), symbolTable);
            var receiver = new ResolvedType(type, true, List.of(), 0);
            if (isAccessible(field, receiver, null))
                fields.put(symbolTable.declaringOwner(field).typeName(), field);
        }
        if (fields.size() != 1) {
            error(id.token(), fields.isEmpty() ? "Static import '" + id.name() + "' is not accessible."
                    : "Ambiguous static import '" + id.name() + "'; use its declaring class name.");
            return null;
        }
        var field = fields.values().iterator().next();
        return checkImportedExpression(id, new MemberAccessExpr(importedReceiver(symbolTable.declaringOwner(field), id.token()),
                id.name(), false, id.token(), id.nonNullBinding(), id.nonNullElements()));
    }

    private boolean moreSpecificImport(SymbolTable.MethodInfo left, SymbolTable.MethodInfo right) {
        if (left.parameters().size() != right.parameters().size()) return false;
        boolean stricter = false;
        for (int i = 0; i < left.parameters().size(); i++) {
            ResolvedType a = symbolTable.resolveParameterType(left, i);
            ResolvedType b = symbolTable.resolveParameterType(right, i);
            if (!symbolTable.isInvocationCompatible(a, b)) return false;
            if (!symbolTable.isInvocationCompatible(b, a)) stricter = true;
        }
        return stricter;
    }

    private ResolvedType importedMethod(MethodCallExpr call, List<TypeInfo> types) {
        // Resolve arguments against each candidate's parameter types, including lambda targets.
        Map<String, SymbolTable.MethodInfo> candidates = new LinkedHashMap<>();
        for (TypeInfo type : types) {
            var receiver = new ResolvedType(type, true, List.of(), 0);
            for (var method : type.findAllMethods(call.methodName(), symbolTable)) {
                if (!method.modifiers().contains("static") || !isAccessible(method, receiver, null)) continue;
                candidates.putIfAbsent(symbolTable.declaringOwner(method).typeName(), method);
            }
        }
        if (candidates.size() > 1 && call.arguments().stream().noneMatch(a -> a instanceof LambdaExpr)) {
            List<ResolvedType> arguments = call.arguments().stream().map(this::checkExpression).toList();
            List<SymbolTable.MethodInfo> applicable = new ArrayList<>();
            for (String ownerName : candidates.keySet()) {
                TypeInfo owner = symbolTable.getType(ownerName);
                for (var method : owner.findAllMethods(call.methodName(), symbolTable)) {
                    if (method.modifiers().contains("static") && owner.acceptsArguments(method, arguments, symbolTable)
                            && isAccessible(method, new ResolvedType(owner, true, List.of(), 0), null))
                        applicable.add(method);
                }
            }
            List<SymbolTable.MethodInfo> best = applicable.stream().filter(method ->
                    applicable.stream().noneMatch(other -> moreSpecificImport(other, method))).toList();
            candidates.clear();
            for (var method : best) candidates.putIfAbsent(symbolTable.declaringOwner(method).typeName(), method);
        }
        if (candidates.size() != 1) {
            error(call.token(), candidates.isEmpty() ? "No accessible static import matches '" + call.methodName() + "'."
                    : "Ambiguous static import '" + call.methodName() + "'; use its declaring class name.");
            return null;
        }
        var method = candidates.values().iterator().next();
        return checkImportedExpression(call, new MethodCallExpr(importedReceiver(symbolTable.declaringOwner(method), call.token()),
                call.methodName(), call.typeArguments(), call.arguments(), false, call.token(), call.nonNullResult()));
    }

    private boolean isTypeReceiver(Expression receiver) {
        if (receiver instanceof IdentifierExpr id)
            return lookupVar(id.name()) == null && resolve(id.name()) != null;
        if (receiver instanceof MemberAccessExpr member) {
            String name = qualifiedTypeName(member);
            return name != null && resolve(name) != null;
        }
        return false;
    }

    private String qualifiedTypeName(Expression expr) {
        if (expr instanceof IdentifierExpr id) return id.name();
        if (expr instanceof MemberAccessExpr member) {
            String prefix = qualifiedTypeName(member.receiver());
            return prefix == null ? null : prefix + "." + member.memberName();
        }
        return null;
    }

    private String requiredQualifier(SymbolTable.MemberOwner owner) {
        return owner != null && currentClass != null && !owner.typeName().equals(currentClass.fullName())
                ? "super." : "this.";
    }

    private void inaccessibleMember(Token token, String kind, String name, SymbolTable.MemberOwner owner) {
        error(token, "Cannot access " + kind + " '" + name + "' declared in '" + owner.typeName() + "' from this context.");
    }

    private void verifyCondition(ResolvedType type, Token token) {
        if (type == null) {
            error(token, "Condition cannot be resolved.");
        }
    }


    public final Map<Expression, ResolvedType> resolvedTypes = new java.util.IdentityHashMap<>();
    /** Compiler-provided expressions, resolved before Java lowering. */
    public final Map<Expression, Expression> builtinExpressions = new IdentityHashMap<>();
    private record AccessContext(TypeInfo accessingClass, String packageName) {}
    private final Map<Expression, AccessContext> accessContexts = new IdentityHashMap<>();

    /** Accessible members for editor queries after the analyzer has left the enclosing type. */
    public List<SymbolTable.MethodInfo> accessibleMethods(Expression receiver) {
        ResolvedType type = resolvedTypes.get(receiver);
        AccessContext context = accessContexts.get(receiver);
        if (type == null || context == null) return List.of();
        return symbolTable.hierarchy(type.info()).stream().flatMap(owner -> owner.methods().stream())
                .filter(method -> symbolTable.isMemberAccessible(method, type.info(), context.accessingClass(),
                        context.packageName(), receiver instanceof SuperExpr))
                .filter(method -> !requiresSuper(receiver, method.modifiers(), symbolTable.declaringOwner(method), context.accessingClass()))
                .filter(method -> !(receiver instanceof SuperExpr && method.modifiers().contains("abstract")))
                .filter(method -> !(receiver instanceof ThisExpr || receiver instanceof SuperExpr) || !method.modifiers().contains("static"))
                .filter(method -> !(receiver instanceof StaticExpr) || method.modifiers().contains("static")
                        && symbolTable.declaringOwner(method).typeName().equals(type.info().fullName())).toList();
    }

    public List<SymbolTable.FieldInfo> accessibleFields(Expression receiver) {
        ResolvedType type = resolvedTypes.get(receiver);
        AccessContext context = accessContexts.get(receiver);
        if (type == null || context == null) return List.of();
        Map<String, SymbolTable.FieldInfo> fields = new LinkedHashMap<>();
        for (TypeInfo owner : symbolTable.hierarchy(type.info())) {
            owner.fields().forEach(field -> fields.putIfAbsent(field.name(), field));
        }
        return fields.values().stream().filter(field -> symbolTable.isMemberAccessible(field, type.info(),
                context.accessingClass(), context.packageName(), receiver instanceof SuperExpr))
                .filter(field -> !requiresSuper(receiver, field.modifiers(), symbolTable.declaringOwner(field), context.accessingClass()))
                .filter(field -> !(receiver instanceof ThisExpr || receiver instanceof SuperExpr) || !field.modifiers().contains("static"))
                .filter(field -> !(receiver instanceof StaticExpr) || field.modifiers().contains("static")
                        && symbolTable.declaringOwner(field).typeName().equals(type.info().fullName())).toList();
    }

    private Expression unwrapNullable(Expression expression) {
        return expression instanceof NullableExpr nullable ? nullable.expression() : expression;
    }

    private ResolvedType checkExpression(Expression expr) {
        if (expr == null) return null;
        accessContexts.put(expr, new AccessContext(currentClass, currentUnit.packageName().orElse("")));
        ResolvedType type = checkExpressionInternal(expr);
        if (type != null) {
            resolvedTypes.put(expr, type);
        }
        return type;
    }

    private ResolvedType checkExpressionInternal(Expression expr) {
        if (expr instanceof NullableExpr nullable) {
            nullableUses.add(nullable.expression());
            ResolvedType value = checkExpression(nullable.expression());
            if (nullable.nullableElements() && value != null && (value.nonNullElements()
                    || value.arrayDimensions() == 0 && !isList(value)))
                error(nullable.token(), "'??' requires nullable collection elements.");
            return value;
        } else if (expr instanceof LiteralExpr lit) {
            if (lit.value() == null) {
                return new ResolvedType(symbolTable.getType("null"), false, List.of(), 0);
            }
            if (lit.value() instanceof Boolean) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }
            if (lit.value() instanceof Number) {
                // Defaults to double or int
                return new ResolvedType(symbolTable.getType("int"), true, List.of(), 0);
            }
            if (lit.value() instanceof String) {
                return new ResolvedType(symbolTable.getType("java.lang.String"), true, List.of(), 0);
            }
            return new ResolvedType(symbolTable.getType("java.lang.Object"), true, List.of(), 0);
        } else if (expr instanceof MarkupExpr me) {
            checkMarkupNode(me.node());
            return new ResolvedType(symbolTable.getType("accent.html.Html"), true, List.of(), 0);
        } else if (expr instanceof IdentifierExpr id) {
            LocalVar v = lookupVar(id.name());
            if (v != null) {
                checkBindingMarker(id.name(), id, v.type, id.token());
                checkElementMarker(id.name(), id.nonNullElements(), v.type, id.token());
                if (v.isRefinedNonNull) {
                    return v.type.withNonNull(true);
                }
                return v.type;
            }

            if (id.name().equals("log") && nativeLoggerName != null
                    && (currentClass == null || currentClass.findField("log", symbolTable) == null)) {
                ResolvedType logger = new ResolvedType(symbolTable.getType("accent.logging.Logger"), true, List.of(), 0);
                checkBindingMarker("log", id, logger, id.token());
                builtinExpressions.put(id, new MethodCallExpr(new IdentifierExpr("accent.logging.LogManager", null),
                        "getLogger", List.of(), List.of(new LiteralExpr(nativeLoggerName, id.token())), false, id.token()));
                return logger;
            }

            // Check field access on current class (missing this. check)
            if (currentClass != null) {
                SymbolTable.FieldInfo fInfo = currentClass.findField(id.name(), symbolTable);
                if (fInfo != null) {
                    ResolvedType receiver = new ResolvedType(currentClass, true, List.of(), 0);
                    if (!isAccessible(fInfo, receiver, null)) {
                        inaccessibleMember(id.token(), "field", id.name(), symbolTable.declaringOwner(fInfo));
                    }
                    if (fInfo.modifiers().contains("static")) {
                        verifyMemberReceiver(null, fInfo.modifiers(), symbolTable.declaringOwner(fInfo), id.name(), id.token());
                    } else {
                        error(id.token(), "Access to field '" + id.name() + "' must be explicitly qualified with '"
                                + requiredQualifier(symbolTable.declaringOwner(fInfo)) + "'.");
                    }
                    // Recover type
                    return symbolTable.resolveFieldType(fInfo);
                }
            }
            
            List<TypeInfo> importedFields = staticImportTypes(id.name(), false);
            if (!importedFields.isEmpty()) return importedField(id, importedFields);

            // Assume package name or class name reference
            TypeInfo classInfo = resolve(id.name());
            if (classInfo != null) {
                if (id.nonNullBinding()) error(id.token(), "Type names must not use a binding '!'.");
                return new ResolvedType(classInfo, true, List.of(), 0);
            }

            error(id.token(), "Cannot resolve symbol '" + id.name() + "'.");
        } else if (expr instanceof StaticExpr st) {
            if (currentClass == null) {
                error(st.token(), "Cannot use 'static.' outside a declaring class; use the declaring class name.");
                return new ResolvedType(symbolTable.getType("java.lang.Object"), true, List.of(), 0);
            }
            return new ResolvedType(currentClass, true, List.of(), 0);
        } else if (expr instanceof ThisExpr te) {
            if (isStaticContext) {
                error(te.token(), "Cannot reference 'this' in a static context.");
            }
            if (currentClass == null) {
                // Inside class extension, 'this' refers to the targetType, which is added to scopes
                LocalVar v = lookupVar("this");
                if (v != null) return v.type;
                error(te.token(), "Cannot reference 'this' outside a class context.");
            }
            return new ResolvedType(currentClass, true, List.of(), 0);
        } else if (expr instanceof SuperExpr se) {
            if (isStaticContext) {
                error(se.token(), "Cannot reference 'super' in a static context.");
            }
            if (currentClass != null && !currentClass.isInterface()) {
                TypeInfo parent = symbolTable.getType(currentClass.superclass().orElse("java.lang.Object"));
                if (parent != null) return new ResolvedType(parent, true, List.of(), 0);
            }
            error(se.token(), "Cannot reference 'super' in this context.");
        } else if (expr instanceof TernaryExpr tern) {
            ResolvedType cond = checkExpression(tern.condition());
            verifyCondition(cond, tern.token());
            ResolvedType thenBranch = checkExpression(tern.thenBranch());
            ResolvedType elseBranch = checkExpression(tern.elseBranch());
            if (thenBranch != null && elseBranch != null) {
                boolean isNonNull = thenBranch.isNonNull() && elseBranch.isNonNull();
                return getCommonSupertype(thenBranch, elseBranch).withNonNull(isNonNull);
            }
            return new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
        } else if (expr instanceof BinaryExpr bin) {
            ResolvedType left = checkExpression(bin.left());
            if (bin.operator().type() == accent.lexer.TokenType.INSTANCEOF) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }
            ResolvedType right = checkExpression(bin.right());

            if (bin.operator().type() == accent.lexer.TokenType.ASSIGN) {
                // Reassignment checks
                if (unwrapNullable(bin.left()) instanceof IdentifierExpr id) {
                    LocalVar v = lookupVar(id.name());
                    if (id.name().equals("log") && builtinExpressions.get(id) instanceof MethodCallExpr) error(bin.operator(), "The built-in 'log' binding cannot be reassigned.");
                    if (v != null) {
                        left = v.type;
                        v.isRefinedNonNull = right != null && right.isNonNull();
                        if (v.isParameter) {
                            error(bin.operator(), "Reassigning method or constructor parameter '" + id.name() + "' is not permitted.");
                        } else if (v.isFinal) {
                            error(bin.operator(), "Reassignment of final local variable '" + id.name() + "'.");
                        }
                    }
                }
                
                right = checkCollectionInitializer(bin.right(), left, right);
                if (left != null && right != null && !right.isCompatibleWith(left, symbolTable)) {
                    error(bin.operator(), "Incompatible types in assignment: expected " + left + ", actual " + right);
                }
                return left;
            }

            if (bin.operator().type() == accent.lexer.TokenType.NULL_COALESCING) {
                // left ?: right
                if (left != null && right != null) {
                    boolean isNonNull = left.isNonNull() || right.isNonNull();
                    return getCommonSupertype(left, right).withNonNull(isNonNull);
                }
            }

            accent.lexer.TokenType opType = bin.operator().type();

            // Textual boolean operators: accept truthy/falsy operands of any type, return boolean
            if (opType == accent.lexer.TokenType.AND  ||
                opType == accent.lexer.TokenType.OR) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }

            if (opType == accent.lexer.TokenType.EQUAL_EQUAL || opType == accent.lexer.TokenType.BANG_EQUAL ||
                opType == accent.lexer.TokenType.LESS || opType == accent.lexer.TokenType.LESS_EQUAL ||
                opType == accent.lexer.TokenType.GREATER || opType == accent.lexer.TokenType.GREATER_EQUAL ||
                opType == accent.lexer.TokenType.AND_AND || opType == accent.lexer.TokenType.OR_OR) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }

            // Primitive numeric operations
            return left;
        } else if (expr instanceof UnaryExpr un) {
            ResolvedType type = checkExpression(un.expression());
            // Unary 'not' or '!' — accepts any truthy/falsy operand, returns boolean
            if (un.operator().type() == accent.lexer.TokenType.NOT || un.operator().type() == accent.lexer.TokenType.BANG) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }

            if (un.operator().type() == accent.lexer.TokenType.PLUS_PLUS || un.operator().type() == accent.lexer.TokenType.MINUS_MINUS) {
                if (un.expression() instanceof IdentifierExpr id) {
                    LocalVar v = lookupVar(id.name());
                    if (v != null) {
                        if (v.isParameter) {
                            error(un.operator(), "Reassigning method or constructor parameter '" + id.name() + "' is not permitted.");
                        } else if (v.isFinal) {
                            error(un.operator(), "Reassignment of final local variable '" + id.name() + "'.");
                        }
                    }
                }
            }
            return type;
        } else if (expr instanceof MemberAccessExpr ma) {
            if (ma.memberName().equals("class")) {
                if (ma.receiver() instanceof StaticExpr) error(ma.token(), "Use the class name for a class literal, not 'static.class'.");
                if (ma.nonNullBinding()) error(ma.token(), "Class literals must not use a binding '!'.");
                return new ResolvedType(symbolTable.getType("java.lang.Class"), true, List.of(), 0);
            }
            if (ma.isOptional()) optionalReceivers.add(ma.receiver());
            ResolvedType rec = checkExpression(ma.receiver());
            if (rec != null) {
                // Resolve member
                SymbolTable.FieldInfo fInfo = rec.info().findField(ma.memberName(), symbolTable);
                if (fInfo != null) {
                    if (!isAccessible(fInfo, rec, ma.receiver())) {
                        inaccessibleMember(ma.token(), "field", ma.memberName(), symbolTable.declaringOwner(fInfo));
                    }
                    verifyMemberReceiver(ma.receiver(), fInfo.modifiers(), symbolTable.declaringOwner(fInfo), ma.memberName(), ma.token());
                    ResolvedType fType = symbolTable.resolveFieldType(fInfo);
                    if (symbolTable.isAccentMember(symbolTable.declaringOwner(fInfo)) || fInfo.modifiers().contains("$accent$contract"))
                        checkBindingMarker(ma.memberName(), ma, fType, ma.token());
                    checkElementMarker(ma.memberName(), ma.nonNullElements(), fType, ma.token());
                    if (ma.isOptional()) {
                        return nullableResult(fType); // Optional chain result is nullable
                    }
                    return fType;
                }
                error(ma.token(), "Cannot resolve field '" + ma.memberName() + "' on type " + rec);
            }
        } else if (expr instanceof MethodCallExpr mc) {
            if (mc.receiver() == null && mc.methodName().equals("log")) {
                if (mc.arguments().size() < 1 || mc.arguments().size() > 2)
                    error(mc.token(), "log requires a message and an optional Throwable.");
                if (!mc.typeArguments().isEmpty() || mc.arguments().stream().anyMatch(arg -> arg instanceof NamedArgExpr))
                    error(mc.token(), "Built-in log uses positional arguments without type arguments.");
                Expression expanded = new MethodCallExpr(new IdentifierExpr("accent.logging.Logger", mc.token()), "log",
                        mc.typeArguments(), mc.arguments(), false, mc.token());
                builtinExpressions.put(mc, expanded);
                ResolvedType result = checkExpression(expanded);
                if (!mc.arguments().isEmpty()) {
                    ResolvedType message = resolvedTypes.get(mc.arguments().getFirst());
                    if (message != null && (message.arrayDimensions() != 0
                            || !Set.of("java.lang.String", "null").contains(message.info().fullName())))
                        error(mc.token(), "log requires a String message.");
                }
                if (mc.arguments().size() == 2) {
                    ResolvedType throwable = resolvedTypes.get(mc.arguments().get(1));
                    if (throwable != null && !throwable.info().fullName().equals("null")
                            && (throwable.arrayDimensions() != 0 || !symbolTable.isSubtype(throwable.info(), symbolTable.getType("java.lang.Throwable"))))
                        error(mc.token(), "The second log argument must be a Throwable.");
                }
                return result;
            }
            if (mc.receiver() == null && Set.of("print", "println").contains(mc.methodName())) {
                int minimum = mc.methodName().equals("print") ? 1 : 0;
                if (mc.arguments().size() < minimum || mc.arguments().size() > 1)
                    error(mc.token(), mc.methodName() + " expects " + (minimum == 0 ? "zero or one argument." : "one argument."));
                if (!mc.typeArguments().isEmpty() || mc.arguments().stream().anyMatch(arg -> arg instanceof NamedArgExpr))
                    error(mc.token(), "Built-in printing uses positional arguments without type arguments.");
                Expression output = new MemberAccessExpr(new IdentifierExpr("java.lang.System", mc.token()), "out", false, mc.token());
                Expression expanded = new MethodCallExpr(output, mc.methodName(), mc.typeArguments(), mc.arguments(), false, mc.token());
                builtinExpressions.put(mc, expanded);
                return checkExpression(expanded);
            }
            if (mc.receiver() == null && (currentClass == null
                    || currentClass.findAllMethods(mc.methodName(), symbolTable).isEmpty())) {
                List<TypeInfo> importedMethods = staticImportTypes(mc.methodName(), true);
                if (!importedMethods.isEmpty()) return importedMethod(mc, importedMethods);
            }
            ResolvedType rec = null;
            if (mc.receiver() != null) {
                if (mc.isOptional()) optionalReceivers.add(mc.receiver());
                rec = checkExpression(mc.receiver());
            } else {
                rec = new ResolvedType(currentClass, true, List.of(), 0);
            }

            List<ResolvedType> argTypes = new ArrayList<>();
            for (int index = 0; index < mc.arguments().size(); index++) {
                Expression arg = mc.arguments().get(index);
                if (arg instanceof LambdaExpr lambda && rec != null) {
                    for (var candidate : rec.info().findAllMethods(mc.methodName(), symbolTable)) {
                        if (!isAccessible(candidate, rec, mc.receiver())) continue;
                        if (candidate.parameters().size() == mc.arguments().size()) {
                            ResolvedType target = symbolTable.resolveParameterType(candidate, index);
                            if (contextualizeLambda(lambda, target)) break;
                        }
                    }
                }
                argTypes.add(checkExpression(arg));
            }

            if (rec != null && rec.info().fullName().equals("accent.runtime.Async")
                    && mc.methodName().equals("run") && mc.arguments().size() == 1
                    && mc.arguments().getFirst() instanceof LambdaExpr lambda) {
                if (mc.nonNullResult()) error(mc.token(), "Java method 'run()' has no declared non-null return contract.");
                if (!lambda.parameters().isEmpty()) error(mc.token(), "Async.run requires a zero-argument operation.");
                ResolvedType result = lambdaResults.get(lambda);
                if (result == null) result = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
                String boxed = switch (result.info().fullName()) {
                    case "void" -> "java.lang.Void";
                    case "null" -> "java.lang.Object";
                    case "boolean" -> "java.lang.Boolean";
                    case "byte" -> "java.lang.Byte";
                    case "short" -> "java.lang.Short";
                    case "char" -> "java.lang.Character";
                    case "int" -> "java.lang.Integer";
                    case "long" -> "java.lang.Long";
                    case "float" -> "java.lang.Float";
                    case "double" -> "java.lang.Double";
                    default -> null;
                };
                if (boxed != null && result.arrayDimensions() == 0) result = new ResolvedType(symbolTable.getType(boxed), result.isNonNull() && !boxed.equals("java.lang.Void"), List.of(), 0);
                if (mc.typeArguments().size() == 1) result = symbolTable.resolveTypeNode(mc.typeArguments().getFirst(), this);
                return new ResolvedType(rec.info(), true, List.of(result), 0);
            }

            if (isList(rec) && mc.receiver() instanceof IdentifierExpr id
                    && (id.name().equals("List") || id.name().equals("java.util.List"))
                    && (mc.methodName().equals("of") || mc.methodName().equals("copyOf"))) {
                if (mc.nonNullResult()) error(mc.token(), "Java factories use unmarked method names.");
                ResolvedType element = new ResolvedType(symbolTable.getType("java.lang.Object"), true, List.of(), 0);
                if (mc.methodName().equals("of")) {
                    for (ResolvedType argument : argTypes) {
                        if (!argument.isNonNull()) error(mc.token(), "List.of requires proven non-null elements.");
                    }
                    if (!argTypes.isEmpty()) element = argTypes.getFirst().withNonNull(true);
                } else if (!argTypes.isEmpty() && !argTypes.getFirst().typeArguments().isEmpty()) {
                    element = argTypes.getFirst().typeArguments().getFirst().withNonNull(true);
                }
                return new ResolvedType(rec.info(), true, List.of(element), 0, true);
            }

            if (mc.methodName().equals("super")) {
                return new ResolvedType(symbolTable.getType("void"), false, List.of(), 0);
            }

            if (rec != null) {
                if (rec.arrayDimensions() > 0 && (mc.methodName().equals("toJson") || mc.methodName().equals("toXml"))) {
                    error(mc.token(), "Cannot resolve method '" + mc.methodName() + "' on array type " + rec);
                    return null;
                }

                if (mc.arguments().size() == 1) {
                    String fieldName = mc.methodName();
                    SymbolTable.FieldInfo fInfo = rec.info().fields().stream()
                            .filter(f -> f.name().equals(fieldName))
                            .findFirst().orElse(null);
                    if (fInfo != null && fInfo.modifiers().contains("final")
                            && rec.info().findAllMethods(mc.methodName(), symbolTable).stream()
                                    .noneMatch(method -> method.parameters().size() == 1)) {
                        error(mc.token(), "Cannot call setter '" + mc.methodName() + "': field '" + fieldName + "' in type '" + rec.info().fullName() + "' is final and cannot have a setter.");
                        return new ResolvedType(symbolTable.getType("void"), false, List.of(), 0);
                    }
                }

                // 1. Resolve normal instance/static methods
                ResolvedType receiver = rec;
                SymbolTable.MethodInfo mInfo = rec.info().findMethod(mc.methodName(), argTypes, symbolTable,
                        method -> isAccessible(method, receiver, mc.receiver()));
                if (mInfo == null) {
                    mInfo = rec.info().findMethod(mc.methodName(), argTypes, symbolTable);
                    if (mInfo != null) {
                        inaccessibleMember(mc.token(), "method", mc.methodName(), symbolTable.declaringOwner(mInfo));
                    }
                }

                if (mInfo != null) {
                    if (mc.receiver() == null && !mInfo.modifiers().contains("static")) {
                        error(mc.token(), "Call to instance method '" + mc.methodName()
                                + "' must be explicitly qualified with '" + requiredQualifier(symbolTable.declaringOwner(mInfo)) + "'.");
                    }
                    verifyMemberReceiver(mc.receiver(), mInfo.modifiers(), symbolTable.declaringOwner(mInfo), mc.methodName(), mc.token());
                    for (int i = 0; i < Math.min(argTypes.size(), mInfo.parameters().size()); i++) {
                        int parameterIndex = i;
                        if (mc.arguments().get(i) instanceof NamedArgExpr named) {
                            for (int j = 0; j < mInfo.parameters().size(); j++)
                                if (mInfo.parameters().get(j).name().equals(named.name())) parameterIndex = j;
                        }
                        ResolvedType expected = symbolTable.resolveParameterType(mInfo, parameterIndex);
                        ResolvedType actual = argTypes.get(i);
                        if ((expected.nonNullElements() || actual.nonNullElements())
                                && (expected.arrayDimensions() > 0 || isList(expected))
                                && !symbolTable.isInvocationCompatible(actual, expected))
                            error(mc.token(), "Argument must preserve the non-null element contract of parameter '"
                                    + mInfo.parameters().get(parameterIndex).name() + "'.");
                    }
                    ResolvedType declaredResult = symbolTable.resolveReturnType(mInfo);
                    if (isList(rec) && !mInfo.modifiers().contains("static")) {
                        ResolvedType element = rec.elementType(symbolTable);
                        String method = mc.methodName();
                        int valueIndex = method.equals("set") || method.equals("add") && argTypes.size() == 2 ? 1 : 0;
                        if ((method.equals("add") || method.equals("set") || method.equals("addFirst") || method.equals("addLast"))
                                && argTypes.size() > valueIndex && !symbolTable.isInvocationCompatible(argTypes.get(valueIndex), element))
                            error(mc.token(), "Non-null elements require a proven non-null value of the element type.");
                        if (rec.nonNullElements() && method.equals("replaceAll") && mc.arguments().size() == 1
                                && mc.arguments().getFirst() instanceof LambdaExpr lambda) {
                            ResolvedType result = lambdaResults.get(lambda);
                            if (result == null || !symbolTable.isInvocationCompatible(result, element))
                                error(mc.token(), "replaceAll must produce proven non-null elements.");
                        }
                        if (rec.nonNullElements() && method.equals("addAll") && !argTypes.isEmpty()
                                && !argTypes.getLast().nonNullElements())
                            error(mc.token(), "addAll requires a collection with non-null elements.");
                        if (Set.of("get", "getFirst", "getLast", "set", "removeFirst", "removeLast").contains(method)
                                || method.equals("remove") && argTypes.size() == 1 && argTypes.getFirst().isPrimitive())
                            declaredResult = element;
                        if (Set.of("subList", "reversed").contains(method)) declaredResult = rec.withNonNull(true);
                    }
                    if ((mc.token() == null || mc.token().type() != accent.lexer.TokenType.AWAIT)
                            && (symbolTable.isAccentMember(symbolTable.declaringOwner(mInfo)) || mInfo.modifiers().contains("$accent$contract") || isList(rec) || nullableUses.contains(mc))) {
                        checkMethodMarker(mc, declaredResult);
                    }
                    ResolvedType retType = rec.info().fullName().equals("accent.runtime.Async")
                            && mc.methodName().equals("await") && !rec.typeArguments().isEmpty()
                            ? rec.typeArguments().getFirst()
                            : declaredResult;
                    if (mc.isOptional()) {
                        retType = nullableResult(retType);
                    }
                    return retType;
                }

                if (mc.typeArguments().isEmpty()
                        && symbolTable.isContainerSerializationMethod(rec, mc.methodName(), mc.arguments().size())) {
                    ResolvedType declaredResult = new ResolvedType(symbolTable.getType("java.lang.String"), true, List.of(), 0);
                    checkMethodMarker(mc, declaredResult);
                    return declaredResult.withNonNull(!mc.isOptional());
                }

                // 2. Resolve extensions using their own imports and the matching signature.
                ResolvedType extensionReceiver = mc.isOptional() ? rec.withNonNull(true) : rec;
                String packageName = currentUnit.packageName().orElse("");
                ExtensionResolver.Match extension = ExtensionResolver.find(symbolTable, extensionReceiver,
                        mc.methodName(), mc.arguments(), argTypes, match -> match.accessibleFrom(symbolTable, packageName));
                if (extension == null) {
                    extension = ExtensionResolver.find(symbolTable, extensionReceiver, mc.methodName(),
                            mc.arguments(), argTypes, match -> true);
                    if (extension != null) {
                        error(mc.token(), "Cannot access extension method '" + mc.methodName()
                                + "' declared in package '" + symbolTable.extensionPackage(extension.extension()) + "' from this context.");
                    }
                }
                if (extension != null) {
                    ResolvedType retType = extension.returnType(symbolTable);
                    checkMethodMarker(mc, retType);
                    return mc.isOptional() ? retType.withNonNull(false) : retType;
                }

                error(mc.token(), "Cannot resolve method '" + mc.methodName() + "' on type " + rec);
            }
        } else if (expr instanceof NewObjectExpr no) {
            ResolvedType type = symbolTable.resolveTypeNode(no.type(), this).withNonNull(true);
            if (symbolTable.isSingletonType(type.info())) {
                error(no.token(), "Cannot construct singleton '" + type.info().fullName() + "'; use .instance().");
            }
            List<ResolvedType> arguments = no.arguments().stream().map(this::checkExpression).toList();
            var candidates = type.info().constructors().stream()
                    .filter(c -> c.parameters().size() == arguments.size()).toList();
            if (!candidates.isEmpty() && candidates.stream().anyMatch(c -> c.parameters().stream().anyMatch(p -> p.type().hasNonNullElements()))) {
                boolean compatible = candidates.stream().anyMatch(c -> {
                    for (int i = 0; i < arguments.size(); i++) {
                        ResolvedType expected = symbolTable.resolveConstructorParameterType(type.info(), c, i);
                        if (expected.nonNullElements() && !symbolTable.isInvocationCompatible(arguments.get(i), expected)) return false;
                    }
                    return true;
                });
                if (!compatible) error(no.token(), "Constructor arguments must preserve non-null element contracts.");
            }
            return type;
        } else if (expr instanceof NewArrayExpr na) {
            ResolvedType type = symbolTable.resolveTypeNode(na.type(), this);
            for (Expression dim : na.dimensions()) {
                checkExpression(dim);
            }
            na.initializer().ifPresent(this::checkExpression);
            return new ResolvedType(type.info(), true, type.typeArguments(),
                    type.arrayDimensions() + Math.max(1, na.dimensions().size()));
        } else if (expr instanceof ArrayInitializerExpr ai) {
            for (Expression element : ai.expressions()) {
                checkExpression(element);
            }
            ResolvedType element = ai.expressions().isEmpty() ? null : resolvedTypes.get(ai.expressions().getFirst());
            if (element == null || element.info().fullName().equals("null"))
                element = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
            return new ResolvedType(element.info(), true, element.typeArguments(), element.arrayDimensions() + 1, false);
        } else if (expr instanceof ArrayAccessExpr aa) {
            boolean savedArrayReceiver = checkingArrayReceiver;
            checkingArrayReceiver = true;
            ResolvedType rec = checkExpression(aa.receiver());
            checkingArrayReceiver = savedArrayReceiver;
            checkExpression(aa.index());
            if (rec != null && isList(rec)) {
                ResolvedType element = rec.elementType(symbolTable);
                if (nullableUses.contains(aa) != !element.isNonNull())
                    error(aa.token(), "List access must match element nullability: use '[index]?' for nullable elements.");
                return aa.receiver() instanceof NullableExpr ? nullableResult(element) : element;
            }
            if (rec != null && rec.arrayDimensions() > 0) {
                ResolvedType element = rec.elementType(symbolTable);
                if (nullableUses.contains(aa) != (!element.isNonNull() && !element.isPrimitive()))
                    error(aa.token(), "Array access must match element nullability: use '[index]?' for nullable elements.");
                return aa.receiver() instanceof NullableExpr ? nullableResult(element) : element;
            }
        } else if (expr instanceof LambdaExpr le) {
            lambdaReturns.push(new ArrayList<>());
            pushScope();
            for (int index = 0; index < le.parameters().size(); index++) {
                Parameter param = le.parameters().get(index);
                ResolvedType pType = symbolTable.resolveTypeNode(param.type(), this);
                checkParameterDefault(param, pType);
                if (param.type() instanceof BaseTypeNode base && base.name().equals("var")
                        && lambdaParameterTypes.containsKey(le)) pType = lambdaParameterTypes.get(le).get(index).withNonNull(
                                lambdaParameterTypes.get(le).get(index).isPrimitive() || param.type().isNonNull());
                scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true));
            }
            if (le.body() instanceof Expression bodyExpr) {
                ResolvedType result = checkExpression(bodyExpr);
                if (result != null) lambdaReturns.peek().add(result);
            } else if (le.body() instanceof Statement bodyStmt) {
                checkStatement(bodyStmt);
            }
            popScope();
            List<ResolvedType> returns = lambdaReturns.pop();
            ResolvedType result = returns.isEmpty()
                    ? new ResolvedType(symbolTable.getType("void"), false, List.of(), 0) : returns.getFirst();
            for (int i = 1; i < returns.size(); i++) result = getCommonSupertype(result, returns.get(i));
            lambdaResults.put(le, result);
            // Lambdas retain the functional interface supplied by their context.
            if (lambdaTargets.containsKey(le)) return lambdaTargets.get(le);
            TypeInfo funcItf = symbolTable.getType("java.lang.Runnable");
            return new ResolvedType(funcItf, true, List.of(), 0);
        } else if (expr instanceof IfExpr ie) {
            checkExpression(ie.condition());
            
            ExpressionContext ctx = new ExpressionContext("if");
            expressionContexts.push(ctx);
            checkStatement(ie.thenBranch());
            checkStatement(ie.elseBranch());
            expressionContexts.pop();

            if (ctx.yieldedTypes.isEmpty()) {
                error(ie.token(), "If expression branches must yield values.");
                TypeInfo obj = symbolTable.getType("java.lang.Object");
                return new ResolvedType(obj, false, List.of(), 0);
            }

            ResolvedType resultType = ctx.yieldedTypes.get(0);
            for (int i = 1; i < ctx.yieldedTypes.size(); i++) {
                resultType = getCommonSupertype(resultType, ctx.yieldedTypes.get(i));
            }
            return resultType;
        } else if (expr instanceof TryExpr te) {
            ExpressionContext ctx = new ExpressionContext("try");
            expressionContexts.push(ctx);
            checkStatement(te.body());
            for (CatchClause cc : te.catchClauses()) {
                pushScope();
                ResolvedType exType = symbolTable.resolveTypeNode(cc.parameter().type(), this);
                scopes.getFirst().put(cc.parameter().name(), new LocalVar(cc.parameter().name(), exType, true, true));
                checkStatement(cc.body());
                popScope();
            }
            expressionContexts.pop();
            te.finallyBlock().ifPresent(this::checkStatement);

            if (ctx.yieldedTypes.isEmpty()) {
                error(te.token(), "Try expression branches must yield values.");
                TypeInfo obj = symbolTable.getType("java.lang.Object");
                return new ResolvedType(obj, false, List.of(), 0);
            }

            ResolvedType resultType = ctx.yieldedTypes.get(0);
            for (int i = 1; i < ctx.yieldedTypes.size(); i++) {
                resultType = getCommonSupertype(resultType, ctx.yieldedTypes.get(i));
            }
            return resultType;
        } else if (expr instanceof LoopExpr le) {
            ExpressionContext ctx = new ExpressionContext("loop");
            expressionContexts.push(ctx);
            checkStatement(le.loop());
            expressionContexts.pop();

            TypeInfo listInfo = symbolTable.getType("java.util.List");
            ResolvedType elemType;
            if (ctx.yieldedTypes.isEmpty()) {
                elemType = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
            } else {
                elemType = ctx.yieldedTypes.get(0);
                for (int i = 1; i < ctx.yieldedTypes.size(); i++) {
                    elemType = getCommonSupertype(elemType, ctx.yieldedTypes.get(i));
                }
            }
            return new ResolvedType(listInfo, true, List.of(elemType), 0);
        } else if (expr instanceof JsonExpr json) {
            for (Expression param : json.interpolations()) {
                checkExpression(param);
            }
            if (json.template() == null) {
                error(json.token(), "JSON template literal must contain a string.");
            }
            return symbolTable.resolveTypeNode(json.resultType(), this);
        } else if (expr instanceof SqlExpr sql) {
            for (Expression param : sql.interpolations()) {
                checkExpression(param);
            }
            if (sql.query() == null) {
                error(sql.token(), "SQL query literal must contain a query string.");
            } else {
                validateSqlSyntax(sql.query(), sql.token());
            }
            TypeInfo listInfo = symbolTable.getType("java.util.List");
            if (sql.resultType().isPresent()) {
                ResolvedType res = symbolTable.resolveTypeNode(sql.resultType().get(), this);
                return new ResolvedType(listInfo, true, List.of(res), 0);
            } else {
                String trimmed = sql.query().trim().toUpperCase();
                boolean isSelect = trimmed.startsWith("SELECT") || trimmed.startsWith("WITH") || trimmed.startsWith("SHOW") || trimmed.startsWith("DESCRIBE");
                if (!isSelect) {
                    TypeInfo intInfo = symbolTable.getType("int");
                    return new ResolvedType(intInfo, true, List.of(), 0);
                }
                TypeInfo mapInfo = symbolTable.getType("java.util.Map");
                TypeInfo stringInfo = symbolTable.getType("java.lang.String");
                TypeInfo objectInfo = symbolTable.getType("java.lang.Object");
                ResolvedType keyType = new ResolvedType(stringInfo, true, List.of(), 0);
                ResolvedType valType = new ResolvedType(objectInfo, false, List.of(), 0);
                ResolvedType mapType = new ResolvedType(mapInfo, true, List.of(keyType, valType), 0);
                return new ResolvedType(listInfo, true, List.of(mapType), 0);
            }
        } else if (expr instanceof NamedArgExpr named) {
            return checkExpression(named.expression());
        } else if (expr instanceof InterpolatedStringExpression interp) {
            for (InterpolatedStringPart part : interp.parts()) {
                if (part instanceof InterpolatedExpressionPart iep) {
                    ResolvedType type = checkExpression(iep.expression());
                    if (type != null && type.info() != null && type.info().fullName().equals("void")) {
                        error(interp.token(), "Interpolation expression cannot have type void.");
                    }
                }
            }
            TypeInfo stringInfo = symbolTable.getType("java.lang.String");
            return new ResolvedType(stringInfo, true, List.of(), 0);
        }

        TypeInfo obj = symbolTable.getType("java.lang.Object");
        return new ResolvedType(obj, false, List.of(), 0);
    }

    private ResolvedType getCommonSupertype(ResolvedType a, ResolvedType b) {
        if (a.isCompatibleWith(b, symbolTable)) return b;
        if (b.isCompatibleWith(a, symbolTable)) return a;
        TypeInfo objInfo = symbolTable.getType("java.lang.Object");
        return new ResolvedType(objInfo, a.isNonNull() && b.isNonNull(), List.of(), 0);
    }

    // --- Diagnostic Helpers ---

    private void error(Token token, String message) {
        int line = token != null ? token.line() : 1;
        int col = token != null ? token.column() : 1;
        diagnostics.add(new Diagnostic(
                DiagnosticSeverity.ERROR,
                "ACCENT-S001",
                message,
                line,
                col
        ));
    }

    private void warning(Token token, String code, String message) {
        int line = token != null ? token.line() : 1;
        int col = token != null ? token.column() : 1;
        diagnostics.add(new Diagnostic(
                DiagnosticSeverity.WARNING,
                code,
                message,
                line,
                col
        ));
    }

    private boolean isBooleanType(ResolvedType type) {
        if (type == null) return false;
        String name = type.info().fullName();
        return name.equals("boolean") || name.equals("java.lang.Boolean");
    }

    private void checkMarkupNode(MarkupNode node) {
        if (node instanceof ElementNode el) {
            for (AttributeNode attr : el.attributes()) {
                checkExpression(attr.value());
            }
            for (MarkupNode child : el.children()) {
                checkMarkupNode(child);
            }
        } else if (node instanceof ComponentNode comp) {
            TypeInfo typeInfo = resolve(comp.typeName());
            if (typeInfo == null) {
                error(comp.token(), "Cannot resolve component '" + comp.typeName() + "'.");
                return;
            }
            
            ResolvedType resolvedCompType = new ResolvedType(typeInfo, true, List.of(), 0);
            TypeInfo compInterfaceInfo = symbolTable.getType("accent.html.Component");
            if (compInterfaceInfo == null) {
                error(comp.token(), "Component interface accent.html.Component not found.");
                return;
            }
            ResolvedType componentInterfaceType = new ResolvedType(compInterfaceInfo, true, List.of(), 0);
            if (!resolvedCompType.isCompatibleWith(componentInterfaceType, symbolTable)) {
                error(comp.token(), comp.typeName() + " cannot be used as a component because it does not implement Component.");
            }
            
            Map<String, ResolvedType> argTypes = new HashMap<>();
            Set<String> dupCheck = new HashSet<>();
            for (ComponentArgumentNode arg : comp.arguments()) {
                if (!dupCheck.add(arg.name())) {
                    error(arg.token(), "Component property '" + arg.name() + "' was provided more than once.");
                }
                ResolvedType valType = checkExpression(arg.value());
                argTypes.put(arg.name(), valType);
            }
            
            boolean hasChildren = !comp.children().isEmpty();
            for (MarkupNode child : comp.children()) {
                checkMarkupNode(child);
            }
            
            List<ConstructorInfo> constructors = typeInfo.constructors();
            if (constructors.isEmpty()) {
                error(comp.token(), "Component " + comp.typeName() + " has no accessible constructors.");
                return;
            }
            
            ConstructorInfo bestConstructor = null;
            String matchError = null;
            
            for (ConstructorInfo c : constructors) {
                boolean matches = true;
                boolean childrenMatched = false;
                
                for (Parameter param : c.parameters()) {
                    ResolvedType paramType = symbolTable.resolveTypeNode(param.type(), this);
                    
                    if (paramType != null && paramType.info().fullName().equals("accent.html.HtmlChildren")) {
                        childrenMatched = true;
                        continue;
                    }
                    
                    if (argTypes.containsKey(param.name())) {
                        ResolvedType argType = argTypes.get(param.name());
                        if (argType != null && paramType != null && !argType.isCompatibleWith(paramType, symbolTable)) {
                            matches = false;
                            matchError = "Property '" + param.name() + "' on " + comp.typeName() + " requires " + paramType.info().simpleName() + ", but " + argType.info().simpleName() + " was provided.";
                            break;
                        }
                    } else {
                        if (param.defaultValue().isEmpty()) {
                            matches = false;
                            matchError = "Required property '" + param.name() + "' was not provided to " + comp.typeName() + ".";
                            break;
                        }
                    }
                }
                
                if (matches) {
                    for (String argName : argTypes.keySet()) {
                        boolean paramExists = false;
                        for (Parameter param : c.parameters()) {
                            if (param.name().equals(argName)) {
                                paramExists = true;
                                break;
                            }
                        }
                        if (!paramExists) {
                            matches = false;
                            matchError = "Unknown property '" + argName + "' on component " + comp.typeName() + ".";
                            break;
                        }
                    }
                }
                
                if (matches) {
                    if (hasChildren && !childrenMatched) {
                        matches = false;
                        matchError = comp.typeName() + " does not accept children.";
                    }
                }
                
                if (matches) {
                    bestConstructor = c;
                    break;
                }
            }
            
            if (bestConstructor == null) {
                error(comp.token(), matchError != null ? matchError : "No matching constructor found for component " + comp.typeName() + ".");
            }
            
        } else if (node instanceof TextNode) {
            // Text is safe.
        } else if (node instanceof ExpressionNode en) {
            checkExpression(en.expression());
        } else if (node instanceof IfMarkupNode imn) {
            ResolvedType condType = checkExpression(imn.condition());
            verifyCondition(condType, imn.token());
            for (MarkupNode n : imn.trueBranch()) {
                checkMarkupNode(n);
            }
            for (MarkupNode n : imn.falseBranch()) {
                checkMarkupNode(n);
            }
        } else if (node instanceof ForMarkupNode fmn) {
            pushScope();
            ResolvedType iterableType = checkExpression(fmn.iterable());
            ResolvedType loopVarType;
            if (fmn.parameter().type() instanceof BaseTypeNode base && base.name().equals("var")) {
                if (iterableType != null && iterableType.arrayDimensions() > 0) {
                    loopVarType = iterableType.elementType(symbolTable);
                } else if (iterableType != null && !iterableType.typeArguments().isEmpty()) {
                    loopVarType = iterableType.typeArguments().getFirst();
                } else {
                    loopVarType = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
                }
                loopVarType = loopVarType.withNonNull(loopVarType.isPrimitive() || fmn.parameter().type().isNonNull());
            } else {
                loopVarType = symbolTable.resolveTypeNode(fmn.parameter().type(), this);
            }
            scopes.getFirst().put(fmn.parameter().name(), new LocalVar(fmn.parameter().name(), loopVarType, true, true));
            for (MarkupNode n : fmn.body()) {
                checkMarkupNode(n);
            }
            popScope();
            for (MarkupNode n : fmn.elseBranch()) {
                checkMarkupNode(n);
            }
        } else if (node instanceof FragmentNode fn) {
            for (MarkupNode n : fn.children()) {
                checkMarkupNode(n);
            }
        }
    }

    private void validateSqlSyntax(String query, Token token) {
        String trimmed = query.trim().toUpperCase();
        if (trimmed.isEmpty()) {
            error(token, "SQL query cannot be empty.");
            return;
        }

        // 1. Parenthesis and quote matching
        int parenDepth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (c == '(') parenDepth++;
                else if (c == ')') parenDepth--;
            }
            if (parenDepth < 0) {
                error(token, "Mismatched parentheses in SQL query: found closing ')' without opening '('.");
                return;
            }
        }
        if (inSingleQuote) {
            error(token, "Unterminated single quote in SQL query.");
            return;
        }
        if (inDoubleQuote) {
            error(token, "Unterminated double quote in SQL query.");
            return;
        }
        if (parenDepth > 0) {
            error(token, "Mismatched parentheses in SQL query: unterminated opening '('.");
            return;
        }

        // 2. Keyword check
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) return;
        String firstWord = tokens[0];

        if (firstWord.equals("SELECT")) {
            boolean hasFrom = false;
            for (String t : tokens) {
                if (t.equals("FROM")) {
                    hasFrom = true;
                    break;
                }
            }
            if (hasFrom) {
                int fromIndex = -1;
                for (int i = 0; i < tokens.length; i++) {
                    if (tokens[i].equals("FROM")) {
                        fromIndex = i;
                        break;
                    }
                }
                if (fromIndex == tokens.length - 1) {
                    error(token, "SQL Syntax Error: Expected table name or expression after 'FROM'.");
                }
                if (fromIndex == 1) {
                    error(token, "SQL Syntax Error: Expected select expressions between 'SELECT' and 'FROM'.");
                }
            }
        } else if (firstWord.equals("INSERT")) {
            if (tokens.length < 2 || !tokens[1].equals("INTO")) {
                error(token, "SQL Syntax Error: Expected 'INTO' after 'INSERT'.");
            }
        } else if (firstWord.equals("UPDATE")) {
            boolean hasSet = false;
            for (String t : tokens) {
                if (t.equals("SET")) {
                    hasSet = true;
                    break;
                }
            }
            if (!hasSet) {
                error(token, "SQL Syntax Error: UPDATE query must contain 'SET' clause.");
            }
        } else if (firstWord.equals("DELETE")) {
            if (tokens.length < 2 || !tokens[1].equals("FROM")) {
                error(token, "SQL Syntax Error: Expected 'FROM' after 'DELETE'.");
            }
        }
    }
}
