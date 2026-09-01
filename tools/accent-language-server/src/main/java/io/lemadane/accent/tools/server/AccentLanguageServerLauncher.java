package io.lemadane.accent.tools.server;

import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import java.io.InputStream;
import java.io.OutputStream;

public class AccentLanguageServerLauncher {
    public static void main(String[] args) {
        try {
            AccentLanguageServer server = new AccentLanguageServer();
            InputStream in = System.in;
            OutputStream out = System.out;
            
            // Redirect standard error for logging to avoid corrupting stdout LSP communication
            System.setErr(System.err); 
            
            var launcher = LSPLauncher.createServerLauncher(server, in, out);
            LanguageClient client = launcher.getRemoteProxy();
            server.connect(client);
            
            launcher.startListening().get();
        } catch (Exception e) {
            System.err.println("Fatal error starting Language Server: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}
