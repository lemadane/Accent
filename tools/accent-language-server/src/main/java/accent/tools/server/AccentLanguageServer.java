package accent.tools.server;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.util.List;

import java.util.concurrent.CompletableFuture;

public final class AccentLanguageServer implements LanguageServer, LanguageClientAware {
    private final AccentTextDocumentService textDocumentService;
    private final AccentWorkspaceService workspaceService;
    private LanguageClient client;

    public AccentLanguageServer() {
        this.textDocumentService = new AccentTextDocumentService(this);
        this.workspaceService = new AccentWorkspaceService(this);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        InitializeResult result = new InitializeResult(new ServerCapabilities());
        
        // Register supported capabilities
        result.getCapabilities().setTextDocumentSync(TextDocumentSyncKind.Full);
        result.getCapabilities().setHoverProvider(true);
        result.getCapabilities().setDefinitionProvider(true);
        result.getCapabilities().setCompletionProvider(new CompletionOptions(true, List.of(".")));
        result.getCapabilities().setExecuteCommandProvider(new ExecuteCommandOptions(List.of("accent.showGeneratedJava")));
        
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        this.textDocumentService.setClient(client);
    }

    public LanguageClient getClient() {
        return client;
    }
}
