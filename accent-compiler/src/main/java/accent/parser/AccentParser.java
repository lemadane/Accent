package accent.parser;

import accent.ast.Ast.*;
import accent.diagnostic.Diagnostic;
import accent.diagnostic.DiagnosticSeverity;
import accent.lexer.Token;
import accent.lexer.TokenType;
import accent.source.SourceFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AccentParser {
    private final SourceFile sourceFile;
    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final boolean isJava;
    private int current = 0;
    private boolean isSpeculative = false;
    private boolean isErrorTolerant = false;

    public void setErrorTolerant(boolean tolerant) {
        this.isErrorTolerant = tolerant;
    }

    public AccentParser(SourceFile sourceFile, List<Token> tokens) {
        this.sourceFile = sourceFile;
        this.tokens = tokens;
        this.isJava = sourceFile.path().getFileName().toString().endsWith(".java");
    }

    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public CompilationUnit parse() {
        Optional<String> packageName = Optional.empty();
        List<String> imports = new ArrayList<>();
        List<TypeDeclaration> declarations = new ArrayList<>();

        try {
            // Package declaration
            if (match(TokenType.PACKAGE)) {
                packageName = Optional.of(parseDottedName());
                consume(TokenType.SEMICOLON, "Expected ';' after package declaration.");
            }

            // Imports
            while (match(TokenType.IMPORT)) {
                boolean isStatic = match(TokenType.STATIC);
                String importPath = parseDottedName();
                if (match(TokenType.DOT)) {
                    consume(TokenType.STAR, "Expected '*' after '.' in import.");
                    importPath += ".*";
                }
                consume(TokenType.SEMICOLON, "Expected ';' after import declaration.");
                imports.add((isStatic ? "static " : "") + importPath);
            }

            // Type Declarations
            String firstTypeName = null;
            while (!isAtEnd()) {
                try {
                    Token startToken = peek();
                    TypeDeclaration decl = parseTypeDeclaration();
                    declarations.add(decl);
                    if (!isJava && !(decl instanceof ExtensionDecl)) {
                        if (firstTypeName == null) {
                            firstTypeName = decl.name();
                        } else {
                            report(startToken, "Only one top-level type is allowed per file (found '" + firstTypeName
                                    + "' and '" + decl.name() + "'); move '" + decl.name() + "' to " + decl.name() + ".accent");
                        }
                    }
                } catch (ParseError error) {
                    synchronize();
                }
            }
        } catch (ParseError error) {
            // Unrecoverable top-level error
        }

        return new CompilationUnit(sourceFile, packageName, imports, declarations, tokens);
    }

    // --- Parser Core Helpers ---

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private Token advance() {
        if (!isAtEnd()) {
            current++;
        }
        return previous();
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) {
            Token token = advance();
            if (type == TokenType.IDENTIFIER) validateIdentifier(token);
            return token;
        }
        if (isErrorTolerant) {
            Token currentToken = peek();
            if (!isSpeculative) {
                diagnostics.add(new Diagnostic(
                        DiagnosticSeverity.ERROR,
                        "ACCENT-P001",
                        message,
                        currentToken.line(),
                        currentToken.column()
                ));
            }
            return new Token(type, "", currentToken.line(), currentToken.column(), currentToken.startOffset(), currentToken.startOffset());
        }
        throw error(peek(), message);
    }

    private static class ParseError extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Reports an error diagnostic without aborting the parse. */
    private void report(Token token, String message) {
        if (!isSpeculative) {
            diagnostics.add(new Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "ACCENT-P001",
                    message,
                    token.line(),
                    token.column()
            ));
        }
    }

    private static final java.util.Set<String> VISIBILITY_WORDS = java.util.Set.of("public", "protected", "private");

    private void validateIdentifier(Token token) {
        if (!isJava && VISIBILITY_WORDS.contains(token.lexeme())) {
            report(token, "'" + token.lexeme() + "' cannot be used as an identifier because it is reserved by Java; choose a different name");
        }
    }

    /** Recognizes visibility modifiers while preserving identifier diagnostics. */
    private boolean checkVisibilityWord() {
        if (!check(TokenType.IDENTIFIER) || !VISIBILITY_WORDS.contains(peek().lexeme())) return false;
        TokenType next = lookAhead(1).type();
        return next != TokenType.ASSIGN && next != TokenType.SEMICOLON && next != TokenType.LEFT_PAREN
                && next != TokenType.DOT && next != TokenType.COMMA && next != TokenType.RIGHT_PAREN;
    }

    /** Parses static/final and collects explicit Accent visibility modifiers. */
    private List<Token> parseKeywordModifiers(List<String> modifiers) {
        List<Token> explicitVisibility = new ArrayList<>();
        while (true) {
            if (match(TokenType.STATIC, TokenType.FINAL)) {
                modifiers.add(previous().lexeme());
            } else if (checkVisibilityWord()) {
                Token t = advance();
                if (isJava) modifiers.add(t.lexeme());
                else explicitVisibility.add(t);
            } else {
                break;
            }
        }
        return explicitVisibility;
    }

    private static int annotationCount(List<String> modifiers) {
        int i = 0;
        while (i < modifiers.size() && modifiers.get(i).startsWith("@")) i++;
        return i;
    }

    private static boolean isOnlyUnderscores(String name) {
        return !name.isEmpty() && name.chars().allMatch(c -> c == '_');
    }

    private static String withPrefix(String name, String prefix) {
        String bare = name;
        while (bare.startsWith("_")) bare = bare.substring(1);
        return prefix + bare;
    }

    private String currentTypeKind = "";

    /** Visibility comes from modifiers; a leading underscore marks non-public declarations. */
    private void applyMemberVisibility(List<String> modifiers, List<Token> explicitVisibility, String name, Token nameToken) {
        if (isOnlyUnderscores(name)) {
            report(nameToken, "'" + name + "' is not a valid member name");
        }
        if (explicitVisibility.size() > 1) {
            report(explicitVisibility.get(1), "Only one visibility modifier is allowed");
        }
        String visibility = explicitVisibility.isEmpty() ? "public" : explicitVisibility.getFirst().lexeme();
        if (!visibility.equals("public")) {
            if (currentTypeKind.equals("interface")) {
                report(nameToken, "Interface members are always public");
            }
            if (!name.startsWith("_")) {
                report(nameToken, "'" + visibility + "' member '" + name
                        + "' must start with '_'; rename it to '_" + name + "'");
            }
        }
        // Accent private lowers to package-private; Java sources bypass this method.
        if (!visibility.equals("private")) modifiers.add(annotationCount(modifiers), visibility);
    }

    private ParseError error(Token token, String message) {
        if (!isSpeculative) {
            diagnostics.add(new Diagnostic(
                    DiagnosticSeverity.ERROR,
                    "ACCENT-P001",
                    message,
                    token.line(),
                    token.column()
            ));
        }
        return new ParseError();
    }

    private void skipBlock() {
        consume(TokenType.LEFT_BRACE, "Expected '{' to start block.");
        int depth = 1;
        while (depth > 0 && !isAtEnd()) {
            Token t = advance();
            if (t.type() == TokenType.LEFT_BRACE) depth++;
            else if (t.type() == TokenType.RIGHT_BRACE) depth--;
        }
    }

    private void synchronize() {
        advance();
        while (!isAtEnd()) {
            if (previous().type() == TokenType.SEMICOLON) return;
            switch (peek().type()) {
                case CLASS:
                case INTERFACE:
                case RECORD:
                case ENUM:
                case EXTENSION:
                case STATIC:
                case FINAL:
                case VOID:
                case RETURN:
                    return;
                default:
                    advance();
            }
        }
    }

    // --- Declaration Parsing ---

    private String parseDottedName() {
        StringBuilder sb = new StringBuilder(consume(TokenType.IDENTIFIER, "Expected identifier.").lexeme());
        while (match(TokenType.DOT)) {
            if (check(TokenType.STAR)) {
                // Let caller handle wildcard
                current--; // unconsume the dot
                break;
            }
            sb.append(".").append(consume(TokenType.IDENTIFIER, "Expected identifier after '.'.").lexeme());
        }
        return sb.toString();
    }

    private TypeDeclaration parseTypeDeclaration() {
        List<String> modifiers = new ArrayList<>();
        while (check(TokenType.AT)) {
            modifiers.add(parseAnnotationAsString());
        }
        
        List<Token> explicitVisibility = parseKeywordModifiers(modifiers);
        if (explicitVisibility.size() > 1) report(explicitVisibility.get(1), "Only one visibility modifier is allowed");
        for (Token t : explicitVisibility) {
            if (!t.lexeme().equals("public")) report(t, "Top-level types are always public");
        }
        if (!isJava && !check(TokenType.EXTENSION)) {
            modifiers.add(annotationCount(modifiers), "public");
            Token nameToken = lookAhead(check(TokenType.SINGLETON) ? 2 : 1);
            if (nameToken.type() == TokenType.IDENTIFIER && nameToken.lexeme().startsWith("_")) {
                report(nameToken, "Type names cannot start with '_' (types are always public); rename '"
                        + nameToken.lexeme() + "' to '" + withPrefix(nameToken.lexeme(), "") + "'");
            }
        }
        if (check(TokenType.INTERFACE)) currentTypeKind = "interface";
        else if (check(TokenType.ENUM)) currentTypeKind = "enum";
        else if (check(TokenType.EXTENSION)) currentTypeKind = "extension";
        else if (check(TokenType.RECORD)) currentTypeKind = "record";
        else currentTypeKind = "class";

        if (match(TokenType.CLASS)) {
            return parseClassDecl(modifiers, false);
        } else if (match(TokenType.SINGLETON)) {
            modifiers.add("singleton");
            if (match(TokenType.CLASS)) return parseClassDecl(modifiers, true);
            if (match(TokenType.RECORD)) return parseRecordDecl(modifiers);
            if (match(TokenType.MODEL)) return parseModelDecl(modifiers);
            throw error(peek(), "Expected 'class', 'record', or 'model' after 'singleton'.");
        } else if (match(TokenType.MODEL)) {
            return parseModelDecl(modifiers);
        } else if (match(TokenType.INTERFACE)) {
            return parseInterfaceDecl(modifiers);
        } else if (match(TokenType.RECORD)) {
            return parseRecordDecl(modifiers);
        } else if (match(TokenType.ENUM)) {
            return parseEnumDecl(modifiers);
        } else if (match(TokenType.EXTENSION)) {
            return parseExtensionDecl();
        }

        throw error(peek(), "Expected class, singleton, model, interface, record, enum, or extension declaration.");
    }

    private String parseAnnotationAsString() {
        consume(TokenType.AT, "Expected '@' for annotation.");
        StringBuilder sb = new StringBuilder("@");
        sb.append(consume(TokenType.IDENTIFIER, "Expected annotation name.").lexeme());
        while (match(TokenType.DOT)) {
            sb.append(".").append(consume(TokenType.IDENTIFIER, "Expected identifier after '.'.") .lexeme());
        }
        if (match(TokenType.LEFT_PAREN)) {
            sb.append("(");
            int depth = 1;
            while (depth > 0 && !isAtEnd()) {
                Token t = advance();
                if (t.type() == TokenType.LEFT_PAREN) depth++;
                else if (t.type() == TokenType.RIGHT_PAREN) depth--;
                sb.append(t.lexeme());
            }
        }
        return sb.toString();
    }

    private ClassDecl parseClassDecl(List<String> modifiers, boolean isSingleton) {
        String name = consume(TokenType.IDENTIFIER, "Expected class name.").lexeme();
        List<TypeParameter> typeParams = parseTypeParameters();

        Optional<TypeNode> superclass = Optional.empty();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("extends")) {
            advance();
            superclass = Optional.of(parseType());
        }

        List<TypeNode> interfaces = new ArrayList<>();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("implements")) {
            advance();
            do {
                interfaces.add(parseType());
            } while (match(TokenType.COMMA));
        }

        consume(TokenType.LEFT_BRACE, "Expected '{' before class body.");
        List<Member> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            members.add(parseMember(name));
        }
        consume(TokenType.RIGHT_BRACE, "Expected '}' after class body.");

        return new ClassDecl(modifiers, name, typeParams, superclass, interfaces, members, isSingleton);
    }

    private InterfaceDecl parseInterfaceDecl(List<String> modifiers) {
        String name = consume(TokenType.IDENTIFIER, "Expected interface name.").lexeme();
        List<TypeParameter> typeParams = parseTypeParameters();

        List<TypeNode> interfaces = new ArrayList<>();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("extends")) {
            advance();
            do {
                interfaces.add(parseType());
            } while (match(TokenType.COMMA));
        }

        consume(TokenType.LEFT_BRACE, "Expected '{' before interface body.");
        List<Member> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            members.add(parseMember(name));
        }
        consume(TokenType.RIGHT_BRACE, "Expected '}' after interface body.");

        return new InterfaceDecl(modifiers, name, typeParams, interfaces, members);
    }

    private RecordDecl parseRecordDecl(List<String> modifiers) {
        String name = consume(TokenType.IDENTIFIER, "Expected record name.").lexeme();
        List<TypeParameter> typeParams = parseTypeParameters();

        consume(TokenType.LEFT_PAREN, "Expected '(' before record components.");
        List<Parameter> components = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                components.add(parseParameter());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RIGHT_PAREN, "Expected ')' after record components.");

        List<TypeNode> interfaces = new ArrayList<>();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("implements")) {
            advance();
            do {
                interfaces.add(parseType());
            } while (match(TokenType.COMMA));
        }

        List<Member> members = new ArrayList<>();
        if (isJava || check(TokenType.LEFT_BRACE)) {
            consume(TokenType.LEFT_BRACE, "Expected '{' before record body.");
            if (!isJava && check(TokenType.RIGHT_BRACE)) {
                report(peek(), "Empty record bodies are not allowed; omit '{}'.");
            }
            while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
                members.add(parseMember(name));
            }
            consume(TokenType.RIGHT_BRACE, "Expected '}' after record body.");
        }

        return new RecordDecl(modifiers, name, typeParams, components, interfaces, members);
    }

    private ModelDecl parseModelDecl(List<String> modifiers) {
        String name = consume(TokenType.IDENTIFIER, "Expected model name.").lexeme();
        List<TypeParameter> typeParams = parseTypeParameters();

        consume(TokenType.LEFT_PAREN, "Expected '(' before model components.");
        List<Parameter> components = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                components.add(parseParameter(true));
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RIGHT_PAREN, "Expected ')' after model components.");

        List<TypeNode> interfaces = new ArrayList<>();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("implements")) {
            advance();
            do {
                interfaces.add(parseType());
            } while (match(TokenType.COMMA));
        }

        List<Member> members = new ArrayList<>();
        if (match(TokenType.LEFT_BRACE)) {
            if (!isJava && check(TokenType.RIGHT_BRACE)) {
                report(peek(), "Empty model bodies are not allowed; omit '{}'.");
            }
            while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
                members.add(parseMember(name));
            }
            consume(TokenType.RIGHT_BRACE, "Expected '}' after model body.");
        }

        return new ModelDecl(modifiers, name, typeParams, components, interfaces, members);
    }

    private EnumDecl parseEnumDecl(List<String> modifiers) {
        String name = consume(TokenType.IDENTIFIER, "Expected enum name.").lexeme();

        List<TypeNode> interfaces = new ArrayList<>();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("implements")) {
            advance();
            do {
                interfaces.add(parseType());
            } while (match(TokenType.COMMA));
        }

        consume(TokenType.LEFT_BRACE, "Expected '{' before enum body.");
        
        List<EnumConstant> constants = new ArrayList<>();
        if (!check(TokenType.RIGHT_BRACE) && !check(TokenType.SEMICOLON)) {
            do {
                String constName = consume(TokenType.IDENTIFIER, "Expected enum constant name.").lexeme();
                List<Expression> arguments = new ArrayList<>();
                if (match(TokenType.LEFT_PAREN)) {
                    if (!check(TokenType.RIGHT_PAREN)) {
                        do {
                            arguments.add(parseExpression(Precedence.NONE));
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after enum constant arguments.");
                }
                constants.add(new EnumConstant(constName, arguments));
            } while (match(TokenType.COMMA));
        }

        if (match(TokenType.SEMICOLON)) {
            // Parse remaining class members
        }

        List<Member> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            members.add(parseMember(name));
        }
        consume(TokenType.RIGHT_BRACE, "Expected '}' after enum body.");

        return new EnumDecl(modifiers, name, interfaces, constants, members);
    }

    private ExtensionDecl parseExtensionDecl() {
        TypeNode targetType = parseType();
        consume(TokenType.LEFT_BRACE, "Expected '{' before extension body.");
        List<Member> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            members.add(parseMember(""));
        }
        consume(TokenType.RIGHT_BRACE, "Expected '}' after extension body.");

        return new ExtensionDecl(targetType, members);
    }

    private Member parseMember(String enclosingClassName) {
        List<String> modifiers = new ArrayList<>();
        while (check(TokenType.AT)) {
            modifiers.add(parseAnnotationAsString());
        }
        List<Token> explicitVisibility = parseKeywordModifiers(modifiers);

        List<TypeParameter> typeParams = parseTypeParameters();

        // Check if it's a constructor: name matches enclosingClassName, followed by '('
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals(enclosingClassName) && lookAhead(1).type() == TokenType.LEFT_PAREN) {
            String name = advance().lexeme();
            if (explicitVisibility.size() > 1) report(explicitVisibility.get(1), "Only one visibility modifier is allowed");
            for (Token t : explicitVisibility) {
                if (!t.lexeme().equals("public")) report(t, "Constructors are always public");
            }
            if (!isJava && (currentTypeKind.equals("class") || currentTypeKind.equals("record"))) {
                modifiers.add(annotationCount(modifiers), "public");
            }
            consume(TokenType.LEFT_PAREN, "Expected '(' for constructor parameters.");
            List<Parameter> parameters = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    parameters.add(parseParameter());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_PAREN, "Expected ')' after constructor parameters.");

            List<TypeNode> throwsList = parseThrows();
            BlockStmt body;
            if (isJava) {
                skipBlock();
                body = new BlockStmt(List.of());
            } else {
                body = parseBlockStatement();
            }
            return new ConstructorDecl(modifiers, typeParams, parameters, throwsList, body);
        }

        // Method or Field
        boolean isGenerator = match(TokenType.GENERATOR);
        TypeNode type;
        String name;
        if (!isGenerator && startsInferredBinding()) {
            boolean isFinal = modifiers.contains("final");
            type = new BaseTypeNode(isFinal ? "final" : "var", List.of(), false);
            name = consume(TokenType.IDENTIFIER, "Expected member name.").lexeme();
        } else {
            type = parseType();
            name = match(TokenType.AWAIT) ? previous().lexeme()
                    : consume(TokenType.IDENTIFIER, "Expected member name.").lexeme();
        }
        if (!isJava) {
            applyMemberVisibility(modifiers, explicitVisibility, name, previous());
        }

        boolean markedMethod = check(TokenType.QUESTION) && lookAhead(1).type() == TokenType.LEFT_PAREN;
        if (markedMethod) advance();
        if (match(TokenType.LEFT_PAREN)) {
            if (markedMethod) {
                if (type instanceof PrimitiveTypeNode) throw error(previous(), "Primitive and void returns cannot be nullable.");
                type = type.withNonNull(false);
            }
            List<Parameter> parameters = new ArrayList<>();
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    parameters.add(parseParameter());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RIGHT_PAREN, "Expected ')' after method parameters.");

            List<TypeNode> throwsList = parseThrows();
            Optional<BlockStmt> body = Optional.empty();
            if (match(TokenType.SEMICOLON)) {
                // Abstract or interface method
            } else {
                if (isJava) {
                    skipBlock();
                    body = Optional.of(new BlockStmt(List.of()));
                } else {
                    body = Optional.of(parseBlockStatement());
                }
            }
            return new MethodDecl(modifiers, typeParams, isGenerator, type, name, parameters, throwsList, body);
        } else {
            // Field
            type = parseBindingSuffix(type);
            if (isGenerator) {
                throw error(peek(), "'generator' keyword is only valid on method declarations.");
            }
            Optional<Expression> initializer = Optional.empty();
            if (match(TokenType.ASSIGN)) {
                initializer = Optional.of(parseExpression(Precedence.NONE));
            }
            if (!isJava && type.isNonNull() && !(type instanceof PrimitiveTypeNode) && initializer.isEmpty()) {
                throw error(peek(), "Non-null field '" + name + "' requires an initializer.");
            }
            consume(TokenType.SEMICOLON, "Expected ';' after field declaration.");
            return new FieldDecl(modifiers, type, name, initializer);
        }
    }

    private List<TypeParameter> parseTypeParameters() {
        List<TypeParameter> params = new ArrayList<>();
        if (match(TokenType.LESS)) {
            do {
                String name = consume(TokenType.IDENTIFIER, "Expected type parameter name.").lexeme();
                Optional<TypeNode> bound = Optional.empty();
                if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("extends")) {
                    advance();
                    bound = Optional.of(parseType());
                }
                params.add(new TypeParameter(name, bound));
            } while (match(TokenType.COMMA));
            consume(TokenType.GREATER, "Expected '>' after type parameters.");
        }
        return params;
    }

    private boolean isTypeStartToken(Token t) {
        if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.VOID) return true;
        String lex = t.lexeme();
        return lex.equals("int") || lex.equals("long") || lex.equals("double") ||
               lex.equals("float") || lex.equals("boolean") || lex.equals("char") ||
               lex.equals("byte") || lex.equals("short");
    }

    private boolean isMutableParameterStart() {
        Token t0 = peek();
        if (t0.lexeme().equals("int") || t0.lexeme().equals("long") || t0.lexeme().equals("double") ||
            t0.lexeme().equals("float") || t0.lexeme().equals("boolean") || t0.lexeme().equals("char") ||
            t0.lexeme().equals("byte") || t0.lexeme().equals("short")) {
            return true;
        }
        if (t0.type() != TokenType.IDENTIFIER) {
            return false;
        }
        int idx = current + 1;
        while (idx < tokens.size()) {
            TokenType tt = tokens.get(idx).type();
            if (tt == TokenType.QUESTION) {
                idx++;
            } else if (tt == TokenType.DOT) {
                idx++;
                if (idx < tokens.size() && tokens.get(idx).type() == TokenType.IDENTIFIER) {
                    idx++;
                }
            } else if (tt == TokenType.LESS) {
                int depth = 1;
                idx++;
                while (idx < tokens.size() && depth > 0) {
                    if (tokens.get(idx).type() == TokenType.LESS) depth++;
                    else if (tokens.get(idx).type() == TokenType.GREATER) depth--;
                    idx++;
                }
            } else if (tt == TokenType.LEFT_BRACKET) {
                idx++;
                if (idx < tokens.size() && tokens.get(idx).type() == TokenType.RIGHT_BRACKET) {
                    idx++;
                }
                if (idx < tokens.size() && tokens.get(idx).type() == TokenType.QUESTION) {
                    idx++;
                }
            } else {
                break;
            }
        }
        if (idx < tokens.size()) {
            Token target = tokens.get(idx);
            if (target.type() == TokenType.IDENTIFIER) {
                return true;
            }
            if (target.type() == TokenType.DOT && idx + 2 < tokens.size() &&
                tokens.get(idx + 1).type() == TokenType.DOT &&
                tokens.get(idx + 2).type() == TokenType.DOT &&
                idx + 3 < tokens.size() && tokens.get(idx + 3).type() == TokenType.IDENTIFIER) {
                return true;
            }
        }
        return false;
    }

    private Parameter parseParameter() {
        return parseParameter(false);
    }

    private Parameter parseParameter(boolean defaultMutable) {
        List<String> annotations = new ArrayList<>();
        while (check(TokenType.AT)) {
            annotations.add(parseAnnotationAsString());
        }
        boolean isExplicitFinal = match(TokenType.FINAL);
        boolean isMutable = defaultMutable && !isExplicitFinal;
        TypeNode type;
        if (match(TokenType.VAR)) {
            if (isMutableParameterStart()) {
                isMutable = !isExplicitFinal;
                type = parseType();
            } else {
                type = new BaseTypeNode("var", List.of(), !isJava);
            }
        } else {
            type = parseType();
        }
        boolean isVarargs = false;
        if (match(TokenType.DOT)) {
            consume(TokenType.DOT, "Expected '...' for varargs.");
            consume(TokenType.DOT, "Expected '...' for varargs.");
            isVarargs = true;
        }
        String name = consume(TokenType.IDENTIFIER, "Expected parameter name.").lexeme();
        type = parseBindingSuffix(type);
        Optional<Expression> defaultValue = Optional.empty();
        if (match(TokenType.ASSIGN)) {
            defaultValue = Optional.of(parseExpression(Precedence.NONE));
        }
        return new Parameter(type, name, isVarargs, defaultValue, isMutable, annotations);
    }

    private boolean startsInferredBinding() {
        if (!check(TokenType.IDENTIFIER)) return false;
        int afterName = 1;
        if (lookAhead(afterName).type() == TokenType.QUESTION || lookAhead(afterName).type() == TokenType.DOUBLE_QUESTION) afterName++;
        TokenType next = lookAhead(afterName).type();
        return next == TokenType.ASSIGN || next == TokenType.SEMICOLON;
    }

    /** A binding's outer nullability is written on its name, never its type. */
    private TypeNode parseBindingSuffix(TypeNode type) {
        if (isJava) return type;
        if (match(TokenType.DOUBLE_QUESTION)) {
            try { return type.withNullableElements(); }
            catch (IllegalArgumentException ex) { throw error(previous(), ex.getMessage()); }
        }
        if (match(TokenType.QUESTION)) {
            if (type instanceof PrimitiveTypeNode) throw error(previous(), "Primitive bindings cannot be nullable; use a boxed type.");
            return type.withNonNull(false);
        }
        if (check(TokenType.BANG)) throw error(peek(), "Non-null is the default; remove '!' and '!!'. Use '?' for nullable bindings.");
        return type;
    }

    private List<TypeNode> parseThrows() {
        List<TypeNode> throwsList = new ArrayList<>();
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("throws")) {
            advance();
            do {
                throwsList.add(parseType());
            } while (match(TokenType.COMMA));
        }
        return throwsList;
    }

    // --- Type Parsing ---

    private TypeNode parseType() {
        TypeNode type;
        if (match(TokenType.VOID)) {
            type = new PrimitiveTypeNode("void");
        } else if (match(TokenType.IDENTIFIER)) {
            validateIdentifier(previous());
            String name = previous().lexeme();
            while (match(TokenType.DOT)) {
                name += "." + consume(TokenType.IDENTIFIER, "Expected identifier after '.'.") .lexeme();
            }
            List<TypeNode> typeArgs = new ArrayList<>();
            if (match(TokenType.LESS)) {
                if (!check(TokenType.GREATER)) do {
                    typeArgs.add(parseType());
                } while (match(TokenType.COMMA));
                consume(TokenType.GREATER, "Expected '>' after type arguments.");
            }
            boolean isNonNull = !isJava && !match(TokenType.QUESTION);
            if (Set.of("int", "long", "double", "float", "boolean", "char", "byte", "short").contains(name)) {
                if (!isNonNull && !isJava) throw error(previous(), "Primitive types cannot be nullable; use a boxed type.");
                type = new PrimitiveTypeNode(name);
            } else type = new BaseTypeNode(name, typeArgs, isNonNull);
        } else {
            // Check primitive types
            Token t = peek();
            if (t.lexeme().equals("int") || t.lexeme().equals("long") || t.lexeme().equals("double") ||
                t.lexeme().equals("float") || t.lexeme().equals("boolean") || t.lexeme().equals("char") ||
                t.lexeme().equals("byte") || t.lexeme().equals("short")) {
                advance();
                type = new PrimitiveTypeNode(t.lexeme());
            } else {
                throw error(t, "Expected type name.");
            }
        }

        // Parse array dimensions
        while (check(TokenType.LEFT_BRACKET) && lookAhead(1).type() == TokenType.RIGHT_BRACKET) {
            advance();
            consume(TokenType.RIGHT_BRACKET, "Expected ']' for array type.");
            boolean isNonNull = !isJava && !match(TokenType.QUESTION);
            type = new ArrayTypeNode(type, isNonNull);
        }

        return type;
    }

    // --- Statement Parsing ---

    private Statement parseStatement() {
        if (check(TokenType.LEFT_BRACE)) {
            return parseBlockStatement();
        }
        if (match(TokenType.IF)) {
            return parseIfStatement();
        }
        if (isEachStart()) {
            advance();
            return parseEachStatement();
        }
        if (match(TokenType.FOR)) {
            return parseForStatement();
        }
        if (match(TokenType.WHILE)) {
            return parseWhileStatement();
        }
        if (match(TokenType.DO)) {
            return parseDoWhileStatement();
        }
        if (match(TokenType.TRY)) {
            return parseTryStatement();
        }
        if (match(TokenType.RETURN)) {
            Optional<Expression> expr = Optional.empty();
            if (!check(TokenType.SEMICOLON)) {
                expr = Optional.of(parseExpression(Precedence.NONE));
            }
            consume(TokenType.SEMICOLON, "Expected ';' after return statement.");
            return new ReturnStmt(expr);
        }
        if (match(TokenType.YIELD)) {
            Expression expr = parseExpression(Precedence.NONE);
            consume(TokenType.SEMICOLON, "Expected ';' after yield statement.");
            return new YieldStmt(expr);
        }
        if (match(TokenType.BREAK)) {
            consume(TokenType.SEMICOLON, "Expected ';' after break.");
            return new BreakStmt();
        }
        if (match(TokenType.CONTINUE)) {
            consume(TokenType.SEMICOLON, "Expected ';' after continue.");
            return new ContinueStmt();
        }
        if (match(TokenType.THROW)) {
            Expression expr = parseExpression(Precedence.NONE);
            consume(TokenType.SEMICOLON, "Expected ';' after throw statement.");
            return new ThrowStmt(expr);
        }
        if (match(TokenType.EMIT)) {
            Expression expr = parseExpression(Precedence.NONE);
            consume(TokenType.SEMICOLON, "Expected ';' after emit statement.");
            return new EmitStmt(expr);
        }

        // Local variable declarations vs Expressions
        if (isLocalVarDeclaration()) {
            return parseLocalVarDeclaration();
        }

        // Expression statement
        Expression expr = parseExpression(Precedence.NONE);
        consume(TokenType.SEMICOLON, "Expected ';' after expression.");
        return new ExprStmt(expr);
    }

    private BlockStmt parseBlockStatement() {
        consume(TokenType.LEFT_BRACE, "Expected '{' to start block.");
        List<Statement> statements = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            statements.add(parseStatement());
        }
        consume(TokenType.RIGHT_BRACE, "Expected '}' after block.");
        return new BlockStmt(statements);
    }

    private Statement parseIfStatement() {
        consume(TokenType.LEFT_PAREN, "Expected '(' after 'if'.");
        Expression condition = parseExpression(Precedence.NONE);
        consume(TokenType.RIGHT_PAREN, "Expected ')' after 'if' condition.");

        BlockStmt thenBranch = parseRequiredBranchBlock("if");
        Optional<Statement> elseBranch = Optional.empty();
        if (match(TokenType.ELSE)) {
            elseBranch = Optional.of(match(TokenType.IF)
                    ? parseIfStatement() : parseRequiredBranchBlock("else"));
        }

        return new IfStmt(condition, thenBranch, elseBranch);
    }

    private BlockStmt parseRequiredBranchBlock(String branch) {
        if (!check(TokenType.LEFT_BRACE)) {
            throw error(peek(), "The '" + branch + "' body requires braces '{ ... }', even for a single statement.");
        }
        return parseBlockStatement();
    }

    private boolean isEachStart() {
        return !isJava && check(TokenType.IDENTIFIER) && peek().lexeme().equals("each")
                && lookAhead(1).type() == TokenType.LEFT_PAREN;
    }

    private Parameter parseEachParameter() {
        int nameOffset = check(TokenType.FINAL) ? 1 : 0;
        if (lookAhead(nameOffset).type() == TokenType.IDENTIFIER) {
            int separator = nameOffset + 1;
            if (lookAhead(separator).type() == TokenType.QUESTION
                    || lookAhead(separator).type() == TokenType.DOUBLE_QUESTION) separator++;
            if (lookAhead(separator).type() == TokenType.IDENTIFIER && lookAhead(separator).lexeme().equals("from")) {
                if (nameOffset == 1) advance();
                String name = consume(TokenType.IDENTIFIER, "Expected item name.").lexeme();
                TypeNode type = parseBindingSuffix(new BaseTypeNode("var", List.of(), true));
                return new Parameter(type, name, false, Optional.empty());
            }
        }
        return parseParameter();
    }

    private void consumeFrom() {
        Token token = consume(TokenType.IDENTIFIER, "Expected 'from' after each binding.");
        if (!token.lexeme().equals("from")) throw error(token, "Expected 'from' after each binding.");
    }

    private Statement parseEachStatement() {
        consume(TokenType.LEFT_PAREN, "Expected '(' after 'each'.");
        Parameter parameter = parseEachParameter();
        consumeFrom();
        Expression iterable = parseExpression(Precedence.NONE);
        consume(TokenType.RIGHT_PAREN, "Expected ')' after each clause.");
        Statement body = parseRequiredBranchBlock("each");
        Optional<Statement> elseBranch = Optional.empty();
        if (match(TokenType.ELSE)) elseBranch = Optional.of(parseRequiredBranchBlock("else"));
        return new ForEachStmt(parameter, iterable, body, elseBranch);
    }

    private Statement parseForStatement() {
        consume(TokenType.LEFT_PAREN, "Expected '(' after 'for'.");

        // We could have an enhanced for: for (Type! name : expr)
        // Let's use lookahead: find ':' before the first ';'
        boolean isEnhanced = false;
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            TokenType t = tokens.get(i).type();
            if (t == TokenType.LEFT_PAREN) depth++;
            if (t == TokenType.RIGHT_PAREN) depth--;
            if (depth < 0) break;
            if (t == TokenType.SEMICOLON) break;
            if (t == TokenType.COLON && depth == 0) {
                isEnhanced = true;
                break;
            }
        }

        if (isEnhanced) {
            Parameter param = parseParameter();
            consume(TokenType.COLON, "Expected ':' after loop variable.");
            Expression iterable = parseExpression(Precedence.NONE);
            consume(TokenType.RIGHT_PAREN, "Expected ')' after for clause.");
            Statement body = parseStatement();
            Optional<Statement> elseBranch = Optional.empty();
            if (match(TokenType.ELSE)) {
                elseBranch = Optional.of(parseRequiredBranchBlock("else"));
            }
            return new ForEachStmt(param, iterable, body, elseBranch);
        } else {
            Optional<Statement> init = Optional.empty();
            if (!match(TokenType.SEMICOLON)) {
                if (isLocalVarDeclaration()) {
                    if (!isJava) {
                        report(peek(), "Traditional for initializers infer their type; omit 'var', explicit types, and 'final'.");
                    }
                    init = Optional.of(parseLocalVarDeclaration());
                } else {
                    Expression initializer = parseExpression(Precedence.NONE);
                    consume(TokenType.SEMICOLON, "Expected ';' after loop initializer.");
                    if (!isJava && initializer instanceof BinaryExpr assignment
                            && assignment.operator().type() == TokenType.ASSIGN
                            && assignment.left() instanceof IdentifierExpr) {
                        init = Optional.of(new ForInitStmt(assignment));
                    } else {
                        init = Optional.of(new ExprStmt(initializer));
                    }
                }
            }

            Optional<Expression> condition = Optional.empty();
            if (!check(TokenType.SEMICOLON)) {
                condition = Optional.of(parseExpression(Precedence.NONE));
            }
            consume(TokenType.SEMICOLON, "Expected ';' after loop condition.");

            Optional<Expression> update = Optional.empty();
            if (!check(TokenType.RIGHT_PAREN)) {
                update = Optional.of(parseExpression(Precedence.NONE));
            }
            consume(TokenType.RIGHT_PAREN, "Expected ')' after for clauses.");

            Statement body = parseStatement();
            Optional<Statement> elseBranch = Optional.empty();
            if (match(TokenType.ELSE)) {
                elseBranch = Optional.of(parseRequiredBranchBlock("else"));
            }
            return new ForStmt(init, condition, update, body, elseBranch);
        }
    }

    private Statement parseWhileStatement() {
        consume(TokenType.LEFT_PAREN, "Expected '(' after 'while'.");
        Expression condition = parseExpression(Precedence.NONE);
        consume(TokenType.RIGHT_PAREN, "Expected ')' after 'while' condition.");
        Statement body = parseStatement();
        Optional<Statement> elseBranch = Optional.empty();
        if (match(TokenType.ELSE)) {
            if (!isJava && !(body instanceof BlockStmt)) report(previous(), "A while/else body requires braces.");
            elseBranch = Optional.of(parseRequiredBranchBlock("else"));
        }
        return new WhileStmt(condition, body, elseBranch);
    }

    private Statement parseDoWhileStatement() {
        Statement body = parseStatement();
        consume(TokenType.WHILE, "Expected 'while' after do body.");
        consume(TokenType.LEFT_PAREN, "Expected '(' after 'while'.");
        Expression condition = parseExpression(Precedence.NONE);
        consume(TokenType.RIGHT_PAREN, "Expected ')' after condition.");
        Optional<Statement> elseBranch = Optional.empty();
        if (!isJava && match(TokenType.ELSE)) {
            if (!(body instanceof BlockStmt)) report(previous(), "A do-while/else body requires braces.");
            elseBranch = Optional.of(parseRequiredBranchBlock("else"));
        } else {
            consume(TokenType.SEMICOLON, "Expected ';' after do-while loop, or 'else { ... }'.");
        }
        return new DoWhileStmt(body, condition, elseBranch);
    }

    private Statement parseTryStatement() {
        BlockStmt body = parseBlockStatement();
        List<CatchClause> catches = new ArrayList<>();
        while (match(TokenType.CATCH)) {
            consume(TokenType.LEFT_PAREN, "Expected '(' after catch.");
            Parameter parameter = parseParameter();
            consume(TokenType.RIGHT_PAREN, "Expected ')' after catch parameter.");
            BlockStmt catchBody = parseBlockStatement();
            catches.add(new CatchClause(parameter, catchBody));
        }

        Optional<BlockStmt> finallyBlock = Optional.empty();
        if (match(TokenType.FINALLY)) {
            finallyBlock = Optional.of(parseBlockStatement());
        }

        return new TryStmt(body, catches, finallyBlock);
    }

    private boolean isLocalVarDeclaration() {
        if (check(TokenType.FINAL) || check(TokenType.VAR)) return true;
        
        int saved = current;
        boolean oldSpeculative = isSpeculative;
        isSpeculative = true;
        try {
            parseType();
            boolean isDecl = check(TokenType.IDENTIFIER);
            current = saved;
            isSpeculative = oldSpeculative;
            return isDecl;
        } catch (Exception e) {
            current = saved;
            isSpeculative = oldSpeculative;
            return false;
        }
    }

    private Statement parseLocalVarDeclaration() {
        boolean isFinal = false;
        boolean isInferred = false;
        Optional<TypeNode> type = Optional.empty();

        if (match(TokenType.FINAL)) {
            isFinal = true;
            if (match(TokenType.VAR)) {
                // Reject 'final var' in compiler checks, let parser process it as inferred
                isInferred = true;
            } else if (startsInferredBinding()) {
                // final x = init
                isInferred = true;
            } else {
                // final String x = init
                type = Optional.of(parseType());
            }
        } else if (match(TokenType.VAR)) {
            isInferred = true;
        } else {
            // Type x = init
            type = Optional.of(parseType());
        }

        String name = consume(TokenType.IDENTIFIER, "Expected variable name.").lexeme();
        boolean nonNullBinding;
        boolean nonNullElements = false;
        if (type.isPresent()) {
            TypeNode bindingType = parseBindingSuffix(type.get());
            type = Optional.of(bindingType);
            nonNullBinding = bindingType.isNonNull() && !(bindingType instanceof PrimitiveTypeNode);
            nonNullElements = bindingType.hasNonNullElements();
        } else {
            nonNullBinding = !match(TokenType.QUESTION);
        }
        Optional<Expression> initializer = Optional.empty();
        if (match(TokenType.ASSIGN)) {
            initializer = Optional.of(parseExpression(Precedence.NONE));
        }

        // In enhanced for context we don't end with Semicolon, but we handle it elsewhere.
        // If we are in a normal statement context, we consume the semicolon.
        if (check(TokenType.SEMICOLON)) {
            consume(TokenType.SEMICOLON, "Expected ';' after variable declaration.");
        }

        if (nonNullBinding && initializer.isEmpty()) {
            throw error(previous(), "Non-null variable '" + name + "' requires an initializer.");
        }
        return new LocalVarDeclStmt(isFinal, isInferred, type, name, initializer, nonNullBinding, nonNullElements);
    }

    // --- Expression Parsing (Pratt Parser) ---

    private enum Precedence {
        NONE,
        ASSIGNMENT,       // = += -=
        TERNARY,          // then / else (Java: ? :)
        LAMBDA,           // ->
        NULL_COALESCING,  // else (null-only fallback)
        OR,               // or
        AND,              // and
        EQUALITY,         // == !=
        COMPARISON,       // < > <= >= instanceof
        TERM,             // + -
        FACTOR,           // * / %
        UNARY,            // ! not - ++ --
        CALL,             // . ?. ( [
        PRIMARY
    }

    private Precedence getPrecedence(Token token) {
        if (!isJava && token.type() == TokenType.IDENTIFIER && token.lexeme().equals("then")) {
            return Precedence.TERNARY;
        }
        return switch (token.type()) {
            case ASSIGN -> Precedence.ASSIGNMENT;
            case QUESTION -> Precedence.TERNARY;
            case ARROW -> Precedence.LAMBDA;
            case NULL_COALESCING -> Precedence.NULL_COALESCING;
            case ELSE -> isJava ? Precedence.NONE : Precedence.NULL_COALESCING;
            case OR -> Precedence.OR;
            case AND -> Precedence.AND;
            case EQUAL_EQUAL, BANG_EQUAL -> Precedence.EQUALITY;
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, INSTANCEOF -> Precedence.COMPARISON;
            case PLUS, MINUS -> Precedence.TERM;
            case STAR, SLASH, PERCENT -> Precedence.FACTOR;
            case DOT, OPTIONAL_CHAIN, LEFT_PAREN, LEFT_BRACKET, PLUS_PLUS, MINUS_MINUS -> Precedence.CALL;
            default -> Precedence.NONE;
        };
    }

    private Expression parseExpression(Precedence precedence) {
        return parseExpression(precedence, false);
    }

    private Expression parseExpression(Precedence precedence, boolean stopAtElse) {
        Token token = advance();
        Expression left = parsePrefix(token, stopAtElse);

        while (true) {
            if (stopAtElse && check(TokenType.ELSE)) break;
            if (check(TokenType.DOUBLE_QUESTION)) {
                Token marker = advance();
                left = new NullableExpr(left, marker, true);
                continue;
            }
            if (isNullablePostfix()) {
                Token marker = advance();
                left = new NullableExpr(left, marker);
                continue;
            }
            if (precedence.ordinal() >= getPrecedence(peek()).ordinal()) break;
            Token op = advance();
            left = parseInfix(left, op, stopAtElse);
        }

        return left;
    }

    private boolean isNullablePostfix() {
        if (!check(TokenType.QUESTION) || previous().endOffset() != peek().startOffset()) return false;
        if (!isJava && lookAhead(1).type() == TokenType.IDENTIFIER
                && lookAhead(1).lexeme().equals("then")) return true;
        return switch (lookAhead(1).type()) {
            case ELSE, SEMICOLON, COMMA, RIGHT_PAREN, RIGHT_BRACKET, RIGHT_BRACE, INTERPOLATION_END, EOF, DOT, OPTIONAL_CHAIN,
                 LEFT_BRACKET, ASSIGN, EQUAL_EQUAL, BANG_EQUAL, NULL_COALESCING, QUESTION,
                 PLUS, MINUS, STAR, SLASH, LESS, GREATER, LESS_EQUAL, GREATER_EQUAL,
                 AND, OR, COLON, ARROW -> true;
            default -> false;
        };
    }

    private Expression parsePrefix(Token token, boolean stopAtElse) {
        return switch (token.type()) {
            case NUMBER -> new LiteralExpr(Double.parseDouble(token.lexeme()), token);
            case STRING -> {
                if (token.lexeme().startsWith("\"\"\"")) {
                    yield new LiteralExpr(token.lexeme().substring(3, token.lexeme().length() - 3), token);
                } else {
                    yield new LiteralExpr(token.lexeme().substring(1, token.lexeme().length() - 1), token);
                }
            }
            case INTERPOLATED_STRING_START -> parseInterpolatedStringExpression(token);
            case TRUE -> new LiteralExpr(true, token);
            case FALSE -> new LiteralExpr(false, token);
            case NULL -> new LiteralExpr(null, token);
            case IDENTIFIER -> {
                validateIdentifier(token);
                if (!isJava && token.lexeme().equals("each") && check(TokenType.LEFT_PAREN)) {
                    yield new LoopExpr(parseEachStatement(), token);
                }
                if (token.lexeme().equals("sql")) {
                    Optional<TypeNode> resultType = Optional.empty();
                    if (match(TokenType.LESS)) {
                        resultType = Optional.of(parseType());
                        consume(TokenType.GREATER, "Expected '>' after SQL result type.");
                    }
                    if (check(TokenType.TEMPLATE_STRING)) {
                        Token templateToken = advance();
                        yield parseSqlExpression(templateToken, resultType);
                    }
                }
                if (token.lexeme().equals("json")) {
                    Optional<TypeNode> resultType = Optional.empty();
                    if (match(TokenType.LESS)) {
                        resultType = Optional.of(parseType());
                        consume(TokenType.GREATER, "Expected '>' after JSON result type.");
                    }
                    if (check(TokenType.TEMPLATE_STRING) || check(TokenType.STRING)) {
                        Token templateToken = advance();
                        yield parseJsonExpression(templateToken, resultType);
                    }
                }
                // Could be start of lambda: x -> x or x! -> x!
                boolean nonNullBinding = false;
                boolean nonNullElements = false;
                if (check(TokenType.ARROW)) {
                    if (nonNullElements) throw error(token, "Use an explicitly typed array or List<T> parameter for '!!' lambdas.");
                    advance(); // consume ->
                    Node body = check(TokenType.LEFT_BRACE) ? parseStatement() : parseExpression(Precedence.NONE, stopAtElse);
                    yield new LambdaExpr(List.of(new Parameter(new BaseTypeNode("var", List.of(), true), token.lexeme(), false, Optional.empty())), body, token);
                }
                yield new IdentifierExpr(token.lexeme(), token, nonNullBinding, nonNullElements);
            }
            case THIS -> new ThisExpr(token);
            case SUPER -> new SuperExpr(token);
            case STATIC -> {
                if (!check(TokenType.DOT)) throw error(peek(), "Use 'static.' to access a static member of the current class.");
                yield new StaticExpr(token);
            }
            case LEFT_PAREN -> {
                // Could be a lambda parameter list: (String s!, int i) -> body
                // Let's do a scan-ahead to see if we have an ARROW after the matching right paren.
                boolean isLambda = false;
                int depth = 1;
                for (int i = current; i < tokens.size(); i++) {
                    TokenType t = tokens.get(i).type();
                    if (t == TokenType.LEFT_PAREN) depth++;
                    if (t == TokenType.RIGHT_PAREN) depth--;
                    if (depth == 0) {
                        if (i + 1 < tokens.size() && tokens.get(i + 1).type() == TokenType.ARROW) {
                            isLambda = true;
                        }
                        break;
                    }
                }

                if (isLambda) {
                    List<Parameter> parameters = new ArrayList<>();
                    if (!check(TokenType.RIGHT_PAREN)) {
                        do {
                            if (check(TokenType.IDENTIFIER)
                                    && (lookAhead(1).type() == TokenType.COMMA || lookAhead(1).type() == TokenType.RIGHT_PAREN
                                        || lookAhead(1).type() == TokenType.QUESTION)) {
                                String parameterName = consume(TokenType.IDENTIFIER, "Expected lambda parameter name.").lexeme();
                                parameters.add(new Parameter(new BaseTypeNode("var", List.of(), !match(TokenType.QUESTION)),
                                        parameterName, false, Optional.empty()));
                            } else parameters.add(parseParameter());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after lambda parameters.");
                    consume(TokenType.ARROW, "Expected '->' for lambda.");
                    Node body;
                    if (check(TokenType.LEFT_BRACE)) {
                        body = parseStatement();
                    } else {
                        body = parseExpression(Precedence.NONE, stopAtElse);
                    }
                    yield new LambdaExpr(parameters, body, token);
                } else {
                    Expression expr = parseExpression(Precedence.NONE);
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after parenthesized expression.");
                    yield expr;
                }
            }
            case BANG -> throw error(token, "Use 'not' instead of '!' for logical negation.");
            case MINUS, PLUS -> {
                Expression right = parseExpression(Precedence.UNARY);
                yield new UnaryExpr(token, right, false);
            }
            case NOT -> {
                // Textual 'not' operator — same precedence as '!'
                Expression right = parseExpression(Precedence.UNARY);
                yield new UnaryExpr(token, right, false);
            }
            case PLUS_PLUS, MINUS_MINUS -> {
                Expression right = parseExpression(Precedence.UNARY);
                yield new UnaryExpr(token, right, false);
            }
            case ASYNC -> {
                Expression operation = parseExpression(Precedence.UNARY);
                LambdaExpr lambda = new LambdaExpr(List.of(), operation, token);
                yield new MethodCallExpr(new IdentifierExpr("accent.runtime.Async", token),
                        "run", List.of(), List.of(lambda), false, token);
            }
            case AWAIT -> {
                Expression value = parseExpression(Precedence.UNARY);
                yield new MethodCallExpr(value, "await", List.of(), List.of(), false, token);
            }
            case LEFT_BRACE -> {
                List<Expression> values = new ArrayList<>();
                if (!check(TokenType.RIGHT_BRACE)) do {
                    values.add(parseExpression(Precedence.NONE));
                } while (match(TokenType.COMMA) && !check(TokenType.RIGHT_BRACE));
                consume(TokenType.RIGHT_BRACE, "Expected '}' after array initializer.");
                yield new ArrayInitializerExpr(values, token);
            }
            case NEW -> {
                TypeNode type = parseType();
                if (type instanceof ArrayTypeNode array && check(TokenType.LEFT_BRACE)) {
                    ArrayInitializerExpr initializer = (ArrayInitializerExpr) parsePrefix(advance(), false);
                    yield new NewArrayExpr(array.elementType(), List.of(), Optional.of(initializer), token);
                }
                if (match(TokenType.LEFT_PAREN)) {
                    List<Expression> arguments = parseCallArguments();
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after arguments.");
                    yield new NewObjectExpr(type, arguments, token);
                } else if (match(TokenType.LEFT_BRACKET)) {
                    List<Expression> dimensions = new ArrayList<>();
                    if (!match(TokenType.RIGHT_BRACKET)) {
                        dimensions.add(parseExpression(Precedence.NONE));
                        consume(TokenType.RIGHT_BRACKET, "Expected ']' for array dimension.");
                    }
                    while (match(TokenType.LEFT_BRACKET)) {
                        consume(TokenType.RIGHT_BRACKET, "Expected ']' for array dimension.");
                    }
                    Optional<ArrayInitializerExpr> initializer = Optional.empty();
                    if (match(TokenType.LEFT_BRACE)) {
                        List<Expression> exprs = new ArrayList<>();
                        if (!check(TokenType.RIGHT_BRACE)) {
                            do {
                                exprs.add(parseExpression(Precedence.NONE));
                            } while (match(TokenType.COMMA));
                        }
                        consume(TokenType.RIGHT_BRACE, "Expected '}' after array initializer.");
                        initializer = Optional.of(new ArrayInitializerExpr(exprs, token));
                    }
                    yield new NewArrayExpr(type, dimensions, initializer, token);
                }
                throw error(token, "Expected '(' or '[' after new type.");
            }
            case IF -> {
                consume(TokenType.LEFT_PAREN, "Expected '(' after 'if'.");
                Expression condition = parseExpression(Precedence.NONE);
                consume(TokenType.RIGHT_PAREN, "Expected ')' after condition.");
                BlockStmt thenBranch = parseBlockStatement();
                consume(TokenType.ELSE, "Expected 'else' branch for if-expression.");
                BlockStmt elseBranch = parseBlockStatement();
                yield new IfExpr(condition, thenBranch, elseBranch, token);
            }
            case TRY -> {
                BlockStmt body = parseBlockStatement();
                List<CatchClause> catches = new ArrayList<>();
                while (match(TokenType.CATCH)) {
                    consume(TokenType.LEFT_PAREN, "Expected '(' after catch.");
                    Parameter parameter = parseParameter();
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after parameter.");
                    BlockStmt catchBody = parseBlockStatement();
                    catches.add(new CatchClause(parameter, catchBody));
                }
                Optional<BlockStmt> finallyBlock = Optional.empty();
                if (match(TokenType.FINALLY)) {
                    finallyBlock = Optional.of(parseBlockStatement());
                }
                yield new TryExpr(body, catches, finallyBlock, token);
            }
            case FOR, WHILE, DO -> {
                // Loop used as expression
                current--; // Unconsume the keyword
                Statement loop = parseStatement();
                yield new LoopExpr(loop, token);
            }
            case LESS -> parseMarkupExpression(token);
            default -> throw error(token, "Expected expression.");
        };
    }

    private Expression parseMarkupExpression(Token lessToken) {
        MarkupNode node = parseMarkupNode(lessToken);
        return new MarkupExpr(node, lessToken);
    }

    private MarkupNode parseMarkupNode(Token lessToken) {
        if (match(TokenType.GREATER)) {
            // Fragment: <> children </>
            List<MarkupNode> children = parseMarkupChildren("", lessToken);
            return new FragmentNode(children, lessToken);
        }
        if (match(TokenType.BANG)) {
            Token docToken = consume(TokenType.IDENTIFIER, "Expected 'DOCTYPE'.");
            if (!docToken.lexeme().equalsIgnoreCase("DOCTYPE")) {
                throw error(docToken, "Expected 'DOCTYPE' after '<!'.");
            }
            Token htmlToken = consume(TokenType.IDENTIFIER, "Expected 'html'.");
            if (!htmlToken.lexeme().equalsIgnoreCase("html")) {
                throw error(htmlToken, "Expected 'html' after '<!DOCTYPE'.");
            }
            consume(TokenType.GREATER, "Expected '>' to close doctype declaration.");
            TextNode docNode = new TextNode("<!DOCTYPE html>", lessToken);
            if (check(TokenType.LESS)) {
                Token nextLess = advance();
                MarkupNode nextNode = parseMarkupNode(nextLess);
                return new FragmentNode(List.of(docNode, nextNode), lessToken);
            }
            return docNode;
        }
        
        Token nameToken = consume(TokenType.IDENTIFIER, "Expected element or component name.");
        String tagName = nameToken.lexeme();
        boolean isComponent = Character.isUpperCase(tagName.charAt(0));
        
        List<AttributeNode> attributes = new ArrayList<>();
        List<ComponentArgumentNode> arguments = new ArrayList<>();
        
        while (!check(TokenType.GREATER) && !check(TokenType.SLASH) && !isAtEnd()) {
            Token attrNameToken = advance();
            String attrName = attrNameToken.lexeme();
            
            Expression attrValue = null;
            if (match(TokenType.ASSIGN)) {
                if (check(TokenType.STRING)) {
                    Token strToken = advance();
                    String val = strToken.lexeme().substring(1, strToken.lexeme().length() - 1);
                    attrValue = new LiteralExpr(val, strToken);
                } else if (match(TokenType.LEFT_BRACE)) {
                    attrValue = parseExpression(Precedence.NONE);
                    consume(TokenType.RIGHT_BRACE, "Expected '}' after attribute expression.");
                } else {
                    throw error(peek(), "Expected string literal or '{expression}' for attribute value.");
                }
            } else {
                attrValue = new LiteralExpr(true, attrNameToken);
            }
            
            if (isComponent) {
                arguments.add(new ComponentArgumentNode(attrName, attrValue, attrNameToken));
            } else {
                attributes.add(new AttributeNode(attrName, attrValue, attrNameToken));
            }
        }
        
        if (match(TokenType.SLASH)) {
            consume(TokenType.GREATER, "Expected '>' after '/' to close self-closing tag.");
            if (isComponent) {
                return new ComponentNode(tagName, arguments, List.of(), lessToken);
            } else {
                return new ElementNode(tagName, attributes, List.of(), lessToken);
            }
        }
        
        consume(TokenType.GREATER, "Expected '>' to close opening tag.");
        
        if (!isComponent && isVoidElement(tagName)) {
            return new ElementNode(tagName, attributes, List.of(), lessToken);
        }
        
        List<MarkupNode> children = parseMarkupChildren(tagName, lessToken);
        
        if (isComponent) {
            return new ComponentNode(tagName, arguments, children, lessToken);
        } else {
            return new ElementNode(tagName, attributes, children, lessToken);
        }
    }

    private List<MarkupNode> parseMarkupChildren(String expectedTagName, Token startToken) {
        List<MarkupNode> children = new ArrayList<>();
        
        while (!isAtEnd()) {
            if (check(TokenType.LESS) && lookAhead(1).type() == TokenType.SLASH) {
                Token lessToken = advance(); // <
                Token slashToken = advance(); // /
                
                if (expectedTagName.isEmpty()) {
                    consume(TokenType.GREATER, "Expected '>' to close fragment.");
                    return children;
                }
                
                Token closingNameToken = consume(TokenType.IDENTIFIER, "Expected closing tag name.");
                String closingName = closingNameToken.lexeme();
                consume(TokenType.GREATER, "Expected '>' to close tag.");
                
                if (!closingName.equals(expectedTagName)) {
                    throw error(closingNameToken, "Expected closing tag </" + expectedTagName + ">, but found </" + closingName + ">.");
                }
                
                return children;
            }
            
            int prevEnd = tokens.get(current - 1).endOffset();
            
            if (match(TokenType.LEFT_BRACE)) {
                children.add(parseMarkupInterpolation());
            } else if (check(TokenType.LESS)) {
                Token lessToken = advance();
                children.add(parseMarkupNode(lessToken));
            } else {
                int textStart = prevEnd;
                int k = current;
                while (k < tokens.size() && tokens.get(k).type() != TokenType.LEFT_BRACE && tokens.get(k).type() != TokenType.LESS) {
                    k++;
                }
                int textEnd = tokens.get(k).startOffset();
                current = k;
                String textVal = sourceFile.content().substring(textStart, textEnd);
                children.add(new TextNode(textVal, tokens.get(current - 1)));
            }
        }
        
        throw error(startToken, "Unterminated HTML element. Expected closing tag </" + expectedTagName + ">.");
    }

    private List<MarkupNode> parseMarkupChildrenInBlock() {
        List<MarkupNode> children = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            if (match(TokenType.LEFT_BRACE)) {
                children.add(parseMarkupInterpolation());
            } else if (check(TokenType.LESS)) {
                Token lessToken = advance();
                children.add(parseMarkupNode(lessToken));
            } else {
                int textStart = tokens.get(current - 1).endOffset();
                int k = current;
                while (k < tokens.size() && tokens.get(k).type() != TokenType.LEFT_BRACE && tokens.get(k).type() != TokenType.LESS && tokens.get(k).type() != TokenType.RIGHT_BRACE) {
                    k++;
                }
                int textEnd = tokens.get(k).startOffset();
                current = k;
                String textVal = sourceFile.content().substring(textStart, textEnd);
                children.add(new TextNode(textVal, tokens.get(current - 1)));
            }
        }
        return children;
    }

    // The outer interpolation braces are distinct from control-flow body braces.
    private MarkupNode parseMarkupInterpolation() {
        MarkupNode node;
        if (check(TokenType.IF)) {
            node = parseMarkupNodeInsideIfChain();
        } else if (isEachStart() || check(TokenType.FOR)) {
            boolean each = isEachStart();
            Token forToken = advance();
            boolean hasParen = match(TokenType.LEFT_PAREN);
            Parameter loopVar = each ? parseEachParameter() : parseParameter();
            if (each) {
                consumeFrom();
            } else if (!match(TokenType.COLON)) {
                Token in = consume(TokenType.IDENTIFIER, "Expected ':' or 'in' in for loop.");
                if (!in.lexeme().equals("in")) throw error(in, "Expected ':' or 'in' in for loop.");
            }
            Expression iterable = parseExpression(Precedence.NONE);
            if (hasParen) consume(TokenType.RIGHT_PAREN, "Expected ')' after for loop expression.");
            consume(TokenType.LEFT_BRACE, "Expected '{' for loop body.");
            List<MarkupNode> body = parseMarkupChildrenInBlock();
            consume(TokenType.RIGHT_BRACE, "Expected '}' after for loop body.");
            List<MarkupNode> elseBranch = List.of();
            if (match(TokenType.ELSE)) {
                consume(TokenType.LEFT_BRACE, "Expected '{' for 'else' block.");
                elseBranch = parseMarkupChildrenInBlock();
                consume(TokenType.RIGHT_BRACE, "Expected '}' after 'else' block.");
            }
            node = new ForMarkupNode(loopVar, iterable, body, elseBranch, forToken);
        } else {
            node = new ExpressionNode(parseExpression(Precedence.NONE));
        }
        consume(TokenType.RIGHT_BRACE, "Expected '}' to close markup expression.");
        return node;
    }

    private MarkupNode parseMarkupNodeInsideIfChain() {
        Token ifToken = consume(TokenType.IF, "Expected 'if' in else if.");
        Expression condition = parseExpression(Precedence.NONE);
        consume(TokenType.LEFT_BRACE, "Expected '{' for 'if' block.");
        List<MarkupNode> trueBranch = parseMarkupChildrenInBlock();
        consume(TokenType.RIGHT_BRACE, "Expected '}' after 'if' block.");
        List<MarkupNode> falseBranch = List.of();
        if (match(TokenType.ELSE)) {
            if (check(TokenType.IF)) {
                falseBranch = List.of(parseMarkupNodeInsideIfChain());
            } else {
                consume(TokenType.LEFT_BRACE, "Expected '{' for 'else' block.");
                falseBranch = parseMarkupChildrenInBlock();
                consume(TokenType.RIGHT_BRACE, "Expected '}' after 'else' block.");
            }
        }
        return new IfMarkupNode(condition, trueBranch, falseBranch, ifToken);
    }

    private boolean isVoidElement(String name) {
        String lower = name.toLowerCase();
        return lower.equals("img") || lower.equals("br") || lower.equals("hr") ||
               lower.equals("input") || lower.equals("meta") || lower.equals("link") ||
               lower.equals("col") || lower.equals("embed") || lower.equals("source") ||
               lower.equals("track") || lower.equals("wbr") || lower.equals("param");
    }

    private Expression parseInfix(Expression left, Token op, boolean stopAtElse) {
        Precedence precedence = getPrecedence(op);
        if (!isJava && op.type() == TokenType.IDENTIFIER && op.lexeme().equals("then")) {
            Expression thenBranch = parseExpression(Precedence.NONE, true);
            consume(TokenType.ELSE, "Expected 'else' after conditional then-branch expression.");
            Expression elseBranch = parseExpression(Precedence.ASSIGNMENT, stopAtElse);
            return new TernaryExpr(left, thenBranch, elseBranch, op);
        }
        return switch (op.type()) {
            case NULL_COALESCING -> throw error(op, "Use 'value else fallback' instead of '?:'.");
            case ELSE -> {
                Token coalesce = new Token(TokenType.NULL_COALESCING, "else", op.line(), op.column(), op.startOffset(), op.endOffset());
                Expression right = parseExpression(Precedence.LAMBDA, stopAtElse);
                yield new BinaryExpr(left, coalesce, right);
            }
            case PLUS_PLUS, MINUS_MINUS -> {
                yield new UnaryExpr(op, left, true);
            }
            case DOT, OPTIONAL_CHAIN -> {
                boolean isOptional = op.type() == TokenType.OPTIONAL_CHAIN;
                // Method type arguments: obj.<T>method(...)
                List<TypeNode> typeArgs = new ArrayList<>();
                if (match(TokenType.LESS)) {
                    do {
                        typeArgs.add(parseType());
                    } while (match(TokenType.COMMA));
                    consume(TokenType.GREATER, "Expected '>' after type arguments.");
                }

                String memberName;
                if (check(TokenType.CLASS) || check(TokenType.AWAIT)) {
                    memberName = advance().lexeme();
                } else {
                    memberName = consume(TokenType.IDENTIFIER, "Expected member name after '" + op.lexeme() + "'.").lexeme();
                }

                boolean marked = false;
                boolean elements = false;
                if (match(TokenType.LEFT_PAREN)) {
                    if (elements) throw error(op, "Methods use a single return marker '!()', not '!!()'.");
                    List<Expression> arguments = parseCallArguments();
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after method arguments.");
                    yield new MethodCallExpr(left, memberName, typeArgs, arguments, isOptional, op, marked);
                } else {
                    yield new MemberAccessExpr(left, memberName, isOptional, op, marked, elements);
                }
            }
            case LEFT_PAREN -> {
                // Call on a method, e.g. identifier(args) or super(args). Only valid if left is IdentifierExpr or SuperExpr.
                if (left instanceof IdentifierExpr id) {
                    if (id.nonNullElements()) throw error(op, "Methods use a single return marker '!()', not '!!()'.");
                    List<Expression> arguments = parseCallArguments();
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after method arguments.");
                    yield new MethodCallExpr(null, id.name(), List.of(), arguments, false, op, id.nonNullBinding());
                } else if (left instanceof SuperExpr) {
                    List<Expression> arguments = parseCallArguments();
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after method arguments.");
                    yield new MethodCallExpr(null, "super", List.of(), arguments, false, op);
                }
                throw error(op, "Function pointers or calling non-method expressions is not supported.");
            }
            case LEFT_BRACKET -> {
                Expression index = parseExpression(Precedence.NONE);
                consume(TokenType.RIGHT_BRACKET, "Expected ']' after array index.");
                yield new ArrayAccessExpr(left, index, op);
            }
            case QUESTION -> {
                if (!isJava) throw error(op, "Use 'condition then value else value' instead of '? :'.");
                Expression thenBranch = parseExpression(Precedence.NONE);
                consume(TokenType.COLON, "Expected ':' after ternary then-branch expression.");
                Expression elseBranch = parseExpression(Precedence.TERNARY);
                yield new TernaryExpr(left, thenBranch, elseBranch, op);
            }
            case ASSIGN -> {
                // Right-associative
                Expression right = parseExpression(Precedence.values()[precedence.ordinal() - 1], stopAtElse);
                yield new BinaryExpr(left, op, right);
            }
            // Textual binary boolean operators — lower to equivalent BinaryExpr
            case AND -> {
                // 'and' -> same as '&&'  (short-circuit AND)
                Expression right = parseExpression(Precedence.AND, stopAtElse);
                yield new BinaryExpr(left, op, right);
            }
            case OR -> {
                // 'or' -> same as '||'  (short-circuit OR)
                Expression right = parseExpression(Precedence.OR, stopAtElse);
                yield new BinaryExpr(left, op, right);
            }
            default -> {
                Expression right = parseExpression(precedence, stopAtElse);
                yield new BinaryExpr(left, op, right);
            }
        };
    }

    private Token lookAhead(int distance) {
        int index = current + distance;
        if (index >= tokens.size()) {
            return tokens.get(tokens.size() - 1);
        }
        return tokens.get(index);
    }

    private List<Expression> parseCallArguments() {
        List<Expression> arguments = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                if (check(TokenType.IDENTIFIER) && lookAhead(1).type() == TokenType.COLON) {
                    String name = consume(TokenType.IDENTIFIER, "Expected named argument name.").lexeme();
                    consume(TokenType.COLON, "Expected ':' after named argument.");
                    Expression expr = parseExpression(Precedence.NONE);
                    arguments.add(new NamedArgExpr(name, expr));
                } else {
                    arguments.add(parseExpression(Precedence.NONE));
                }
            } while (match(TokenType.COMMA));
        }
        return arguments;
    }
    private Expression parseSqlExpression(Token templateToken, Optional<TypeNode> resultType) {
        String content = templateToken.lexeme().substring(1, templateToken.lexeme().length() - 1);
        StringBuilder queryBuilder = new StringBuilder();
        List<Expression> interpolations = new ArrayList<>();
        
        int i = 0;
        int len = content.length();
        while (i < len) {
            char c = content.charAt(i);
            if (c == '{') {
                int start = i + 1;
                int depth = 1;
                int end = -1;
                for (int j = start; j < len; j++) {
                    char nextC = content.charAt(j);
                    if (nextC == '{') depth++;
                    else if (nextC == '}') depth--;
                    if (depth == 0) {
                        end = j;
                        break;
                    }
                }
                if (end == -1) {
                    throw error(templateToken, "Unterminated expression interpolation inside sql template string.");
                }
                String exprStr = content.substring(start, end);
                Expression parsedExpr = parseInlineExpression(exprStr, templateToken);
                interpolations.add(parsedExpr);
                queryBuilder.append("?");
                i = end + 1;
            } else {
                queryBuilder.append(c);
                i++;
            }
        }
        
        return new SqlExpr(queryBuilder.toString(), interpolations, resultType, templateToken);
    }

        private Expression parseJsonExpression(Token templateToken, Optional<TypeNode> resultType) {
        if (resultType.isEmpty()) {
            throw error(templateToken, "JSON literals require a target Record type, e.g., json<User>\"\"...\"\"\"");
        }

        String content = templateToken.lexeme().substring(1, templateToken.lexeme().length() - 1);
        if (templateToken.lexeme().startsWith("\"\"\"")) {
            content = templateToken.lexeme().substring(3, templateToken.lexeme().length() - 3);
        }

        StringBuilder queryBuilder = new StringBuilder();
        List<Expression> interpolations = new ArrayList<>();

        int i = 0;
        int len = content.length();
        while (i < len) {
            char c = content.charAt(i);
            if (c == '$' && i + 1 < len && content.charAt(i + 1) == '{') {
                int start = i + 2;
                int depth = 1;
                int end = -1;
                for (int j = start; j < len; j++) {
                    char nextC = content.charAt(j);
                    if (nextC == '{') depth++;
                    else if (nextC == '}') depth--;
                    if (depth == 0) {
                        end = j;
                        break;
                    }
                }
                if (end == -1) {
                    throw error(templateToken, "Unterminated expression interpolation inside json template string.");
                }
                String exprStr = content.substring(start, end);
                Expression parsedExpr = parseInlineExpression(exprStr, templateToken);
                interpolations.add(parsedExpr);
                queryBuilder.append("?");
                i = end + 1;
            } else {
                queryBuilder.append(c);
                i++;
            }
        }

        return new JsonExpr(queryBuilder.toString(), interpolations, resultType.get(), templateToken);
    }

    private Expression parseInlineExpression(String exprStr, Token templateToken) {
        accent.source.SourceFile file = new accent.source.SourceFile(java.nio.file.Path.of("sql-inline-expression"), exprStr);
        accent.lexer.AccentLexer lexer = new accent.lexer.AccentLexer(file);
        accent.lexer.LexResult lexResult = lexer.lex();
        if (!lexResult.diagnostics().isEmpty()) {
            throw error(templateToken, "Invalid expression syntax inside sql template string: " + lexResult.diagnostics().get(0).message());
        }
        
        AccentParser parser = new AccentParser(file, lexResult.tokens());
        Expression expr = parser.parseExpression(Precedence.NONE);
        if (!parser.diagnostics().isEmpty()) {
            throw error(templateToken, "Failed to parse expression inside sql template string: " + parser.diagnostics().get(0).message());
        }
        return expr;
    }

    private Expression parseInterpolatedStringExpression(Token startToken) {
        List<InterpolatedStringPart> parts = new ArrayList<>();
        while (!check(TokenType.INTERPOLATED_STRING_END) && !isAtEnd()) {
            if (match(TokenType.INTERPOLATED_STRING_TEXT)) {
                Token textToken = previous();
                parts.add(new InterpolatedTextPart(unescapeString(textToken.lexeme())));
            } else if (match(TokenType.INTERPOLATION_START)) {
                if (check(TokenType.INTERPOLATION_END)) {
                    error(peek(), "Interpolation expression cannot be empty.");
                    advance(); // consume '}'
                    parts.add(new InterpolatedTextPart(""));
                } else {
                    Expression expr = parseExpression(Precedence.NONE);
                    consume(TokenType.INTERPOLATION_END, "Expected '}' at the end of interpolation expression.");
                    parts.add(new InterpolatedExpressionPart(expr));
                }
            } else {
                Token invalid = advance();
                error(invalid, "Unexpected token in interpolated string: " + invalid.lexeme());
            }
        }
        consume(TokenType.INTERPOLATED_STRING_END, "Unterminated interpolated string.");
        return new InterpolatedStringExpression(parts, startToken);
    }

    private static String unescapeString(String value) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 'f' -> sb.append('\f');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> {
                        sb.append(c);
                        sb.append(next);
                    }
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
