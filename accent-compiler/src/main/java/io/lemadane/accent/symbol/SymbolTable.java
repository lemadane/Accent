package io.lemadane.accent.symbol;

import io.lemadane.accent.ast.Ast.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

public final class SymbolTable {
    private final Map<String, TypeInfo> typeCache = new HashMap<>();
    private final Map<String, List<TypeInfo>> packageTypes = new HashMap<>();
    private final ClassLoader classLoader;
    private final List<ExtensionDecl> extensions = new ArrayList<>();

    public SymbolTable() {
        this(null);
    }

    public SymbolTable(ClassLoader classLoader) {
        this.classLoader = classLoader;
        prepopulatePrimitives();
    }

    private void prepopulatePrimitives() {
        String[] primitives = {"int", "long", "double", "float", "boolean", "char", "byte", "short", "void"};
        for (String prim : primitives) {
            TypeInfo info = new TypeInfo(
                    prim,
                    false, // isInterface
                    false, // isRecord
                    false, // isEnum
                    true,  // isPrimitive
                    Optional.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
            typeCache.put(prim, info);
        }
        // Prepopulate null type
        TypeInfo nullInfo = new TypeInfo(
                "null",
                false, // isInterface
                false, // isRecord
                false, // isEnum
                false, // isPrimitive
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        typeCache.put("null", nullInfo);
    }

    public void addCompilationUnit(CompilationUnit unit) {
        String pkg = unit.packageName().orElse("");
        for (TypeDeclaration decl : unit.declarations()) {
            if (decl instanceof ExtensionDecl ext) {
                extensions.add(ext);
            } else {
                TypeInfo info = createSourceTypeInfo(pkg, decl, unit);
                typeCache.put(info.fullName(), info);
                packageTypes.computeIfAbsent(pkg, k -> new ArrayList<>()).add(info);
            }
        }
    }

    public List<ExtensionDecl> extensions() {
        return List.copyOf(extensions);
    }

    public TypeInfo getType(String fullName) {
        if (typeCache.containsKey(fullName)) {
            return typeCache.get(fullName);
        }

        ClassLoader loader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        // Try load via reflection
        try {
            Class<?> clazz = Class.forName(fullName, true, loader);
            TypeInfo info = createReflectedTypeInfo(clazz);
            typeCache.put(fullName, info);
            return info;
        } catch (ClassNotFoundException e) {
            // Fallback for nested classes
            if (fullName.contains(".")) {
                int lastDot = fullName.lastIndexOf('.');
                String possibleOuter = fullName.substring(0, lastDot);
                String innerName = fullName.substring(lastDot + 1);
                TypeInfo outerInfo = getType(possibleOuter);
                if (outerInfo != null) {
                    // Try looking for nested class
                    try {
                        Class<?> clazz = Class.forName(possibleOuter + "$" + innerName, true, loader);
                        TypeInfo info = createReflectedTypeInfo(clazz);
                        typeCache.put(fullName, info);
                        return info;
                    } catch (ClassNotFoundException ignored) {}
                }
            }
            return null;
        }
    }

    public ResolvedType resolveTypeNode(TypeNode node, ImportResolver resolver) {
        if (node instanceof PrimitiveTypeNode prim) {
            return new ResolvedType(getType(prim.name()), true, List.of(), 0);
        } else if (node instanceof ArrayTypeNode arr) {
            ResolvedType elem = resolveTypeNode(arr.elementType(), resolver);
            return new ResolvedType(elem.info(), arr.isNonNull(), elem.typeArguments(), elem.arrayDimensions() + 1);
        } else if (node instanceof BaseTypeNode base) {
            TypeInfo info = resolver.resolve(base.name());
            if (info == null) {
                // If resolver cannot resolve, default to String or throw
                info = getType("java.lang.String");
            }
            List<ResolvedType> args = new ArrayList<>();
            for (TypeNode arg : base.typeArguments()) {
                args.add(resolveTypeNode(arg, resolver));
            }
            return new ResolvedType(info, base.isNonNull(), args, 0);
        }
        return null;
    }

    // --- Source TypeInfo Creation ---

    private TypeInfo createSourceTypeInfo(String pkg, TypeDeclaration decl, CompilationUnit unit) {
        String fullName = pkg.isEmpty() ? decl.name() : pkg + "." + decl.name();
        boolean isInterface = decl instanceof InterfaceDecl;
        boolean isRecord = decl instanceof RecordDecl;
        boolean isEnum = decl instanceof EnumDecl;

        Optional<String> superclass = Optional.empty();
        List<String> interfaces = new ArrayList<>();

        if (decl instanceof ClassDecl cd) {
            superclass = cd.superclass().map(t -> resolveTypeName(getTypeName(t), unit));
            for (TypeNode itf : cd.interfaces()) {
                interfaces.add(resolveTypeName(getTypeName(itf), unit));
            }
        } else if (decl instanceof InterfaceDecl id) {
            for (TypeNode itf : id.interfaces()) {
                interfaces.add(resolveTypeName(getTypeName(itf), unit));
            }
        } else if (decl instanceof RecordDecl rd) {
            superclass = Optional.of("java.lang.Record");
            for (TypeNode itf : rd.interfaces()) {
                interfaces.add(resolveTypeName(getTypeName(itf), unit));
            }
        } else if (decl instanceof EnumDecl ed) {
            superclass = Optional.of("java.lang.Enum");
            for (TypeNode itf : ed.interfaces()) {
                interfaces.add(resolveTypeName(getTypeName(itf), unit));
            }
        }

        List<MethodInfo> methods = new ArrayList<>();
        List<FieldInfo> fields = new ArrayList<>();
        List<ConstructorInfo> constructors = new ArrayList<>();

        if (isRecord) {
            RecordDecl rd = (RecordDecl) decl;
            for (Parameter p : rd.components()) {
                fields.add(new FieldInfo(p.name(), List.of("private", "final"), p.type()));
                methods.add(new MethodInfo(p.name(), List.of("public"), p.type(), List.of()));
            }
        }

        // Add implicit default constructor if record or enum or if class has no constructors
        boolean hasConstructors = false;
        for (Member member : decl.members()) {
            if (member instanceof ConstructorDecl) hasConstructors = true;
        }

        for (Member member : decl.members()) {
            if (member instanceof FieldDecl fd) {
                TypeNode type = fd.type();
                if (type instanceof BaseTypeNode base && (base.name().equals("final") || base.name().equals("var"))) {
                    if (fd.initializer().isPresent() && fd.initializer().get() instanceof LiteralExpr lit) {
                        if (lit.value() instanceof String) {
                            type = new BaseTypeNode("java.lang.String", List.of(), true);
                        } else if (lit.value() instanceof Boolean) {
                            type = new PrimitiveTypeNode("boolean");
                        } else if (lit.value() instanceof Number) {
                            type = new PrimitiveTypeNode("int");
                        } else {
                            type = new BaseTypeNode("java.lang.Object", List.of(), false);
                        }
                    } else {
                        type = new BaseTypeNode("java.lang.Object", List.of(), false);
                    }
                }
                fields.add(new FieldInfo(fd.name(), fd.modifiers(), type));
            } else if (member instanceof MethodDecl md) {
                methods.add(new MethodInfo(md.name(), md.modifiers(), md.returnType(), md.parameters()));
            } else if (member instanceof ConstructorDecl cd) {
                constructors.add(new ConstructorInfo(cd.modifiers(), cd.parameters()));
            }
        }

        if (!hasConstructors && !isInterface) {
            if (isRecord) {
                RecordDecl rd = (RecordDecl) decl;
                constructors.add(new ConstructorInfo(List.of("public"), rd.components()));
            } else {
                constructors.add(new ConstructorInfo(List.of("public"), List.of()));
            }
        }

        return new TypeInfo(fullName, isInterface, isRecord, isEnum, false, superclass, interfaces, methods, fields, constructors);
    }

    private String getTypeName(TypeNode node) {
        if (node instanceof BaseTypeNode base) return base.name();
        if (node instanceof PrimitiveTypeNode prim) return prim.name();
        if (node instanceof ArrayTypeNode arr) return getTypeName(arr.elementType()) + "[]";
        return "";
    }

    // --- Reflected TypeInfo Creation ---

    private TypeInfo createReflectedTypeInfo(Class<?> clazz) {
        String fullName = clazz.getName().replace('$', '.');
        boolean isInterface = clazz.isInterface();
        boolean isEnum = clazz.isEnum();
        boolean isRecord = clazz.isRecord();

        Optional<String> superclass = Optional.ofNullable(clazz.getSuperclass()).map(Class::getName);
        List<String> interfaces = new ArrayList<>();
        for (Class<?> itf : clazz.getInterfaces()) {
            interfaces.add(itf.getName());
        }

        List<MethodInfo> methods = new ArrayList<>();
        List<FieldInfo> fields = new ArrayList<>();
        List<ConstructorInfo> constructors = new ArrayList<>();

        // Map fields
        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) || Modifier.isProtected(f.getModifiers())) {
                fields.add(new FieldInfo(f.getName(), getModifiersList(f.getModifiers()), mapClassToTypeNode(f.getType())));
            }
        }

        // Map methods
        for (Method m : clazz.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers())) {
                List<Parameter> params = new ArrayList<>();
                int idx = 0;
                for (Class<?> pType : m.getParameterTypes()) {
                    params.add(new Parameter(mapClassToTypeNode(pType), "p" + idx++, false, Optional.empty()));
                }
                methods.add(new MethodInfo(m.getName(), getModifiersList(m.getModifiers()), mapClassToTypeNode(m.getReturnType()), params));
            }
        }

        // Map constructors
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())) {
                List<Parameter> params = new ArrayList<>();
                int idx = 0;
                for (Class<?> pType : c.getParameterTypes()) {
                    params.add(new Parameter(mapClassToTypeNode(pType), "p" + idx++, false, Optional.empty()));
                }
                constructors.add(new ConstructorInfo(getModifiersList(c.getModifiers()), params));
            }
        }

        return new TypeInfo(fullName, isInterface, isRecord, isEnum, false, superclass, interfaces, methods, fields, constructors);
    }

    private List<String> getModifiersList(int mod) {
        List<String> list = new ArrayList<>();
        if (Modifier.isPublic(mod)) list.add("public");
        if (Modifier.isProtected(mod)) list.add("protected");
        if (Modifier.isPrivate(mod)) list.add("private");
        if (Modifier.isStatic(mod)) list.add("static");
        if (Modifier.isFinal(mod)) list.add("final");
        return list;
    }

    private TypeNode mapClassToTypeNode(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return new PrimitiveTypeNode(clazz.getName());
        }
        if (clazz.isArray()) {
            return new ArrayTypeNode(mapClassToTypeNode(clazz.getComponentType()), false);
        }
        return new BaseTypeNode(clazz.getName().replace('$', '.'), List.of(), false);
    }

    // --- Resolved Types Models ---

    public record ResolvedType(
            TypeInfo info,
            boolean isNonNull,
            List<ResolvedType> typeArguments,
            int arrayDimensions
    ) {
        public boolean isPrimitive() {
            return info.isPrimitive() && arrayDimensions == 0;
        }

        public ResolvedType withNonNull(boolean nonNull) {
            return new ResolvedType(info, nonNull, typeArguments, arrayDimensions);
        }

        public boolean isCompatibleWith(ResolvedType other, SymbolTable table) {
            if (this.info.fullName().equals("null")) {
                return !other.isPrimitive() && !other.isNonNull;
            }
            // Primitive compatibility
            if (this.isPrimitive() && other.isPrimitive()) {
                if (this.info.fullName().equals(other.info.fullName())) return true;
                // Basic numeric promotions
                if (this.info.fullName().equals("int") && other.info.fullName().equals("long")) return true;
                if (this.info.fullName().equals("int") && other.info.fullName().equals("double")) return true;
                if (this.info.fullName().equals("long") && other.info.fullName().equals("double")) return true;
                return false;
            }

            if (this.isPrimitive() != other.isPrimitive()) return false;

            // Nullability check: cannot assign nullable to non-null
            if (!this.isNonNull && other.isNonNull) return false;

            // Array compatibility
            if (this.arrayDimensions != other.arrayDimensions) {
                // Java allows assigning array to Object
                if (other.info.fullName().equals("java.lang.Object") && other.arrayDimensions == 0) return true;
                return false;
            }

            if (this.arrayDimensions > 0) {
                // Element compatibility
                return this.info.fullName().equals(other.info.fullName()) || other.info.fullName().equals("java.lang.Object");
            }

            // Standard object compatibility: this must be a subtype of other
            return isSubtypeOf(this.info, other.info.fullName(), table);
        }

        private boolean isSubtypeOf(TypeInfo type, String targetFullName, SymbolTable table) {
            if (type.fullName().equals(targetFullName)) return true;
            if (type.superclass().isPresent()) {
                TypeInfo superInfo = table.getType(type.superclass().get());
                if (superInfo != null && isSubtypeOf(superInfo, targetFullName, table)) return true;
            }
            for (String itf : type.interfaces()) {
                TypeInfo itfInfo = table.getType(itf);
                if (itfInfo != null && isSubtypeOf(itfInfo, targetFullName, table)) return true;
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(info.fullName());
            if (!typeArguments.isEmpty()) {
                sb.append("<");
                for (int i = 0; i < typeArguments.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeArguments.get(i).toString());
                }
                sb.append(">");
            }
            for (int i = 0; i < arrayDimensions; i++) {
                sb.append("[]");
            }
            if (isNonNull && !info.isPrimitive()) {
                sb.append("!");
            }
            return sb.toString();
        }
    }

    public record TypeInfo(
            String fullName,
            boolean isInterface,
            boolean isRecord,
            boolean isEnum,
            boolean isPrimitive,
            Optional<String> superclass,
            List<String> interfaces,
            List<MethodInfo> methods,
            List<FieldInfo> fields,
            List<ConstructorInfo> constructors
    ) {
        public String simpleName() {
            int lastDot = fullName.lastIndexOf('.');
            return lastDot == -1 ? fullName : fullName.substring(lastDot + 1);
        }
    }

    public record MethodInfo(
            String name,
            List<String> modifiers,
            TypeNode returnType,
            List<Parameter> parameters
    ) {}

    public record FieldInfo(
            String name,
            List<String> modifiers,
            TypeNode type
    ) {}

    public record ConstructorInfo(
            List<String> modifiers,
            List<Parameter> parameters
    ) {}

    private String resolveTypeName(String name, CompilationUnit unit) {
        for (String imp : unit.imports()) {
            if (imp.endsWith("." + name)) {
                return imp;
            }
        }
        String pkg = unit.packageName().orElse("");
        if (!pkg.isEmpty()) {
            return pkg + "." + name;
        }
        return name;
    }

    // --- Import Resolver Interface ---

    public interface ImportResolver {
        TypeInfo resolve(String simpleName);
    }
}
