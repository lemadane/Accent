package io.lemadane.jatot.tools.server;

import io.lemadane.jatot.ast.Ast.CompilationUnit;
import io.lemadane.jatot.semantic.SemanticAnalyzer;
import io.lemadane.jatot.tools.core.JotatLanguageService;
import io.lemadane.jatot.tools.core.JotatLanguageService.ProjectContext;
import io.lemadane.jatot.tools.core.JotatLanguageService.CompilationResult;
import io.lemadane.jatot.tools.core.CompletionProvider;
import io.lemadane.jatot.tools.core.HoverProvider;
import io.lemadane.jatot.tools.core.DefinitionProvider;
import io.lemadane.jatot.tools.core.DefinitionProvider.DefinitionLocation;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class JatotTextDocumentService implements TextDocumentService {
    private final JatotLanguageServer server;
    private final ProjectContext context;
    private LanguageClient client;

    public JatotTextDocumentService(JatotLanguageServer server) {
        this.server = server;
        this.context = new ProjectContext(new ArrayList<>());
    }

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    private Path getPathFromUri(String uriStr) {
        try {
            return Paths.get(new URI(uriStr));
        } catch (Exception e) {
            return Paths.get(uriStr);
        }
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        Path path = getPathFromUri(params.getTextDocument().getUri());
        context.putFile(path, params.getTextDocument().getText());
        triggerValidation(path, params.getTextDocument().getUri());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        Path path = getPathFromUri(params.getTextDocument().getUri());
        // For full sync, we just take the first content change text
        if (!params.getContentChanges().isEmpty()) {
            String newText = params.getContentChanges().get(0).getText();
            context.putFile(path, newText);
            triggerValidation(path, params.getTextDocument().getUri());
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        Path path = getPathFromUri(params.getTextDocument().getUri());
        context.removeFile(path);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // No-op or double-validate
    }

    private void triggerValidation(Path path, String uri) {
        CompletableFuture.runAsync(() -> {
            try {
                CompilationResult result = context.analyze(path, true);
                List<Diagnostic> lspDiagnostics = new ArrayList<>();
                for (io.lemadane.jatot.diagnostic.Diagnostic d : result.diagnostics()) {
                    DiagnosticSeverity severity = DiagnosticSeverity.Error;
                    if (d.severity() == io.lemadane.jatot.diagnostic.DiagnosticSeverity.WARNING) {
                        severity = DiagnosticSeverity.Warning;
                    } else if (d.severity() == io.lemadane.jatot.diagnostic.DiagnosticSeverity.INFO) {
                        severity = DiagnosticSeverity.Information;
                    }

                    // LSP Position is 0-indexed for line and character
                    Position start = new Position(d.line() - 1, d.column() - 1);
                    Position end = new Position(d.line() - 1, d.column()); // Simple end position
                    Range range = new Range(start, end);

                    Diagnostic lspDiag = new Diagnostic(range, d.message(), severity, "jatot", d.code());
                    lspDiagnostics.add(lspDiag);
                }
                if (client != null) {
                    client.publishDiagnostics(new PublishDiagnosticsParams(uri, lspDiagnostics));
                }
            } catch (Exception e) {
                System.err.println("Validation failed for " + path + ": " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = getPathFromUri(params.getTextDocument().getUri());
            CompilationUnit unit = context.getUnit(path);
            SemanticAnalyzer analyzer = context.getAnalyzer(path);
            if (unit == null) {
                return Either.forLeft(Collections.emptyList());
            }

            // LSP position is 0-indexed; convert to 1-indexed for the core engine if needed,
            // but our completion provider expects 1-indexed line and 1-indexed column.
            int line = params.getPosition().getLine() + 1;
            int col = params.getPosition().getCharacter() + 1;

            List<CompletionProvider.CompletionItem> coreItems = CompletionProvider.getCompletions(unit, analyzer, line, col);
            List<CompletionItem> lspItems = new ArrayList<>();
            for (CompletionProvider.CompletionItem ci : coreItems) {
                CompletionItem lspItem = new CompletionItem(ci.label());
                lspItem.setDetail(ci.detail());
                switch (ci.kind()) {
                    case "Keyword":
                        lspItem.setKind(CompletionItemKind.Keyword);
                        break;
                    case "Method":
                        lspItem.setKind(CompletionItemKind.Method);
                        break;
                    case "Field":
                        lspItem.setKind(CompletionItemKind.Field);
                        break;
                    case "Class":
                        lspItem.setKind(CompletionItemKind.Class);
                        break;
                    default:
                        lspItem.setKind(CompletionItemKind.Variable);
                        break;
                }
                lspItems.add(lspItem);
            }
            return Either.forLeft(lspItems);
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = getPathFromUri(params.getTextDocument().getUri());
            CompilationUnit unit = context.getUnit(path);
            SemanticAnalyzer analyzer = context.getAnalyzer(path);
            if (unit == null || analyzer == null) return null;

            int line = params.getPosition().getLine() + 1;
            int col = params.getPosition().getCharacter() + 1;

            String hoverMarkdown = HoverProvider.getHover(unit, analyzer, line, col);
            if (hoverMarkdown == null) return null;

            MarkupContent content = new MarkupContent(MarkupKind.MARKDOWN, hoverMarkdown);
            return new Hover(content);
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = getPathFromUri(params.getTextDocument().getUri());
            CompilationUnit unit = context.getUnit(path);
            SemanticAnalyzer analyzer = context.getAnalyzer(path);
            if (unit == null || analyzer == null) return null;

            int line = params.getPosition().getLine() + 1;
            int col = params.getPosition().getCharacter() + 1;

            DefinitionLocation def = DefinitionProvider.getDefinition(unit, analyzer, line, col);
            if (def == null) return null;

            Position start = new Position(def.line() - 1, def.column() - 1);
            Position end = new Position(def.line() - 1, def.column());
            Location loc = new Location(def.uri(), new Range(start, end));
            return Either.forLeft(Collections.singletonList(loc));
        });
    }

    public ProjectContext getContext() {
        return context;
    }
}
