import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions
} from 'vscode-languageclient/node';

let client: LanguageClient;

export function activate(context: vscode.ExtensionContext) {
    console.log('Activating Accent VS Code extension...');

    // 1. Register Accent Document Formatter (2-space base indent, 4-space continuation indent)
    const formatterDisposable = vscode.languages.registerDocumentFormattingEditProvider(
        'accent',
        {
            provideDocumentFormattingEdits(document, options) {
                return formatAccentDocument(document, options);
            }
        }
    );
    context.subscriptions.push(formatterDisposable);

    // 2. Find the language server executable
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    let serverPath = '';
    if (workspaceRoot) {
        // Try the Gradle installDist distribution directory
        const scriptName = process.platform === 'win32' ? 'accent-language-server.bat' : 'accent-language-server';
        const candidate = path.join(workspaceRoot, 'tools', 'accent-language-server', 'build', 'install', 'accent-language-server', 'bin', scriptName);
        if (fs.existsSync(candidate)) {
            serverPath = candidate;
        }
    }

    // Default configuration fallback
    const configPath = vscode.workspace.getConfiguration('accent').get<string>('languageServer.path');
    if (configPath && fs.existsSync(configPath)) {
        serverPath = configPath;
    }

    if (!serverPath) {
        const globalFallback = path.join('/home/lem/Projects/java/accent/tools/accent-language-server/build/install/accent-language-server/bin', process.platform === 'win32' ? 'accent-language-server.bat' : 'accent-language-server');
        if (fs.existsSync(globalFallback)) {
            serverPath = globalFallback;
        }
    }

    if (!serverPath) {
        // If not found, look at system path or suggest running gradle installDist
        vscode.window.showWarningMessage('Accent Language Server not found. Please run "./gradlew :tools:accent-language-server:installDist" in your workspace root.');
        return;
    }

    // 2. Start the Language Server process
    const serverOptions: ServerOptions = {
        command: serverPath,
        args: []
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'accent' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.accent')
        }
    };

    client = new LanguageClient(
        'accentLanguageServer',
        'Accent Language Server',
        serverOptions,
        clientOptions
    );

    client.start();

    // 3. Register showGeneratedJava command
    const disposable = vscode.commands.registerCommand('accent.showGeneratedJava', async () => {
        const activeEditor = vscode.window.activeTextEditor;
        if (!activeEditor || activeEditor.document.languageId !== 'accent') {
            vscode.window.showErrorMessage('No active Accent file selected.');
            return;
        }

        const uri = activeEditor.document.uri.toString();
        try {
            // Execute the custom command registered in the Language Server
            const generatedJava = await vscode.commands.executeCommand<string>('accent.showGeneratedJava', uri);
            if (generatedJava) {
                const doc = await vscode.workspace.openTextDocument({
                    content: generatedJava,
                    language: 'java'
                });
                await vscode.window.showTextDocument(doc, vscode.ViewColumn.Beside, true);
            } else {
                vscode.window.showWarningMessage('Could not retrieve generated Java.');
            }
        } catch (error: any) {
            vscode.window.showErrorMessage(`Failed to show generated Java: ${error.message}`);
        }
    });

    context.subscriptions.push(disposable);
}

function formatAccentDocument(
    document: vscode.TextDocument,
    options: vscode.FormattingOptions
): vscode.TextEdit[] {
    const text = document.getText();
    if (!text) return [];

    const baseIndentSize = options.tabSize || 2;
    const continuationIndentSize = 4;
    const insertSpaces = options.insertSpaces !== false;

    const lines = text.split(/\r?\n/);
    const formattedLines: string[] = [];
    const literalLines = scanLiteralLines(lines);

    let blockDepth = 0;
    let inContinuation = false;
    let continuationLevel = 0;
    let inBlockComment = false;

    for (let i = 0; i < lines.length; i++) {
        const rawLine = lines[i];
        const trimmed = rawLine.trim();
        if (literalLines[i].protected) {
            // Preserve both the content and the closing delimiter's indentation.
            formattedLines.push(rawLine);
            blockDepth = Math.max(0, blockDepth + countNetBraceChange(literalLines[i].code));
            inContinuation = false;
            continuationLevel = 0;
            continue;
        }

        if (trimmed === '') {
            formattedLines.push('');
            continue;
        }

        if (inBlockComment) {
            if (trimmed.includes('*/')) {
                inBlockComment = false;
            }
            formattedLines.push(buildIndent(blockDepth, baseIndentSize, continuationLevel, continuationIndentSize, insertSpaces) + trimmed);
            continue;
        }

        if (trimmed.startsWith('/*')) {
            if (!trimmed.includes('*/')) {
                inBlockComment = true;
            }
            formattedLines.push(buildIndent(blockDepth, baseIndentSize, continuationLevel, continuationIndentSize, insertSpaces) + trimmed);
            continue;
        }

        const leadingCloses = countLeadingCloseBraces(trimmed);
        const effectiveBlockDepth = Math.max(0, blockDepth - leadingCloses);
        const curContinuationIndent = inContinuation ? continuationLevel * continuationIndentSize : 0;
        const indentStr = buildIndent(effectiveBlockDepth, baseIndentSize, 0, curContinuationIndent, insertSpaces);

        formattedLines.push(indentStr + normalizeLineSpacing(trimmed));

        const netBraceChange = countNetBraceChange(trimmed);
        blockDepth = Math.max(0, blockDepth + netBraceChange);

        const nextLineIsContinuation = isStatementContinuation(trimmed);
        if (nextLineIsContinuation) {
            if (!inContinuation) {
                inContinuation = true;
                continuationLevel = 1;
            }
        } else {
            inContinuation = false;
            continuationLevel = 0;
        }
    }

    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    const resultText = formattedLines.join(eol);

    const fullRange = new vscode.Range(
        document.positionAt(0),
        document.positionAt(text.length)
    );

    return [vscode.TextEdit.replace(fullRange, resultText)];
}

// Mask literals and comments while retaining delimiter state across lines.
// Lines touching a multiline literal remain byte-for-byte unchanged.
function scanLiteralLines(lines: string[]): { protected: boolean; code: string }[] {
    let delimiter = '';
    let blockComment = false;
    return lines.map(line => {
        let preserve = delimiter !== '';
        let code = '';
        for (let i = 0; i < line.length;) {
            if (blockComment) {
                if (line.startsWith('*/', i)) {
                    blockComment = false;
                    i += 2;
                } else {
                    i++;
                }
            } else if (delimiter) {
                if (line[i] === '\\') {
                    i += 2;
                } else if (line.startsWith(delimiter, i)) {
                    i += delimiter.length;
                    delimiter = '';
                } else {
                    i++;
                }
            } else if (line.startsWith('//', i)) {
                break;
            } else if (line.startsWith('/*', i)) {
                blockComment = true;
                i += 2;
            } else if (line.startsWith('"""', i) || line[i] === '`') {
                delimiter = line[i] === '`' ? '`' : '"""';
                preserve = true;
                i += delimiter.length;
            } else if (line[i] === '"' || line[i] === "'") {
                const quote = line[i++];
                while (i < line.length) {
                    if (line[i] === '\\') {
                        i += 2;
                    } else if (line[i++] === quote) {
                        break;
                    }
                }
            } else {
                code += line[i++];
            }
        }
        return { protected: preserve, code };
    });
}

function buildIndent(
    blockDepth: number,
    baseIndentSize: number,
    continuationLevel: number,
    continuationIndentSize: number,
    insertSpaces: boolean
): string {
    const totalSpaces = (blockDepth * baseIndentSize) + (continuationLevel * continuationIndentSize);
    if (!insertSpaces) {
        const tabs = Math.floor(totalSpaces / baseIndentSize);
        const rem = totalSpaces % baseIndentSize;
        return '\t'.repeat(tabs) + ' '.repeat(rem);
    }
    return ' '.repeat(totalSpaces);
}

function countLeadingCloseBraces(line: string): number {
    let count = 0;
    for (let i = 0; i < line.length; i++) {
        const c = line.charAt(i);
        if (c === '}' || c === ']' || c === ')') {
            count++;
        } else if (/\s/.test(c)) {
            continue;
        } else {
            break;
        }
    }
    return count;
}

function countNetBraceChange(line: string): number {
    let net = 0;
    let inString = false;
    let stringChar = '';

    for (let i = 0; i < line.length; i++) {
        const c = line.charAt(i);
        if (!inString && c === '/' && i + 1 < line.length && line.charAt(i + 1) === '/') {
            break;
        }
        if (inString) {
            if (c === '\\') {
                i++;
            } else if (c === stringChar) {
                inString = false;
            }
            continue;
        }
        if (c === '"' || c === "'") {
            inString = true;
            stringChar = c;
            continue;
        }
        if (c === '{' || c === '(' || c === '[') {
            net++;
        } else if (c === '}' || c === ')' || c === ']') {
            net--;
        }
    }
    return net;
}

function isStatementContinuation(line: string): boolean {
    if (!line || line.startsWith('//') || line.startsWith('/*') || line.startsWith('*')) {
        return false;
    }
    const trimmed = line.trim();
    if (trimmed.endsWith(';') || trimmed.endsWith('{') || trimmed.endsWith('}')) {
        return false;
    }
    if (
        trimmed.endsWith(',') || trimmed.endsWith('+') || trimmed.endsWith('-') ||
        trimmed.endsWith('*') || trimmed.endsWith('/') || trimmed.endsWith('=') ||
        trimmed.endsWith('&&') || trimmed.endsWith('||') || trimmed.endsWith('?:') ||
        trimmed.endsWith('?') || trimmed.endsWith(':') || trimmed.endsWith('and') ||
        trimmed.endsWith('or') || trimmed.endsWith('->') || trimmed.endsWith('=>')
    ) {
        return true;
    }
    if (
        trimmed.startsWith('.') || trimmed.startsWith('?') || trimmed.startsWith('+') ||
        trimmed.startsWith('-') || trimmed.startsWith('*') || trimmed.startsWith('/') ||
        trimmed.startsWith('&&') || trimmed.startsWith('||') || trimmed.startsWith('and') ||
        trimmed.startsWith('or')
    ) {
        return true;
    }
    let openParenCount = 0;
    for (let i = 0; i < trimmed.length; i++) {
        const c = trimmed.charAt(i);
        if (c === '(' || c === '[') openParenCount++;
        else if (c === ')' || c === ']') openParenCount--;
    }
    return openParenCount > 0;
}

function normalizeLineSpacing(line: string): string {
    if (line.startsWith('//') || line.startsWith('/*') || line.startsWith('*')) {
        return line;
    }
    let result = '';
    let inString = false;
    let stringChar = '';
    let lastWasSpace = false;

    for (let i = 0; i < line.length; i++) {
        const c = line.charAt(i);
        if (inString) {
            result += c;
            if (c === '\\' && i + 1 < line.length) {
                result += line.charAt(++i);
            } else if (c === stringChar) {
                inString = false;
            }
            continue;
        }
        if (c === '"' || c === "'") {
            inString = true;
            stringChar = c;
            result += c;
            lastWasSpace = false;
            continue;
        }
        if (/\s/.test(c)) {
            if (!lastWasSpace) {
                result += ' ';
                lastWasSpace = true;
            }
        } else {
            result += c;
            lastWasSpace = false;
        }
    }
    return result.trim();
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
