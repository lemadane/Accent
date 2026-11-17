# AccentCore

Shared Accent compiler and language runtime, extracted from Accent commit
`c2e14e7efa9e7dcd46cc53f93d7dcd02fab96a11`. Existing Java package names are preserved.

| Artifact | Responsibility |
| --- | --- |
| `accent:accent-core` | Lexer, parser, AST, semantic analysis, lowering, Java emitter, compiler API and CLI; depends on `accent-runtime` |
| `accent:accent-runtime` | Language helpers, symbols, JSON/XML serialization and logging; no external production dependencies |
| AccentServerSide | HTTP/Undertow, HTML rendering, web utilities, Spring web adapter and backend demos |
| AccentSQL / AccentOrm / AccentSqlite | JDBC execution and Spring SQL adapter / row mapping / SQLite JDBC adapter |

Neither Core artifact depends on the backend, Spring, Undertow, or JDBC. The grammar
still recognizes HTML and SQL constructs; applications using them must supply
AccentServerSide for HTML or AccentSQL (with AccentOrm) for SQL. This preserves the language while separating its dependencies.

## Build

Requires a JDK 21 toolchain (Gradle can run on a newer JDK).

```sh
./gradlew test assemble
./gradlew publishToMavenLocal
./gradlew :accent-core:run --args='check /path/to/example.accent'
```

If JDK 21 is not auto-detected, pass
`-Dorg.gradle.java.installations.paths=/path/to/jdk-21`.
The two libraries publish version `0.1.0-alpha.2`, including sources.
Tests here exercise the core without backend libraries on its classpath. The backend
retains the full original integration suite, including language and web regressions.

## Backend integration

AccentServerSide is a separate repository. Its `accent-compiler` module contains backend
libraries and depends on the versioned `accent:accent-core` artifact. Its CLI
includes Core and Runtime dependencies.

Publish Core locally before building ServerSide independently. ServerSide also
requires locally published AccentOrm and AccentSQL artifacts:

```sh
./gradlew test publishToMavenLocal
# From an AccentServerSide checkout anywhere on disk:
./gradlew build
```

For coordinated source development, run ServerSide with
`./gradlew build -PaccentCoreDir=/path/to/AccentCore -PaccentOrmDir=/path/to/AccentOrm -PaccentSqlDir=/path/to/AccentSQL`.
Neither repository requires a particular parent directory. Core has its own CI and
publishes both artifacts to GitHub Packages when a release is published.

## AccentAndroid boundary

AccentAndroid should use the compiler on the **build host** and package only the
compatible runtime in the Android application. Do not put the compiler or backend
facade on the Android application runtime classpath.

This extraction is not yet an Android SDK or an Android compatibility certification.
Both artifacts currently target Java 21. In particular, `Async` uses virtual threads,
serialization uses record reflection, and compiler output currently uses the host
javac defaults. Follow-up Android work must provide an Android-compatible async
implementation, audit runtime APIs against the chosen minimum SDK, and integrate
Java source generation with the Android Gradle build and D8/R8. Native UI bindings
belong in AccentAndroid. No Android build or device test has been performed here.
