package accent.tools.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AccentWorkspaceService implements WorkspaceService {
    private final AccentLanguageServer server;

    public AccentWorkspaceService(AccentLanguageServer server) {
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
        if ("accent.showGeneratedJava".equals(params.getCommand())) {
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
                        AccentTextDocumentService docService = (AccentTextDocumentService) server.getTextDocumentService();
                        return docService.getContext().getGeneratedJava(path);
                    } catch (Exception e) {
                        return "// Error generating Java source: " + e.getMessage();
                    }
                });
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
