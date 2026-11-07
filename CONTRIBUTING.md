# Contributing to Accent

Thank you for your interest in contributing to Accent! This guide will help you get started.

## Development Setup

### Prerequisites

- **JDK 21** or later
- **Gradle 9.6.1** (or use the included Gradle wrapper)

### Building from Source

```bash
# Clone the repository
git clone https://github.com/accent-lang/accent.git
cd accent-lang

# Build and run tests
./gradlew clean build
```

### Running the CLI

```bash
# Check a Accent source file
./gradlew run --args="check examples/HelloAccent.accent"

# Print the token stream
./gradlew run --args="tokens examples/HelloAccent.accent"
```

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
