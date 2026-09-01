package io.lemadane.accent.ast;

import io.lemadane.accent.source.SourceFile;
import io.lemadane.accent.lexer.Token;
import java.util.List;
import java.util.Optional;

public interface Ast {

    sealed interface Node permits TypeNode, TypeDeclaration, Member, Statement, Expression, TypeParameter, EnumConstant, Parameter, CatchClause, CompilationUnit, MarkupNode, AttributeNode, ComponentArgumentNode {}

    // Compilation Unit
    record CompilationUnit(
            SourceFile sourceFile,
            Optional<String> packageName,
            List<String> imports,
            List<TypeDeclaration> declarations,
            List<Token> tokens
    ) implements Node {}

    // Types in code
    sealed interface TypeNode extends Node permits BaseTypeNode, ArrayTypeNode, PrimitiveTypeNode {
        boolean isNonNull();
        TypeNode withNonNull(boolean nonNull);
    }

    record BaseTypeNode(String name, List<TypeNode> typeArguments, boolean isNonNull) implements TypeNode {
        @Override public boolean isNonNull() { return isNonNull; }
        @Override public TypeNode withNonNull(boolean nonNull) { return new BaseTypeNode(name, typeArguments, nonNull); }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder(name);
            if (!typeArguments.isEmpty()) {
                sb.append("<");
                for (int i = 0; i < typeArguments.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeArguments.get(i).toString());
                }
                sb.append(">");
            }
            if (isNonNull) sb.append("!");
            return sb.toString();
        }
    }

    record ArrayTypeNode(TypeNode elementType, boolean isNonNull) implements TypeNode {
        @Override public boolean isNonNull() { return isNonNull; }
        @Override public TypeNode withNonNull(boolean nonNull) { return new ArrayTypeNode(elementType, nonNull); }
        @Override public String toString() {
            return elementType.toString() + "[]" + (isNonNull ? "!" : "");
        }
    }

    record PrimitiveTypeNode(String name) implements TypeNode {
        @Override public boolean isNonNull() { return true; } // Primitive types are implicitly non-null
        @Override public TypeNode withNonNull(boolean nonNull) { return this; }
        @Override public String toString() { return name; }
    }

    // Type Declarations
    sealed interface TypeDeclaration extends Node permits ClassDecl, InterfaceDecl, RecordDecl, EnumDecl, ExtensionDecl {
        List<String> modifiers();
        String name();
        List<Member> members();
    }

    record ClassDecl(
            List<String> modifiers,
            String name,
            List<TypeParameter> typeParameters,
            Optional<TypeNode> superclass,
            List<TypeNode> interfaces,
            List<Member> members
    ) implements TypeDeclaration {}

    record InterfaceDecl(
            List<String> modifiers,
            String name,
            List<TypeParameter> typeParameters,
            List<TypeNode> interfaces,
            List<Member> members
    ) implements TypeDeclaration {}

    record RecordDecl(
            List<String> modifiers,
            String name,
            List<TypeParameter> typeParameters,
            List<Parameter> components,
            List<TypeNode> interfaces,
            List<Member> members
    ) implements TypeDeclaration {}

    record EnumDecl(
            List<String> modifiers,
            String name,
            List<TypeNode> interfaces,
            List<EnumConstant> constants,
            List<Member> members
    ) implements TypeDeclaration {}

    record ExtensionDecl(
            TypeNode targetType,
            List<Member> members
    ) implements TypeDeclaration {
        @Override public List<String> modifiers() { return List.of("public"); }
        @Override public String name() { 
            // Unique extension class name based on target type
            return "Extension_" + targetType.toString().replaceAll("[^a-zA-Z0-9_]", "_");
        }
    }

    record TypeParameter(String name, Optional<TypeNode> bound) implements Node {}

    record EnumConstant(String name, List<Expression> arguments) implements Node {}

    record Parameter(TypeNode type, String name, boolean isVarargs, Optional<Expression> defaultValue) implements Node {}

    // Members
    sealed interface Member extends Node permits FieldDecl, ConstructorDecl, MethodDecl {
        List<String> modifiers();
    }

    record FieldDecl(
            List<String> modifiers,
            TypeNode type,
            String name,
            Optional<Expression> initializer
    ) implements Member {}

    record ConstructorDecl(
            List<String> modifiers,
            List<TypeParameter> typeParameters,
            List<Parameter> parameters,
            List<TypeNode> throwsList,
            BlockStmt body
    ) implements Member {}

    record MethodDecl(
            List<String> modifiers,
            List<TypeParameter> typeParameters,
            boolean isGenerator,
            TypeNode returnType,
            String name,
            List<Parameter> parameters,
            List<TypeNode> throwsList,
            Optional<BlockStmt> body // Empty if abstract/interface method
    ) implements Member {}

    // Statements
    sealed interface Statement extends Node permits BlockStmt, LocalVarDeclStmt, IfStmt, ForStmt, ForEachStmt, WhileStmt, DoWhileStmt, TryStmt, ReturnStmt, YieldStmt, BreakStmt, ContinueStmt, ExprStmt, ThrowStmt, EmitStmt {}

    record BlockStmt(List<Statement> statements) implements Statement {}

    record LocalVarDeclStmt(
            boolean isFinal,
            boolean isInferred,
            Optional<TypeNode> type,
            String name,
            Optional<Expression> initializer
    ) implements Statement {}

    record IfStmt(Expression condition, Statement thenBranch, Optional<Statement> elseBranch) implements Statement {}

    record ForStmt(
            Optional<Statement> init,
            Optional<Expression> condition,
            Optional<Expression> update,
            Statement body
    ) implements Statement {}

    record ForEachStmt(Parameter parameter, Expression iterable, Statement body) implements Statement {}

    record WhileStmt(Expression condition, Statement body) implements Statement {}

    record DoWhileStmt(Statement body, Expression condition) implements Statement {}

    record TryStmt(
            BlockStmt body,
            List<CatchClause> catchClauses,
            Optional<BlockStmt> finallyBlock
    ) implements Statement {}

    record ReturnStmt(Optional<Expression> expression) implements Statement {}

    record YieldStmt(Expression expression) implements Statement {}

    record BreakStmt() implements Statement {}

    record ContinueStmt() implements Statement {}

    record ExprStmt(Expression expression) implements Statement {}

    record ThrowStmt(Expression expression) implements Statement {}

    /** Emits a value from a generator function. */
    record EmitStmt(Expression expression) implements Statement {}

    record CatchClause(Parameter parameter, BlockStmt body) implements Node {}

    // Expressions
    sealed interface Expression extends Node permits LiteralExpr, IdentifierExpr, ThisExpr, SuperExpr, BinaryExpr, UnaryExpr, MemberAccessExpr, MethodCallExpr, NewObjectExpr, NewArrayExpr, ArrayInitializerExpr, ArrayAccessExpr, LambdaExpr, AsyncExpr, AwaitExpr, IfExpr, TryExpr, LoopExpr, NamedArgExpr, MarkupExpr, TernaryExpr, SqlExpr, JsonExpr, InterpolatedStringExpression {
        // Source location helper
        default Token token() { return null; }
    }

    record LiteralExpr(Object value, Token token) implements Expression {}

    record IdentifierExpr(String name, Token token) implements Expression {}

    record ThisExpr(Token token) implements Expression {}

    record SuperExpr(Token token) implements Expression {}

    record BinaryExpr(Expression left, Token operator, Expression right) implements Expression {}

    record UnaryExpr(Token operator, Expression expression, boolean isPostfix) implements Expression {}

    record MemberAccessExpr(Expression receiver, String memberName, boolean isOptional, Token token) implements Expression {}

    record MethodCallExpr(
            Expression receiver, // Can be null if implicit this
            String methodName,
            List<TypeNode> typeArguments,
            List<Expression> arguments,
            boolean isOptional,
            Token token
    ) implements Expression {}

    record NewObjectExpr(TypeNode type, List<Expression> arguments, Token token) implements Expression {}

    record NewArrayExpr(
            TypeNode type,
            List<Expression> dimensions,
            Optional<ArrayInitializerExpr> initializer,
            Token token
    ) implements Expression {}

    record ArrayInitializerExpr(List<Expression> expressions, Token token) implements Expression {}

    record ArrayAccessExpr(Expression receiver, Expression index, Token token) implements Expression {}

    record LambdaExpr(
            List<Parameter> parameters,
            Node body, // Can be Expression or BlockStmt
            Token token
    ) implements Expression {}

    record AsyncExpr(Expression expression, Token token) implements Expression {}

    record AwaitExpr(Expression expression, Token token) implements Expression {}

    record IfExpr(Expression condition, BlockStmt thenBranch, BlockStmt elseBranch, Token token) implements Expression {}

    record TryExpr(
            BlockStmt body,
            List<CatchClause> catchClauses,
            Optional<BlockStmt> finallyBlock,
            Token token
    ) implements Expression {}

    record LoopExpr(Statement loop, Token token) implements Expression {}

    record NamedArgExpr(String name, Expression expression) implements Expression {
        @Override public Token token() { return expression.token(); }
    }

    record TernaryExpr(
            Expression condition,
            Expression thenBranch,
            Expression elseBranch,
            Token token
    ) implements Expression {}

    record MarkupExpr(MarkupNode node, Token token) implements Expression {}

    record SqlExpr(
            String query,
            List<Expression> interpolations,
            Optional<TypeNode> resultType,
            Token token
    ) implements Expression {}

    record JsonExpr(
            String template,
            List<Expression> interpolations,
            TypeNode resultType,
            Token token
    ) implements Expression {}

    sealed interface InterpolatedStringPart permits InterpolatedTextPart, InterpolatedExpressionPart {}
    record InterpolatedTextPart(String text) implements InterpolatedStringPart {}
    record InterpolatedExpressionPart(Expression expression) implements InterpolatedStringPart {}

    record InterpolatedStringExpression(List<InterpolatedStringPart> parts, Token token) implements Expression {}

    record AttributeNode(String name, Expression value, Token token) implements Node {}

    record ComponentArgumentNode(String name, Expression value, Token token) implements Node {}

    sealed interface MarkupNode extends Node permits ElementNode, ComponentNode, TextNode, ExpressionNode, IfMarkupNode, ForMarkupNode, FragmentNode {
        Token token();
    }

    record ElementNode(String tagName, List<AttributeNode> attributes, List<MarkupNode> children, Token token) implements MarkupNode {}

    record ComponentNode(String typeName, List<ComponentArgumentNode> arguments, List<MarkupNode> children, Token token) implements MarkupNode {}

    record TextNode(String value, Token token) implements MarkupNode {}

    record ExpressionNode(Expression expression) implements MarkupNode {
        @Override public Token token() { return expression.token(); }
    }

    record IfMarkupNode(Expression condition, List<MarkupNode> trueBranch, List<MarkupNode> falseBranch, Token token) implements MarkupNode {}

    record ForMarkupNode(Parameter parameter, Expression iterable, List<MarkupNode> body, Token token) implements MarkupNode {}

    record FragmentNode(List<MarkupNode> children, Token token) implements MarkupNode {}
}
