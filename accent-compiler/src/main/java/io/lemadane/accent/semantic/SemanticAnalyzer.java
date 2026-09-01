package io.lemadane.accent.semantic;

import io.lemadane.accent.ast.Ast.*;
import io.lemadane.accent.diagnostic.Diagnostic;
import io.lemadane.accent.diagnostic.DiagnosticSeverity;
import io.lemadane.accent.lexer.Token;
import io.lemadane.accent.symbol.SymbolTable;
import io.lemadane.accent.symbol.SymbolTable.ResolvedType;
import io.lemadane.accent.symbol.SymbolTable.TypeInfo;
import io.lemadane.accent.symbol.SymbolTable.ConstructorInfo;
import io.lemadane.accent.symbol.SymbolTable.ImportResolver;
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
    
    // Stack of expression contexts for yield checks
    private final Deque<ExpressionContext> expressionContexts = new ArrayDeque<>();

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
        // Resolve simple type name to full type name using package and imports
        if (currentUnit == null) return symbolTable.getType(simpleName);

        // 1. Same package
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        TypeInfo info = symbolTable.getType(fullName);
        if (info != null) return info;

        // 2. Explicit imports
        for (String imp : currentUnit.imports()) {
            if (imp.endsWith("." + simpleName)) {
                info = symbolTable.getType(imp);
                if (info != null) return info;
            }
        }

        // 3. Wildcard imports
        for (String imp : currentUnit.imports()) {
            if (imp.endsWith(".*")) {
                String pkgName = imp.substring(0, imp.length() - 2);
                info = symbolTable.getType(pkgName + "." + simpleName);
                if (info != null) return info;
            }
        }

        // 4. Implicit java.lang
        info = symbolTable.getType("java.lang." + simpleName);
        if (info != null) return info;

        // 5. Raw fallback
        return symbolTable.getType(simpleName);
    }

    public void analyze(CompilationUnit unit) {
        this.currentUnit = unit;
        for (TypeDeclaration decl : unit.declarations()) {
            if (decl instanceof ClassDecl cd) {
                analyzeClass(cd);
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
    }

    private boolean hasLoggingAnnotation(List<String> modifiers) {
        if (modifiers == null) return false;
        if (modifiers.contains("@io.lemadane.accent.logging.Logging")) {
            return true;
        }
        if (modifiers.contains("@Logging")) {
            if (currentUnit == null) return false;
            return currentUnit.imports().contains("io.lemadane.accent.logging.Logging") || currentUnit.imports().contains("io.lemadane.accent.logging.*");
        }
        return false;
    }

    private void injectLoggerScope(List<String> modifiers, List<Member> members, Token errorToken) {
        if (hasLoggingAnnotation(modifiers)) {
            if (members.stream().anyMatch(m -> m instanceof FieldDecl fd && fd.name().equals("log"))) {
                error(errorToken, "Cannot generate logging field 'log' because the type already declares a field named 'log'.");
            }
            TypeInfo loggerInfo = symbolTable.getType("io.lemadane.accent.logging.Logger");
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
                    "io.lemadane.accent.logging.Logger", false, false, false, false, Optional.empty(), List.of(),
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

    private void analyzeClass(ClassDecl cd) {
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? cd.name() : pkg + "." + cd.name();
        this.currentClass = symbolTable.getType(fullName);
        
        pushScope();
        injectLoggerScope(cd.modifiers(), cd.members(), null);

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

        for (Member member : rd.members()) {
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
                    scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true)); // All parameters are immutable
                }

                md.body().ifPresent(this::checkStatement);
                popScope();
            }
        }
    }

    private void analyzeMember(Member member) {
        if (member instanceof FieldDecl fd) {
            isStaticContext = fd.modifiers().contains("static");
            fd.initializer().ifPresent(this::checkExpression);
        } else if (member instanceof ConstructorDecl cd) {
            isStaticContext = false;
            pushScope();
            for (Parameter param : cd.parameters()) {
                ResolvedType pType = symbolTable.resolveTypeNode(param.type(), this);
                scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true));
            }
            checkStatement(cd.body());
            popScope();
        } else if (member instanceof MethodDecl md) {
            isStaticContext = md.modifiers().contains("static");
            pushScope();
            for (Parameter param : md.parameters()) {
                ResolvedType pType = symbolTable.resolveTypeNode(param.type(), this);
                scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true));
            }
            md.body().ifPresent(this::checkStatement);
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
        } else if (stmt instanceof LocalVarDeclStmt varDecl) {
            ResolvedType type = null;
            if (varDecl.type().isPresent()) {
                type = symbolTable.resolveTypeNode(varDecl.type().get(), this);
            }

            ResolvedType initType = null;
            if (varDecl.initializer().isPresent()) {
                initType = checkExpression(varDecl.initializer().get());
                if (type == null) {
                    type = initType; // Type inference
                }
            }

            if (type == null) {
                error(null, "Cannot infer type of variable '" + varDecl.name() + "' without initializer.");
                TypeInfo objInfo = symbolTable.getType("java.lang.Object");
                type = new ResolvedType(objInfo, false, List.of(), 0);
            }

            if (varDecl.initializer().isPresent() && initType != null) {
                if (!initType.isCompatibleWith(type, symbolTable)) {
                    error(null, "Incompatible types: expected " + type + ", actual " + initType);
                }
            }

            // Shadows check
            scopes.getFirst().put(varDecl.name(), new LocalVar(varDecl.name(), type, varDecl.isFinal(), false));
        } else if (stmt instanceof IfStmt ifs) {
            ResolvedType condType = checkExpression(ifs.condition());
            verifyCondition(condType, ifs.condition().token());

            // Null checking flow refinement
            boolean refinedNonNull = false;
            String refinedVarName = "";
            
            // Check direct null comparison: x != null
            if (ifs.condition() instanceof BinaryExpr bin && bin.operator().type() == io.lemadane.accent.lexer.TokenType.BANG_EQUAL) {
                if (bin.left() instanceof IdentifierExpr id && bin.right() instanceof LiteralExpr lit && lit.value() == null) {
                    refinedVarName = id.name();
                    refinedNonNull = true;
                } else if (bin.right() instanceof IdentifierExpr id && bin.left() instanceof LiteralExpr lit && lit.value() == null) {
                    refinedVarName = id.name();
                    refinedNonNull = true;
                }
            }

            if (refinedNonNull) {
                LocalVar v = lookupVar(refinedVarName);
                if (v != null) {
                    boolean original = v.isRefinedNonNull;
                    v.isRefinedNonNull = true;
                    checkStatement(ifs.thenBranch());
                    v.isRefinedNonNull = original; // Restore
                } else {
                    checkStatement(ifs.thenBranch());
                }
            } else {
                checkStatement(ifs.thenBranch());
            }

            ifs.elseBranch().ifPresent(this::checkStatement);
        } else if (stmt instanceof ForStmt fs) {
            pushScope();
            fs.init().ifPresent(this::checkStatement);
            fs.condition().ifPresent(this::checkExpression);
            fs.update().ifPresent(this::checkExpression);
            checkStatement(fs.body());
            popScope();
        } else if (stmt instanceof ForEachStmt fes) {
            ResolvedType iterType = checkExpression(fes.iterable());
            ResolvedType elemType;
            if (fes.parameter().type() instanceof BaseTypeNode base && base.name().equals("var")) {
                if (iterType != null && iterType.info().fullName().equals("java.util.List") && !iterType.typeArguments().isEmpty()) {
                    elemType = iterType.typeArguments().get(0);
                } else if (iterType != null && iterType.arrayDimensions() > 0) {
                    elemType = new ResolvedType(iterType.info(), iterType.isNonNull(), iterType.typeArguments(), iterType.arrayDimensions() - 1);
                } else {
                    elemType = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
                }
            } else {
                elemType = symbolTable.resolveTypeNode(fes.parameter().type(), this);
            }

            pushScope();
            // Enhance parameter is implicitly final and parameter-like (immutable)
            scopes.getFirst().put(fes.parameter().name(), new LocalVar(fes.parameter().name(), elemType, true, true));
            checkStatement(fes.body());
            popScope();
        } else if (stmt instanceof WhileStmt ws) {
            checkExpression(ws.condition());
            checkStatement(ws.body());
        } else if (stmt instanceof DoWhileStmt dws) {
            checkStatement(dws.body());
            checkExpression(dws.condition());
        } else if (stmt instanceof TryStmt ts) {
            checkStatement(ts.body());
            for (CatchClause cc : ts.catchClauses()) {
                pushScope();
                ResolvedType exType = symbolTable.resolveTypeNode(cc.parameter().type(), this);
                scopes.getFirst().put(cc.parameter().name(), new LocalVar(cc.parameter().name(), exType, true, true));
                checkStatement(cc.body());
                popScope();
            }
            ts.finallyBlock().ifPresent(this::checkStatement);
        } else if (stmt instanceof ReturnStmt rs) {
            rs.expression().ifPresent(this::checkExpression);
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
            // Warning for ignored future
            if (type != null && type.info().fullName().equals("io.lemadane.accent.runtime.AccentFuture")) {
                warning(es.expression().token(), "ACCENT-W001", "Unobserved or ignored async future. Consider assigning it to a variable or using 'await'.");
            }
        }
    }

    private void verifyCondition(ResolvedType type, Token token) {
        if (type == null || !type.info().fullName().equals("boolean")) {
            error(token, "Condition must be of boolean type.");
        }
    }

    public final Map<Expression, ResolvedType> resolvedTypes = new java.util.IdentityHashMap<>();

    private ResolvedType checkExpression(Expression expr) {
        if (expr == null) return null;
        ResolvedType type = checkExpressionInternal(expr);
        if (type != null) {
            resolvedTypes.put(expr, type);
        }
        return type;
    }

    private ResolvedType checkExpressionInternal(Expression expr) {
        if (expr instanceof LiteralExpr lit) {
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
            return new ResolvedType(symbolTable.getType("io.lemadane.accent.html.Html"), true, List.of(), 0);
        } else if (expr instanceof IdentifierExpr id) {
            LocalVar v = lookupVar(id.name());
            if (v != null) {
                if (v.isRefinedNonNull) {
                    return v.type.withNonNull(true);
                }
                return v.type;
            }

            // Check field access on current class (missing this. check)
            if (currentClass != null && !isStaticContext) {
                boolean hasField = currentClass.fields().stream().anyMatch(f -> f.name().equals(id.name()));
                if (hasField) {
                    error(id.token(), "Access to field '" + id.name() + "' must be explicitly qualified with 'this.'.");
                    // Recover type
                    SymbolTable.FieldInfo fInfo = currentClass.fields().stream().filter(f -> f.name().equals(id.name())).findFirst().get();
                    return symbolTable.resolveTypeNode(fInfo.type(), this);
                }
            }
            
            // Assume package name or class name reference
            TypeInfo classInfo = resolve(id.name());
            if (classInfo != null) {
                return new ResolvedType(classInfo, true, List.of(), 0);
            }

            error(id.token(), "Cannot resolve symbol '" + id.name() + "'.");
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
            if (currentClass != null && currentClass.superclass().isPresent()) {
                return new ResolvedType(symbolTable.getType(currentClass.superclass().get()), true, List.of(), 0);
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
            ResolvedType right = checkExpression(bin.right());

            if (bin.operator().type() == io.lemadane.accent.lexer.TokenType.ASSIGN) {
                // Reassignment checks
                if (bin.left() instanceof IdentifierExpr id) {
                    LocalVar v = lookupVar(id.name());
                    if (v != null) {
                        if (v.isParameter) {
                            error(bin.operator(), "Reassigning method or constructor parameter '" + id.name() + "' is not permitted.");
                        } else if (v.isFinal) {
                            error(bin.operator(), "Reassignment of final local variable '" + id.name() + "'.");
                        }
                    }
                }
                
                if (left != null && right != null && !right.isCompatibleWith(left, symbolTable)) {
                    error(bin.operator(), "Incompatible types in assignment: expected " + left + ", actual " + right);
                }
                return left;
            }

            if (bin.operator().type() == io.lemadane.accent.lexer.TokenType.NULL_COALESCING) {
                // left ?? right
                if (left != null && right != null) {
                    boolean isNonNull = left.isNonNull() || right.isNonNull();
                    return getCommonSupertype(left, right).withNonNull(isNonNull);
                }
            }

            io.lemadane.accent.lexer.TokenType opType = bin.operator().type();

            // Textual boolean-only operators: require boolean operands on both sides
            if (opType == io.lemadane.accent.lexer.TokenType.AND  ||
                opType == io.lemadane.accent.lexer.TokenType.OR   ||
                opType == io.lemadane.accent.lexer.TokenType.NAND ||
                opType == io.lemadane.accent.lexer.TokenType.NOR  ||
                opType == io.lemadane.accent.lexer.TokenType.XOR  ||
                opType == io.lemadane.accent.lexer.TokenType.XNOR) {
                String opName = bin.operator().lexeme();
                if (left != null && !isBooleanType(left)) {
                    error(bin.operator(), "Operator '" + opName + "' requires boolean operands, but found " + left.info().simpleName() + " on the left.");
                }
                if (right != null && !isBooleanType(right)) {
                    error(bin.operator(), "Operator '" + opName + "' requires boolean operands, but found " + right.info().simpleName() + " on the right.");
                }
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }

            if (opType == io.lemadane.accent.lexer.TokenType.EQUAL_EQUAL || opType == io.lemadane.accent.lexer.TokenType.BANG_EQUAL ||
                opType == io.lemadane.accent.lexer.TokenType.LESS || opType == io.lemadane.accent.lexer.TokenType.LESS_EQUAL ||
                opType == io.lemadane.accent.lexer.TokenType.GREATER || opType == io.lemadane.accent.lexer.TokenType.GREATER_EQUAL ||
                opType == io.lemadane.accent.lexer.TokenType.AND_AND || opType == io.lemadane.accent.lexer.TokenType.OR_OR) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }

            // Primitive numeric operations
            return left;
        } else if (expr instanceof UnaryExpr un) {
            ResolvedType type = checkExpression(un.expression());
            // Textual 'not' — requires a boolean operand
            if (un.operator().type() == io.lemadane.accent.lexer.TokenType.NOT) {
                if (type != null && !isBooleanType(type)) {
                    error(un.operator(), "Operator 'not' requires a boolean operand, but found " + type.info().simpleName() + ".");
                }
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }
            if (un.operator().type() == io.lemadane.accent.lexer.TokenType.PLUS_PLUS || un.operator().type() == io.lemadane.accent.lexer.TokenType.MINUS_MINUS) {
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
            ResolvedType rec = checkExpression(ma.receiver());
            if (rec != null) {
                // Resolve member
                SymbolTable.FieldInfo fInfo = rec.info().fields().stream().filter(f -> f.name().equals(ma.memberName())).findFirst().orElse(null);
                if (fInfo != null) {
                    ResolvedType fType = symbolTable.resolveTypeNode(fInfo.type(), this);
                    if (ma.isOptional()) {
                        return fType.withNonNull(false); // Optional chain result is nullable
                    }
                    return fType;
                }
                error(ma.token(), "Cannot resolve field '" + ma.memberName() + "' on type " + rec);
            }
        } else if (expr instanceof MethodCallExpr mc) {
            ResolvedType rec = null;
            if (mc.receiver() != null) {
                rec = checkExpression(mc.receiver());
            } else {
                // Implicit method call (must verify if it's an instance method of this)
                if (currentClass != null && !isStaticContext) {
                    boolean hasMethod = currentClass.methods().stream().anyMatch(m -> m.name().equals(mc.methodName()));
                    if (hasMethod) {
                        error(mc.token(), "Call to instance method '" + mc.methodName() + "' must be explicitly qualified with 'this.'.");
                    }
                }
                rec = new ResolvedType(currentClass, true, List.of(), 0);
            }

            List<ResolvedType> argTypes = new ArrayList<>();
            for (Expression arg : mc.arguments()) {
                argTypes.add(checkExpression(arg));
            }

            if (rec != null) {
                // 1. Resolve normal instance/static methods
                SymbolTable.MethodInfo mInfo = rec.info().methods().stream()
                        .filter(m -> m.name().equals(mc.methodName()))
                        .findFirst().orElse(null);

                if (mInfo != null) {
                    ResolvedType retType = symbolTable.resolveTypeNode(mInfo.returnType(), this);
                    if (mc.isOptional()) {
                        retType = retType.withNonNull(false);
                    }
                    return retType;
                }

                // 2. Resolve extension methods
                for (ExtensionDecl ext : symbolTable.extensions()) {
                    ResolvedType extTarget = symbolTable.resolveTypeNode(ext.targetType(), this);
                    if (rec.isCompatibleWith(extTarget, symbolTable)) {
                        SymbolTable.MethodInfo extMethod = ext.members().stream()
                                .filter(m -> m instanceof MethodDecl)
                                .map(m -> (MethodDecl) m)
                                .filter(m -> m.name().equals(mc.methodName()))
                                .map(m -> new SymbolTable.MethodInfo(m.name(), m.modifiers(), m.returnType(), m.parameters()))
                                .findFirst().orElse(null);

                        if (extMethod != null) {
                            ResolvedType retType = symbolTable.resolveTypeNode(extMethod.returnType(), this);
                            if (mc.isOptional()) {
                                retType = retType.withNonNull(false);
                            }
                            return retType;
                        }
                    }
                }

                error(mc.token(), "Cannot resolve method '" + mc.methodName() + "' on type " + rec);
            }
        } else if (expr instanceof NewObjectExpr no) {
            ResolvedType type = symbolTable.resolveTypeNode(no.type(), this);
            for (Expression arg : no.arguments()) {
                checkExpression(arg);
            }
            return type;
        } else if (expr instanceof NewArrayExpr na) {
            ResolvedType type = symbolTable.resolveTypeNode(na.type(), this);
            for (Expression dim : na.dimensions()) {
                checkExpression(dim);
            }
            na.initializer().ifPresent(this::checkExpression);
            return type;
        } else if (expr instanceof ArrayInitializerExpr ai) {
            for (Expression element : ai.expressions()) {
                checkExpression(element);
            }
            // Return Object array fallback or let type inference handle
            TypeInfo obj = symbolTable.getType("java.lang.Object");
            return new ResolvedType(obj, true, List.of(), 1);
        } else if (expr instanceof ArrayAccessExpr aa) {
            ResolvedType rec = checkExpression(aa.receiver());
            checkExpression(aa.index());
            if (rec != null && rec.arrayDimensions() > 0) {
                return new ResolvedType(rec.info(), false, rec.typeArguments(), rec.arrayDimensions() - 1);
            }
        } else if (expr instanceof LambdaExpr le) {
            pushScope();
            for (Parameter param : le.parameters()) {
                ResolvedType pType = symbolTable.resolveTypeNode(param.type(), this);
                scopes.getFirst().put(param.name(), new LocalVar(param.name(), pType, true, true));
            }
            if (le.body() instanceof Expression bodyExpr) {
                checkExpression(bodyExpr);
            } else if (le.body() instanceof Statement bodyStmt) {
                checkStatement(bodyStmt);
            }
            popScope();
            // Lambdas map to functional interface
            TypeInfo funcItf = symbolTable.getType("java.lang.Runnable");
            return new ResolvedType(funcItf, true, List.of(), 0);
        } else if (expr instanceof AsyncExpr ae) {
            ResolvedType type = checkExpression(ae.expression());
            TypeInfo fut = symbolTable.getType("io.lemadane.accent.runtime.AccentFuture");
            return new ResolvedType(fut, true, List.of(type), 0);
        } else if (expr instanceof AwaitExpr awe) {
            ResolvedType type = checkExpression(awe.expression());
            
            // Warnings check
            if (awe.expression() instanceof AsyncExpr) {
                warning(awe.token(), "ACCENT-W002", "Immediate 'await async' pattern detected. Concurrency is not gained. Prefer synchronous calls.");
            }

            if (type != null && type.info().fullName().equals("io.lemadane.accent.runtime.AccentFuture")) {
                if (!type.typeArguments().isEmpty()) {
                    return type.typeArguments().get(0);
                }
                TypeInfo obj = symbolTable.getType("java.lang.Object");
                return new ResolvedType(obj, false, List.of(), 0);
            } else {
                error(awe.token(), "Awaiting a non-future value of type: " + type);
            }
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
            TypeInfo compInterfaceInfo = symbolTable.getType("io.lemadane.accent.html.Component");
            if (compInterfaceInfo == null) {
                error(comp.token(), "Component interface io.lemadane.accent.html.Component not found.");
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
                    
                    if (paramType != null && paramType.info().fullName().equals("io.lemadane.accent.html.HtmlChildren")) {
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
            checkExpression(fmn.iterable());
            ResolvedType loopVarType = symbolTable.resolveTypeNode(fmn.parameter().type(), this);
            scopes.getFirst().put(fmn.parameter().name(), new LocalVar(fmn.parameter().name(), loopVarType, true, true));
            for (MarkupNode n : fmn.body()) {
                checkMarkupNode(n);
            }
            popScope();
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
