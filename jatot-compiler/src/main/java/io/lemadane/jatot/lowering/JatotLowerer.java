package io.lemadane.jatot.lowering;

import io.lemadane.jatot.ast.Ast.*;
import io.lemadane.jatot.symbol.SymbolTable;
import io.lemadane.jatot.symbol.SymbolTable.ResolvedType;
import io.lemadane.jatot.symbol.SymbolTable.TypeInfo;
import io.lemadane.jatot.symbol.SymbolTable.ConstructorInfo;
import io.lemadane.jatot.symbol.SymbolTable.ImportResolver;
import io.lemadane.jatot.lexer.Token;
import io.lemadane.jatot.lexer.TokenType;
import java.util.*;

public final class JatotLowerer implements ImportResolver {
    private final SymbolTable symbolTable;
    
    // State for name resolution
    private CompilationUnit currentUnit;
    
    // Scopes for type checking during lowering
    private final List<Map<String, ResolvedType>> scopes = new ArrayList<>();
    private TypeInfo currentClass;
    private boolean isStaticContext;

    // Generated statements to insert before the current statement
    private final List<Statement> pendingStatements = new ArrayList<>();
    private int tempVarCounter = 0;

    public JatotLowerer(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    @Override
    public TypeInfo resolve(String simpleName) {
        if (currentUnit == null) return symbolTable.getType(simpleName);
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        TypeInfo info = symbolTable.getType(fullName);
        if (info != null) return info;

        for (String imp : currentUnit.imports()) {
            if (imp.endsWith("." + simpleName)) {
                info = symbolTable.getType(imp);
                if (info != null) return info;
            }
        }
        for (String imp : currentUnit.imports()) {
            if (imp.endsWith(".*")) {
                String pkgName = imp.substring(0, imp.length() - 2);
                info = symbolTable.getType(pkgName + "." + simpleName);
                if (info != null) return info;
            }
        }
        info = symbolTable.getType("java.lang." + simpleName);
        if (info != null) return info;
        return symbolTable.getType(simpleName);
    }

    private void pushScope() {
        scopes.addFirst(new HashMap<>());
    }

    private void popScope() {
        scopes.removeFirst();
    }

    private ResolvedType lookupVarType(String name) {
        for (Map<String, ResolvedType> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

    public CompilationUnit lower(CompilationUnit unit) {
        this.currentUnit = unit;
        List<TypeDeclaration> declarations = new ArrayList<>();
        for (TypeDeclaration decl : unit.declarations()) {
            declarations.add(lowerTypeDeclaration(decl));
        }
        this.currentUnit = null;
        return new CompilationUnit(unit.sourceFile(), unit.packageName(), unit.imports(), declarations, unit.tokens());
    }

    private boolean hasLoggingAnnotation(List<String> modifiers) {
        if (modifiers == null) return false;
        if (modifiers.contains("@io.lemadane.jatot.logging.Logging")) {
            return true;
        }
        if (modifiers.contains("@Logging")) {
            if (currentUnit == null) return false;
            return currentUnit.imports().contains("io.lemadane.jatot.logging.Logging") || currentUnit.imports().contains("io.lemadane.jatot.logging.*");
        }
        return false;
    }

    private void injectLoggerField(List<String> modifiers, List<Member> members, String className) {
        if (hasLoggingAnnotation(modifiers)) {
            TypeNode type = new BaseTypeNode("io.lemadane.jatot.logging.Logger", List.of(), true);
            Expression classRef = new IdentifierExpr(className, null);
            Expression classDotClass = new MemberAccessExpr(classRef, "class", false, null);
            Expression logManager = new IdentifierExpr("io.lemadane.jatot.logging.LogManager", null);
            Expression getLogger = new MethodCallExpr(logManager, "getLogger", List.of(), List.of(classDotClass), false, null);
            FieldDecl logField = new FieldDecl(List.of("private", "static", "final"), type, "log", Optional.of(getLogger));
            members.add(logField);
        }
    }

    private TypeDeclaration lowerTypeDeclaration(TypeDeclaration decl) {
        String pkg = currentUnit.packageName().orElse("");
        String fullName = pkg.isEmpty() ? decl.name() : pkg + "." + decl.name();
        
        if (decl instanceof ExtensionDecl ed) {
            this.currentClass = null;
            List<Member> members = new ArrayList<>();
            for (Member m : ed.members()) {
                members.add(lowerMember(m));
            }
            return new ExtensionDecl(ed.targetType(), members);
        }

        this.currentClass = symbolTable.getType(fullName);

        List<Member> members = new ArrayList<>();
        injectLoggerField(decl.modifiers(), members, decl.name());

        for (Member m : decl.members()) {
            members.add(lowerMember(m));
        }

        this.currentClass = null;

        if (decl instanceof ClassDecl cd) {
            return new ClassDecl(cd.modifiers(), cd.name(), cd.typeParameters(), cd.superclass(), cd.interfaces(), members);
        } else if (decl instanceof InterfaceDecl id) {
            return new InterfaceDecl(id.modifiers(), id.name(), id.typeParameters(), id.interfaces(), members);
        } else if (decl instanceof RecordDecl rd) {
            return new RecordDecl(rd.modifiers(), rd.name(), rd.typeParameters(), rd.components(), rd.interfaces(), members);
        } else if (decl instanceof EnumDecl ed) {
            return new EnumDecl(ed.modifiers(), ed.name(), ed.interfaces(), ed.constants(), members);
        }
        return decl;
    }

    private Member lowerMember(Member member) {
        if (member instanceof FieldDecl fd) {
            isStaticContext = fd.modifiers().contains("static");
            Optional<Expression> init = fd.initializer().map(this::lowerExpressionAndLift);
            TypeNode type = fd.type();
            if (currentClass != null && (type instanceof BaseTypeNode base && (base.name().equals("final") || base.name().equals("var")))) {
                SymbolTable.FieldInfo fInfo = currentClass.fields().stream().filter(f -> f.name().equals(fd.name())).findFirst().orElse(null);
                if (fInfo != null) {
                    type = fInfo.type();
                }
            }
            return new FieldDecl(fd.modifiers(), type, fd.name(), init);
        } else if (member instanceof ConstructorDecl cd) {
            isStaticContext = false;
            pushScope();
            for (Parameter p : cd.parameters()) {
                scopes.getFirst().put(p.name(), symbolTable.resolveTypeNode(p.type(), this));
            }
            BlockStmt body = lowerBlock(cd.body());
            popScope();
            return new ConstructorDecl(cd.modifiers(), cd.typeParameters(), cd.parameters(), cd.throwsList(), body);
        } else if (member instanceof MethodDecl md) {
            isStaticContext = md.modifiers().contains("static");
            pushScope();
            for (Parameter p : md.parameters()) {
                scopes.getFirst().put(p.name(), symbolTable.resolveTypeNode(p.type(), this));
            }
            Optional<BlockStmt> body = md.body().map(this::lowerBlock);
            popScope();
            return new MethodDecl(md.modifiers(), md.typeParameters(), md.isGenerator(), md.returnType(), md.name(), md.parameters(), md.throwsList(), body);
        }
        return member;
    }

    private BlockStmt lowerBlock(BlockStmt block) {
        pushScope();
        List<Statement> stmtList = new ArrayList<>();
        for (Statement s : block.statements()) {
            stmtList.addAll(lowerStatement(s));
        }
        popScope();
        return new BlockStmt(stmtList);
    }

    private List<Statement> lowerStatement(Statement stmt) {
        List<Statement> savedPending = new ArrayList<>(pendingStatements);
        pendingStatements.clear();

        Statement result;
        if (stmt instanceof BlockStmt bs) {
            result = lowerBlock(bs);
        } else if (stmt instanceof LocalVarDeclStmt varDecl) {
            ResolvedType type = varDecl.type().map(t -> symbolTable.resolveTypeNode(t, this)).orElse(null);
            Optional<Expression> init = varDecl.initializer().map(this::lowerExpression);
            if (type == null && init.isPresent()) {
                type = getTypeOf(init.get());
            }
            if (type != null) {
                scopes.getFirst().put(varDecl.name(), type);
            }
            result = new LocalVarDeclStmt(varDecl.isFinal(), varDecl.isInferred(), varDecl.type(), varDecl.name(), init);
        } else if (stmt instanceof IfStmt ifs) {
            Expression cond = lowerExpression(ifs.condition());
            Statement thenB = lowerBlock(ifs.thenBranch() instanceof BlockStmt ? (BlockStmt) ifs.thenBranch() : new BlockStmt(List.of(ifs.thenBranch())));
            Optional<Statement> elseB = ifs.elseBranch().map(eb -> {
                if (eb instanceof BlockStmt) return lowerBlock((BlockStmt) eb);
                if (eb instanceof IfStmt) return lowerStatement(eb).get(0);
                return new BlockStmt(lowerStatement(eb));
            });
            result = new IfStmt(cond, thenB, elseB);
        } else if (stmt instanceof ForStmt fs) {
            pushScope();
            Optional<Statement> init = fs.init().map(i -> lowerStatement(i).get(0)); // simple single init
            Optional<Expression> cond = fs.condition().map(this::lowerExpression);
            Optional<Expression> upd = fs.update().map(this::lowerExpression);
            Statement body = lowerBlock(fs.body() instanceof BlockStmt ? (BlockStmt) fs.body() : new BlockStmt(List.of(fs.body())));
            popScope();
            result = new ForStmt(init, cond, upd, body);
        } else if (stmt instanceof ForEachStmt fes) {
            Expression iter = lowerExpression(fes.iterable());
            pushScope();
            ResolvedType pType;
            if (fes.parameter().type() instanceof BaseTypeNode base && base.name().equals("var")) {
                ResolvedType iterType = getTypeOf(iter);
                if (iterType != null && iterType.info().fullName().equals("java.util.List") && !iterType.typeArguments().isEmpty()) {
                    pType = iterType.typeArguments().get(0);
                } else if (iterType != null && iterType.arrayDimensions() > 0) {
                    pType = new ResolvedType(iterType.info(), iterType.isNonNull(), iterType.typeArguments(), iterType.arrayDimensions() - 1);
                } else {
                    pType = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
                }
            } else {
                pType = symbolTable.resolveTypeNode(fes.parameter().type(), this);
            }
            scopes.getFirst().put(fes.parameter().name(), pType);
            Statement body = lowerBlock(fes.body() instanceof BlockStmt ? (BlockStmt) fes.body() : new BlockStmt(List.of(fes.body())));
            popScope();
            result = new ForEachStmt(fes.parameter(), iter, body);
        } else if (stmt instanceof WhileStmt ws) {
            Expression cond = lowerExpression(ws.condition());
            Statement body = lowerBlock(ws.body() instanceof BlockStmt ? (BlockStmt) ws.body() : new BlockStmt(List.of(ws.body())));
            result = new WhileStmt(cond, body);
        } else if (stmt instanceof DoWhileStmt dws) {
            Statement body = lowerBlock(dws.body() instanceof BlockStmt ? (BlockStmt) dws.body() : new BlockStmt(List.of(dws.body())));
            Expression cond = lowerExpression(dws.condition());
            result = new DoWhileStmt(body, cond);
        } else if (stmt instanceof TryStmt ts) {
            BlockStmt body = lowerBlock(ts.body());
            List<CatchClause> catches = new ArrayList<>();
            for (CatchClause cc : ts.catchClauses()) {
                pushScope();
                ResolvedType exType = symbolTable.resolveTypeNode(cc.parameter().type(), this);
                scopes.getFirst().put(cc.parameter().name(), exType);
                BlockStmt catchBody = lowerBlock(cc.body());
                popScope();
                catches.add(new CatchClause(cc.parameter(), catchBody));
            }
            Optional<BlockStmt> finallyBlock = ts.finallyBlock().map(this::lowerBlock);
            result = new TryStmt(body, catches, finallyBlock);
        } else if (stmt instanceof ReturnStmt rs) {
            result = new ReturnStmt(rs.expression().map(this::lowerExpression));
        } else if (stmt instanceof YieldStmt ys) {
            result = new YieldStmt(lowerExpression(ys.expression()));
        } else if (stmt instanceof ThrowStmt ts) {
            result = new ThrowStmt(lowerExpression(ts.expression()));
        } else if (stmt instanceof EmitStmt es) {
            result = new EmitStmt(lowerExpression(es.expression()));
        } else if (stmt instanceof ExprStmt es) {
            result = new ExprStmt(lowerExpression(es.expression()));
        } else {
            result = stmt;
        }

        List<Statement> output = new ArrayList<>(pendingStatements);
        output.add(result);

        pendingStatements.clear();
        pendingStatements.addAll(savedPending);

        return output;
    }

    private Expression lowerExpressionAndLift(Expression expr) {
        Expression lowered = lowerExpression(expr);
        // If there are pending statements from the expression, we wrap it or return it.
        // For fields, we cannot inject pending statements, so they shouldn't contain block expressions anyway.
        return lowered;
    }

    private String nextTempVar() {
        return "temp$" + (tempVarCounter++);
    }

    private Expression lowerExpression(Expression expr) {
        if (expr instanceof LiteralExpr || expr instanceof IdentifierExpr || expr instanceof ThisExpr || expr instanceof SuperExpr) {
            return expr;
        } else if (expr instanceof TernaryExpr tern) {
            return new TernaryExpr(
                    lowerExpression(tern.condition()),
                    lowerExpression(tern.thenBranch()),
                    lowerExpression(tern.elseBranch()),
                    tern.token()
            );
        } else if (expr instanceof SqlExpr sql) {
            List<Expression> loweredInterpolations = new ArrayList<>();
            for (Expression param : sql.interpolations()) {
                loweredInterpolations.add(lowerExpression(param));
            }
            return new SqlExpr(sql.query(), loweredInterpolations, sql.resultType(), sql.token());
        } else if (expr instanceof InterpolatedStringExpression interp) {
            List<InterpolatedStringPart> loweredParts = new ArrayList<>();
            for (InterpolatedStringPart part : interp.parts()) {
                if (part instanceof InterpolatedTextPart itp) {
                    loweredParts.add(itp);
                } else if (part instanceof InterpolatedExpressionPart iep) {
                    loweredParts.add(new InterpolatedExpressionPart(lowerExpression(iep.expression())));
                }
            }
            return new InterpolatedStringExpression(loweredParts, interp.token());
        } else if (expr instanceof BinaryExpr bin) {
            if (bin.operator().type() == TokenType.NULL_COALESCING) {
                // Lower left and right
                Expression left = lowerExpression(bin.left());
                Expression right = lowerExpression(bin.right());
                ResolvedType type = getTypeOf(left);

                String tempVarName = nextTempVar();
                Token checkOp = new Token(TokenType.BANG_EQUAL, "!=", bin.operator().line(), bin.operator().column());
                Expression cond = new BinaryExpr(tempAssign(tempVarName, left, type), checkOp, new LiteralExpr(null, null));
                
                BlockStmt thenBranch = new BlockStmt(List.of(new YieldStmt(new IdentifierExpr(tempVarName, null))));
                BlockStmt elseBranch = new BlockStmt(List.of(new YieldStmt(right)));
                
                // Return the IfExpr which will be lowered next!
                return lowerExpression(new IfExpr(cond, thenBranch, elseBranch, bin.operator()));
            }

            return new BinaryExpr(lowerExpression(bin.left()), bin.operator(), lowerExpression(bin.right()));
        } else if (expr instanceof UnaryExpr un) {
            return new UnaryExpr(un.operator(), lowerExpression(un.expression()), un.isPostfix());
        } else if (expr instanceof MemberAccessExpr ma) {
            if (ma.isOptional()) {
                // receiver?.memberName
                // Lift optional chain!
                Expression rec = lowerExpression(ma.receiver());
                ResolvedType type = getTypeOf(rec);
                String tempVarName = nextTempVar();
                
                Token checkOp = new Token(TokenType.BANG_EQUAL, "!=", ma.token().line(), ma.token().column());
                Expression cond = new BinaryExpr(tempAssign(tempVarName, rec, type), checkOp, new LiteralExpr(null, null));
                
                BlockStmt thenBranch = new BlockStmt(List.of(new YieldStmt(new MemberAccessExpr(new IdentifierExpr(tempVarName, null), ma.memberName(), false, ma.token()))));
                BlockStmt elseBranch = new BlockStmt(List.of(new YieldStmt(new LiteralExpr(null, null))));
                
                return lowerExpression(new IfExpr(cond, thenBranch, elseBranch, ma.token()));
            }
            return new MemberAccessExpr(lowerExpression(ma.receiver()), ma.memberName(), false, ma.token());
        } else if (expr instanceof MethodCallExpr mc) {
            if (mc.isOptional()) {
                // receiver?.methodName(args)
                Expression rec = lowerExpression(mc.receiver());
                ResolvedType type = getTypeOf(rec);
                String tempVarName = nextTempVar();
                
                Token checkOp = new Token(TokenType.BANG_EQUAL, "!=", mc.token().line(), mc.token().column());
                Expression cond = new BinaryExpr(tempAssign(tempVarName, rec, type), checkOp, new LiteralExpr(null, null));
                
                List<Parameter> params = List.of();
                if (type != null) {
                    SymbolTable.MethodInfo mInfo = type.info().methods().stream()
                            .filter(m -> m.name().equals(mc.methodName()))
                            .findFirst().orElse(null);
                    if (mInfo != null) {
                        params = mInfo.parameters();
                    }
                }
                List<Expression> args = resolveArguments(params, mc.arguments());

                BlockStmt thenBranch = new BlockStmt(List.of(new YieldStmt(
                        new MethodCallExpr(new IdentifierExpr(tempVarName, null), mc.methodName(), mc.typeArguments(), args, false, mc.token())
                )));
                BlockStmt elseBranch = new BlockStmt(List.of(new YieldStmt(new LiteralExpr(null, null))));
                
                return lowerExpression(new IfExpr(cond, thenBranch, elseBranch, mc.token()));
            }

            Expression rec = mc.receiver() != null ? lowerExpression(mc.receiver()) : null;
            List<Parameter> params = List.of();
            boolean isExtension = false;
            String extClassName = null;
            MethodDecl extMethod = null;
            
            if (rec != null) {
                ResolvedType recType = getTypeOf(rec);
                if (recType != null) {
                    SymbolTable.MethodInfo mInfo = recType.info().methods().stream()
                            .filter(m -> m.name().equals(mc.methodName()))
                            .findFirst().orElse(null);
                    if (mInfo != null) {
                        params = mInfo.parameters();
                    } else {
                        // Check extension
                        for (ExtensionDecl ext : symbolTable.extensions()) {
                            ResolvedType extTarget = symbolTable.resolveTypeNode(ext.targetType(), this);
                            if (recType.isCompatibleWith(extTarget, symbolTable)) {
                                extMethod = ext.members().stream()
                                        .filter(m -> m instanceof MethodDecl)
                                        .map(m -> (MethodDecl) m)
                                        .filter(m -> m.name().equals(mc.methodName()))
                                        .findFirst().orElse(null);
                                if (extMethod != null) {
                                    isExtension = true;
                                    extClassName = ext.name();
                                    params = extMethod.parameters();
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                // Implicit this
                if (currentClass != null) {
                    SymbolTable.MethodInfo mInfo = currentClass.methods().stream()
                            .filter(m -> m.name().equals(mc.methodName()))
                            .findFirst().orElse(null);
                    if (mInfo != null) {
                        params = mInfo.parameters();
                    }
                }
            }

            List<Expression> resolvedArgs = resolveArguments(params, mc.arguments());
            
            if (isExtension) {
                List<Expression> staticArgs = new ArrayList<>();
                staticArgs.add(rec);
                staticArgs.addAll(resolvedArgs);
                return new MethodCallExpr(new IdentifierExpr(extClassName, null), mc.methodName(), mc.typeArguments(), staticArgs, false, mc.token());
            }

            return new MethodCallExpr(rec, mc.methodName(), mc.typeArguments(), resolvedArgs, false, mc.token());
        } else if (expr instanceof NewObjectExpr no) {
            ResolvedType type = symbolTable.resolveTypeNode(no.type(), this);
            List<Parameter> params = List.of();
            if (type != null && !type.info().constructors().isEmpty()) {
                params = type.info().constructors().get(0).parameters();
            }
            List<Expression> resolvedArgs = resolveArguments(params, no.arguments());
            return new NewObjectExpr(no.type(), resolvedArgs, no.token());
        } else if (expr instanceof NewArrayExpr na) {
            List<Expression> dims = new ArrayList<>();
            for (Expression dim : na.dimensions()) {
                dims.add(lowerExpression(dim));
            }
            return new NewArrayExpr(na.type(), dims, na.initializer(), na.token());
        } else if (expr instanceof ArrayAccessExpr aa) {
            return new ArrayAccessExpr(lowerExpression(aa.receiver()), lowerExpression(aa.index()), aa.token());
        } else if (expr instanceof LambdaExpr le) {
            return new LambdaExpr(le.parameters(), le.body(), le.token()); // keep lambdas unchanged
        } else if (expr instanceof AsyncExpr ae) {
            // Lower: async expr -> JatotRuntime.async(() -> expr)
            Expression inner = lowerExpression(ae.expression());
            ResolvedType type = getTypeOf(inner);
            
            // Build lambda () -> inner
            LambdaExpr lambda = new LambdaExpr(List.of(), inner, null);
            return new MethodCallExpr(new IdentifierExpr("io.lemadane.jatot.runtime.JatotRuntime", null), "async", List.of(), List.of(lambda), false, ae.token());
        } else if (expr instanceof AwaitExpr awe) {
            // Lower: await expr -> expr.await()
            Expression inner = lowerExpression(awe.expression());
            return new MethodCallExpr(inner, "await", List.of(), List.of(), false, awe.token());
        } else if (expr instanceof IfExpr ie) {
            // Lower block expression: if (cond) { yield X; } else { yield Y; }
            ResolvedType type = getTypeOf(ie);
            String tempVarName = nextTempVar();
            
            // Declare temp
            pendingStatements.add(new LocalVarDeclStmt(false, false, Optional.of(mapResolvedTypeToNode(type)), tempVarName, Optional.empty()));
            scopes.getFirst().put(tempVarName, type);
            
            // Replace yields in branches
            Statement thenB = replaceYields(ie.thenBranch(), tempVarName);
            Statement elseB = replaceYields(ie.elseBranch(), tempVarName);
            
            pendingStatements.add(new IfStmt(lowerExpression(ie.condition()), thenB, Optional.of(elseB)));
            
            return new IdentifierExpr(tempVarName, null);
        } else if (expr instanceof TryExpr te) {
            // Lower try expression
            ResolvedType type = getTypeOf(te);
            String tempVarName = nextTempVar();
            
            pendingStatements.add(new LocalVarDeclStmt(false, false, Optional.of(mapResolvedTypeToNode(type)), tempVarName, Optional.empty()));
            scopes.getFirst().put(tempVarName, type);
            
            BlockStmt body = (BlockStmt) replaceYields(te.body(), tempVarName);
            List<CatchClause> catches = new ArrayList<>();
            for (CatchClause cc : te.catchClauses()) {
                catches.add(new CatchClause(cc.parameter(), (BlockStmt) replaceYields(cc.body(), tempVarName)));
            }
            Optional<BlockStmt> finallyBlock = te.finallyBlock().map(this::lowerBlock);
            
            pendingStatements.add(new TryStmt(body, catches, finallyBlock));
            
            return new IdentifierExpr(tempVarName, null);
        } else if (expr instanceof LoopExpr le) {
            // Lower loop expression
            ResolvedType type = getTypeOf(le); // List<Elem>
            ResolvedType elemType = type.typeArguments().isEmpty() ? 
                    new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0) : 
                    type.typeArguments().get(0);
            
            String tempListName = nextTempVar();
            
            // List<Elem> tempListName = new ArrayList<>();
            TypeNode listTypeNode = new BaseTypeNode("java.util.List", List.of(mapResolvedTypeToBoxedNode(elemType)), true);
            TypeNode arrayListTypeNode = new BaseTypeNode("java.util.ArrayList", List.of(mapResolvedTypeToBoxedNode(elemType)), true);
            Expression newArrayList = new NewObjectExpr(arrayListTypeNode, List.of(), null);
            
            pendingStatements.add(new LocalVarDeclStmt(false, false, Optional.of(listTypeNode), tempListName, Optional.of(newArrayList)));
            scopes.getFirst().put(tempListName, type);
            
            // Lower loop statement replacing yield with tempListName.add(val)
            Statement loopStmt = lowerLoopYields(le.loop(), tempListName);
            pendingStatements.add(loopStmt);
            
            return new IdentifierExpr(tempListName, null);
        } else if (expr instanceof MarkupExpr me) {
            return lowerMarkupExpression(me);
        } else if (expr instanceof NamedArgExpr named) {
            return new NamedArgExpr(named.name(), lowerExpression(named.expression()));
        }

        return expr;
    }

    private Expression tempAssign(String name, Expression val, ResolvedType type) {
        pendingStatements.add(new LocalVarDeclStmt(false, false, Optional.of(mapResolvedTypeToNode(type)), name, Optional.empty()));
        scopes.getFirst().put(name, type);
        Token assignToken = new Token(TokenType.ASSIGN, "=", 1, 1);
        pendingStatements.add(new ExprStmt(new BinaryExpr(new IdentifierExpr(name, null), assignToken, val)));
        return new IdentifierExpr(name, null);
    }

    private Statement replaceYields(Statement stmt, String varName) {
        if (stmt instanceof YieldStmt ys) {
            int line = ys.expression().token() != null ? ys.expression().token().line() : 1;
            int col = ys.expression().token() != null ? ys.expression().token().column() : 1;
            Token assignToken = new Token(TokenType.ASSIGN, "=", line, col);
            return new ExprStmt(new BinaryExpr(new IdentifierExpr(varName, null), assignToken, lowerExpression(ys.expression())));
        } else if (stmt instanceof BlockStmt bs) {
            List<Statement> list = new ArrayList<>();
            for (Statement s : bs.statements()) {
                list.add(replaceYields(s, varName));
            }
            return new BlockStmt(list);
        } else if (stmt instanceof IfStmt ifs) {
            return new IfStmt(ifs.condition(), replaceYields(ifs.thenBranch(), varName), ifs.elseBranch().map(eb -> replaceYields(eb, varName)));
        } else if (stmt instanceof TryStmt ts) {
            BlockStmt body = (BlockStmt) replaceYields(ts.body(), varName);
            List<CatchClause> catches = new ArrayList<>();
            for (CatchClause cc : ts.catchClauses()) {
                catches.add(new CatchClause(cc.parameter(), (BlockStmt) replaceYields(cc.body(), varName)));
            }
            return new TryStmt(body, catches, ts.finallyBlock());
        }
        return stmt;
    }

    private Statement lowerLoopYields(Statement stmt, String listName) {
        if (stmt instanceof YieldStmt ys) {
            // yield X; -> listName.add(X);
            return new ExprStmt(new MethodCallExpr(new IdentifierExpr(listName, null), "add", List.of(), List.of(lowerExpression(ys.expression())), false, null));
        } else if (stmt instanceof BlockStmt bs) {
            List<Statement> list = new ArrayList<>();
            for (Statement s : bs.statements()) {
                list.add(lowerLoopYields(s, listName));
            }
            return new BlockStmt(list);
        } else if (stmt instanceof IfStmt ifs) {
            return new IfStmt(ifs.condition(), lowerLoopYields(ifs.thenBranch(), listName), ifs.elseBranch().map(eb -> lowerLoopYields(eb, listName)));
        } else if (stmt instanceof ForStmt fs) {
            return new ForStmt(fs.init(), fs.condition(), fs.update(), lowerLoopYields(fs.body(), listName));
        } else if (stmt instanceof ForEachStmt fes) {
            return new ForEachStmt(fes.parameter(), fes.iterable(), lowerLoopYields(fes.body(), listName));
        } else if (stmt instanceof WhileStmt ws) {
            return new WhileStmt(ws.condition(), lowerLoopYields(ws.body(), listName));
        } else if (stmt instanceof DoWhileStmt dws) {
            return new DoWhileStmt(lowerLoopYields(dws.body(), listName), dws.condition());
        } else if (stmt instanceof TryStmt ts) {
            BlockStmt body = (BlockStmt) lowerLoopYields(ts.body(), listName);
            List<CatchClause> catches = new ArrayList<>();
            for (CatchClause cc : ts.catchClauses()) {
                catches.add(new CatchClause(cc.parameter(), (BlockStmt) lowerLoopYields(cc.body(), listName)));
            }
            return new TryStmt(body, catches, ts.finallyBlock());
        }
        return stmt;
    }

    private TypeNode mapResolvedTypeToNode(ResolvedType type) {
        if (type.isPrimitive()) {
            return new PrimitiveTypeNode(type.info().fullName());
        }
        TypeNode node = new BaseTypeNode(type.info().fullName(), List.of(), type.isNonNull());
        for (int i = 0; i < type.arrayDimensions(); i++) {
            node = new ArrayTypeNode(node, type.isNonNull());
        }
        return node;
    }

    private TypeNode mapResolvedTypeToBoxedNode(ResolvedType type) {
        if (type.isPrimitive()) {
            String boxedName = switch (type.info().fullName()) {
                case "int" -> "java.lang.Integer";
                case "long" -> "java.lang.Long";
                case "double" -> "java.lang.Double";
                case "float" -> "java.lang.Float";
                case "boolean" -> "java.lang.Boolean";
                case "char" -> "java.lang.Character";
                case "byte" -> "java.lang.Byte";
                case "short" -> "java.lang.Short";
                default -> "java.lang.Object";
            };
            return new BaseTypeNode(boxedName, List.of(), type.isNonNull());
        }
        return mapResolvedTypeToNode(type);
    }

    // --- Lightweight Type Checking during Lowering ---

    private ResolvedType getTypeOf(Expression expr) {
        if (expr instanceof LiteralExpr lit) {
            if (lit.value() == null) return new ResolvedType(symbolTable.getType("null"), false, List.of(), 0);
            if (lit.value() instanceof Boolean) return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            if (lit.value() instanceof Number) return new ResolvedType(symbolTable.getType("int"), true, List.of(), 0);
            if (lit.value() instanceof String) return new ResolvedType(symbolTable.getType("java.lang.String"), true, List.of(), 0);
        } else if (expr instanceof IdentifierExpr id) {
            ResolvedType type = lookupVarType(id.name());
            if (type != null) return type;
            // Lookup field on current class
            if (currentClass != null && !isStaticContext) {
                SymbolTable.FieldInfo fInfo = currentClass.fields().stream().filter(f -> f.name().equals(id.name())).findFirst().orElse(null);
                if (fInfo != null) return symbolTable.resolveTypeNode(fInfo.type(), this);
            }
            TypeInfo classInfo = resolve(id.name());
            if (classInfo != null) return new ResolvedType(classInfo, true, List.of(), 0);
        } else if (expr instanceof ThisExpr) {
            if (currentClass != null) return new ResolvedType(currentClass, true, List.of(), 0);
            ResolvedType type = lookupVarType("this");
            if (type != null) return type;
        } else if (expr instanceof TernaryExpr tern) {
            ResolvedType thenBranch = getTypeOf(tern.thenBranch());
            ResolvedType elseBranch = getTypeOf(tern.elseBranch());
            if (thenBranch != null && elseBranch != null) {
                boolean isNonNull = thenBranch.isNonNull() && elseBranch.isNonNull();
                return getCommonSupertype(thenBranch, elseBranch).withNonNull(isNonNull);
            }
            return new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
        } else if (expr instanceof BinaryExpr bin) {
            TokenType opType = bin.operator().type();
            if (opType == TokenType.EQUAL_EQUAL || opType == TokenType.BANG_EQUAL ||
                opType == TokenType.LESS || opType == TokenType.LESS_EQUAL ||
                opType == TokenType.GREATER || opType == TokenType.GREATER_EQUAL ||
                opType == TokenType.AND_AND || opType == TokenType.OR_OR ||
                opType == TokenType.AND || opType == TokenType.OR ||
                opType == TokenType.NAND || opType == TokenType.NOR ||
                opType == TokenType.XOR || opType == TokenType.XNOR) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }
            return getTypeOf(bin.left());
        } else if (expr instanceof UnaryExpr un) {
            if (un.operator().type() == TokenType.NOT ||
                un.operator().type() == TokenType.BANG) {
                return new ResolvedType(symbolTable.getType("boolean"), true, List.of(), 0);
            }
            return getTypeOf(un.expression());
        } else if (expr instanceof MemberAccessExpr ma) {
            ResolvedType rec = getTypeOf(ma.receiver());
            if (rec != null) {
                SymbolTable.FieldInfo fInfo = rec.info().fields().stream().filter(f -> f.name().equals(ma.memberName())).findFirst().orElse(null);
                if (fInfo != null) return symbolTable.resolveTypeNode(fInfo.type(), this);
            }
        } else if (expr instanceof MethodCallExpr mc) {
            ResolvedType rec = null;
            if (mc.receiver() != null) {
                rec = getTypeOf(mc.receiver());
            } else {
                rec = new ResolvedType(currentClass, true, List.of(), 0);
            }
            if (rec != null) {
                SymbolTable.MethodInfo mInfo = rec.info().methods().stream().filter(m -> m.name().equals(mc.methodName())).findFirst().orElse(null);
                if (mInfo != null) return symbolTable.resolveTypeNode(mInfo.returnType(), this);

                // Extension methods
                for (ExtensionDecl ext : symbolTable.extensions()) {
                    ResolvedType extTarget = symbolTable.resolveTypeNode(ext.targetType(), this);
                    if (rec.isCompatibleWith(extTarget, symbolTable)) {
                        MethodDecl extMethod = ext.members().stream()
                                .filter(m -> m instanceof MethodDecl)
                                .map(m -> (MethodDecl) m)
                                .filter(m -> m.name().equals(mc.methodName()))
                                .findFirst().orElse(null);
                        if (extMethod != null) return symbolTable.resolveTypeNode(extMethod.returnType(), this);
                    }
                }
            }
        } else if (expr instanceof NewObjectExpr no) {
            return symbolTable.resolveTypeNode(no.type(), this);
        } else if (expr instanceof NewArrayExpr na) {
            return symbolTable.resolveTypeNode(na.type(), this);
        } else if (expr instanceof ArrayAccessExpr aa) {
            ResolvedType rec = getTypeOf(aa.receiver());
            if (rec != null && rec.arrayDimensions() > 0) {
                return new ResolvedType(rec.info(), false, rec.typeArguments(), rec.arrayDimensions() - 1);
            }
        } else if (expr instanceof AsyncExpr ae) {
            ResolvedType type = getTypeOf(ae.expression());
            return new ResolvedType(symbolTable.getType("io.lemadane.jatot.runtime.JatotFuture"), true, List.of(type), 0);
        } else if (expr instanceof AwaitExpr awe) {
            ResolvedType type = getTypeOf(awe.expression());
            if (type != null && type.info().fullName().equals("io.lemadane.jatot.runtime.JatotFuture")) {
                if (!type.typeArguments().isEmpty()) return type.typeArguments().get(0);
            }
        } else if (expr instanceof IfExpr ie) {
            ResolvedType t1 = getTypeOf(ie.thenBranch().statements().get(0) instanceof YieldStmt ? ((YieldStmt) ie.thenBranch().statements().get(0)).expression() : new LiteralExpr(null, null));
            return t1;
        } else if (expr instanceof TryExpr te) {
            ResolvedType t1 = getTypeOf(te.body().statements().get(0) instanceof YieldStmt ? ((YieldStmt) te.body().statements().get(0)).expression() : new LiteralExpr(null, null));
            return t1;
        } else if (expr instanceof LoopExpr le) {
            ResolvedType elemType = null;
            List<ResolvedType> yields = findYieldTypes(le.loop());
            if (!yields.isEmpty()) {
                elemType = yields.get(0);
                for (int i = 1; i < yields.size(); i++) {
                    elemType = getCommonSupertype(elemType, yields.get(i));
                }
            }
            if (elemType == null) {
                elemType = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
            }
            return new ResolvedType(symbolTable.getType("java.util.List"), true, List.of(elemType), 0);
        } else if (expr instanceof SqlExpr sql) {
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
            return getTypeOf(named.expression());
        } else if (expr instanceof InterpolatedStringExpression) {
            return new ResolvedType(symbolTable.getType("java.lang.String"), true, List.of(), 0);
        }

        return new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
    }

    private List<ResolvedType> findYieldTypes(Statement stmt) {
        List<ResolvedType> types = new ArrayList<>();
        findYieldTypesHelper(stmt, types);
        return types;
    }

    private void findYieldTypesHelper(Statement stmt, List<ResolvedType> types) {
        if (stmt instanceof YieldStmt ys) {
            types.add(getTypeOf(ys.expression()));
        } else if (stmt instanceof BlockStmt bs) {
            for (Statement s : bs.statements()) {
                findYieldTypesHelper(s, types);
            }
        } else if (stmt instanceof IfStmt ifs) {
            findYieldTypesHelper(ifs.thenBranch(), types);
            ifs.elseBranch().ifPresent(eb -> findYieldTypesHelper(eb, types));
        } else if (stmt instanceof TryStmt ts) {
            findYieldTypesHelper(ts.body(), types);
            for (CatchClause cc : ts.catchClauses()) {
                findYieldTypesHelper(cc.body(), types);
            }
            ts.finallyBlock().ifPresent(fb -> findYieldTypesHelper(fb, types));
        } else if (stmt instanceof ForStmt fs) {
            pushScope();
            if (fs.init().isPresent()) {
                Statement init = fs.init().get();
                if (init instanceof LocalVarDeclStmt lvd) {
                    ResolvedType type = null;
                    if (lvd.type().isPresent()) {
                        type = symbolTable.resolveTypeNode(lvd.type().get(), this);
                    }
                    if (lvd.isInferred() && lvd.initializer().isPresent()) {
                        type = getTypeOf(lvd.initializer().get());
                    }
                    if (type != null) {
                        scopes.getFirst().put(lvd.name(), type);
                    }
                }
            }
            findYieldTypesHelper(fs.body(), types);
            popScope();
        } else if (stmt instanceof ForEachStmt fes) {
            pushScope();
            ResolvedType pType;
            if (fes.parameter().type() instanceof BaseTypeNode base && base.name().equals("var")) {
                ResolvedType iterType = getTypeOf(fes.iterable());
                if (iterType != null && iterType.info().fullName().equals("java.util.List") && !iterType.typeArguments().isEmpty()) {
                    pType = iterType.typeArguments().get(0);
                } else if (iterType != null && iterType.arrayDimensions() > 0) {
                    pType = new ResolvedType(iterType.info(), iterType.isNonNull(), iterType.typeArguments(), iterType.arrayDimensions() - 1);
                } else {
                    pType = new ResolvedType(symbolTable.getType("java.lang.Object"), false, List.of(), 0);
                }
            } else {
                pType = symbolTable.resolveTypeNode(fes.parameter().type(), this);
            }
            scopes.getFirst().put(fes.parameter().name(), pType);
            findYieldTypesHelper(fes.body(), types);
            popScope();
        } else if (stmt instanceof WhileStmt ws) {
            findYieldTypesHelper(ws.body(), types);
        } else if (stmt instanceof DoWhileStmt dws) {
            findYieldTypesHelper(dws.body(), types);
        }
    }

    private ResolvedType getCommonSupertype(ResolvedType a, ResolvedType b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.isCompatibleWith(b, symbolTable)) return b;
        if (b.isCompatibleWith(a, symbolTable)) return a;
        SymbolTable.TypeInfo objInfo = symbolTable.getType("java.lang.Object");
        return new ResolvedType(objInfo, a.isNonNull() && b.isNonNull(), List.of(), 0);
    }

    private List<Expression> resolveArguments(List<Parameter> params, List<Expression> args) {
        if (params.isEmpty()) {
            List<Expression> lowered = new ArrayList<>();
            for (Expression arg : args) {
                lowered.add(lowerExpression(arg));
            }
            return lowered;
        }
        
        boolean hasNamed = args.stream().anyMatch(a -> a instanceof NamedArgExpr);
        boolean hasDefaults = params.stream().anyMatch(p -> p.defaultValue().isPresent());
        
        if (!hasNamed && !hasDefaults) {
            List<Expression> lowered = new ArrayList<>();
            for (Expression arg : args) {
                lowered.add(lowerExpression(arg));
            }
            return lowered;
        }
        
        Expression[] resolved = new Expression[params.size()];
        
        // Fill positional and named arguments
        int positionalIdx = 0;
        for (Expression arg : args) {
            if (arg instanceof NamedArgExpr named) {
                int paramIdx = -1;
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i).name().equals(named.name())) {
                        paramIdx = i;
                        break;
                    }
                }
                if (paramIdx != -1) {
                    resolved[paramIdx] = lowerExpression(named.expression());
                }
            } else {
                // Find next unfilled position
                while (positionalIdx < resolved.length && resolved[positionalIdx] != null) {
                    positionalIdx++;
                }
                if (positionalIdx < resolved.length) {
                    resolved[positionalIdx] = lowerExpression(arg);
                    positionalIdx++;
                }
            }
        }
        
        // Fill remaining with default values
        for (int i = 0; i < resolved.length; i++) {
            if (resolved[i] == null) {
                if (params.get(i).defaultValue().isPresent()) {
                    resolved[i] = lowerExpression(params.get(i).defaultValue().get());
                } else {
                    resolved[i] = new LiteralExpr(null, null);
                }
            }
        }
        
        return List.of(resolved);
    }

    private Expression lowerMarkupExpression(MarkupExpr me) {
        String writerName = "__writer";
        Parameter writerParam = new Parameter(new BaseTypeNode("io.lemadane.jatot.html.HtmlWriter", List.of(), true), writerName, false, Optional.empty());
        List<Statement> stmts = compileMarkupNodes(List.of(me.node()), writerName);
        return new LambdaExpr(List.of(writerParam), new BlockStmt(stmts), me.token());
    }

    private List<Statement> compileMarkupNodes(List<MarkupNode> nodes, String writerName) {
        List<MarkupAction> actions = new ArrayList<>();
        for (MarkupNode n : nodes) {
            collectMarkupActions(n, actions);
        }
        
        List<MarkupAction> merged = new ArrayList<>();
        StringBuilder currentStatic = new StringBuilder();
        for (MarkupAction act : actions) {
            if (act instanceof StaticAction sa) {
                currentStatic.append(sa.value());
            } else {
                if (currentStatic.length() > 0) {
                    merged.add(new StaticAction(currentStatic.toString()));
                    currentStatic.setLength(0);
                }
                merged.add(act);
            }
        }
        if (currentStatic.length() > 0) {
            merged.add(new StaticAction(currentStatic.toString()));
        }
        
        List<Statement> stmts = new ArrayList<>();
        Expression writerExpr = new IdentifierExpr(writerName, null);
        
        for (MarkupAction act : merged) {
            if (act instanceof StaticAction sa) {
                stmts.add(new ExprStmt(new MethodCallExpr(writerExpr, "literal", List.of(), List.of(new LiteralExpr(sa.value(), null)), false, null)));
            } else if (act instanceof DynamicTextAction dta) {
                stmts.add(new ExprStmt(new MethodCallExpr(writerExpr, "text", List.of(), List.of(lowerExpression(dta.expression())), false, null)));
            } else if (act instanceof DynamicAttrAction daa) {
                stmts.add(new ExprStmt(new MethodCallExpr(writerExpr, "literal", List.of(), List.of(new LiteralExpr(" " + daa.name() + "=\"", null)), false, null)));
                String method = daa.isUrl() ? "urlAttribute" : "attribute";
                stmts.add(new ExprStmt(new MethodCallExpr(writerExpr, method, List.of(), List.of(lowerExpression(daa.expression())), false, null)));
                stmts.add(new ExprStmt(new MethodCallExpr(writerExpr, "literal", List.of(), List.of(new LiteralExpr("\"", null)), false, null)));
            } else if (act instanceof DynamicBoolAttrAction dba) {
                Expression cond = lowerExpression(dba.expression());
                Statement write = new ExprStmt(new MethodCallExpr(writerExpr, "literal", List.of(), List.of(new LiteralExpr(" " + dba.name(), null)), false, null));
                stmts.add(new IfStmt(cond, write, Optional.empty()));
            } else if (act instanceof ComponentAction ca) {
                ComponentNode comp = ca.node();
                TypeInfo typeInfo = resolve(comp.typeName());
                if (typeInfo == null) {
                    typeInfo = symbolTable.getType(comp.typeName());
                }
                
                ConstructorInfo matched = null;
                if (typeInfo != null && !typeInfo.constructors().isEmpty()) {
                    matched = typeInfo.constructors().get(0);
                }
                
                List<Expression> constructorArgs = new ArrayList<>();
                if (matched != null) {
                    for (Parameter param : matched.parameters()) {
                        ResolvedType paramType = symbolTable.resolveTypeNode(param.type(), this);
                        if (paramType != null && paramType.info().fullName().equals("io.lemadane.jatot.html.HtmlChildren")) {
                            String childWriterName = "__childWriter";
                            Parameter childWriterParam = new Parameter(new BaseTypeNode("io.lemadane.jatot.html.HtmlWriter", List.of(), true), childWriterName, false, Optional.empty());
                            List<Statement> childStmts = compileMarkupNodes(comp.children(), childWriterName);
                            constructorArgs.add(new LambdaExpr(List.of(childWriterParam), new BlockStmt(childStmts), null));
                        } else {
                            ComponentArgumentNode argNode = null;
                            for (ComponentArgumentNode arg : comp.arguments()) {
                                if (arg.name().equals(param.name())) {
                                    argNode = arg;
                                    break;
                                }
                            }
                            if (argNode != null) {
                                constructorArgs.add(lowerExpression(argNode.value()));
                            } else {
                                if (param.defaultValue().isPresent()) {
                                    constructorArgs.add(lowerExpression(param.defaultValue().get()));
                                } else {
                                    constructorArgs.add(new LiteralExpr(null, null));
                                }
                            }
                        }
                    }
                }
                
                TypeNode compType = new BaseTypeNode(typeInfo != null ? typeInfo.fullName() : comp.typeName(), List.of(), true);
                Expression newComponent = new NewObjectExpr(compType, constructorArgs, null);
                stmts.add(new ExprStmt(new MethodCallExpr(writerExpr, "component", List.of(), List.of(newComponent), false, null)));
            } else if (act instanceof IfAction ia) {
                IfMarkupNode imn = ia.node();
                Statement trueB = new BlockStmt(compileMarkupNodes(imn.trueBranch(), writerName));
                Statement falseB = new BlockStmt(compileMarkupNodes(imn.falseBranch(), writerName));
                stmts.add(new IfStmt(lowerExpression(imn.condition()), trueB, Optional.of(falseB)));
            } else if (act instanceof ForAction fa) {
                ForMarkupNode fmn = fa.node();
                Statement body = new BlockStmt(compileMarkupNodes(fmn.body(), writerName));
                stmts.add(new ForEachStmt(fmn.parameter(), lowerExpression(fmn.iterable()), body));
            }
        }
        return stmts;
    }

    private void collectMarkupActions(MarkupNode node, List<MarkupAction> actions) {
        if (node instanceof ElementNode el) {
            actions.add(new StaticAction("<" + el.tagName()));
            for (AttributeNode attr : el.attributes()) {
                if (attr.value() instanceof LiteralExpr lit) {
                    if (lit.value() instanceof Boolean && (Boolean) lit.value()) {
                        actions.add(new StaticAction(" " + attr.name()));
                    } else {
                        String val = String.valueOf(lit.value());
                        actions.add(new StaticAction(" " + attr.name() + "=\"" + escapeHtml(val) + "\""));
                    }
                } else {
                    if (isBooleanAttribute(attr.name())) {
                        actions.add(new DynamicBoolAttrAction(attr.name(), attr.value()));
                    } else {
                        actions.add(new DynamicAttrAction(attr.name(), attr.value(), isUrlAttribute(attr.name())));
                    }
                }
            }
            if (isVoidElement(el.tagName())) {
                actions.add(new StaticAction(">"));
            } else {
                actions.add(new StaticAction(">"));
                for (MarkupNode child : el.children()) {
                    collectMarkupActions(child, actions);
                }
                actions.add(new StaticAction("</" + el.tagName() + ">"));
            }
        } else if (node instanceof ComponentNode comp) {
            actions.add(new ComponentAction(comp));
        } else if (node instanceof TextNode text) {
            actions.add(new StaticAction(text.value()));
        } else if (node instanceof ExpressionNode en) {
            actions.add(new DynamicTextAction(en.expression()));
        } else if (node instanceof IfMarkupNode imn) {
            actions.add(new IfAction(imn));
        } else if (node instanceof ForMarkupNode fmn) {
            actions.add(new ForAction(fmn));
        } else if (node instanceof FragmentNode fn) {
            for (MarkupNode child : fn.children()) {
                collectMarkupActions(child, actions);
            }
        }
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isUrlAttribute(String name) {
        String lower = name.toLowerCase();
        return lower.equals("href") || lower.equals("src") || lower.equals("action") || lower.equals("formaction") || lower.equals("data");
    }

    private static boolean isBooleanAttribute(String name) {
        String lower = name.toLowerCase();
        return lower.equals("disabled") || lower.equals("checked") || lower.equals("selected") || lower.equals("readonly") ||
               lower.equals("required") || lower.equals("multiple") || lower.equals("autofocus") || lower.equals("novalidate") ||
               lower.equals("hidden") || lower.equals("open") || lower.equals("async") || lower.equals("defer") ||
               lower.equals("loop") || lower.equals("autoplay") || lower.equals("controls");
    }

    private static boolean isVoidElement(String name) {
        String lower = name.toLowerCase();
        return lower.equals("img") || lower.equals("br") || lower.equals("hr") ||
               lower.equals("input") || lower.equals("meta") || lower.equals("link") ||
               lower.equals("col") || lower.equals("embed") || lower.equals("source") ||
               lower.equals("track") || lower.equals("wbr") || lower.equals("param");
    }

    private static sealed interface MarkupAction permits StaticAction, DynamicTextAction, DynamicAttrAction, DynamicBoolAttrAction, ComponentAction, IfAction, ForAction {}
    private record StaticAction(String value) implements MarkupAction {}
    private record DynamicTextAction(Expression expression) implements MarkupAction {}
    private record DynamicAttrAction(String name, Expression expression, boolean isUrl) implements MarkupAction {}
    private record DynamicBoolAttrAction(String name, Expression expression) implements MarkupAction {}
    private record ComponentAction(ComponentNode node) implements MarkupAction {}
    private record IfAction(IfMarkupNode node) implements MarkupAction {}
    private record ForAction(ForMarkupNode node) implements MarkupAction {}
}
