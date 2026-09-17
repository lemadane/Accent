package accent.emitter;

import accent.ast.Ast.*;
import java.util.List;
import java.util.Optional;

public final class JavaEmitter {
    private final StringBuilder sb = new StringBuilder();
    private int indentLevel = 0;
    
    // State for extension methods
    private boolean isInsideExtension = false;
    private TypeNode extensionTargetType = null;
    private String currentClassName = null;
    private boolean isInsideGenerator = false;

    public JavaEmitter() {
    }

    public String emit(CompilationUnit unit) {
        sb.setLength(0);
        indentLevel = 0;

        // Package
        if (unit.packageName().isPresent()) {
            printIndent();
            sb.append("package ").append(unit.packageName().get()).append(";\n\n");
        }

        // Imports
        for (String imp : unit.imports()) {
            printIndent();
            sb.append("import ").append(imp).append(";\n");
        }
        if (!unit.imports().isEmpty()) {
            sb.append("\n");
        }

        // Declarations
        for (TypeDeclaration decl : unit.declarations()) {
            emitTypeDeclaration(decl);
            sb.append("\n");
        }

        return sb.toString();
    }

    private void printIndent() {
        sb.append("    ".repeat(indentLevel));
    }

    private void emitTypeDeclaration(TypeDeclaration decl) {
        if (decl instanceof ExtensionDecl ed) {
            emitExtensionDeclaration(ed);
            return;
        }

        String savedClassName = currentClassName;
        currentClassName = decl.name();

        printIndent();
        // Modifiers
        for (String mod : decl.modifiers()) {
            sb.append(mod).append(" ");
        }

        if (decl instanceof ClassDecl cd) {
            sb.append("class ").append(cd.name());
            emitTypeParameters(cd.typeParameters());
            if (cd.superclass().isPresent()) {
                sb.append(" extends ").append(emitType(cd.superclass().get()));
            }
            if (!cd.interfaces().isEmpty()) {
                sb.append(" implements ");
                for (int i = 0; i < cd.interfaces().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(emitType(cd.interfaces().get(i)));
                }
            }
        } else if (decl instanceof InterfaceDecl id) {
            sb.append("interface ").append(id.name());
            emitTypeParameters(id.typeParameters());
            if (!id.interfaces().isEmpty()) {
                sb.append(" extends ");
                for (int i = 0; i < id.interfaces().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(emitType(id.interfaces().get(i)));
                }
            }
        } else if (decl instanceof RecordDecl rd) {
            sb.append("record ").append(rd.name());
            emitTypeParameters(rd.typeParameters());
            sb.append("(");
            for (int i = 0; i < rd.components().size(); i++) {
                if (i > 0) sb.append(", ");
                Parameter p = rd.components().get(i);
                sb.append(emitType(p.type())).append(p.isVarargs() ? "... " : " ").append(p.name());
            }
            sb.append(")");
            if (!rd.interfaces().isEmpty()) {
                sb.append(" implements ");
                for (int i = 0; i < rd.interfaces().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(emitType(rd.interfaces().get(i)));
                }
            }
        } else if (decl instanceof EnumDecl ed) {
            sb.append("enum ").append(ed.name());
            if (!ed.interfaces().isEmpty()) {
                sb.append(" implements ");
                for (int i = 0; i < ed.interfaces().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(emitType(ed.interfaces().get(i)));
                }
            }
        }

        sb.append(" {\n");
        indentLevel++;

        if (decl instanceof EnumDecl ed) {
            printIndent();
            for (int i = 0; i < ed.constants().size(); i++) {
                if (i > 0) sb.append(", ");
                EnumConstant c = ed.constants().get(i);
                sb.append(c.name());
                if (!c.arguments().isEmpty()) {
                    sb.append("(");
                    for (int j = 0; j < c.arguments().size(); j++) {
                        if (j > 0) sb.append(", ");
                        sb.append(emitExpression(c.arguments().get(j)));
                    }
                    sb.append(")");
                }
            }
            sb.append(";\n\n");
        }

        for (Member member : decl.members()) {
            emitMember(member);
        }

        indentLevel--;
        printIndent();
        sb.append("}\n");
        currentClassName = savedClassName;
    }

    private void emitExtensionDeclaration(ExtensionDecl ed) {
        printIndent();
        sb.append("public class ").append(ed.name()).append(" {\n");
        indentLevel++;

        isInsideExtension = true;
        extensionTargetType = ed.targetType();

        for (Member member : ed.members()) {
            emitMember(member);
        }

        isInsideExtension = false;
        extensionTargetType = null;

        indentLevel--;
        printIndent();
        sb.append("}\n");
    }

    private void emitTypeParameters(List<TypeParameter> typeParams) {
        if (typeParams.isEmpty()) return;
        sb.append("<");
        for (int i = 0; i < typeParams.size(); i++) {
            if (i > 0) sb.append(", ");
            TypeParameter tp = typeParams.get(i);
            sb.append(tp.name());
            if (tp.bound().isPresent()) {
                sb.append(" extends ").append(emitType(tp.bound().get()));
            }
        }
        sb.append(">");
    }

    private void emitMember(Member member) {
        if (member instanceof FieldDecl fd) {
            printIndent();
            for (String mod : fd.modifiers()) {
                sb.append(mod).append(" ");
            }
            sb.append(emitType(fd.type())).append(" ").append(fd.name());
            if (fd.initializer().isPresent()) {
                sb.append(" = ").append(emitExpression(fd.initializer().get()));
            }
            sb.append(";\n");
        } else if (member instanceof ConstructorDecl cd) {
            printIndent();
            for (String mod : cd.modifiers()) {
                sb.append(mod).append(" ");
            }
            emitTypeParameters(cd.typeParameters());
            if (!cd.typeParameters().isEmpty()) sb.append(" ");
            
            // Record/Enum/Class constructors don't print type name separately
            // Assuming constructor matches enclosing class/record simple name
            String name = currentClassName != null ? currentClassName : "";
            sb.append(name).append("(");
            emitParameters(cd.parameters());
            sb.append(")");
            emitThrows(cd.throwsList());
            sb.append(" {\n");
            
            indentLevel++;
            // Inject runtime null checks for non-null parameters
            for (Parameter p : cd.parameters()) {
                if (p.type().isNonNull() && !isPrimitive(p.type())) {
                    printIndent();
                    sb.append("java.util.Objects.requireNonNull(").append(p.name())
                            .append(", \"Parameter '").append(p.name()).append("' must not be null\");\n");
                }
            }
            for (Statement s : cd.body().statements()) {
                emitStatement(s);
            }
            indentLevel--;
            printIndent();
            sb.append("}\n");
        } else if (member instanceof MethodDecl md) {
            printIndent();
            
            // Extension method modifier mapping: static helper
            if (isInsideExtension) {
                sb.append("public static ");
            } else {
                for (String mod : md.modifiers()) {
                    sb.append(mod).append(" ");
                }
            }

            emitTypeParameters(md.typeParameters());
            if (!md.typeParameters().isEmpty()) sb.append(" ");

            if (md.isGenerator()) {
                sb.append("java.lang.Iterable<").append(boxedType(md.returnType())).append("> ").append(md.name()).append("(");
            } else {
                sb.append(emitType(md.returnType())).append(" ").append(md.name()).append("(");
            }
            
            if (isInsideExtension && extensionTargetType != null) {
                // First parameter is target type receiver: targetType _this
                sb.append(emitType(extensionTargetType)).append(" _this");
                if (!md.parameters().isEmpty()) sb.append(", ");
            }
            
            emitParameters(md.parameters());
            sb.append(")");
            emitThrows(md.throwsList());

            if (md.body().isPresent()) {
                sb.append(" {\n");
                indentLevel++;
                
                // Inject runtime null checks for non-null parameters (including receiver)
                if (isInsideExtension && extensionTargetType != null && extensionTargetType.isNonNull() && !isPrimitive(extensionTargetType)) {
                    printIndent();
                    sb.append("java.util.Objects.requireNonNull(_this, \"Receiver '_this' must not be null\");\n");
                }
                for (Parameter p : md.parameters()) {
                    if (p.type().isNonNull() && !isPrimitive(p.type())) {
                        printIndent();
                        sb.append("java.util.Objects.requireNonNull(").append(p.name())
                                .append(", \"Parameter '").append(p.name()).append("' must not be null\");\n");
                    }
                }

                if (md.isGenerator()) {
                    printIndent();
                    sb.append("return accent.runtime.AccentGenerator.of((__emit) -> {\n");
                    indentLevel++;
                    boolean savedInsideGenerator = isInsideGenerator;
                    isInsideGenerator = true;
                    for (Statement s : md.body().get().statements()) {
                        emitStatement(s);
                    }
                    isInsideGenerator = savedInsideGenerator;
                    indentLevel--;
                    printIndent();
                    sb.append("});\n");
                } else {
                    for (Statement s : md.body().get().statements()) {
                        emitStatement(s);
                    }
                }
                indentLevel--;
                printIndent();
                sb.append("}\n");
            } else {
                sb.append(";\n");
            }
        }
    }

    private String boxedType(TypeNode type) {
        String typeName = "";
        if (type instanceof PrimitiveTypeNode prim) {
            typeName = prim.name();
        } else if (type instanceof BaseTypeNode base) {
            typeName = base.name();
        } else {
            return emitType(type);
        }
        return switch (typeName) {
            case "int"     -> "Integer";
            case "long"    -> "Long";
            case "double"  -> "Double";
            case "float"   -> "Float";
            case "boolean" -> "Boolean";
            case "char"    -> "Character";
            case "byte"    -> "Byte";
            case "short"   -> "Short";
            default        -> emitType(type);
        };
    }

    private boolean isPrimitive(TypeNode type) {
        return type instanceof PrimitiveTypeNode;
    }

    private void emitParameters(List<Parameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            Parameter p = parameters.get(i);
            sb.append(emitType(p.type())).append(p.isVarargs() ? "... " : " ").append(p.name());
        }
    }

    private void emitThrows(List<TypeNode> throwsList) {
        if (throwsList.isEmpty()) return;
        sb.append(" throws ");
        for (int i = 0; i < throwsList.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitType(throwsList.get(i)));
        }
    }

    // --- Type Emitter ---

    private String emitType(TypeNode type) {
        if (type instanceof PrimitiveTypeNode prim) {
            return prim.name();
        } else if (type instanceof ArrayTypeNode arr) {
            return emitType(arr.elementType()) + "[]";
        } else if (type instanceof BaseTypeNode base) {
            StringBuilder r = new StringBuilder(base.name());
            if (!base.typeArguments().isEmpty()) {
                r.append("<");
                for (int i = 0; i < base.typeArguments().size(); i++) {
                    if (i > 0) r.append(", ");
                    r.append(emitType(base.typeArguments().get(i)));
                }
                r.append(">");
            }
            return r.toString();
        }
        return "";
    }

    // --- Statement Emitter ---

    private void emitStatement(Statement stmt) {
        if (stmt instanceof BlockStmt bs) {
            printIndent();
            sb.append("{\n");
            indentLevel++;
            for (Statement s : bs.statements()) {
                emitStatement(s);
            }
            indentLevel--;
            printIndent();
            sb.append("}\n");
        } else if (stmt instanceof LocalVarDeclStmt varDecl) {
            printIndent();
            if (varDecl.isFinal()) {
                sb.append("final ");
            }
            if (varDecl.isInferred()) {
                sb.append("var ");
            } else if (varDecl.type().isPresent()) {
                sb.append(emitType(varDecl.type().get())).append(" ");
            }
            sb.append(varDecl.name());
            if (varDecl.initializer().isPresent()) {
                sb.append(" = ").append(emitExpression(varDecl.initializer().get()));
            }
            sb.append(";\n");
        } else if (stmt instanceof IfStmt ifs) {
            printIndent();
            sb.append("if (").append(emitCondition(ifs.condition())).append(") ");
            
            // Nested block printing
            emitInlineOrBlockStatement(ifs.thenBranch());
            
            if (ifs.elseBranch().isPresent()) {
                sb.append(" else ");
                emitInlineOrBlockStatement(ifs.elseBranch().get());
            }
            sb.append("\n");
        } else if (stmt instanceof ForStmt fs) {
            printIndent();
            sb.append("for (");
            if (fs.init().isPresent()) {
                // Strip the final newline and semicolon from simple init statement if needed
                String initStr = emitStatementToString(fs.init().get()).trim();
                if (initStr.endsWith(";")) initStr = initStr.substring(0, initStr.length() - 1);
                sb.append(initStr);
            }
            sb.append("; ");
            fs.condition().ifPresent(c -> sb.append(emitCondition(c)));
            sb.append("; ");
            fs.update().ifPresent(u -> sb.append(emitExpression(u)));
            sb.append(") ");
            emitInlineOrBlockStatement(fs.body());
            sb.append("\n");
        } else if (stmt instanceof ForEachStmt fes) {
            printIndent();
            sb.append("for (").append(emitType(fes.parameter().type())).append(" ")
                    .append(fes.parameter().name()).append(" : ").append(emitExpression(fes.iterable())).append(") ");
            emitInlineOrBlockStatement(fes.body());
            sb.append("\n");
        } else if (stmt instanceof WhileStmt ws) {
            printIndent();
            sb.append("while (").append(emitCondition(ws.condition())).append(") ");
            emitInlineOrBlockStatement(ws.body());
            sb.append("\n");
        } else if (stmt instanceof DoWhileStmt dws) {
            printIndent();
            sb.append("do ");
            emitInlineOrBlockStatement(dws.body());
            sb.append(" while (").append(emitCondition(dws.condition())).append(");\n");

        } else if (stmt instanceof TryStmt ts) {
            printIndent();
            sb.append("try {\n");
            indentLevel++;
            for (Statement s : ts.body().statements()) {
                emitStatement(s);
            }
            indentLevel--;
            printIndent();
            sb.append("}");
            for (CatchClause cc : ts.catchClauses()) {
                sb.append(" catch (").append(emitType(cc.parameter().type())).append(" ")
                        .append(cc.parameter().name()).append(") {\n");
                indentLevel++;
                for (Statement s : cc.body().statements()) {
                    emitStatement(s);
                }
                indentLevel--;
                printIndent();
                sb.append("}");
            }
            if (ts.finallyBlock().isPresent()) {
                sb.append(" finally {\n");
                indentLevel++;
                for (Statement s : ts.finallyBlock().get().statements()) {
                    emitStatement(s);
                }
                indentLevel--;
                printIndent();
                sb.append("}");
            }
            sb.append("\n");
        } else if (stmt instanceof ReturnStmt rs) {
            printIndent();
            sb.append("return");
            if (rs.expression().isPresent()) {
                sb.append(" ").append(emitExpression(rs.expression().get()));
            }
            sb.append(";\n");
        } else if (stmt instanceof YieldStmt ys) {
            printIndent();
            sb.append("yield ").append(emitExpression(ys.expression())).append(";\n");
        } else if (stmt instanceof ThrowStmt ts) {
            printIndent();
            sb.append("throw ").append(emitExpression(ts.expression())).append(";\n");
        } else if (stmt instanceof EmitStmt es) {
            printIndent();
            sb.append("__emit.accept(").append(emitExpression(es.expression())).append(");\n");
        } else if (stmt instanceof BreakStmt) {
            printIndent();
            sb.append("break;\n");
        } else if (stmt instanceof ContinueStmt) {
            printIndent();
            sb.append("continue;\n");
        } else if (stmt instanceof ExprStmt es) {
            printIndent();
            sb.append(emitExpression(es.expression())).append(";\n");
        }
    }

    private void emitInlineOrBlockStatement(Statement stmt) {
        if (stmt instanceof BlockStmt bs) {
            sb.append("{\n");
            indentLevel++;
            for (Statement s : bs.statements()) {
                emitStatement(s);
            }
            indentLevel--;
            printIndent();
            sb.append("}");
        } else {
            sb.append("{\n");
            indentLevel++;
            emitStatement(stmt);
            indentLevel--;
            printIndent();
            sb.append("}");
        }
    }

    private String emitStatementToString(Statement stmt) {
        int savedIndent = indentLevel;
        indentLevel = 0;
        StringBuilder oldSb = new StringBuilder(sb);
        sb.setLength(0);
        emitStatement(stmt);
        String res = sb.toString();
        sb.setLength(0);
        sb.append(oldSb);
        indentLevel = savedIndent;
        return res;
    }

    private boolean isNativeBooleanExpr(Expression expr) {
        if (expr instanceof LiteralExpr lit && lit.value() instanceof Boolean) {
            return true;
        }
        if (expr instanceof BinaryExpr bin) {
            accent.lexer.TokenType type = bin.operator().type();
            return type == accent.lexer.TokenType.EQUAL_EQUAL || type == accent.lexer.TokenType.BANG_EQUAL ||
                   type == accent.lexer.TokenType.LESS || type == accent.lexer.TokenType.LESS_EQUAL ||
                   type == accent.lexer.TokenType.GREATER || type == accent.lexer.TokenType.GREATER_EQUAL ||
                   type == accent.lexer.TokenType.AND_AND || type == accent.lexer.TokenType.OR_OR ||
                   type == accent.lexer.TokenType.AND || type == accent.lexer.TokenType.OR ||
                   type == accent.lexer.TokenType.NAND || type == accent.lexer.TokenType.NOR ||
                   type == accent.lexer.TokenType.XOR || type == accent.lexer.TokenType.XNOR;
        }
        if (expr instanceof UnaryExpr un) {
            accent.lexer.TokenType type = un.operator().type();
            return type == accent.lexer.TokenType.BANG || type == accent.lexer.TokenType.NOT;
        }
        return false;
    }

    private String emitCondition(Expression cond) {
        if (isNativeBooleanExpr(cond)) {
            return emitExpression(cond);
        }
        return "accent.runtime.AccentRuntime.isTruthy(" + emitExpression(cond) + ")";
    }

    // --- Expression Emitter ---

    private String emitExpression(Expression expr) {
        if (expr instanceof LiteralExpr lit) {
            if (lit.value() == null) return "null";
            if (lit.value() instanceof String) return "\"" + escapeJavaString((String) lit.value()) + "\"";
            if (lit.value() instanceof Boolean) return lit.value().toString();
            if (lit.value() instanceof Number) {
                // If it is integer, format as integer
                double d = ((Number) lit.value()).doubleValue();
                if (d == (long) d) {
                    return Long.toString((long) d);
                }
                return Double.toString(d);
            }
            return lit.value().toString();
        } else if (expr instanceof IdentifierExpr id) {
            return id.name();
        } else if (expr instanceof ThisExpr) {
            // Extension receiver mapping: this -> _this
            return isInsideExtension ? "_this" : "this";
        } else if (expr instanceof SuperExpr) {
            return "super";
        } else if (expr instanceof TernaryExpr tern) {
            return "(" + emitCondition(tern.condition()) + " ? " + emitExpression(tern.thenBranch()) + " : " + emitExpression(tern.elseBranch()) + ")";
        } else if (expr instanceof JsonExpr json) {
            StringBuilder sb = new StringBuilder();
            String template = json.template();
            String[] parts = template.split("\\?", -1);
            sb.append("\"");
            int j = 0;
            for (int i = 0; i < parts.length; i++) {
                sb.append(escapeJavaString(parts[i]));
                if (i < parts.length - 1) {
                    sb.append("\" + ").append(emitExpression(json.interpolations().get(j++))).append(" + \"");
                }
            }
            sb.append("\"");
            String classLiteral = emitType(json.resultType()) + ".class";
            return "accent.json.Json.parse(" + sb.toString() + ", " + classLiteral + ")";
        } else if (expr instanceof SqlExpr sql) {
            String sqlString = "\"" + escapeJavaString(sql.query()) + "\"";
            String paramList;
            if (sql.interpolations().isEmpty()) {
                paramList = "java.util.List.of()";
            } else {
                StringBuilder psb = new StringBuilder("java.util.List.of(");
                for (int i = 0; i < sql.interpolations().size(); i++) {
                    if (i > 0) psb.append(", ");
                    psb.append(emitExpression(sql.interpolations().get(i)));
                }
                psb.append(")");
                paramList = psb.toString();
            }
            String classLiteral = sql.resultType().isPresent() 
                    ? emitType(sql.resultType().get()) + ".class"
                    : "java.util.Map.class";
            
            String trimmed = sql.query().trim().toUpperCase();
            boolean isSelect = trimmed.startsWith("SELECT") || trimmed.startsWith("WITH") || trimmed.startsWith("SHOW") || trimmed.startsWith("DESCRIBE");
            String castType;
            if (isSelect) {
                castType = sql.resultType().isPresent() 
                        ? "java.util.List<" + emitType(sql.resultType().get()) + ">"
                        : "java.util.List<java.util.Map<String, Object>>";
            } else {
                castType = "Integer";
            }
            
            return "((" + castType + ") accent.sql.Sql.execute(" + sqlString + ", " + paramList + ", " + classLiteral + "))";
        } else if (expr instanceof InterpolatedStringExpression interp) {
            if (interp.parts().isEmpty()) {
                return "\"\"";
            }
            StringBuilder concat = new StringBuilder();
            boolean isFirst = true;
            boolean startWithEmpty = false;
            
            if (interp.parts().get(0) instanceof InterpolatedExpressionPart) {
                startWithEmpty = true;
            }
            
            if (startWithEmpty) {
                concat.append("\"\"");
                isFirst = false;
            }
            
            for (InterpolatedStringPart part : interp.parts()) {
                if (!isFirst) {
                    concat.append(" + ");
                }
                isFirst = false;
                
                if (part instanceof InterpolatedTextPart itp) {
                    concat.append("\"").append(escapeJavaString(itp.text())).append("\"");
                } else if (part instanceof InterpolatedExpressionPart iep) {
                    concat.append(emitExpression(iep.expression()));
                }
            }
            return "(" + concat.toString() + ")";
        } else if (expr instanceof BinaryExpr bin) {
            if (bin.operator().type() == accent.lexer.TokenType.ASSIGN) {
                return emitExpression(bin.left()) + " = " + emitExpression(bin.right());
            }
            accent.lexer.TokenType type = bin.operator().type();
            if (type == accent.lexer.TokenType.AND || type == accent.lexer.TokenType.AND_AND) {
                return "(" + emitCondition(bin.left()) + " && " + emitCondition(bin.right()) + ")";
            }
            if (type == accent.lexer.TokenType.OR || type == accent.lexer.TokenType.OR_OR) {
                return "(" + emitCondition(bin.left()) + " || " + emitCondition(bin.right()) + ")";
            }
            if (type == accent.lexer.TokenType.NAND) {
                return "(!(" + emitCondition(bin.left()) + " && " + emitCondition(bin.right()) + "))";
            }
            if (type == accent.lexer.TokenType.NOR) {
                return "(!(" + emitCondition(bin.left()) + " || " + emitCondition(bin.right()) + "))";
            }
            if (type == accent.lexer.TokenType.XOR) {
                return "(" + emitCondition(bin.left()) + " != " + emitCondition(bin.right()) + ")";
            }
            if (type == accent.lexer.TokenType.XNOR) {
                return "(" + emitCondition(bin.left()) + " == " + emitCondition(bin.right()) + ")";
            }
            String left = emitExpression(bin.left());
            String right = emitExpression(bin.right());
            return "(" + left + " " + bin.operator().lexeme() + " " + right + ")";
        } else if (expr instanceof UnaryExpr un) {
            String opLex = un.operator().lexeme();
            // Textual 'not' or '!' translates to '!' with emitCondition
            if (un.operator().type() == accent.lexer.TokenType.NOT || un.operator().type() == accent.lexer.TokenType.BANG) {
                return "(!" + emitCondition(un.expression()) + ")";
            }

            if (opLex.equals("++") || opLex.equals("--")) {
                if (un.isPostfix()) {
                    return emitExpression(un.expression()) + opLex;
                }
                return opLex + emitExpression(un.expression());
            }
            if (un.isPostfix()) {
                return "(" + emitExpression(un.expression()) + opLex + ")";
            }
            return "(" + opLex + emitExpression(un.expression()) + ")";
        } else if (expr instanceof MemberAccessExpr ma) {
            return emitExpression(ma.receiver()) + "." + ma.memberName();
        } else if (expr instanceof MethodCallExpr mc) {
            StringBuilder r = new StringBuilder();
            if (mc.receiver() != null) {
                r.append(emitExpression(mc.receiver())).append(".");
            }
            r.append(mc.methodName());
            if (!mc.typeArguments().isEmpty()) {
                r.append("<");
                for (int i = 0; i < mc.typeArguments().size(); i++) {
                    if (i > 0) r.append(", ");
                    r.append(emitType(mc.typeArguments().get(i)));
                }
                r.append(">");
            }
            r.append("(");
            for (int i = 0; i < mc.arguments().size(); i++) {
                if (i > 0) r.append(", ");
                r.append(emitExpression(mc.arguments().get(i)));
            }
            r.append(")");
            return r.toString();
        } else if (expr instanceof NewObjectExpr no) {
            if (no.type() instanceof ArrayTypeNode arr) {
                StringBuilder r = new StringBuilder("new ").append(emitType(arr.elementType())).append("[");
                for (int i = 0; i < no.arguments().size(); i++) {
                    if (i > 0) r.append("][");
                    r.append(emitExpression(no.arguments().get(i)));
                }
                r.append("]");
                return r.toString();
            }
            StringBuilder r = new StringBuilder("new ").append(emitType(no.type())).append("(");
            for (int i = 0; i < no.arguments().size(); i++) {
                if (i > 0) r.append(", ");
                r.append(emitExpression(no.arguments().get(i)));
            }
            r.append(")");
            return r.toString();
        } else if (expr instanceof NewArrayExpr na) {
            TypeNode baseType = na.type();
            while (baseType instanceof ArrayTypeNode arr) {
                baseType = arr.elementType();
            }
            StringBuilder r = new StringBuilder("new ").append(emitType(baseType));
            for (Expression dim : na.dimensions()) {
                r.append("[").append(emitExpression(dim)).append("]");
            }
            if (na.dimensions().isEmpty()) {
                r.append("[]");
            }
            if (na.initializer().isPresent()) {
                r.append(" ").append(emitExpression(na.initializer().get()));
            }
            return r.toString();
        } else if (expr instanceof ArrayInitializerExpr ai) {
            StringBuilder r = new StringBuilder("{");
            for (int i = 0; i < ai.expressions().size(); i++) {
                if (i > 0) r.append(", ");
                r.append(emitExpression(ai.expressions().get(i)));
            }
            r.append("}");
            return r.toString();
        } else if (expr instanceof ArrayAccessExpr aa) {
            return emitExpression(aa.receiver()) + "[" + emitExpression(aa.index()) + "]";
        } else if (expr instanceof LambdaExpr le) {
            StringBuilder r = new StringBuilder("(");
            for (int i = 0; i < le.parameters().size(); i++) {
                if (i > 0) r.append(", ");
                Parameter p = le.parameters().get(i);
                r.append(p.name()); // standard Java lambdas omit parameter types in inferred contexts
            }
            r.append(") -> ");
            if (le.body() instanceof Expression) {
                r.append(emitExpression((Expression) le.body()));
            } else {
                // Block Statement
                r.append(emitStatementToString((Statement) le.body()));
            }
            return r.toString();
        } else if (expr instanceof NamedArgExpr named) {
            return named.name() + ": " + emitExpression(named.expression());
        }

        return "";
    }

    private static String escapeJavaString(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
