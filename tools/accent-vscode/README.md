# Accent VS Code Extension

Standard IDE support for the Accent programming language in VS Code.

## Features

Ordered by day-to-day developer usefulness:

1. **Diagnostics**: Parser and semantic diagnostics from the Accent compiler frontend.
2. **Navigation and completion**: Completion, hover information, and jump-to-definition through the language server.
3. **Syntax highlighting**: Java-shaped declarations, explicit visibility, interpolated strings, HTML components, `async` / `await`, and visible `?` / `??` contracts.
4. **Formatting**: Uses the editor's indentation settings (2 spaces when no tab size is provided), with 4-space continuation indentation; preserves multiline literal contents.
5. **Generated Java command**: The extension registers **Accent: Show Generated Java**, backed by the server's transpile command. The CLI's `--save-java` output is also available for inspecting generated Java.

## Console output and logging

Use `print(...)` or `println(...)` without `System.out`. An automatic `log` binding provides severity and diagnostic-context methods without an import or `@Logging`. `log("message")` calls static `Logger.log("message")` at INFO, even with a local variable named `log`; `log.error("message", exception)` selects ERROR. `Logger` is an implicitly available class; `new Logger()` creates an instance named after the calling class.

```accent
println("Hello, Accent!");
log("Started");
final log = new Logger();
log.put("requestId", "R1");
try {
    log.info("Processing request");
} finally {
    log.clear();
}
```

Completion offers `print`, `println`, `log`, and `Logger`; `log.` includes logging and context methods. Generated Java previews expand these shorthands. User-defined members still require `this.`, `super.`, or `static.` as appropriate; bare printing and logging calls select built-ins.

## Visibility

| Accent modifier | Java output | Access |
| --- | --- | --- |
| `public` | `public` | Public |
| `protected` | `protected` | Same package and subclasses, following Java protected-access rules |
| `private` | No visibility modifier | Same package only |

Protected and private declarations require an underscore prefix (`protected int _step`, `private int _cache`). The modifier determines access; an underscore is a visual marker. Members without a modifier are public, even if their names begin with `_` or `__`. Accent `private` remains package-private. Top-level types, declared constructors, and interface members remain public.

Use `this.member` / `this.method()` for declarations and overrides in the current class. Use `super.member` / `super.method()` for inherited instance members on the current object or to call a parent implementation. Calls through another object remain normal virtual calls. `super` bypasses overrides; `this` retains virtual dispatch. Completion filters `this.` and `super.` to follow these rules. Static members declared in the current class require `static.member` / `static.method()`. External and inherited static members use the declaring class name. Bare static calls and static access through object references are rejected. Completion after `static.` offers only static members declared in the current class. Extension blocks keep `this` as their target receiver.

Migrate old name-based declarations by adding `private` or `protected`; rename unprefixed non-public declarations and their references to include `_`. Change inherited `this.member` access to `super.member`. Keep Java library names unchanged; generated storage is exempt from the prefix rule.

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

Use `accent-vscode-0.1.17.vsix` for the current syntax, with required non-public prefixes, `this`/`super`/`static` member qualification, `?` / `??` contracts, and `async` / `await` support. Install it from the repository root:

```bash
code --install-extension tools/accent-vscode/accent-vscode-0.1.17.vsix --force
```

Reload VS Code after installation. Archives through `0.1.16` retain their previous documentation. Rebuild the language server and install `0.1.17` for diagnostics and completion matching the member, non-null binding, method-result, and collection-element rules.

To rebuild the current package:

From the repository root:

```bash
./gradlew :tools:accent-language-server:installDist --no-configuration-cache
cd tools/accent-vscode
npm ci
npm run compile
node test/async-syntax.test.cjs
node test/formatter.test.cjs
npx @vscode/vsce package --out accent-vscode-0.1.17.vsix
python3 test/package.test.py
```

Install the newly generated VSIX in VS Code and reload the window. Rebuild the language server as well as the extension so completion, diagnostics, and highlighting use the same language version.


## Visible nullability contracts

References and elements are non-null by default. Use `String name?` for a nullable
binding and `name?` when referring to it. `List<String?> names` and
`String?[] names` allow nullable elements in a non-null collection.
`List<String> names??` and `String[] names??` permit both a nullable collection
and nullable elements. The former `!` and `!!` contract syntax is obsolete.

A non-null element is accessed as `names.get(0)` or `names[0]`. A nullable element
uses `names.get(0)?` or `names[0]?`. Optional calls such as `names?.get(0)?` skip a
null receiver and therefore have a nullable overall result. Declare nullable
method results as `String getName?()` and call them as `customer.getName()?`.

Null coalescing uses `?:`: `nickname? ?: "Unknown"`. It evaluates the fallback
only for null, preserving empty strings, zero, and false. The ternary expression
remains `condition ? yes : no`; `??` is reserved for collection markers.

Non-null fields and locals require initializers. These contracts do not imply
non-empty or immutable values. Rebuild the language server and extension together
for current diagnostics, completion, and highlighting; the VSIX does not bundle
the server. See the [language guide](https://github.com/accent-lang/accent/blob/main/README.md#1-nullable-and-non-null-reference-types)
for examples and Java boundary rules.

## Mandatory conditional braces

Accent requires `{ ... }` around every `if` and `else` body, even one statement.
Use `if (user? != null) { names.add(user?); }`. `else if (...) { ... }` chains
remain valid; empty branches use `{}`. The rule includes `for/else` fallback
bodies. The language server reports unbraced branches as syntax errors; rebuild
it to get the updated diagnostics. The formatter does not invent missing braces.

For existing code, replace `if (ready) return;` with
`if (ready) { return; }`, and `else return;` with `else { return; }`.
The unbraced forms are compiler errors, regardless of indentation. This applies
to Accent source; ordinary `.java` files retain Java's syntax. See the
[language guide](https://github.com/accent-lang/accent/blob/main/README.md#11-if-expression)
for a complete nested `if` / `else if` / `else` example.

## Static imports

Use `import static java.lang.Math.sqrt;` followed by `sqrt(value)`, or `import static java.lang.Math.*;` for wildcard imports. Imported fields also use bare names, such as `PI`. No `$` prefix is needed. Named imports take precedence over wildcard imports; qualify ambiguous names with their declaring class. Generated Java previews show the resolved class-qualified access.

## While loops with else

`while (condition) { ... } else { ... }` runs `else` after normal completion, including zero iterations, and skips it when `break` exits that loop. Both bodies require braces. While expressions can also `yield` values from the `else` block.

The expression form is `final values = while (condition) { yield value; } else { yield fallback; };`. It collects all executed `yield` values into a list:

- Normal completion can produce `[1, 2, 3]`: `1` and `2` from the loop, then `3` from `else`.
- Zero iterations can produce `[9]`: only the fallback from `else`.
- A `break` after yielding `7` produces `[7]`: the `else` yields are skipped.

The expression requires a final semicolon. See the [while-expression guide](https://github.com/accent-lang/accent/blob/main/README.md#13-while-and-do-while-expressions).
