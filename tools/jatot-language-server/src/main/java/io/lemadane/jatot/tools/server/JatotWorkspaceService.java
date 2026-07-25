package io.lemadane.jatot.tools.server;

import io.lemadane.jatot.ast.Ast.CompilationUnit;
import io.lemadane.jatot.tools.core.JotatLanguageService;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class JatotWorkspaceService implements WorkspaceService {
    private final JatotLanguageServer server;

    public JatotWorkspaceService(JatotLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if ("jatot.showGeneratedJava".equals(params.getCommand())) {
            List<Object> args = params.getArguments();
            if (args != null && !args.isEmpty()) {
                String uriStr = args.get(0).toString();
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Path path;
                        try {
                            path = Paths.get(new URI(uriStr));
                        } catch (Exception e) {
                            path = Paths.get(uriStr);
                        }
                        JatotTextDocumentService docService = (JatotTextDocumentService) server.getTextDocumentService();
                        CompilationUnit unit = docService.getContext().getUnit(path);
                        if (unit != null) {
                            return JotatLanguageService.getGeneratedJava(unit, docService.getContext().getClassLoader());
                        }
                        return "// Source file not parsed or empty AST.";
                    } catch (Exception e) {
                        return "// Error generating Java source: " + e.getMessage();
                    }
                });
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
