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
    private boolean nonNullMethodReturn;
    private TypeNode methodReturnType;
    private java.util.Set<String> guardedParameterNames = java.util.Set.of();
    private boolean beforeParameterGuards;
    private final java.util.Deque<String> loopBrokenVars = new java.util.ArrayDeque<>();
    private int loopCounter = 0;

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

        // Declarations: Accent files hold exactly one (always public) top-level type, plus optional extensions.
        String primaryDeclName = null;
        for (TypeDeclaration decl : unit.declarations()) {
            if (!(decl instanceof ExtensionDecl)) {
                if (decl.modifiers().contains("public")) {
                    primaryDeclName = decl.name();
                    break;
                }
                if (primaryDeclName == null) {
                    primaryDeclName = decl.name();
                }
            }
        }
        for (TypeDeclaration decl : unit.declarations()) {
            emitTypeDeclaration(decl, primaryDeclName);
            sb.append("\n");
        }

        return sb.toString();
    }

    private void printIndent() {
        sb.append("    ".repeat(indentLevel));
    }

    private void emitTypeDeclaration(TypeDeclaration decl, String primaryDeclName) {
        if (decl instanceof ExtensionDecl ed) {
            emitExtensionDeclaration(ed);
            return;
        }

        String savedClassName = currentClassName;
        currentClassName = decl.name();

        if (decl.isSingleton()) {
            printIndent();
            sb.append("@accent.runtime.Singleton\n");
        }
        printIndent();
        // Modifiers
        for (String mod : decl.modifiers()) {
            if (mod.equals("singleton")) continue;
            if (mod.equals("public") && !decl.name().equals(primaryDeclName)) {
                continue;
            }
            sb.append(mod).append(" ");
        }

        if (decl instanceof ClassDecl cd) {
            if (cd.isSingleton() && !cd.modifiers().contains("final")) {
                sb.append("final ");
            }
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
        } else if (decl instanceof ModelDecl md) {
            if (!md.modifiers().contains("final")) {
                sb.append("final ");
            }
            sb.append("class ").append(md.name());
            emitTypeParameters(md.typeParameters());
            sb.append(" implements java.io.Serializable, accent.runtime.AccentModel");
            for (TypeNode itf : md.interfaces()) {
                sb.append(", ").append(emitType(itf));
            }
        } else if (decl instanceof RecordDecl rd) {
            sb.append("record ").append(rd.name());
            emitTypeParameters(rd.typeParameters());
            sb.append("(");
            for (int i = 0; i < rd.components().size(); i++) {
                if (i > 0) sb.append(", ");
                Parameter p = rd.components().get(i);
                emitParameter(p);
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

        if (decl.isSingleton()) {
            printIndent();
            sb.append("private static final class Holder {\n");
            indentLevel++;
            printIndent();
            sb.append("private static final ").append(decl.name()).append(" INSTANCE = ");
            if (decl instanceof ModelDecl) sb.append(decl.name()).append(".$accentCreateSingleton();\n");
            else sb.append("new ").append(decl.name()).append("();\n");
            indentLevel--;
            printIndent();
            sb.append("}\n\n");
            printIndent();
            sb.append("@accent.runtime.NonNullReturn public static ").append(decl.name()).append(" instance() { return Holder.INSTANCE; }\n\n");
            printIndent();
            sb.append("private Object readResolve() { return Holder.INSTANCE; }\n\n");
            if (decl instanceof ClassDecl && decl.members().stream().noneMatch(m -> m instanceof ConstructorDecl)) {
                printIndent();
                sb.append("private ").append(decl.name()).append("() {}\n\n");
            }
        }

        if (decl instanceof RecordDecl rd
                && rd.components().stream().anyMatch(p -> p.type().isNonNull() && !isPrimitive(p.type()))
                && rd.members().stream().noneMatch(m -> m instanceof ConstructorDecl c
                        && c.parameters().size() == rd.components().size())) {
            printIndent();
            sb.append("public ").append(rd.name()).append(" {\n");
            indentLevel++;
            for (Parameter p : rd.components()) {
                if ((p.type().isNonNull() || p.type().hasNonNullElements()) && !isPrimitive(p.type())) {
                    printIndent();
                    if (p.type().hasNonNullElements() && guardedParameterNames.contains(p.name()))
                        sb.append("final ").append(emitType(p.type())).append(" ").append(p.name()).append(" = ")
                                .append(guardValue("$accent$" + p.name(), p.type())).append(";\n");
                    else if (p.type().hasNonNullElements()) sb.append(p.name()).append(" = ").append(guardValue(p.name(), p.type())).append(";\n");
                    else sb.append("java.util.Objects.requireNonNull(").append(p.name()).append(");\n");
                }
            }
            indentLevel--;
            printIndent();
            sb.append("}\n");
        }

        if (decl instanceof ModelDecl md) {
            printIndent();
            sb.append("private static final long serialVersionUID = 1L;\n\n");

            // Fields
            for (Parameter p : md.components()) {
                printIndent();
                if (p.isMutable()) {
                    sb.append("private ");
                } else {
                    sb.append("private final ");
                }
                sb.append(emitType(p.type())).append(" ").append(p.name()).append(";\n");
            }
            sb.append("\n");

            // Constructor
            printIndent();
            sb.append(md.isSingleton() ? "private " : "public ").append(md.name()).append("(");
            for (int i = 0; i < md.components().size(); i++) {
                if (i > 0) sb.append(", ");
                Parameter p = md.components().get(i);
                emitParameter(p);
            }
            sb.append(") {\n");
            indentLevel++;
            for (Parameter p : md.components()) {
                printIndent();
                sb.append("this.").append(p.name()).append(" = ").append(guardValue(p.name(), p.type())).append(";\n");
            }
            indentLevel--;
            printIndent();
            sb.append("}\n\n");

            // Getters
            for (Parameter p : md.components()) {
                printIndent();
                if (p.type().hasNonNullElements()) sb.append("@accent.runtime.NonNullElements ");
                emitNullabilityAnnotation(p.type());
                sb.append("public ").append(emitType(p.type())).append(" ").append(p.name()).append("() {\n");
                indentLevel++;
                printIndent();
                sb.append("return this.").append(p.name()).append(";\n");
                indentLevel--;
                printIndent();
                sb.append("}\n\n");
            }

            // Setters for var fields
            for (Parameter p : md.components()) {
                if (p.isMutable()) {
                    String setterName = p.name();
                    printIndent();
                    sb.append("public void ").append(setterName).append("(");
                    emitParameter(p);
                    sb.append(") {\n");
                    indentLevel++;
                    printIndent();
                    sb.append("this.").append(p.name()).append(" = ").append(guardValue(p.name(), p.type())).append(";\n");
                    indentLevel--;
                    printIndent();
                    sb.append("}\n\n");
                }
            }

            if (!md.isSingleton()) {
                // Copy method
                printIndent();
                sb.append("@accent.runtime.NonNullReturn public ").append(md.name()).append(" copy(");
                for (int i = 0; i < md.components().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Parameter p = md.components().get(i);
                    emitParameter(p);
                }
                sb.append(") {\n");
                indentLevel++;
                printIndent();
                sb.append("return new ").append(md.name()).append("(");
                for (int i = 0; i < md.components().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(md.components().get(i).name());
                }
                sb.append(");\n");
                indentLevel--;
                printIndent();
                sb.append("}\n\n");

            }

            // equals method
            printIndent();
            sb.append("@Override\n");
            printIndent();
            sb.append("public boolean equals(Object o) {\n");
            indentLevel++;
            printIndent();
            sb.append("if (this == o) return true;\n");
            printIndent();
            sb.append("if (o == null || getClass() != o.getClass()) return false;\n");
            printIndent();
            sb.append(md.name()).append(" that = (").append(md.name()).append(") o;\n");
            if (md.components().isEmpty()) {
                printIndent();
                sb.append("return true;\n");
            } else {
                printIndent();
                sb.append("return ");
                for (int i = 0; i < md.components().size(); i++) {
                    if (i > 0) sb.append(" && ");
                    Parameter p = md.components().get(i);
                    sb.append("java.util.Objects.equals(this.").append(p.name()).append(", that.").append(p.name()).append(")");
                }
                sb.append(";\n");
            }
            indentLevel--;
            printIndent();
            sb.append("}\n\n");

            // hashCode method
            printIndent();
            sb.append("@Override\n");
            printIndent();
            sb.append("public int hashCode() {\n");
            indentLevel++;
            printIndent();
            sb.append("return java.util.Objects.hash(");
            for (int i = 0; i < md.components().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("this.").append(md.components().get(i).name());
            }
            sb.append(");\n");
            indentLevel--;
            printIndent();
            sb.append("}\n\n");

            // toString method
            printIndent();
            sb.append("@Override\n");
            printIndent();
            sb.append("@accent.runtime.NonNullReturn public String toString() {\n");
            indentLevel++;
            printIndent();
            sb.append("return \"").append(md.name()).append("[\"");
            if (!md.components().isEmpty()) sb.append(" + ");
            for (int i = 0; i < md.components().size(); i++) {
                if (i > 0) sb.append(" + \", \" + ");
                Parameter p = md.components().get(i);
                sb.append("\"").append(p.name()).append("=\" + this.").append(p.name());
            }
            sb.append(" + \"]\";\n");
            indentLevel--;
            printIndent();
            sb.append("}\n\n");
        }

        if (decl instanceof RecordDecl rd && rd.members().stream().noneMatch(m ->
                m instanceof MethodDecl method && method.name().equals("toString") && method.parameters().isEmpty())) {
            printIndent();
            sb.append("@accent.runtime.NonNullReturn public String toString() { return \"")
                    .append(rd.name()).append("[\"");
            for (int i = 0; i < rd.components().size(); i++) {
                Parameter p = rd.components().get(i);
                sb.append(" + \"").append(i == 0 ? "" : ", ").append(p.name()).append("=\" + this.").append(p.name());
            }
            sb.append(" + \"]\"; }\n");
        }

        if (decl instanceof ModelDecl || decl instanceof RecordDecl) {
            for (String format : List.of("Json", "Xml")) {
                String methodName = "to" + format;
                boolean custom = decl.members().stream().anyMatch(m -> m instanceof MethodDecl method
                        && method.name().equals(methodName) && method.parameters().isEmpty());
                if (!custom) {
                    printIndent();
                    sb.append("@accent.runtime.NonNullReturn public String ").append(methodName).append("() { return accent.runtime.DataSerialization.")
                            .append(methodName)
                            .append("(this); }\n\n");
                }
            }
        }

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
        java.util.Set<String> savedParameterNames = guardedParameterNames;
        boolean savedBeforeGuards = beforeParameterGuards;
        List<Parameter> memberParameters = member instanceof MethodDecl m ? m.parameters()
                : member instanceof ConstructorDecl c ? c.parameters() : List.of();
        guardedParameterNames = memberParameters.stream().filter(p -> p.type().hasNonNullElements())
                .map(Parameter::name).collect(java.util.stream.Collectors.toSet());
        beforeParameterGuards = true;
        TypeNode savedReturnType = methodReturnType;
        methodReturnType = member instanceof MethodDecl method ? method.returnType() : null;
        boolean savedReturnContract = nonNullMethodReturn;
        nonNullMethodReturn = member instanceof MethodDecl method && !method.isGenerator()
                && method.returnType().isNonNull() && !isPrimitive(method.returnType());
        if (member instanceof FieldDecl fd) {
            emitNullabilityAnnotation(fd.type());
            if (fd.type().hasNonNullElements()) sb.append("@accent.runtime.NonNullElements ");
            printIndent();
            for (String mod : fd.modifiers()) {
                sb.append(mod).append(" ");
            }
            sb.append(emitType(fd.type())).append(" ").append(fd.name());
            if (fd.initializer().isPresent()) {
                sb.append(" = ").append(guardValue(emitInitializer(fd.initializer().get(), fd.type()), fd.type()));
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
            Statement firstStmt = cd.body().statements().isEmpty() ? null : cd.body().statements().get(0);
            boolean hasSuperCall = false;
            if (firstStmt instanceof ExprStmt es && es.expression() instanceof MethodCallExpr mc && "super".equals(mc.methodName())) {
                hasSuperCall = true;
                emitStatement(firstStmt);
            }
            // Inject runtime null checks for non-null parameters
            for (Parameter p : cd.parameters()) {
                if ((p.type().isNonNull() || p.type().hasNonNullElements()) && !isPrimitive(p.type())) {
                    printIndent();
                    if (p.type().hasNonNullElements() && guardedParameterNames.contains(p.name()))
                        sb.append("final ").append(emitType(p.type())).append(" ").append(p.name()).append(" = ")
                                .append(guardValue("$accent$" + p.name(), p.type())).append(";\n");
                    else if (p.type().hasNonNullElements()) sb.append(p.name()).append(" = ").append(guardValue(p.name(), p.type())).append(";\n");
                    else sb.append("java.util.Objects.requireNonNull(").append(p.name()).append(");\n");
                }
            }
            beforeParameterGuards = false;
            for (int i = hasSuperCall ? 1 : 0; i < cd.body().statements().size(); i++) {
                emitStatement(cd.body().statements().get(i));
            }
            indentLevel--;
            printIndent();
            sb.append("}\n");
        } else if (member instanceof MethodDecl md) {
            printIndent();
            if (!md.isGenerator()) emitNullabilityAnnotation(md.returnType());
            if (md.returnType().hasNonNullElements()) sb.append("@accent.runtime.NonNullElements ");
            
            // Extension method modifier mapping: static helper
            for (String mod : md.modifiers()) {
                sb.append(mod).append(" ");
            }
            if (isInsideExtension && !md.modifiers().contains("static")) sb.append("static ");

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
                    if ((p.type().isNonNull() || p.type().hasNonNullElements()) && !isPrimitive(p.type())) {
                        printIndent();
                        if (p.type().hasNonNullElements() && guardedParameterNames.contains(p.name()))
                        sb.append("final ").append(emitType(p.type())).append(" ").append(p.name()).append(" = ")
                                .append(guardValue("$accent$" + p.name(), p.type())).append(";\n");
                    else if (p.type().hasNonNullElements()) sb.append(p.name()).append(" = ").append(guardValue(p.name(), p.type())).append(";\n");
                    else sb.append("java.util.Objects.requireNonNull(").append(p.name()).append(");\n");
                    }
                }

                beforeParameterGuards = false;
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
        nonNullMethodReturn = savedReturnContract;
        methodReturnType = savedReturnType;
        guardedParameterNames = savedParameterNames;
        beforeParameterGuards = savedBeforeGuards;
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

    private void emitNullabilityAnnotation(TypeNode type) {
        if (!isPrimitive(type)) sb.append(type.isNonNull() ? "@accent.runtime.NonNullReturn " : "@accent.runtime.Nullable ");
    }

    private void emitParameter(Parameter parameter) {
        emitNullabilityAnnotation(parameter.type());
        if (parameter.type().hasNonNullElements()) sb.append("@accent.runtime.NonNullElements ");
        for (String annotation : parameter.annotations()) sb.append(annotation).append(" ");
        sb.append(emitType(parameter.type())).append(parameter.isVarargs() ? "... " : " ").append(beforeParameterGuards && guardedParameterNames.contains(parameter.name())
                ? "$accent$" + parameter.name() : parameter.name());
    }

    private void emitParameters(List<Parameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            Parameter p = parameters.get(i);
            emitParameter(p);
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
                sb.append(" = ").append(varDecl.nonNullElements()
                        ? "accent.runtime.ElementContracts." + (varDecl.nonNullBinding() ? "protect" : "protectNullable") + "(" + emitInitializer(varDecl.initializer().get(), varDecl.type().orElse(null)) + ")"
                        : guardBinding(emitInitializer(varDecl.initializer().get(), varDecl.type().orElse(null)), varDecl.nonNullBinding()));
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
            if (fs.elseBranch().isPresent()) {
                String id = "loop_" + (loopCounter++);
                String ranVar = "__ran_" + id;
                String brokenVar = "__broken_" + id;
                printIndent();
                sb.append("boolean ").append(ranVar).append(" = false;\n");
                printIndent();
                sb.append("boolean ").append(brokenVar).append(" = false;\n");
                loopBrokenVars.push(brokenVar);

                printIndent();
                sb.append("for (");
                if (fs.init().isPresent()) {
                    String initStr = emitStatementToString(fs.init().get()).trim();
                    if (initStr.endsWith(";")) initStr = initStr.substring(0, initStr.length() - 1);
                    sb.append(initStr);
                }
                sb.append("; ");
                fs.condition().ifPresent(c -> sb.append(emitCondition(c)));
                sb.append("; ");
                fs.update().ifPresent(u -> sb.append(emitExpression(u)));
                sb.append(") {\n");
                indentLevel++;
                printIndent();
                sb.append(ranVar).append(" = true;\n");
                if (fs.body() instanceof BlockStmt bs) {
                    for (Statement s : bs.statements()) {
                        emitStatement(s);
                    }
                } else {
                    emitStatement(fs.body());
                }
                indentLevel--;
                printIndent();
                sb.append("}\n");

                loopBrokenVars.pop();

                printIndent();
                sb.append("if (!").append(ranVar).append(" || !").append(brokenVar).append(") ");
                emitInlineOrBlockStatement(fs.elseBranch().get());
                sb.append("\n");
            } else {
                loopBrokenVars.push("");
                printIndent();
                sb.append("for (");
                if (fs.init().isPresent()) {
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
                loopBrokenVars.pop();
            }
        } else if (stmt instanceof ForEachStmt fes) {
            if (fes.elseBranch().isPresent()) {
                String id = "loop_" + (loopCounter++);
                String ranVar = "__ran_" + id;
                String brokenVar = "__broken_" + id;
                String iterableVar = "__iterable_" + id;
                printIndent();
                sb.append("final var ").append(iterableVar).append(" = ").append(emitExpression(fes.iterable())).append(";\n");
                printIndent();
                sb.append("boolean ").append(ranVar).append(" = false;\n");
                printIndent();
                sb.append("boolean ").append(brokenVar).append(" = false;\n");
                loopBrokenVars.push(brokenVar);

                printIndent();
                sb.append("if (accent.runtime.AccentRuntime.isTruthy(").append(iterableVar).append(")) {\n");
                indentLevel++;
                printIndent();
                sb.append("for (").append(emitType(fes.parameter().type())).append(" ")
                        .append(fes.parameter().name()).append(" : ").append(iterableVar).append(") {\n");
                indentLevel++;
                printIndent();
                sb.append(ranVar).append(" = true;\n");
                if (fes.body() instanceof BlockStmt bs) {
                    for (Statement s : bs.statements()) {
                        emitStatement(s);
                    }
                } else {
                    emitStatement(fes.body());
                }
                indentLevel--;
                printIndent();
                sb.append("}\n");
                indentLevel--;
                printIndent();
                sb.append("}\n");

                loopBrokenVars.pop();

                printIndent();
                sb.append("if (!").append(ranVar).append(" || !").append(brokenVar).append(") ");
                emitInlineOrBlockStatement(fes.elseBranch().get());
                sb.append("\n");
            } else {
                loopBrokenVars.push("");
                printIndent();
                sb.append("for (").append(emitType(fes.parameter().type())).append(" ")
                        .append(fes.parameter().name()).append(" : ").append(emitExpression(fes.iterable())).append(") ");
                emitInlineOrBlockStatement(fes.body());
                sb.append("\n");
                loopBrokenVars.pop();
            }
        } else if (stmt instanceof WhileStmt ws) {
            if (ws.elseBranch().isPresent()) {
                Expression condition = ws.condition();
                // Java rejects a loop whose condition is the constant false, but its Accent else is valid.
                if (condition instanceof LiteralExpr literal && Boolean.FALSE.equals(literal.value())) {
                    condition = new MethodCallExpr(new MemberAccessExpr(new IdentifierExpr("java.lang.Boolean", null),
                            "TRUE", false, null), "equals", List.of(), List.of(condition), false, null);
                }
                emitStatement(new ForStmt(Optional.empty(), Optional.of(condition), Optional.empty(), ws.body(), ws.elseBranch()));
                return;
            }
            loopBrokenVars.push("");
            printIndent();
            sb.append("while (").append(emitCondition(ws.condition())).append(") ");
            emitInlineOrBlockStatement(ws.body());
            sb.append("\n");
            loopBrokenVars.pop();
        } else if (stmt instanceof DoWhileStmt dws) {
            String brokenVar = dws.elseBranch().isPresent() ? "__broken_loop_" + (loopCounter++) : "";
            if (!brokenVar.isEmpty()) {
                printIndent();
                sb.append("boolean ").append(brokenVar).append(" = false;\n");
            }
            loopBrokenVars.push(brokenVar);
            printIndent();
            sb.append("do ");
            emitInlineOrBlockStatement(dws.body());
            sb.append(" while (").append(emitCondition(dws.condition())).append(");\n");
            loopBrokenVars.pop();
            if (dws.elseBranch().isPresent()) {
                printIndent();
                sb.append("if (!").append(brokenVar).append(") ");
                emitInlineOrBlockStatement(dws.elseBranch().get());
                sb.append("\n");
            }

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
                sb.append(" ").append(methodReturnType != null && (nonNullMethodReturn || methodReturnType.hasNonNullElements()) ? guardValue(emitExpression(rs.expression().get()), methodReturnType) : guardBinding(emitExpression(rs.expression().get()), nonNullMethodReturn));
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
            if (!loopBrokenVars.isEmpty() && !loopBrokenVars.peek().isEmpty()) {
                sb.append(loopBrokenVars.peek()).append(" = true; ");
            }
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
                   type == accent.lexer.TokenType.AND || type == accent.lexer.TokenType.OR;
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

    private String emitInitializer(Expression expression, TypeNode type) {
        if (expression instanceof ArrayInitializerExpr && type instanceof ArrayTypeNode)
            return "new " + emitType(type) + emitExpression(expression);
        return emitExpression(expression);
    }

    private String guardValue(String value, TypeNode type) {
        if (type.hasNonNullElements()) return "accent.runtime.ElementContracts." + (type.isNonNull() ? "protect" : "protectNullable") + "(" + value + ")";
        return guardBinding(value, type.isNonNull() && !isPrimitive(type));
    }

    private String guardBinding(String value, boolean nonNull) {
        return nonNull ? "java.util.Objects.requireNonNull(" + value + ")" : value;
    }

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
            return beforeParameterGuards && guardedParameterNames.contains(id.name()) ? "$accent$" + id.name() : id.name();
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
                boolean nonNull = bin.left() instanceof IdentifierExpr id && id.nonNullBinding()
                        || bin.left() instanceof MemberAccessExpr ma && ma.nonNullBinding();
                boolean elements = bin.left() instanceof IdentifierExpr id && id.nonNullElements()
                        || bin.left() instanceof MemberAccessExpr ma && ma.nonNullElements();
                if (bin.left() instanceof ArrayAccessExpr aa) {
                    String target = emitExpression(aa.receiver()) + "[" + emitExpression(aa.index()) + "]";
                    return target + " = " + guardBinding(emitExpression(bin.right()), aa.nonNullElement());
                }
                return emitExpression(bin.left()) + " = " + (elements
                        ? "accent.runtime.ElementContracts." + (nonNull ? "protect" : "protectNullable") + "(" + emitExpression(bin.right()) + ")"
                        : guardBinding(emitExpression(bin.right()), nonNull));
            }
            accent.lexer.TokenType type = bin.operator().type();
            if (type == accent.lexer.TokenType.AND || type == accent.lexer.TokenType.AND_AND) {
                return "(" + emitCondition(bin.left()) + " && " + emitCondition(bin.right()) + ")";
            }
            if (type == accent.lexer.TokenType.OR || type == accent.lexer.TokenType.OR_OR) {
                return "(" + emitCondition(bin.left()) + " || " + emitCondition(bin.right()) + ")";
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
            if (!mc.typeArguments().isEmpty()) {
                r.append("<");
                for (int i = 0; i < mc.typeArguments().size(); i++) {
                    if (i > 0) r.append(", ");
                    r.append(emitType(mc.typeArguments().get(i)));
                }
                r.append(">");
            }
            r.append(mc.methodName());
            r.append("(");
            for (int i = 0; i < mc.arguments().size(); i++) {
                if (i > 0) r.append(", ");
                r.append(emitExpression(mc.arguments().get(i)));
            }
            r.append(")");
            return guardBinding(r.toString(), mc.nonNullResult());
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
            return guardBinding(emitExpression(aa.receiver()) + "[" + emitExpression(aa.index()) + "]", aa.nonNullElement());
        } else if (expr instanceof LambdaExpr le) {
            boolean savedReturnContract = nonNullMethodReturn;
            nonNullMethodReturn = false;
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
            nonNullMethodReturn = savedReturnContract;
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
