"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.deactivate = exports.activate = void 0;
const vscode = require("vscode");
const path = require("path");
const fs = require("fs");
const node_1 = require("vscode-languageclient/node");
let client;
function activate(context) {
    console.log('Activating Accent VS Code extension...');
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    let serverPath = '';
    if (workspaceRoot) {
        const scriptName = process.platform === 'win32' ? 'accent-language-server.bat' : 'accent-language-server';
        const candidate = path.join(workspaceRoot, 'tools', 'accent-language-server', 'build', 'install', 'accent-language-server', 'bin', scriptName);
        if (fs.existsSync(candidate)) {
            serverPath = candidate;
        }
    }
    const configPath = vscode.workspace.getConfiguration('accent').get('languageServer.path');
    if (configPath && fs.existsSync(configPath)) {
        serverPath = configPath;
    }
    if (!serverPath) {
        vscode.window.showWarningMessage('Accent Language Server not found. Please run "./gradlew :tools:accent-language-server:installDist" in your workspace root.');
        return;
    }
    const serverOptions = {
        command: serverPath,
        args: []
    };
    const clientOptions = {
        documentSelector: [{ scheme: 'file', language: 'accent' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.accent')
        }
    };
    client = new node_1.LanguageClient('accentLanguageServer', 'Accent Language Server', serverOptions, clientOptions);
    client.start();
    const disposable = vscode.commands.registerCommand('accent.showGeneratedJava', async () => {
        const activeEditor = vscode.window.activeTextEditor;
        if (!activeEditor || activeEditor.document.languageId !== 'accent') {
            vscode.window.showErrorMessage('No active Accent file selected.');
            return;
        }
        const uri = activeEditor.document.uri.toString();
        try {
            const generatedJava = await vscode.commands.executeCommand('accent.showGeneratedJava', uri);
            if (generatedJava) {
                const doc = await vscode.workspace.openTextDocument({
                    content: generatedJava,
                    language: 'java'
                });
                await vscode.window.showTextDocument(doc, vscode.ViewColumn.Beside, true);
            }
            else {
                vscode.window.showWarningMessage('Could not retrieve generated Java.');
            }
        }
        catch (error) {
            vscode.window.showErrorMessage(`Failed to show generated Java: ${error.message}`);
        }
    });
    context.subscriptions.push(disposable);
}
exports.activate = activate;
function deactivate() {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
exports.deactivate = deactivate;
//# sourceMappingURL=extension.js.map
