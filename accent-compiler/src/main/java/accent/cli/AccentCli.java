package accent.cli;

import accent.compiler.CompilationResult;
import accent.compiler.AccentCompiler;
import accent.diagnostic.Diagnostic;
import accent.lexer.AccentLexer;
import accent.lexer.LexResult;
import accent.lexer.Token;
import accent.parser.AccentParser;
import accent.source.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AccentCli {
    private static final String VERSION = "0.1.0";

    private AccentCli() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(arguments);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] arguments) {
        if (arguments.length == 0) {
            printUsage();
            return 2;
        }

        // Parse global options
        List<String> argsList = new ArrayList<>();
        Path outputDir = Path.of("build/classes/accent");
        List<String> classpath = new ArrayList<>();
        Path generatedSourceDir = Path.of("build/generated/sources/accent");
        boolean saveJava = true;
        boolean verbose = false;

        for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i];
            if (arg.equals("-h") || arg.equals("--help") || arg.equals("help")) {
                printUsage();
                return 0;
            }
            if (arg.equals("-v") || arg.equals("--version") || arg.equals("version")) {
                System.out.println("Accent version " + VERSION);
                return 0;
            }
            if (arg.equals("-d")) {
                if (i + 1 < arguments.length) {
                    outputDir = Path.of(arguments[++i]);
                } else {
                    System.err.println("Error: -d requires an output directory.");
                    return 2;
                }
            } else if (arg.equals("-cp") || arg.equals("-classpath")) {
                if (i + 1 < arguments.length) {
                    classpath.addAll(List.of(arguments[++i].split(System.getProperty("path.separator"))));
                } else {
                    System.err.println("Error: -cp requires a classpath string.");
                    return 2;
                }
            } else if (arg.equals("--save-java") || arg.equals("-s")) {
                saveJava = true;
                if (i + 1 < arguments.length && !arguments[i + 1].startsWith("-")) {
                    generatedSourceDir = Path.of(arguments[++i]);
                }
            } else if (arg.equals("--verbose")) {
                verbose = true;
            } else {
                argsList.add(arg);
            }
        }

        if (argsList.size() < 2) {
            printUsage();
            return 2;
        }

        String command = argsList.get(0);
        String targetPath = argsList.get(1);

        try {
            return switch (command) {
                case "tokens" -> runTokens(targetPath);
                case "check" -> runCheck(targetPath);
                case "compile" -> runCompile(targetPath, outputDir, classpath, generatedSourceDir, saveJava);
                case "run" -> runExecute(targetPath, outputDir, classpath, generatedSourceDir, saveJava, verbose);
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    yield 2;
                }
            };
        } catch (Exception e) {
            if (verbose) {
                e.printStackTrace();
            } else {
                System.err.println("Error: " + e.getMessage());
            }
            return 1;
        }
    }

    private static int runTokens(String pathStr) throws IOException {
        Path path = Path.of(pathStr);
        String content = Files.readString(path, StandardCharsets.UTF_8);
        SourceFile source = new SourceFile(path, content);
        LexResult result = new AccentLexer(source).lex();

        result.diagnostics().forEach(diagnostic ->
                System.err.println(diagnostic.format(path.getFileName().toString())));

        if (!result.successful()) {
            return 1;
        }

        for (Token token : result.tokens()) {
            System.out.printf(
                    "%4d:%-3d %-18s %s%n",
                    token.line(),
                    token.column(),
                    token.type(),
                    token.lexeme().replace("\n", "\\n"));
        }
        return 0;
    }

    private static int runCheck(String pathStr) {
        Path path = Path.of(pathStr);
        CompilationResult result = new AccentCompiler().check(path);

        result.diagnostics().forEach(diagnostic ->
                System.err.println(diagnostic.format(path.getFileName().toString())));

        if (!result.successful()) {
            return 1;
        }

        int tokenCount = result.compilationUnit().orElseThrow().tokens().size();
        System.out.printf("Accent source is valid (%d tokens).%n", tokenCount);
        return 0;
    }

    private static int runCompile(String pathStr, Path outputDir, List<String> classpath, Path generatedSourceDir, boolean saveJava) {
        Path path = Path.of(pathStr);
        CompilationResult result = new AccentCompiler().compile(List.of(path), outputDir, classpath, generatedSourceDir, saveJava);

        result.diagnostics().forEach(diagnostic ->
                System.err.println(diagnostic.format(path.getFileName().toString())));

        if (!result.successful()) {
            return 1;
        }

        System.out.println("Compilation successful. Output written to " + outputDir);
        return 0;
    }

    private static int runExecute(String target, Path outputDir, List<String> classpath, Path generatedSourceDir, boolean saveJava, boolean verbose) throws Exception {
        Path targetPath = Path.of(target);
        String mainClassName;

        if (Files.exists(targetPath) && (target.endsWith(".accent") || target.endsWith(".java"))) {
            // Compile containing directory of the main file
            Path compileTarget = targetPath.getParent() != null ? targetPath.getParent() : Path.of(".");
            CompilationResult result = new AccentCompiler().compile(List.of(compileTarget), outputDir, classpath, generatedSourceDir, saveJava);

            result.diagnostics().forEach(diagnostic ->
                    System.err.println(diagnostic.format(targetPath.getFileName().toString())));

            if (!result.successful()) {
                return 1;
            }

            // Detect package name and class name from target file to run
            String content = Files.readString(targetPath, StandardCharsets.UTF_8);
            SourceFile source = new SourceFile(targetPath, content);
            LexResult lexResult = new AccentLexer(source).lex();
            AccentParser parser = new AccentParser(source, lexResult.tokens());
            accent.ast.Ast.CompilationUnit unit = parser.parse();

            String pkg = unit.packageName().orElse("");
            String simpleName = unit.declarations().isEmpty() ? "Main" : unit.declarations().get(0).name();
            mainClassName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        } else {
            mainClassName = target;
        }

        // Launch in a separate JVM process using our local JDK!
        List<String> cmd = new ArrayList<>();
        String javaBinary = Path.of("").toAbsolutePath().resolve("jdk/bin/java").toString();
        if (!Files.exists(Path.of(javaBinary))) {
            javaBinary = "java"; // Fallback to path if local jdk not found
        }
        cmd.add(javaBinary);

        List<String> cpList = new ArrayList<>(classpath);
        cpList.add(outputDir.toString());
        // Also add runtime classes if any are needed
        cpList.add(Path.of("build/classes/java/main").toString()); // Main project runtime (for AccentFuture etc)

        cmd.add("-cp");
        cmd.add(String.join(System.getProperty("path.separator"), cpList));
        cmd.add(mainClassName);

        if (verbose) {
            System.out.println("Running command: " + String.join(" ", cmd));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }

    private static void printUsage() {
        System.err.println("Usage: accent [options] <command> <target>");
        System.err.println("Commands:");
        System.err.println("  tokens <file>                 Print all tokens of a Accent source file");
        System.err.println("  check <file>                  Perform syntactic and semantic check on a file");
        System.err.println("  compile <file-or-dir>         Compile Accent/Java files to class files");
        System.err.println("  run <main-file-or-class>      Compile and execute a main class");
        System.err.println("Options:");
        System.err.println("  -d <dir>                      Output directory for class files (default: build/classes/accent)");
        System.err.println("  -cp <classpath>               Classpath for compilation and execution");
        System.err.println("  -s, --save-java               Save generated intermediate .java files");
        System.err.println("  --verbose                     Print debug information on compiler errors");
    }
}
