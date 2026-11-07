package accent.symbol;

import accent.ast.Ast.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Predicate;

public final class SymbolTable {
    private final Map<String, TypeInfo> typeCache = new HashMap<>();
    private final Map<String, List<TypeInfo>> packageTypes = new HashMap<>();
    private final ClassLoader classLoader;
    private final Set<String> singletonTypes = new HashSet<>();
    private final List<ExtensionDecl> extensions = new ArrayList<>();
    private final Map<ExtensionDecl, String> extensionPackages = new IdentityHashMap<>();
    private final Map<ExtensionDecl, CompilationUnit> extensionUnits = new IdentityHashMap<>();
    private final Map<String, CompilationUnit> sourceUnits = new HashMap<>();
    private final Map<FieldInfo, MemberOwner> fieldOwners = new IdentityHashMap<>();
    private final Map<MethodInfo, MemberOwner> methodOwners = new IdentityHashMap<>();
    private final Map<String, String> typePackages = new HashMap<>();

    public record MemberOwner(String typeName, String packageName) {}

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
                extensionPackages.put(ext, pkg);
                extensionUnits.put(ext, unit);
            } else {
                TypeInfo info = createSourceTypeInfo(pkg, decl, unit);
                sourceUnits.put(info.fullName(), unit);
                typeCache.put(info.fullName(), info);
                if (decl.isSingleton()) singletonTypes.add(info.fullName());
                packageTypes.computeIfAbsent(pkg, k -> new ArrayList<>()).add(info);
            }
        }
    }

    public List<ExtensionDecl> extensions() {
        return List.copyOf(extensions);
    }

    public String extensionPackage(ExtensionDecl extension) {
        return extensionPackages.getOrDefault(extension, "");
    }

    public CompilationUnit extensionUnit(ExtensionDecl extension) {
        return extensionUnits.get(extension);
    }

    public TypeInfo resolveInUnit(String name, CompilationUnit unit) {
        if (unit == null) return getType(name);
        TypeInfo info;
        if (name.contains(".")) {
            info = getType(name);
            if (info != null) return info;
        }
        String pkg = unit.packageName().orElse("");
        info = getType(pkg.isEmpty() ? name : pkg + "." + name);
        if (info != null) return info;
        for (String imp : unit.imports()) {
            String imported = imp.startsWith("static ") ? imp.substring(7) : imp;
            if (imported.equals(name) || imported.endsWith("." + name)) {
                info = getType(imported);
                if (info != null) return info;
            }
        }
        for (String imp : unit.imports()) {
            String imported = imp.startsWith("static ") ? imp.substring(7) : imp;
            if (imported.endsWith(".*")) {
                info = getType(imported.substring(0, imported.length() - 1) + name);
                if (info != null) return info;
            }
        }
        for (String implicit : List.of("java.lang.", "java.util.", "java.math.")) {
            info = getType(implicit + name);
            if (info != null) return info;
        }
        return getType(name);
    }

    public ResolvedType resolveTypeInUnit(TypeNode node, CompilationUnit unit) {
        return resolveTypeNode(node, name -> resolveInUnit(name, unit));
    }

    private ResolvedType resolveMemberType(TypeNode node, MemberOwner owner) {
        CompilationUnit unit = owner == null ? null : sourceUnits.get(owner.typeName());
        return resolveTypeInUnit(node, unit);
    }

    public ResolvedType resolveFieldType(FieldInfo field) {
        return resolveMemberType(field.type(), declaringOwner(field));
    }

    public ResolvedType resolveReturnType(MethodInfo method) {
        return resolveMemberType(method.returnType(), declaringOwner(method));
    }

    public ResolvedType resolveParameterType(MethodInfo method, int index) {
        return resolveMemberType(method.parameters().get(index).type(), declaringOwner(method));
    }

    public boolean isInvocationCompatible(ResolvedType argument, ResolvedType parameter) {
        if (argument == null || parameter == null || argument.isCompatibleWith(parameter, this)) return true;
        if (argument.isPrimitive() && !parameter.isPrimitive()) {
            String boxed = switch (argument.info().fullName()) {
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
            return boxed != null && new ResolvedType(getType(boxed), true, List.of(), 0)
                    .isCompatibleWith(parameter, this);
        }
        if (!argument.isPrimitive() && argument.arrayDimensions() == 0 && parameter.isPrimitive()) {
            String unboxed = switch (argument.info().fullName()) {
                case "java.lang.Boolean" -> "boolean";
                case "java.lang.Byte" -> "byte";
                case "java.lang.Short" -> "short";
                case "java.lang.Character" -> "char";
                case "java.lang.Integer" -> "int";
                case "java.lang.Long" -> "long";
                case "java.lang.Float" -> "float";
                case "java.lang.Double" -> "double";
                default -> null;
            };
            return unboxed != null && new ResolvedType(getType(unboxed), true, List.of(), 0)
                    .isCompatibleWith(parameter, this);
        }
        return false;
    }

    public MemberOwner declaringOwner(FieldInfo field) {
        return fieldOwners.get(field);
    }

    public MemberOwner declaringOwner(MethodInfo method) {
        return methodOwners.get(method);
    }

    private TypeInfo registerMembers(TypeInfo type, String packageName) {
        typePackages.put(type.fullName(), packageName);
        MemberOwner owner = new MemberOwner(type.fullName(), packageName);
        type.fields().forEach(field -> fieldOwners.put(field, owner));
        type.methods().forEach(method -> methodOwners.put(method, owner));
        return type;
    }

    public boolean isSubtype(TypeInfo type, TypeInfo target) {
        if (type == null || target == null) return false;
        if (target.fullName().equals("java.lang.Object") && !type.isPrimitive()) return true;
        return hierarchy(type).stream().anyMatch(candidate -> candidate.fullName().equals(target.fullName()));
    }

    public List<TypeInfo> hierarchy(TypeInfo type) {
        if (type == null) return List.of();
        List<TypeInfo> hierarchy = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<TypeInfo> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            TypeInfo candidate = pending.removeFirst();
            if (!visited.add(candidate.fullName())) continue;
            hierarchy.add(candidate);
            List<String> parents = new ArrayList<>(candidate.interfaces());
            candidate.superclass().ifPresent(parents::add);
            for (String parent : parents) {
                TypeInfo parentType = getTypeWithFallback(this, parent);
                if (parentType != null) pending.addLast(parentType);
            }
        }
        if (!type.isPrimitive() && visited.add("java.lang.Object")) {
            TypeInfo object = getType("java.lang.Object");
            if (object != null) hierarchy.add(object);
        }
        return hierarchy;
    }

    public boolean isMemberAccessible(FieldInfo field, TypeInfo receiver, TypeInfo accessingClass,
                                      String accessingPackage, boolean superAccess) {
        return isMemberAccessible(field.modifiers(), declaringOwner(field), receiver,
                accessingClass, accessingPackage, superAccess);
    }

    public boolean isMemberAccessible(MethodInfo method, TypeInfo receiver, TypeInfo accessingClass,
                                      String accessingPackage, boolean superAccess) {
        MemberOwner owner = declaringOwner(method);
        if (owner != null && method.modifiers().contains("static") && !receiver.fullName().equals(owner.typeName())) {
            TypeInfo declaringType = getType(owner.typeName());
            if (declaringType != null && declaringType.isInterface()) return false;
        }
        return isMemberAccessible(method.modifiers(), owner, receiver,
                accessingClass, accessingPackage, superAccess);
    }

    private boolean isMemberAccessible(List<String> modifiers, MemberOwner owner, TypeInfo receiver,
                                      TypeInfo accessingClass, String accessingPackage, boolean superAccess) {
        if (owner == null || modifiers.contains("public")) return true;
        if (modifiers.contains("private")) {
            return accessingClass != null && accessingClass.fullName().equals(owner.typeName())
                    && receiver.fullName().equals(owner.typeName());
        }
        boolean isProtected = modifiers.contains("protected");
        if (!isProtected && !inheritsPackageMember(receiver, owner)) return false;
        if (accessingPackage.equals(owner.packageName())) return true;
        if (!isProtected || !isSubtype(accessingClass, getType(owner.typeName()))) return false;
        // Protected instance access outside the package needs a receiver of the accessing
        // subclass (or one of its subclasses), except for an explicit super access.
        return modifiers.contains("static") || superAccess || isSubtype(receiver, accessingClass);
    }

    /** Package members cease to be inherited when a superclass crosses a package boundary. */
    public boolean inheritsPackageMember(TypeInfo type, MemberOwner owner) {
        Set<String> visited = new HashSet<>();
        while (type != null && visited.add(type.fullName())) {
            if (type.fullName().equals(owner.typeName())) return true;
            if (!owner.packageName().equals(typePackages.get(type.fullName()))) return false;
            type = type.superclass().map(name -> getTypeWithFallback(this, name)).orElse(null);
        }
        return false;
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

    public boolean isSingletonType(TypeInfo type) {
        return type != null && singletonTypes.contains(type.fullName());
    }

    /** Native container methods require record/model element or value types. */
    public boolean isContainerSerializationMethod(ResolvedType receiver, String name, int argumentCount) {
        if (receiver == null || receiver.info() == null || receiver.arrayDimensions() != 0
                || argumentCount != 0 || !(name.equals("toJson") || name.equals("toXml"))) return false;
        boolean map = receiver.isSubtypeOf(receiver.info(), "java.util.Map", this);
        if (!map && !receiver.isSubtypeOf(receiver.info(), "java.util.List", this)) return false;
        int index = map ? 1 : 0;
        if (receiver.typeArguments().size() <= index) return false;
        ResolvedType value = receiver.typeArguments().get(index);
        return value.arrayDimensions() == 0 && (value.info().isRecord()
                || value.isSubtypeOf(value.info(), "accent.runtime.AccentModel", this));
    }

    public ResolvedType resolveTypeNode(TypeNode node, ImportResolver resolver) {
        if (node instanceof PrimitiveTypeNode prim) {
            return new ResolvedType(getType(prim.name()), true, List.of(), 0);
        } else if (node instanceof ArrayTypeNode arr) {
            ResolvedType elem = resolveTypeNode(arr.elementType(), resolver);
            return new ResolvedType(elem.info(), arr.isNonNull(), elem.typeArguments(), elem.arrayDimensions() + 1);
        } else if (node instanceof BaseTypeNode base) {
            TypeInfo info = resolver != null ? resolver.resolve(base.name()) : null;
            if (info == null) {
                info = getType(base.name());
            }
            if (info == null) {
                info = getType("java.lang." + base.name());
            }
            if (info == null) {
                info = getType("java.util." + base.name());
            }
            if (info == null) {
                info = getType("java.lang.Object");
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
        } else if (decl instanceof ModelDecl md) {
            interfaces.add("accent.runtime.AccentModel");
            if (!interfaces.contains("java.io.Serializable")) {
                interfaces.add("java.io.Serializable");
            }
            for (TypeNode itf : md.interfaces()) {
                interfaces.add(resolveTypeName(getTypeName(itf), unit));
            }
        } else if (decl instanceof RecordDecl rd) {
            if (rd.isSingleton()) interfaces.add("accent.runtime.AccentModel");
            else superclass = Optional.of("java.lang.Record");
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
            BaseTypeNode stringType = new BaseTypeNode("java.lang.String", List.of(), true);
            PrimitiveTypeNode booleanType = new PrimitiveTypeNode("boolean");
            PrimitiveTypeNode intType = new PrimitiveTypeNode("int");
            BaseTypeNode objectType = new BaseTypeNode("java.lang.Object", List.of(), false);
            Parameter objParam = new Parameter(objectType, "o", false, Optional.empty());
            methods.add(new MethodInfo("toString", List.of("public"), stringType, List.of()));
            methods.add(new MethodInfo("equals", List.of("public"), booleanType, List.of(objParam)));
            methods.add(new MethodInfo("hashCode", List.of("public"), intType, List.of()));
        }

        if (decl instanceof ModelDecl md) {
            BaseTypeNode selfType = new BaseTypeNode(fullName, List.of(), true);
            PrimitiveTypeNode voidType = new PrimitiveTypeNode("void");
            BaseTypeNode stringType = new BaseTypeNode("java.lang.String", List.of(), true);
            PrimitiveTypeNode booleanType = new PrimitiveTypeNode("boolean");
            PrimitiveTypeNode intType = new PrimitiveTypeNode("int");
            BaseTypeNode objectType = new BaseTypeNode("java.lang.Object", List.of(), false);
            Parameter objParam = new Parameter(objectType, "o", false, Optional.empty());

            for (Parameter p : md.components()) {
                List<String> fieldMods = p.isMutable() ? List.of("private") : List.of("private", "final");
                fields.add(new FieldInfo(p.name(), fieldMods, p.type()));
                methods.add(new MethodInfo(p.name(), List.of("public"), p.type(), List.of()));
                if (p.isMutable()) {
                    String setterName = p.name();
                    methods.add(new MethodInfo(setterName, List.of("public"), voidType, List.of(p)));
                }
            }

            if (!md.isSingleton()) methods.add(new MethodInfo("copy", List.of("public"), selfType, md.components()));
            methods.add(new MethodInfo("toString", List.of("public"), stringType, List.of()));
            methods.add(new MethodInfo("equals", List.of("public"), booleanType, List.of(objParam)));
            methods.add(new MethodInfo("hashCode", List.of("public"), intType, List.of()));
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
                List<String> modifiers = new ArrayList<>(fd.modifiers());
                if (isInterface) {
                    addImplicitPublic(modifiers);
                    if (!modifiers.contains("static")) modifiers.add("static");
                    if (!modifiers.contains("final")) modifiers.add("final");
                }
                fields.add(new FieldInfo(fd.name(), modifiers, type));
            } else if (member instanceof MethodDecl md) {
                List<String> modifiers = new ArrayList<>(md.modifiers());
                if (isInterface) addImplicitPublic(modifiers);
                methods.add(new MethodInfo(md.name(), modifiers, md.returnType(), md.parameters()));
            } else if (member instanceof ConstructorDecl cd) {
                constructors.add(new ConstructorInfo(cd.modifiers(), cd.parameters()));
            }
        }

        if (!hasConstructors && !isInterface) {
            if (isRecord) {
                RecordDecl rd = (RecordDecl) decl;
                constructors.add(new ConstructorInfo(List.of("public"), rd.components()));
            } else if (decl instanceof ModelDecl md) {
                constructors.add(new ConstructorInfo(List.of("public"), md.components()));
            } else {
                constructors.add(new ConstructorInfo(List.of("public"), List.of()));
            }
        }

        if (decl.isSingleton()) {
            BaseTypeNode selfType = new BaseTypeNode(fullName, List.of(), true);
            methods.add(new MethodInfo("instance", List.of("public", "static"), selfType, List.of()));
        }

        if (isRecord || decl instanceof ModelDecl) {
            for (String name : List.of("toJson", "toXml")) {
                if (methods.stream().noneMatch(m -> m.name().equals(name) && m.parameters().isEmpty())) {
                    methods.add(new MethodInfo(name, List.of("public"),
                            new BaseTypeNode("java.lang.String", List.of(), true), List.of()));
                }
            }
        }

        return registerMembers(new TypeInfo(fullName, isInterface, isRecord, isEnum, false,
                superclass, interfaces, methods, fields, constructors), pkg);
    }

    private String getTypeName(TypeNode node) {
        if (node instanceof BaseTypeNode base) return base.name();
        if (node instanceof PrimitiveTypeNode prim) return prim.name();
        if (node instanceof ArrayTypeNode arr) return getTypeName(arr.elementType()) + "[]";
        return "";
    }

    private static void addImplicitPublic(List<String> modifiers) {
        if (!modifiers.contains("public") && !modifiers.contains("protected") && !modifiers.contains("private")) {
            modifiers.add("public");
        }
    }

    // --- Reflected TypeInfo Creation ---

    private TypeInfo createReflectedTypeInfo(Class<?> clazz) {
        String fullName = clazz.getName().replace('$', '.');
        boolean isInterface = clazz.isInterface();
        boolean isEnum = clazz.isEnum();
        boolean isRecord = clazz.isRecord();
        if (clazz.isAnnotationPresent(accent.runtime.Singleton.class)) singletonTypes.add(clazz.getName().replace('$', '.'));

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
            if (f.isSynthetic()) continue;
            fields.add(new FieldInfo(f.getName(), getModifiersList(f.getModifiers()), mapClassToTypeNode(f.getType())));
        }

        // Map methods
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isBridge() || m.isSynthetic()) continue;
            List<Parameter> params = new ArrayList<>();
            int idx = 0;
            for (Class<?> pType : m.getParameterTypes()) {
                boolean varargs = m.isVarArgs() && idx == m.getParameterCount() - 1;
                params.add(new Parameter(mapClassToTypeNode(varargs ? pType.getComponentType() : pType),
                        "p" + idx++, varargs, Optional.empty()));
            }
            methods.add(new MethodInfo(m.getName(), getModifiersList(m.getModifiers()), mapClassToTypeNode(m.getReturnType()), params));
        }

        // Map constructors
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())) {
                List<Parameter> params = new ArrayList<>();
                int idx = 0;
                for (Class<?> pType : c.getParameterTypes()) {
                    boolean varargs = c.isVarArgs() && idx == c.getParameterCount() - 1;
                    params.add(new Parameter(mapClassToTypeNode(varargs ? pType.getComponentType() : pType),
                            "p" + idx++, varargs, Optional.empty()));
                }
                constructors.add(new ConstructorInfo(getModifiersList(c.getModifiers()), params));
            }
        }

        return registerMembers(new TypeInfo(fullName, isInterface, isRecord, isEnum, false,
                superclass, interfaces, methods, fields, constructors), clazz.getPackageName());
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

            // Standard object compatibility: Object source or subtype target
            if (this.info.fullName().equals("java.lang.Object") && !other.isPrimitive()) return true;
            return isSubtypeOf(this.info, other.info.fullName(), table);
        }

        private boolean isSubtypeOf(TypeInfo type, String targetFullName, SymbolTable table) {
            if (type.fullName().equals(targetFullName)) return true;
            if (targetFullName.equals("java.lang.Object") && !type.isPrimitive()) return true;
            if (type.superclass().isPresent()) {
                TypeInfo superInfo = getTypeWithFallback(table, type.superclass().get());
                if (superInfo != null && isSubtypeOf(superInfo, targetFullName, table)) return true;
            }
            for (String itf : type.interfaces()) {
                TypeInfo itfInfo = getTypeWithFallback(table, itf);
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

    private static TypeInfo getTypeWithFallback(SymbolTable table, String name) {
        TypeInfo info = table.getType(name);
        if (info != null) return info;
        int lastDot = name.lastIndexOf('.');
        String simpleName = lastDot != -1 ? name.substring(lastDot + 1) : name;
        info = table.getType(simpleName);
        if (info != null) return info;
        info = table.getType("java.lang." + simpleName);
        if (info != null) return info;
        info = table.getType("java.util." + simpleName);
        return info;
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

        public MethodInfo findMethod(String name, SymbolTable symbolTable) {
            return findMethod(name, null, symbolTable);
        }

        public MethodInfo findMethod(String name, List<ResolvedType> argTypes, SymbolTable symbolTable) {
            return findMethod(name, argTypes, symbolTable, method -> true);
        }

        public MethodInfo findMethod(String name, List<ResolvedType> argTypes, SymbolTable symbolTable,
                                     Predicate<MethodInfo> accessible) {
            List<MethodInfo> allCandidates = findAllMethods(name, symbolTable);
            if (argTypes != null) {
                List<MethodInfo> applicable = allCandidates.stream()
                        .filter(method -> acceptsArguments(method, argTypes, symbolTable)).toList();
                // Only use the permissive generic/varargs fallback when no signature is
                // compatible. An accessible overload with the wrong signature must not
                // hide an inaccessible overload that actually matches the arguments.
                if (!applicable.isEmpty()) allCandidates = applicable;
            }
            List<MethodInfo> candidates = allCandidates.stream().filter(accessible).toList();
            if (candidates.isEmpty()) return null;

            if (argTypes != null) {
                // Filter by parameter count matching
                List<MethodInfo> arityMatches = candidates.stream()
                        .filter(m -> m.parameters().size() == argTypes.size())
                        .toList();
                if (!arityMatches.isEmpty()) {
                    // Try to find compatible type match with non-Object return type
                    for (MethodInfo m : arityMatches) {
                        if (acceptsArguments(m, argTypes, symbolTable) && !isObjectReturnType(m.returnType())) {
                            return m;
                        }
                    }
                    // Fallback to any compatible match
                    for (MethodInfo m : arityMatches) {
                        if (acceptsArguments(m, argTypes, symbolTable)) return m;
                    }
                    // Arity match with non-object return type
                    MethodInfo nonObj = arityMatches.stream().filter(m -> !isObjectReturnType(m.returnType())).findFirst().orElse(null);
                    if (nonObj != null) return nonObj;
                    return arityMatches.get(0);
                }
            }

            // Fallback: pick candidate with most specific return type (non-Object)
            MethodInfo nonObj = candidates.stream().filter(m -> !isObjectReturnType(m.returnType())).findFirst().orElse(null);
            return nonObj != null ? nonObj : candidates.get(0);
        }

        public List<MethodInfo> findAllMethods(String name, SymbolTable symbolTable) {
            List<MethodInfo> list = new ArrayList<>();
            collectMethods(name, symbolTable, list, new HashSet<>());
            return list;
        }

        private void collectMethods(String name, SymbolTable symbolTable, List<MethodInfo> list, Set<String> visited) {
            if (!visited.add(fullName)) return;
            for (MethodInfo m : methods) {
                if (m.name().equals(name)) {
                    list.add(m);
                }
            }
            if (superclass.isPresent()) {
                TypeInfo superInfo = getTypeWithFallback(symbolTable, superclass.get());
                if (superInfo != null) {
                    superInfo.collectMethods(name, symbolTable, list, visited);
                }
            }
            for (String itf : interfaces) {
                TypeInfo itfInfo = getTypeWithFallback(symbolTable, itf);
                if (itfInfo != null) {
                    itfInfo.collectMethods(name, symbolTable, list, visited);
                }
            }
            if (!fullName.equals("java.lang.Object") && !isPrimitive) {
                TypeInfo objInfo = symbolTable.getType("java.lang.Object");
                if (objInfo != null) {
                    objInfo.collectMethods(name, symbolTable, list, visited);
                }
            }
        }

        private boolean isObjectReturnType(TypeNode type) {
            if (type instanceof BaseTypeNode base) {
                return base.name().equals("java.lang.Object") || base.name().equals("Object");
            }
            return false;
        }

        private boolean acceptsArguments(MethodInfo method, List<ResolvedType> arguments, SymbolTable table) {
            List<Parameter> parameters = method.parameters();
            boolean varargs = !parameters.isEmpty() && parameters.getLast().isVarargs();
            int fixedCount = parameters.size() - (varargs ? 1 : 0);
            if (arguments.size() < fixedCount || !varargs && arguments.size() != fixedCount) return false;
            for (int i = 0; i < fixedCount; i++) {
                if (!table.isInvocationCompatible(arguments.get(i), table.resolveParameterType(method, i))) return false;
            }
            if (!varargs) return true;
            ResolvedType element = table.resolveParameterType(method, fixedCount);
            if (arguments.size() == parameters.size()) {
                ResolvedType array = new ResolvedType(element.info(), false, element.typeArguments(), element.arrayDimensions() + 1);
                if (table.isInvocationCompatible(arguments.get(fixedCount), array)) return true;
            }
            for (int i = fixedCount; i < arguments.size(); i++) {
                if (!table.isInvocationCompatible(arguments.get(i), element)) return false;
            }
            return true;
        }

        public FieldInfo findField(String name, SymbolTable symbolTable) {
            for (FieldInfo f : fields) {
                if (f.name().equals(name)) return f;
            }
            if (superclass.isPresent()) {
                TypeInfo superInfo = getTypeWithFallback(symbolTable, superclass.get());
                if (superInfo != null) {
                    FieldInfo f = superInfo.findField(name, symbolTable);
                    if (f != null) return f;
                }
            }
            for (String itf : interfaces) {
                TypeInfo itfInfo = getTypeWithFallback(symbolTable, itf);
                if (itfInfo != null) {
                    FieldInfo f = itfInfo.findField(name, symbolTable);
                    if (f != null) return f;
                }
            }
            return null;
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
