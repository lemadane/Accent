# Accent VS Code Extension

Standard IDE support for the Accent programming language in VS Code.

## Features
- **Syntax Highlighting**: Complete highlighting for standard Java structures, multiline interpolated strings, JSX-style HTML components, and `Async.start()` and `.await()` method calls.
- **Language Diagnostics**: Real-time parser and compiler semantic diagnostics.
- **Code Intellisense**: Auto-completion, hover information, and jump-to-definition.
- **Code Formatting**: Automatic formatting with 2-space base indent and 4-space continuation indent.
- **Generated Java Code View**: Side-by-side read-only transpile preview.

## Getting Started
1. Run `./gradlew :tools:accent-language-server:installDist` to build and package the Language Server executable.
2. Launch VS Code or package using `vsce package`.

## Concurrency Syntax

Use `Async.start()` to start an operation and `.await()` to retrieve its result:

```accent
import accent.runtime.Async;

final customerAsync = Async.start(() -> this.customerService.findRequired(id));
final customer = customerAsync.await();
```

If `findRequired(id)` returns `Customer`, `customerAsync` has type `accent.runtime.Async<Customer>`. The editor treats `start` and `await` as methods; neither `async` nor `await` is a keyword. Prefix syntax such as `async service.findRequired(id)` or `await customerAsync` is no longer supported.

## Building Updated Editor Support

Use `accent-vscode-0.1.5.vsix` for the current syntax: `Async.start()` and `.await()` are method calls. Install it from the repository root:

```bash
code --install-extension tools/accent-vscode/accent-vscode-0.1.5.vsix --force
```

Reload VS Code after installation. The archives through `0.1.4` are older releases and retain their original grammar.

To rebuild the current package:

From the repository root:

```bash
./gradlew :tools:accent-language-server:installDist --no-configuration-cache
cd tools/accent-vscode
npm ci
npm run compile
npx @vscode/vsce package --out accent-vscode-0.1.5.vsix
python3 test/package.test.py
```

Install the newly generated VSIX in VS Code and reload the window. Rebuild the language server as well as the extension so completion, diagnostics, and highlighting use the same language version.
