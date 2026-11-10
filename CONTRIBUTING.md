# Contributing to Accent

Thank you for your interest in contributing to Accent! This guide will help you get started.

## Development Setup

### Prerequisites

- **Java 21 toolchain** (the compiler targets `--release 21`)
- The included **Gradle 9.6.1 wrapper**

### Building from Source

```bash
# Clone the repository
git clone https://github.com/accent-lang/accent.git
cd accent

# Build and run tests
./gradlew clean build
```

### Running the CLI

```bash
# Check an Accent project, including references between files
./gradlew :accent-compiler:run --args="check src/e2e/examples/hello"

# Print the token stream
./gradlew :accent-compiler:run --args="tokens src/e2e/examples/hello/HelloAccent.accent"
```

Use at most one ordinary top-level type per `.accent` file; extension blocks may appear alone or accompany it. Explicit member visibility maps `public` to Java public, `protected` to Java protected (same package plus subclasses), and `private` to Java package-private. Protected/private members require an underscore prefix; the modifier determines access and unmodified members are public. Prefixes alone never determine access. Use `this` for members declared or overridden in the current class and `super` for inherited instance members on the current object. Calls on other objects retain normal virtual dispatch. Top-level types, declared constructors, and interface members remain public. Check a directory when a feature spans several source files, and compile it to verify the generated Java as well.

## Repository map

| Area | Location |
| --- | --- |
| Compiler frontend, lowering, Java emitter, CLI, and runtime libraries | `accent-compiler` |
| Spring HTML routing, SQL datasource wiring, and logging configuration | `accent-spring-boot-starter` |
| Shared completion, hover, definition, and diagnostic services | `tools/accent-language-tools` |
| LSP server | `tools/accent-language-server` |
| VS Code client, grammar, formatter, and package tests | `tools/accent-vscode` |
| Spring routing and SQL demo applications | `accent-compiler/src/e2e` |

The IntelliJ module has source in `tools/accent-intellij` but is disabled in `settings.gradle`; it is excluded from the root build.

## Verifying language and documentation changes

Run compiler and editor tests when changing syntax or visibility:

```bash
./gradlew :accent-compiler:test :tools:accent-language-tools:test
```

Visibility changes must cover generated JVM modifiers, same-package access, cross-package subclasses, unrelated callers, Java interoperability, and editor completion filtering. Check required non-public prefixes and `this`/`super` qualifiers for fields, matching overloads, overrides, hidden fields, and multilevel inheritance. Generated storage and imported Java declarations retain their existing naming. Preserve the distinction between Accent `private` (package-private) and Java source `private` (class-private).

The README prioritizes broadly useful features before specialized utilities. Describe implemented behavior and limitations; frontend acceptance alone does not prove an example compiles to executable bytecode. Keep CLI paths, module names, and editor instructions aligned with the checkout.

The Spring setup snippets have an independent consumer check:

```bash
python3 scripts/test-readme-spring.py
```

For editor changes, rebuild the server and follow the [extension build and package checks](tools/accent-vscode/README.md#building-updated-editor-support). Existing VSIX archives retain their embedded grammar and documentation until rebuilt.

## Conditional body braces

Every Accent `if` and `else` body requires `{ ... }`, including single statements
and empty branches. `else if (...) { ... }` chains are supported; each conditional
body and the final `else` must be braced. `for/else` fallback bodies also require
braces. Java source interoperability retains Java's own branch syntax.

Keep embedded Accent fixtures and README examples braced. Cover missing braces,
empty statements, nested branches, `else if`, loop fallbacks, and valid generated
Java in `ConditionalBracesTest`. This rule does not change truthiness or add new
null-flow proofs.

When updating examples, wrap the whole branch in braces and preserve which `if`
owns each `else`. Replace empty `;` branches with `{}`. Keep unbraced Accent
only in explicitly labeled invalid examples or compiler rejection tests.
Check statement examples, truthy conditions, if-expressions, HTML conditionals,
and `for/else` fallbacks when reviewing documentation.

## Nullability contracts

Keep declarations, examples, diagnostics, and editor support consistent:

| Contract | Declaration | Use |
| --- | --- | --- |
| Non-null reference | `String name = "John";` | `name` |
| Nullable reference | `String name? = null;` | `name?` |
| Non-null method result | `String name() { return "John"; }` | `this.name()` |
| Nullable method result | `String name?() { return null; }` | `this.name()?` |
| Non-null list and elements | `List<String> names = new ArrayList<String>();` | `names.get(0)` |
| Nullable list and elements | `List<String> names?? = null;` | `names?.get(0)?` |
| Nullable array and elements | `String[] names?? = null;` | `names?[0]?` |

Reference types and elements are non-null by default. `List<String?> names`
and `String?[] names` allow nullable elements in a non-null collection. The
`??` binding suffix permits both a nullable collection and nullable elements.
Use `?:` for null coalescing; `??` is no longer a coalescing operator.

Non-null fields and locals require initializers. Nullable bindings keep `?` at
use sites, including after a proof such as `if (value? != null) { ... }`.
Optional calls remain nullable because they can be skipped. Preserve inherited
return guarantees and separately compiled contract metadata.

Test all four collection/element combinations, initializers, mutations, null
proofs, aliases, optional calls, return contracts, overrides, and Java boundaries.
Include iterator, sublist, and bulk mutations. Guarded lists copy ordinary inputs;
reference-array boundaries copy and validate inputs. JVM array writes from Java
cannot be intercepted, so guarded Accent reads must detect introduced nulls.
The contracts guarantee neither non-emptiness nor deep immutability. HTTP binding
annotations should specify parameter names explicitly when guards rename JVM parameters.

Run the contract and documentation regression tests:

```bash
./gradlew :accent-compiler:test --tests accent.compiler.NonNullBindingTest --tests accent.compiler.NonNullMethodTest --tests accent.compiler.NonNullElementsTest --tests accent.compiler.AsyncDocumentationEndToEndTest :tools:accent-language-tools:test --max-workers=1
```

The method and element suites compile the corresponding README examples. Use a
single worker if child-JVM HTTP tests time out under build contention.

## Testing Concurrency Changes

Accent supports `async expression` as shorthand for `Async.run(() -> expression)` and `await valueAsync` as shorthand for `valueAsync.await()`. Both forms return the same `accent.runtime.Async<T>` handles and results, and may be mixed. The compiler rewrites the shorthands to these method calls, so test both forms against the same behavior. Keep examples, diagnostics, completions, and packaged grammars consistent with both forms.

Run the runtime and compiled-language concurrency tests:

```bash
./gradlew :accent-compiler:test --tests accent.runtime.AccentRuntimeTest --tests accent.compiler.AsyncConcurrencyEndToEndTest --tests accent.compiler.AsyncSyntaxEquivalenceEndToEndTest --tests accent.compiler.AsyncDocumentationEndToEndTest
```

The E2E suite covers virtual-thread overlap, inferred and explicit result types, nested and void operations, repeated and simultaneous awaits, failures, timed waits, cancellation, interruption, invalid operations, and interoperability between shorthand and method syntax. `AsyncSyntaxEquivalenceEndToEndTest` runs a shared behavioral contract against both forms, including captures, evaluation order, short-circuiting, generic identity, and failure propagation. Use latches and bounded waits to test overlap rather than timing-based speed comparisons. `AsyncDocumentationEndToEndTest` reads the actual README and VS Code guide snippets, compiles them, and executes them.

## Testing Standalone HTTP

Undertow is included in the compiler/runtime distribution. Run real-socket and compiled-Accent HTTP tests with:

```bash
./gradlew :accent-compiler:test --tests accent.http.HttpEndToEndTest
```

The suite covers routing, inferred handler and middleware parameters, JSON and HTML, encoded URLs, body limits, HTTP methods, virtual-thread concurrency, graceful shutdown, and repeated CLI execution with bundled dependencies. Use ephemeral ports and close every application to avoid leaked listeners.

## Making Changes

1. **Fork** the repository and create a feature branch from `main`.
2. **Write tests** for any new functionality or bug fixes.
3. **Follow the existing code style** — the project uses `-Xlint:all -Werror`, so the compiler will enforce warnings as errors.
4. **Keep commits focused** — each commit should address a single concern.
5. **Update documentation** if your changes affect the public API, CLI usage, or language features.

## Pull Request Process

1. Ensure `./gradlew clean build` passes with no warnings or errors.
2. Update the `README.md` if you've added new features or changed existing behavior.
3. Open a pull request against the `main` branch.
4. Describe your changes clearly in the PR description, including any design decisions and the motivation behind the change.

## Reporting Issues

- Use [GitHub Issues](https://github.com/accent-lang/accent/issues) to report bugs or request features.
- Include a minimal `.accent` code sample that demonstrates the issue, if applicable.
- Describe expected vs. actual behavior.

## Code Style

- Java 21 source level with `--release 21`.
- UTF-8 encoding.
- All compiler warnings are treated as errors (`-Xlint:all -Werror`).
- Use descriptive names; avoid abbreviations.
- Write Javadoc for public types and methods.

## License

By contributing to Accent, you agree that your contributions will be licensed under the [MIT License](LICENSE).
