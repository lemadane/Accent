# Accent VS Code Extension

Standard IDE support for the Accent programming language in VS Code.

## Features
- **Syntax Highlighting**: Complete highlighting for standard Java structures, multiline interpolated strings, JSX-style HTML components, and `async` / `await` shorthand alongside `Async.run()` / `.await()` method calls.
- **Language Diagnostics**: Real-time parser and compiler semantic diagnostics.
- **Code Intellisense**: Auto-completion, hover information, and jump-to-definition.
- **Code Formatting**: Automatic formatting with 2-space base indent and 4-space continuation indent.
- **Generated Java Code View**: Side-by-side read-only transpile preview.

## Getting Started
1. Run `./gradlew :tools:accent-language-server:installDist` to build and package the Language Server executable.
2. Launch VS Code or package using `vsce package`.

## Concurrency Syntax

Accent provides two compiler shorthands:

| Shorthand | Equivalent method call | Result type |
| --- | --- | --- |
| `async expression` | `Async.run(() -> expression)` | `Async<T>` |
| `await valueAsync` | `valueAsync.await()` | `T` |

The shorthand and method forms have identical behavior. For example:

```accent
final customerAsync = async this.customerService.findRequired(id);
final customer = await customerAsync;
```

Or the equivalent methods:

```accent
import accent.runtime.Async;

final customerAsync = Async.run(() -> this.customerService.findRequired(id));
final customer = customerAsync.await();
```

If `findRequired(id)` returns `Customer`, `customerAsync` has type `accent.runtime.Async<Customer>`. Both forms can be mixed. The shorthand does not require an import; the explicit method form does. The editor highlights `async` and prefix `await` as keywords, while `.await()` remains a method call.

## Building Updated Editor Support

Use `accent-vscode-0.1.6.vsix` for the current syntax: `async` and `await` are shorthand for `Async.run()` and `.await()`. Install it from the repository root:

```bash
code --install-extension tools/accent-vscode/accent-vscode-0.1.6.vsix --force
```

Reload VS Code after installation. The archives through `0.1.5` are older releases and retain their original grammar.

To rebuild the current package:

From the repository root:

```bash
./gradlew :tools:accent-language-server:installDist --no-configuration-cache
cd tools/accent-vscode
npm ci
npm run compile
npx @vscode/vsce package --out accent-vscode-0.1.6.vsix
python3 test/package.test.py
```

Install the newly generated VSIX in VS Code and reload the window. Rebuild the language server as well as the extension so completion, diagnostics, and highlighting use the same language version.
