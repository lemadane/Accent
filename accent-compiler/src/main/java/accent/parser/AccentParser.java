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
            while (!isAtEnd()) {
                try {
                    declarations.add(parseTypeDeclaration());
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
        if (check(type)) return advance();
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
                case PUBLIC:
                case PROTECTED:
                case PRIVATE:
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
        
        while (match(TokenType.PUBLIC, TokenType.PROTECTED, TokenType.PRIVATE, TokenType.STATIC, TokenType.FINAL)) {
            modifiers.add(previous().lexeme());
        }

        if (match(TokenType.CLASS)) {
            return parseClassDecl(modifiers);
        } else if (match(TokenType.INTERFACE)) {
            return parseInterfaceDecl(modifiers);
        } else if (match(TokenType.RECORD)) {
            return parseRecordDecl(modifiers);
        } else if (match(TokenType.ENUM)) {
            return parseEnumDecl(modifiers);
        } else if (match(TokenType.EXTENSION)) {
            return parseExtensionDecl();
        }

        throw error(peek(), "Expected class, interface, record, enum, or extension declaration.");
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

    private ClassDecl parseClassDecl(List<String> modifiers) {
        String name = consume(TokenType.IDENTIFIER, "Expected class name.").lexeme();
        List<TypeParameter> typeParams = parseTypeParameters();

        Optional<TypeNode> superclass = Optional.empty();
        if (match(TokenType.ELSE)) { // Wait, Java uses extends, let's allow identifier 'extends' or key token
            // Wait, does the lexer treat 'extends' as keyword? Let's check TokenType.
            // In TokenType.java, there is no EXTENDS keyword!
            // This means 'extends' is lexed as IDENTIFIER!
            // Let's check if we match IDENTIFIER "extends"
        }
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

        return new ClassDecl(modifiers, name, typeParams, superclass, interfaces, members);
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

        consume(TokenType.LEFT_BRACE, "Expected '{' before record body.");
        List<Member> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            members.add(parseMember(name));
        }
        consume(TokenType.RIGHT_BRACE, "Expected '}' after record body.");

        return new RecordDecl(modifiers, name, typeParams, components, interfaces, members);
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
        while (match(TokenType.PUBLIC, TokenType.PROTECTED, TokenType.PRIVATE, TokenType.STATIC, TokenType.FINAL)) {
            modifiers.add(previous().lexeme());
        }

        List<TypeParameter> typeParams = parseTypeParameters();

        // Check if it's a constructor: name matches enclosingClassName, followed by '('
        if (check(TokenType.IDENTIFIER) && peek().lexeme().equals(enclosingClassName) && lookAhead(1).type() == TokenType.LEFT_PAREN) {
            String name = advance().lexeme();
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
        if (!isGenerator && check(TokenType.IDENTIFIER) && (lookAhead(1).type() == TokenType.ASSIGN || lookAhead(1).type() == TokenType.SEMICOLON)) {
            boolean isFinal = modifiers.contains("final");
            type = new BaseTypeNode(isFinal ? "final" : "var", List.of(), false);
            name = advance().lexeme();
        } else {
            type = parseType();
            name = consume(TokenType.IDENTIFIER, "Expected member name.").lexeme();
        }

        if (match(TokenType.LEFT_PAREN)) {
            // Method
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
            if (isGenerator) {
                throw error(peek(), "'generator' keyword is only valid on method declarations.");
            }
            Optional<Expression> initializer = Optional.empty();
            if (match(TokenType.ASSIGN)) {
                initializer = Optional.of(parseExpression(Precedence.NONE));
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

    private Parameter parseParameter() {
        TypeNode type;
        if (match(TokenType.VAR)) {
            type = new BaseTypeNode("var", List.of(), false);
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
        Optional<Expression> defaultValue = Optional.empty();
        if (match(TokenType.ASSIGN)) {
            defaultValue = Optional.of(parseExpression(Precedence.NONE));
        }
        return new Parameter(type, name, isVarargs, defaultValue);
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
            String name = previous().lexeme();
            while (match(TokenType.DOT)) {
                name += "." + consume(TokenType.IDENTIFIER, "Expected identifier after '.'.") .lexeme();
            }
            List<TypeNode> typeArgs = new ArrayList<>();
            if (match(TokenType.LESS)) {
                do {
                    typeArgs.add(parseType());
                } while (match(TokenType.COMMA));
                consume(TokenType.GREATER, "Expected '>' after type arguments.");
            }
            boolean isNonNull = match(TokenType.BANG);
            type = new BaseTypeNode(name, typeArgs, isNonNull);
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
        while (match(TokenType.LEFT_BRACKET)) {
            consume(TokenType.RIGHT_BRACKET, "Expected ']' for array type.");
            boolean isNonNull = match(TokenType.BANG);
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

        Statement thenBranch = parseStatement();
        Optional<Statement> elseBranch = Optional.empty();
        if (match(TokenType.ELSE)) {
            elseBranch = Optional.of(parseStatement());
        }

        return new IfStmt(condition, thenBranch, elseBranch);
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
                elseBranch = Optional.of(parseStatement());
            }
            return new ForEachStmt(param, iterable, body, elseBranch);
        } else {
            Optional<Statement> init = Optional.empty();
            if (!match(TokenType.SEMICOLON)) {
                if (isLocalVarDeclaration()) {
                    init = Optional.of(parseLocalVarDeclaration());
                } else {
                    Expression initializer = parseExpression(Precedence.NONE);
                    consume(TokenType.SEMICOLON, "Expected ';' after loop initializer.");
                    init = Optional.of(new ExprStmt(initializer));
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
                elseBranch = Optional.of(parseStatement());
            }
            return new ForStmt(init, condition, update, body, elseBranch);
        }
    }

    private Statement parseWhileStatement() {
        consume(TokenType.LEFT_PAREN, "Expected '(' after 'while'.");
        Expression condition = parseExpression(Precedence.NONE);
        consume(TokenType.RIGHT_PAREN, "Expected ')' after 'while' condition.");
        Statement body = parseStatement();
        return new WhileStmt(condition, body);
    }

    private Statement parseDoWhileStatement() {
        Statement body = parseStatement();
        consume(TokenType.WHILE, "Expected 'while' after do body.");
        consume(TokenType.LEFT_PAREN, "Expected '(' after 'while'.");
        Expression condition = parseExpression(Precedence.NONE);
        consume(TokenType.RIGHT_PAREN, "Expected ')' after condition.");
        consume(TokenType.SEMICOLON, "Expected ';' after do-while loop.");
        return new DoWhileStmt(body, condition);
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
            } else if (check(TokenType.IDENTIFIER) && lookAhead(1).type() == TokenType.ASSIGN) {
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
        Optional<Expression> initializer = Optional.empty();
        if (match(TokenType.ASSIGN)) {
            initializer = Optional.of(parseExpression(Precedence.NONE));
        }

        // In enhanced for context we don't end with Semicolon, but we handle it elsewhere.
        // If we are in a normal statement context, we consume the semicolon.
        if (check(TokenType.SEMICOLON)) {
            consume(TokenType.SEMICOLON, "Expected ';' after variable declaration.");
        }

        return new LocalVarDeclStmt(isFinal, isInferred, type, name, initializer);
    }

    // --- Expression Parsing (Pratt Parser) ---

    private enum Precedence {
        NONE,
        ASSIGNMENT,       // = += -=
        TERNARY,          // ? :
        LAMBDA,           // ->
        NULL_COALESCING,  // ??
        OR,               // ||  or  nor
        XOR_XNOR,         // xor xnor
        AND,              // &&  and  nand
        EQUALITY,         // == !=
        COMPARISON,       // < > <= >= instanceof
        TERM,             // + -
        FACTOR,           // * / %
        UNARY,            // ! not - ++ --
        CALL,             // . ?. ( [
        PRIMARY
    }

    private Precedence getPrecedence(TokenType type) {
        return switch (type) {
            case ASSIGN -> Precedence.ASSIGNMENT;
            case QUESTION -> Precedence.TERNARY;
            case ARROW -> Precedence.LAMBDA;
            case NULL_COALESCING -> Precedence.NULL_COALESCING;
            case OR_OR, OR, NOR -> Precedence.OR;
            case XOR, XNOR -> Precedence.XOR_XNOR;
            case AND_AND, AND, NAND -> Precedence.AND;
            case EQUAL_EQUAL, BANG_EQUAL -> Precedence.EQUALITY;
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> Precedence.COMPARISON;
            case PLUS, MINUS -> Precedence.TERM;
            case STAR, SLASH, PERCENT -> Precedence.FACTOR;
            case DOT, OPTIONAL_CHAIN, LEFT_PAREN, LEFT_BRACKET, PLUS_PLUS, MINUS_MINUS -> Precedence.CALL;
            default -> Precedence.NONE;
        };
    }

    private Expression parseExpression(Precedence precedence) {
        Token token = advance();
        Expression left = parsePrefix(token);

        while (precedence.ordinal() < getPrecedence(peek().type()).ordinal()) {
            Token op = advance();
            left = parseInfix(left, op);
        }

        return left;
    }

    private Expression parsePrefix(Token token) {
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
                // Could be start of lambda: x -> x
                if (check(TokenType.ARROW)) {
                    advance(); // consume ->
                    Node body = check(TokenType.LEFT_BRACE) ? parseStatement() : parseExpression(Precedence.NONE);
                    yield new LambdaExpr(List.of(new Parameter(new BaseTypeNode("Object", List.of(), false), token.lexeme(), false, Optional.empty())), body, token);
                }
                yield new IdentifierExpr(token.lexeme(), token);
            }
            case THIS -> new ThisExpr(token);
            case SUPER -> new SuperExpr(token);
            case LEFT_PAREN -> {
                // Could be a lambda parameter list: (String! s, int i) -> body
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
                            parameters.add(parseParameter());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after lambda parameters.");
                    consume(TokenType.ARROW, "Expected '->' for lambda.");
                    Node body;
                    if (check(TokenType.LEFT_BRACE)) {
                        body = parseStatement();
                    } else {
                        body = parseExpression(Precedence.NONE);
                    }
                    yield new LambdaExpr(parameters, body, token);
                } else {
                    Expression expr = parseExpression(Precedence.NONE);
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after parenthesized expression.");
                    yield expr;
                }
            }
            case BANG, MINUS, PLUS -> {
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
                Expression right = parseExpression(Precedence.UNARY);
                yield new AsyncExpr(right, token);
            }
            case AWAIT -> {
                Expression right = parseExpression(Precedence.UNARY);
                yield new AwaitExpr(right, token);
            }
            case NEW -> {
                TypeNode type = parseType();
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
            
            if (check(TokenType.LEFT_BRACE)) {
                advance(); // {
                
                if (check(TokenType.IF)) {
                    Token ifToken = advance();
                    Expression condition = parseExpression(Precedence.NONE);
                    consume(TokenType.LEFT_BRACE, "Expected '{' for 'if' block.");
                    List<MarkupNode> trueBranch = parseMarkupChildrenInBlock();
                    List<MarkupNode> falseBranch = List.of();
                    if (match(TokenType.ELSE)) {
                        if (check(TokenType.IF)) {
                            MarkupNode nestedIf = parseMarkupNodeInsideIfChain();
                            falseBranch = List.of(nestedIf);
                        } else {
                            consume(TokenType.LEFT_BRACE, "Expected '{' for 'else' block.");
                            falseBranch = parseMarkupChildrenInBlock();
                        }
                    }
                    consume(TokenType.RIGHT_BRACE, "Expected '}' to close expression block.");
                    children.add(new IfMarkupNode(condition, trueBranch, falseBranch, ifToken));
                } else if (check(TokenType.FOR)) {
                    Token forToken = advance();
                    boolean hasParen = match(TokenType.LEFT_PAREN);
                    Parameter loopVar = parseParameter();
                    if (!match(TokenType.COLON) && !check(TokenType.IDENTIFIER) && !peek().lexeme().equals("in")) {
                        throw error(peek(), "Expected ':' or 'in' in for loop.");
                    }
                    if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("in")) {
                        advance();
                    }
                    Expression iterable = parseExpression(Precedence.NONE);
                    if (hasParen) {
                        consume(TokenType.RIGHT_PAREN, "Expected ')' after for loop expression.");
                    }
                    consume(TokenType.LEFT_BRACE, "Expected '{' for loop block.");
                    List<MarkupNode> body = parseMarkupChildrenInBlock();
                    consume(TokenType.RIGHT_BRACE, "Expected '}' to close expression block.");
                    List<MarkupNode> elseBranch = List.of();
                    if (match(TokenType.ELSE)) {
                        consume(TokenType.LEFT_BRACE, "Expected '{' for 'else' block.");
                        elseBranch = parseMarkupChildrenInBlock();
                        consume(TokenType.RIGHT_BRACE, "Expected '}' to close expression block.");
                    }
                    children.add(new ForMarkupNode(loopVar, iterable, body, elseBranch, forToken));
                } else {
                    Expression expr = parseExpression(Precedence.NONE);
                    consume(TokenType.RIGHT_BRACE, "Expected '}' after expression.");
                    children.add(new ExpressionNode(expr));
                }
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
            if (check(TokenType.LEFT_BRACE)) {
                advance(); // {
                if (check(TokenType.IF)) {
                    Token ifToken = advance();
                    Expression condition = parseExpression(Precedence.NONE);
                    consume(TokenType.LEFT_BRACE, "Expected '{' for 'if' block.");
                    List<MarkupNode> trueBranch = parseMarkupChildrenInBlock();
                    List<MarkupNode> falseBranch = List.of();
                    if (match(TokenType.ELSE)) {
                        if (check(TokenType.IF)) {
                            MarkupNode nestedIf = parseMarkupNodeInsideIfChain();
                            falseBranch = List.of(nestedIf);
                        } else {
                            consume(TokenType.LEFT_BRACE, "Expected '{' for 'else' block.");
                            falseBranch = parseMarkupChildrenInBlock();
                        }
                    }
                    consume(TokenType.RIGHT_BRACE, "Expected '}' to close expression block.");
                    children.add(new IfMarkupNode(condition, trueBranch, falseBranch, ifToken));
                } else if (check(TokenType.FOR)) {
                    Token forToken = advance();
                    boolean hasParen = match(TokenType.LEFT_PAREN);
                    Parameter loopVar = parseParameter();
                    if (!match(TokenType.COLON) && !check(TokenType.IDENTIFIER) && !peek().lexeme().equals("in")) {
                        throw error(peek(), "Expected ':' or 'in' in for loop.");
                    }
                    if (check(TokenType.IDENTIFIER) && peek().lexeme().equals("in")) {
                        advance();
                    }
                    Expression iterable = parseExpression(Precedence.NONE);
                    if (hasParen) {
                        consume(TokenType.RIGHT_PAREN, "Expected ')' after for loop expression.");
                    }
                    consume(TokenType.LEFT_BRACE, "Expected '{' loop block.");
                    List<MarkupNode> body = parseMarkupChildrenInBlock();
                    consume(TokenType.RIGHT_BRACE, "Expected '}' to close expression block.");
                    List<MarkupNode> elseBranch = List.of();
                    if (match(TokenType.ELSE)) {
                        consume(TokenType.LEFT_BRACE, "Expected '{' for 'else' block.");
                        elseBranch = parseMarkupChildrenInBlock();
                        consume(TokenType.RIGHT_BRACE, "Expected '}' to close expression block.");
                    }
                    children.add(new ForMarkupNode(loopVar, iterable, body, elseBranch, forToken));
                } else {
                    Expression expr = parseExpression(Precedence.NONE);
                    consume(TokenType.RIGHT_BRACE, "Expected '}' after expression.");
                    children.add(new ExpressionNode(expr));
                }
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

    private MarkupNode parseMarkupNodeInsideIfChain() {
        Token ifToken = consume(TokenType.IF, "Expected 'if' in else if.");
        Expression condition = parseExpression(Precedence.NONE);
        consume(TokenType.LEFT_BRACE, "Expected '{' for 'if' block.");
        List<MarkupNode> trueBranch = parseMarkupChildrenInBlock();
        List<MarkupNode> falseBranch = List.of();
        if (match(TokenType.ELSE)) {
            if (check(TokenType.IF)) {
                MarkupNode nestedIf = parseMarkupNodeInsideIfChain();
                falseBranch = List.of(nestedIf);
            } else {
                consume(TokenType.LEFT_BRACE, "Expected '{' for 'else' block.");
                falseBranch = parseMarkupChildrenInBlock();
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

    private Expression parseInfix(Expression left, Token op) {
        Precedence precedence = getPrecedence(op.type());
        return switch (op.type()) {
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
                if (check(TokenType.CLASS)) {
                    memberName = advance().lexeme();
                } else {
                    memberName = consume(TokenType.IDENTIFIER, "Expected member name after '" + op.lexeme() + "'.").lexeme();
                }

                if (match(TokenType.LEFT_PAREN)) {
                    List<Expression> arguments = parseCallArguments();
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after method arguments.");
                    yield new MethodCallExpr(left, memberName, typeArgs, arguments, isOptional, op);
                } else {
                    yield new MemberAccessExpr(left, memberName, isOptional, op);
                }
            }
            case LEFT_PAREN -> {
                // Call on a method, e.g. identifier(args). Only valid if left is IdentifierExpr.
                if (left instanceof IdentifierExpr id) {
                    List<Expression> arguments = parseCallArguments();
                    consume(TokenType.RIGHT_PAREN, "Expected ')' after method arguments.");
                    yield new MethodCallExpr(null, id.name(), List.of(), arguments, false, op);
                }
                throw error(op, "Function pointers or calling non-method expressions is not supported.");
            }
            case LEFT_BRACKET -> {
                Expression index = parseExpression(Precedence.NONE);
                consume(TokenType.RIGHT_BRACKET, "Expected ']' after array index.");
                yield new ArrayAccessExpr(left, index, op);
            }
            case QUESTION -> {
                Expression thenBranch = parseExpression(Precedence.NONE);
                consume(TokenType.COLON, "Expected ':' after ternary then-branch expression.");
                Expression elseBranch = parseExpression(Precedence.TERNARY);
                yield new TernaryExpr(left, thenBranch, elseBranch, op);
            }
            case ASSIGN -> {
                // Right-associative
                Expression right = parseExpression(Precedence.values()[precedence.ordinal() - 1]);
                yield new BinaryExpr(left, op, right);
            }
            // Textual binary boolean operators — lower to equivalent BinaryExpr
            case AND -> {
                // 'and' -> same as '&&'  (short-circuit AND)
                Expression right = parseExpression(Precedence.AND);
                yield new BinaryExpr(left, op, right);
            }
            case OR -> {
                // 'or' -> same as '||'  (short-circuit OR)
                Expression right = parseExpression(Precedence.OR);
                yield new BinaryExpr(left, op, right);
            }
            case NAND -> {
                // 'nand' -> short-circuit: !(left && right)
                Expression right = parseExpression(Precedence.AND);
                yield new BinaryExpr(left, op, right);
            }
            case NOR -> {
                // 'nor' -> short-circuit: !(left || right)
                Expression right = parseExpression(Precedence.OR);
                yield new BinaryExpr(left, op, right);
            }
            case XOR -> {
                // 'xor' -> both operands evaluated: left != right
                Expression right = parseExpression(Precedence.XOR_XNOR);
                yield new BinaryExpr(left, op, right);
            }
            case XNOR -> {
                // 'xnor' -> both operands evaluated: left == right
                Expression right = parseExpression(Precedence.XOR_XNOR);
                yield new BinaryExpr(left, op, right);
            }
            default -> {
                Expression right = parseExpression(precedence);
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
                    String name = advance().lexeme();
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
