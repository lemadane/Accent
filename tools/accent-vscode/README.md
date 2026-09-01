# Accent VS Code Extension

Standard IDE support for the Accent programming language in VS Code.

## Features
- **Syntax Highlighting**: Complete highlighting for standard Java structures, multiline interpolated strings, JSX-style HTML components, and `async`/`await` primitives.
- **Language Diagnostics**: Real-time parser and compiler semantic diagnostics.
- **Code Intellisense**: Auto-completion, hover information, and jump-to-definition.
- **Generated Java Code View**: Side-by-side read-only transpile preview.

## Getting Started
1. Run `./gradlew :tools:accent-language-server:installDist` to build and package the Language Server executable.
2. Launch VS Code or package using `vsce package`.
