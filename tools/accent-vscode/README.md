# Accent VS Code Extension

Standard IDE support for the Accent programming language in VS Code.

## Features

Ordered by day-to-day developer usefulness:

1. **Diagnostics**: Parser and semantic diagnostics from the Accent compiler frontend.
2. **Navigation and completion**: Completion, hover information, and jump-to-definition through the language server.
3. **Syntax highlighting**: Java-shaped declarations, explicit visibility, interpolated strings, HTML components, and `async` / `await`.
4. **Formatting**: Uses the editor's indentation settings (2 spaces when no tab size is provided), with 4-space continuation indentation; preserves multiline literal contents.
5. **Generated Java command**: The extension registers **Accent: Show Generated Java**, backed by the server's transpile command. The CLI's `--save-java` output is also available for inspecting generated Java.

## Visibility

| Accent modifier | Java output | Access |
| --- | --- | --- |
| `public` | `public` | Public |
| `protected` | `protected` | Same package and subclasses, following Java protected-access rules |
| `private` | No visibility modifier | Same package only |

Explicit modifiers override name-based defaults. Without a modifier, `name` is public, `_name` is package-private, and `__name` is protected. Accent `private` does not generate a Java private member. Top-level types, declared constructors, and interface members remain public; explicit `public` is optional. Visibility words cannot be used as identifiers.

Declare at most one ordinary top-level type per `.accent` file; extension blocks may appear alone or accompany it. See [the language guide](https://github.com/accent-lang/accent/blob/main/README.md#visibility) for the complete rules.

## Getting started

1. Use a Java 21 toolchain and build the server from the repository root:
   ```bash
   ./gradlew :tools:accent-language-server:installDist --no-configuration-cache
   ```
2. Build and install the extension using the instructions below.
3. When opening another application's workspace, configure the absolute server launcher path in VS Code settings:
   ```json
   {
     "accent.languageServer.path": "/path/to/accent/tools/accent-language-server/build/install/accent-language-server/bin/accent-language-server"
   }
   ```
   Use the `.bat` launcher on Windows. When the Accent checkout itself is the workspace, the extension detects this distribution automatically. A configured existing path overrides that detection.
4. Reload VS Code and open a `.accent` file.

The language server is built separately; the VSIX does not bundle its distribution. Without a server, highlighting and formatting are available, but diagnostics and navigation require the server. `accent.trace.server` enables LSP tracing (`off`, `messages`, or `verbose`).

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

Use `accent-vscode-0.1.7.vsix` for the current syntax, including explicit `public`, `protected`, and `private` modifiers plus name-based defaults and `async` / `await` shorthand for `Async.run()` / `.await()`. Install it from the repository root:

```bash
code --install-extension tools/accent-vscode/accent-vscode-0.1.7.vsix --force
```

Reload VS Code after installation. Archives through `0.1.6` retain their original grammar; rebuild the language server and install `0.1.7` for explicit visibility support.

To rebuild the current package:

From the repository root:

```bash
./gradlew :tools:accent-language-server:installDist --no-configuration-cache
cd tools/accent-vscode
npm ci
npm run compile
node test/async-syntax.test.cjs
node test/formatter.test.cjs
npx @vscode/vsce package --out accent-vscode-0.1.7.vsix
python3 test/package.test.py
```

Install the newly generated VSIX in VS Code and reload the window. Rebuild the language server as well as the extension so completion, diagnostics, and highlighting use the same language version.
