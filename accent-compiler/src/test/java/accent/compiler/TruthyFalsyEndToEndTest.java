package accent.compiler;

import static org.junit.jupiter.api.Assertions.*;

import accent.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for JS-style truthy and falsy condition evaluation.
 * Tests booleans, numbers, strings, null, objects, control flow (if, while, do-while, ternary, if-expr),
 * and logical operators (and, or, not, nand, nor, xor, xnor, &&, ||, !).
 */
class TruthyFalsyEndToEndTest {

    private Path createTempDir() throws Exception {
        Path temp = Files.createTempDirectory("accent-truthy-temp");
        temp.toFile().deleteOnExit();
        return temp;
    }

    private CompilationResult compile(Path srcDir, Path binDir, Path genDir) {
        AccentCompiler compiler = new AccentCompiler();
        List<String> cp = List.of("build/classes/java/main", "build/classes/java/test");
        return compiler.compile(List.of(srcDir), binDir, cp, genDir, true);
    }

    private void runClass(Path binDir, String mainClassName) throws Exception {
        java.net.URLClassLoader classLoader = new java.net.URLClassLoader(
            new java.net.URL[] {
                binDir.toUri().toURL(),
                Path.of("build/classes/java/main").toUri().toURL()
            },
            ClassLoader.getSystemClassLoader()
        );
        try {
            Class<?> clazz = classLoader.loadClass(mainClassName);
            java.lang.reflect.Method mainMethod = clazz.getMethod("main", String[].class);
            mainMethod.setAccessible(true);
            mainMethod.invoke(null, (Object) new String[0]);
        } finally {
            classLoader.close();
        }
    }

    @Test
    void testIfStatementTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "import java.util.Optional;\n" +
            "public class IfMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // Strings\n" +
            "        final strVal = \"hello\";\n" +
            "        final emptyStr = \"\";\n" +
            "        boolean strRan = false;\n" +
            "        if (strVal) { strRan = true; }\n" +
            "        if (!strRan) throw new RuntimeException(\"non-empty string should be truthy\");\n" +
            "\n" +
            "        if (emptyStr) { throw new RuntimeException(\"empty string should be falsy\"); }\n" +
            "\n" +
            "        // Numbers\n" +
            "        final numVal = 42;\n" +
            "        final zeroVal = 0;\n" +
            "        final doubleZero = 0.0;\n" +
            "        final doubleVal = 3.14;\n" +
            "        boolean numRan = false;\n" +
            "        if (numVal) { numRan = true; }\n" +
            "        if (!numRan) throw new RuntimeException(\"non-zero int should be truthy\");\n" +
            "\n" +
            "        if (zeroVal) { throw new RuntimeException(\"zero int should be falsy\"); }\n" +
            "        if (doubleZero) { throw new RuntimeException(\"double zero should be falsy\"); }\n" +
            "\n" +
            "        boolean dblRan = false;\n" +
            "        if (doubleVal) { dblRan = true; }\n" +
            "        if (!dblRan) throw new RuntimeException(\"non-zero double should be truthy\");\n" +
            "\n" +
            "        // Null, objects, collections, maps, optionals\n" +
            "        final Object nullObj = null;\n" +
            "        final Object realObj = new Object();\n" +
            "        final emptyList = List.of();\n" +
            "        final nonEmptyList = List.of(\"item\");\n" +
            "        final emptyMap = Map.of();\n" +
            "        final nonEmptyMap = Map.of(\"key\", \"val\");\n" +
            "        final emptyOptional = Optional.empty();\n" +
            "        final nonEmptyOptional = Optional.of(\"item\");\n"
 +
            "\n" +
            "        if (nullObj) { throw new RuntimeException(\"null should be falsy\"); }\n" +
            "\n" +
            "        boolean objRan = false;\n" +
            "        if (realObj) { objRan = true; }\n" +
            "        if (!objRan) throw new RuntimeException(\"object reference should be truthy\");\n" +
            "\n" +
            "        if (emptyList) { throw new RuntimeException(\"empty list should be falsy\"); }\n" +
            "        boolean listRan = false;\n" +
            "        if (nonEmptyList) { listRan = true; }\n" +
            "        if (!listRan) throw new RuntimeException(\"non-empty list should be truthy\");\n" +
            "\n" +
            "        if (emptyMap) { throw new RuntimeException(\"empty map should be falsy\"); }\n" +
            "        boolean mapRan = false;\n" +
            "        if (nonEmptyMap) { mapRan = true; }\n" +
            "        if (!mapRan) throw new RuntimeException(\"non-empty map should be truthy\");\n" +
            "\n" +
            "        if (emptyOptional) { throw new RuntimeException(\"empty optional should be falsy\"); }\n" +
            "        boolean optRan = false;\n" +
            "        if (nonEmptyOptional) { optRan = true; }\n" +
            "        if (!optRan) throw new RuntimeException(\"non-empty optional should be truthy\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("IfMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.IfMain");
    }

    @Test
    void testLoopsAndTernaryTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "public class LoopsTernaryMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // While loop\n" +
            "        var count = 3;\n" +
            "        var iterations = 0;\n" +
            "        while (count) {\n" +
            "            iterations++;\n" +
            "            count--;\n" +
            "        }\n" +
            "        if (iterations != 3) throw new RuntimeException(\"while loop failed, expected 3 got \" + iterations);\n" +
            "\n" +
            "        // Do-while loop\n" +
            "        var doCount = 1;\n" +
            "        var doRuns = 0;\n" +
            "        do {\n" +
            "            doRuns++;\n" +
            "            doCount--;\n" +
            "        } while (doCount);\n" +
            "        if (doRuns != 1) throw new RuntimeException(\"do-while loop failed, expected 1 got \" + doRuns);\n" +
            "\n" +
            "        // Ternary operator\n" +
            "        final strRes = \"yes\" ? \"ok\" : \"no\";\n" +
            "        if (!strRes.equals(\"ok\")) throw new RuntimeException(\"ternary string failed\");\n" +
            "\n" +
            "        final zeroRes = 0 ? \"bad\" : \"good\";\n" +
            "        if (!zeroRes.equals(\"good\")) throw new RuntimeException(\"ternary zero failed\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("LoopsTernaryMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.LoopsTernaryMain");
    }

    @Test
    void testLogicalOperatorsTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "public class LogicalOpsMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // not / !\n" +
            "        if (not \"hello\") throw new RuntimeException(\"not 'hello' should be false\");\n" +
            "        if (!\"world\")   throw new RuntimeException(\"! 'world' should be false\");\n" +
            "        if (!not 0)     throw new RuntimeException(\"! not 0 should be false\");\n" +
            "\n" +
            "        // and / &&\n" +
            "        if (!(\"abc\" and 100)) throw new RuntimeException(\"'abc' and 100 should be true\");\n" +
            "        if (\"abc\" and 0)     throw new RuntimeException(\"'abc' and 0 should be false\");\n" +
            "        if (!(\"abc\" && 5))   throw new RuntimeException(\"'abc' && 5 should be true\");\n" +
            "\n" +
            "        // or / ||\n" +
            "        if (!(\"\" or 5))      throw new RuntimeException(\"'' or 5 should be true\");\n" +
            "        if (0 or \"\")         throw new RuntimeException(\"0 or '' should be false\");\n" +
            "        if (!(0 || \"foo\"))   throw new RuntimeException(\"0 || 'foo' should be true\");\n" +
            "\n" +
            "        // nand / nor / xor / xnor\n" +
            "        if (\"a\" nand \"b\")   throw new RuntimeException(\"'a' nand 'b' should be false\");\n" +
            "        if (!(\"\" nand \"b\")) throw new RuntimeException(\"'' nand 'b' should be true\");\n" +
            "\n" +
            "        if (\"a\" nor \"b\")    throw new RuntimeException(\"'a' nor 'b' should be false\");\n" +
            "        if (!(0 nor \"\"))    throw new RuntimeException(\"0 nor '' should be true\");\n" +
            "\n" +
            "        if (!(\"a\" xor 0))   throw new RuntimeException(\"'a' xor 0 should be true\");\n" +
            "        if (\"a\" xor \"b\")    throw new RuntimeException(\"'a' xor 'b' should be false\");\n" +
            "\n" +
            "        if (!(\"a\" xnor \"b\")) throw new RuntimeException(\"'a' xnor 'b' should be true\");\n" +
            "        if (\"a\" xnor 0)     throw new RuntimeException(\"'a' xnor 0 should be false\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("LogicalOpsMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.LogicalOpsMain");
    }

    @Test
    void testCustomGenericMapAndContainerTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "import java.util.HashMap;\n" +
            "public class CustomMapMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final emptyCustomMap = new CustomMap();\n" +
            "        if (emptyCustomMap) { throw new RuntimeException(\"empty custom map should be falsy\"); }\n" +
            "\n" +
            "        final nonEmptyCustomMap = new CustomMap();\n" +
            "        nonEmptyCustomMap.put(\"a\", 1);\n" +
            "        if (!nonEmptyCustomMap) { throw new RuntimeException(\"non-empty custom map should be truthy\"); }\n" +
            "\n" +
            "        final emptyContainer = new CustomContainer(true);\n" +
            "        if (emptyContainer) { throw new RuntimeException(\"empty custom container should be falsy\"); }\n" +
            "\n" +
            "        final nonEmptyContainer = new CustomContainer(false);\n" +
            "        if (!nonEmptyContainer) { throw new RuntimeException(\"non-empty custom container should be truthy\"); }\n" +
            "    }\n" +
            "}\n" +
            "class CustomMap extends HashMap {\n" +
            "    public Object put(Object key, Object value) {\n" +
            "        return super.put(key, value);\n" +
            "    }\n" +
            "}\n" +
            "class CustomContainer {\n" +
            "    private final boolean empty;\n" +
            "    public CustomContainer(boolean empty) { this.empty = empty; }\n" +
            "    public boolean isEmpty() { return this.empty; }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("CustomMapMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.CustomMapMain");
    }

    @Test
    void testQueueStackListTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "import java.util.ArrayList;\n" +
            "import java.util.LinkedList;\n" +
            "import java.util.Queue;\n" +
            "import java.util.Stack;\n" +
            "public class QueueStackListMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // List\n" +
            "        final emptyList = new ArrayList<String>();\n" +
            "        if (emptyList) { throw new RuntimeException(\"empty List should be falsy\"); }\n" +
            "\n" +
            "        final nonEmptyList = new ArrayList<String>();\n" +
            "        nonEmptyList.add(\"item\");\n" +
            "        if (!nonEmptyList) { throw new RuntimeException(\"non-empty List should be truthy\"); }\n" +
            "\n" +
            "        // Queue\n" +
            "        final Queue<String> emptyQueue = new LinkedList<String>();\n" +
            "        if (emptyQueue) { throw new RuntimeException(\"empty Queue should be falsy\"); }\n" +
            "\n" +
            "        final Queue<String> nonEmptyQueue = new LinkedList<String>();\n" +
            "        nonEmptyQueue.add(\"item\");\n" +
            "        if (!nonEmptyQueue) { throw new RuntimeException(\"non-empty Queue should be truthy\"); }\n" +
            "\n" +
            "        // Stack\n" +
            "        final emptyStack = new Stack<String>();\n" +
            "        if (emptyStack) { throw new RuntimeException(\"empty Stack should be falsy\"); }\n" +
            "\n" +
            "        final nonEmptyStack = new Stack<String>();\n" +
            "        nonEmptyStack.push(\"item\");\n" +
            "        if (!nonEmptyStack) { throw new RuntimeException(\"non-empty Stack should be truthy\"); }\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("QueueStackListMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.QueueStackListMain");
    }

    @Test
    void testExhaustiveContainersAndTypesTruthyFalsy() throws Exception {
        String code =
            "package test;\n" +
            "import java.util.*;\n" +
            "import java.util.concurrent.*;\n" +
            "public class ExhaustiveMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // 1. All Java Lists\n" +
            "        final emptyArrayList = new ArrayList<String>();\n" +
            "        if (emptyArrayList) throw new RuntimeException(\"empty ArrayList should be falsy\");\n" +
            "        final fullArrayList = new ArrayList<String>(); fullArrayList.add(\"x\");\n" +
            "        if (!fullArrayList) throw new RuntimeException(\"full ArrayList should be truthy\");\n" +
            "\n" +
            "        final emptyLinkedList = new LinkedList<String>();\n" +
            "        if (emptyLinkedList) throw new RuntimeException(\"empty LinkedList should be falsy\");\n" +
            "        final fullLinkedList = new LinkedList<String>(); fullLinkedList.add(\"x\");\n" +
            "        if (!fullLinkedList) throw new RuntimeException(\"full LinkedList should be truthy\");\n" +
            "\n" +
            "        final emptyCopyList = new CopyOnWriteArrayList<String>();\n" +
            "        if (emptyCopyList) throw new RuntimeException(\"empty CopyOnWriteArrayList should be falsy\");\n" +
            "        final fullCopyList = new CopyOnWriteArrayList<String>(); fullCopyList.add(\"x\");\n" +
            "        if (!fullCopyList) throw new RuntimeException(\"full CopyOnWriteArrayList should be truthy\");\n" +
            "\n" +
            "        // 2. All Java Sets\n" +
            "        final emptyHashSet = new HashSet<String>();\n" +
            "        if (emptyHashSet) throw new RuntimeException(\"empty HashSet should be falsy\");\n" +
            "        final fullHashSet = new HashSet<String>(); fullHashSet.add(\"x\");\n" +
            "        if (!fullHashSet) throw new RuntimeException(\"full HashSet should be truthy\");\n" +
            "\n" +
            "        final emptyTreeSet = new TreeSet<String>();\n" +
            "        if (emptyTreeSet) throw new RuntimeException(\"empty TreeSet should be falsy\");\n" +
            "        final fullTreeSet = new TreeSet<String>(); fullTreeSet.add(\"x\");\n" +
            "        if (!fullTreeSet) throw new RuntimeException(\"full TreeSet should be truthy\");\n" +
            "\n" +
            "        final Set<String> emptyLinkedSet = new LinkedHashSet<String>();\n" +
            "        if (emptyLinkedSet) throw new RuntimeException(\"empty LinkedHashSet should be falsy\");\n" +
            "        final Set<String> fullLinkedSet = new LinkedHashSet<String>(); fullLinkedSet.add(\"x\");\n" +
            "        if (!fullLinkedSet) throw new RuntimeException(\"full LinkedHashSet should be truthy\");\n" +
            "\n" +
            "        final Set<String> emptySkipSet = new ConcurrentSkipListSet<String>();\n" +
            "        if (emptySkipSet) throw new RuntimeException(\"empty ConcurrentSkipListSet should be falsy\");\n" +
            "        final Set<String> fullSkipSet = new ConcurrentSkipListSet<String>(); fullSkipSet.add(\"x\");\n" +
            "        if (!fullSkipSet) throw new RuntimeException(\"full ConcurrentSkipListSet should be truthy\");\n" +
            "\n" +
            "        // 3. All Java Maps\n" +
            "        final Map<String, String> emptyHashMap = new HashMap<String, String>();\n" +
            "        if (emptyHashMap) throw new RuntimeException(\"empty HashMap should be falsy\");\n" +
            "        final Map<String, String> fullHashMap = new HashMap<String, String>(); fullHashMap.put(\"a\", \"b\");\n" +
            "        if (!fullHashMap) throw new RuntimeException(\"full HashMap should be truthy\");\n" +
            "\n" +
            "        final Map<String, String> emptyTreeMap = new TreeMap<String, String>();\n" +
            "        if (emptyTreeMap) throw new RuntimeException(\"empty TreeMap should be falsy\");\n" +
            "        final Map<String, String> fullTreeMap = new TreeMap<String, String>(); fullTreeMap.put(\"a\", \"b\");\n" +
            "        if (!fullTreeMap) throw new RuntimeException(\"full TreeMap should be truthy\");\n" +
            "\n" +
            "        final Map<String, String> emptyLinkedMap = new LinkedHashMap<String, String>();\n" +
            "        if (emptyLinkedMap) throw new RuntimeException(\"empty LinkedHashMap should be falsy\");\n" +
            "        final Map<String, String> fullLinkedMap = new LinkedHashMap<String, String>(); fullLinkedMap.put(\"a\", \"b\");\n" +
            "        if (!fullLinkedMap) throw new RuntimeException(\"full LinkedHashMap should be truthy\");\n" +
            "\n" +
            "        final Map<String, String> emptyConcurrentMap = new ConcurrentHashMap<String, String>();\n" +
            "        if (emptyConcurrentMap) throw new RuntimeException(\"empty ConcurrentHashMap should be falsy\");\n" +
            "        final Map<String, String> fullConcurrentMap = new ConcurrentHashMap<String, String>(); fullConcurrentMap.put(\"a\", \"b\");\n" +
            "        if (!fullConcurrentMap) throw new RuntimeException(\"full ConcurrentHashMap should be truthy\");\n" +
            "\n" +
            "        final Map<String, String> emptyHashtable = new Hashtable<String, String>();\n" +
            "        if (emptyHashtable) throw new RuntimeException(\"empty Hashtable should be falsy\");\n" +
            "        final Map<String, String> fullHashtable = new Hashtable<String, String>(); fullHashtable.put(\"a\", \"b\");\n" +
            "        if (!fullHashtable) throw new RuntimeException(\"full Hashtable should be truthy\");\n" +
            "\n" +
            "        // 4. Queues & Deques\n" +
            "        final Queue<String> emptyDeque = new ArrayDeque<String>();\n" +
            "        if (emptyDeque) throw new RuntimeException(\"empty ArrayDeque should be falsy\");\n" +
            "        final Queue<String> fullDeque = new ArrayDeque<String>(); fullDeque.add(\"x\");\n" +
            "        if (!fullDeque) throw new RuntimeException(\"full ArrayDeque should be truthy\");\n" +
            "\n" +
            "        final Queue<String> emptyPriorityQueue = new PriorityQueue<String>();\n" +
            "        if (emptyPriorityQueue) throw new RuntimeException(\"empty PriorityQueue should be falsy\");\n" +
            "        final Queue<String> fullPriorityQueue = new PriorityQueue<String>(); fullPriorityQueue.add(\"x\");\n" +
            "        if (!fullPriorityQueue) throw new RuntimeException(\"full PriorityQueue should be truthy\");\n" +
            "\n" +
            "        final Queue<String> emptyBlockingQueue = new LinkedBlockingQueue<String>();\n" +
            "        if (emptyBlockingQueue) throw new RuntimeException(\"empty LinkedBlockingQueue should be falsy\");\n" +
            "        final Queue<String> fullBlockingQueue = new LinkedBlockingQueue<String>(); fullBlockingQueue.add(\"x\");\n" +
            "        if (!fullBlockingQueue) throw new RuntimeException(\"full LinkedBlockingQueue should be truthy\");\n" +
            "\n" +
            "        // 5. Arrays (Primitive & Object)\n" +
            "        final emptyIntArr = new int[](0);\n" +
            "        if (emptyIntArr) throw new RuntimeException(\"empty int array should be falsy\");\n" +
            "        final fullIntArr = new int[](2);\n" +
            "        if (!fullIntArr) throw new RuntimeException(\"full int array should be truthy\");\n" +
            "\n" +
            "        final emptyStrArr = new String[](0);\n" +
            "        if (emptyStrArr) throw new RuntimeException(\"empty String array should be falsy\");\n" +
            "        final fullStrArr = new String[](1);\n" +
            "        if (!fullStrArr) throw new RuntimeException(\"full String array should be truthy\");\n" +
            "\n" +
            "        // 6. CharSequence & Characters\n" +
            "        final emptySb = new StringBuilder(\"\");\n" +
            "        if (emptySb) throw new RuntimeException(\"empty StringBuilder should be falsy\");\n" +
            "        final fullSb = new StringBuilder(\"data\");\n" +
            "        if (!fullSb) throw new RuntimeException(\"full StringBuilder should be truthy\");\n" +
            "\n" +
            "        final Character nullChar = Character.valueOf(Character.MIN_VALUE);\n" +
            "        if (nullChar) throw new RuntimeException(\"null character Character.MIN_VALUE should be falsy\");\n" +
            "        final Character validChar = Character.valueOf(Character.MAX_VALUE);\n" +
            "        if (!validChar) throw new RuntimeException(\"character Character.MAX_VALUE should be truthy\");\n" +
            "\n" +
            "        // 7. Edge Cases: Nested containers & collections containing null/zero\n" +
            "        final nestedList = List.of(List.of());\n" +
            "        if (!nestedList) throw new RuntimeException(\"List containing empty list should be truthy (outer list has 1 element)\");\n" +
            "\n" +
            "        final nullContainingList = Collections.singletonList(null);\n" +
            "        if (!nullContainingList) throw new RuntimeException(\"List containing null element should be truthy (size 1)\");\n" +
            "\n" +
            "        final zeroContainingList = List.of(0);\n" +
            "        if (!zeroContainingList) throw new RuntimeException(\"List containing 0 should be truthy (size 1)\");\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("ExhaustiveMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.ExhaustiveMain");
    }

    @Test
    void testIfElseAndForLoopTruthyFalsyForListsAndMaps() throws Exception {
        String code =
            "package test;\n" +
            "import java.util.ArrayList;\n" +
            "import java.util.HashMap;\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "public class IfElseForMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        final emptyList = List.of();\n" +
            "        final nonEmptyList = List.of(\"item1\", \"item2\");\n" +
            "        final emptyMap = Map.of();\n" +
            "        final nonEmptyMap = Map.of(\"k1\", \"v1\");\n" +
            "\n" +
            "        // 1. if / else with empty list -> MUST go to else branch\n" +
            "        boolean emptyListElseRan = false;\n" +
            "        if (emptyList) {\n" +
            "            throw new RuntimeException(\"emptyList should not enter then branch\");\n" +
            "        } else {\n" +
            "            emptyListElseRan = true;\n" +
            "        }\n" +
            "        if (!emptyListElseRan) throw new RuntimeException(\"emptyList failed to enter else branch\");\n" +
            "\n" +
            "        // 2. if / else with non-empty list -> MUST go to then branch\n" +
            "        boolean nonEmptyListThenRan = false;\n" +
            "        if (nonEmptyList) {\n" +
            "            nonEmptyListThenRan = true;\n" +
            "        } else {\n" +
            "            throw new RuntimeException(\"nonEmptyList should not enter else branch\");\n" +
            "        }\n" +
            "        if (!nonEmptyListThenRan) throw new RuntimeException(\"nonEmptyList failed to enter then branch\");\n" +
            "\n" +
            "        // 3. if / else with empty map -> MUST go to else branch\n" +
            "        boolean emptyMapElseRan = false;\n" +
            "        if (emptyMap) {\n" +
            "            throw new RuntimeException(\"emptyMap should not enter then branch\");\n" +
            "        } else {\n" +
            "            emptyMapElseRan = true;\n" +
            "        }\n" +
            "        if (!emptyMapElseRan) throw new RuntimeException(\"emptyMap failed to enter else branch\");\n" +
            "\n" +
            "        // 4. if / else with non-empty map -> MUST go to then branch\n" +
            "        boolean nonEmptyMapThenRan = false;\n" +
            "        if (nonEmptyMap) {\n" +
            "            nonEmptyMapThenRan = true;\n" +
            "        } else {\n" +
            "            throw new RuntimeException(\"nonEmptyMap should not enter else branch\");\n" +
            "        }\n" +
            "        if (!nonEmptyMapThenRan) throw new RuntimeException(\"nonEmptyMap failed to enter then branch\");\n" +
            "\n" +
            "        // 5. for loop condition with List (drain list until empty)\n" +
            "        var listToDrain = new ArrayList<String>(List.of(\"a\", \"b\", \"c\"));\n" +
            "        int listLoopRuns = 0;\n" +
            "        for (; listToDrain; ) {\n" +
            "            listLoopRuns++;\n" +
            "            listToDrain.remove(0);\n" +
            "        }\n" +
            "        if (listLoopRuns != 3) throw new RuntimeException(\"for loop with List condition expected 3 runs, got \" + listLoopRuns);\n" +
            "\n" +
            "        // 6. for loop condition with Map (drain map until empty)\n" +
            "        var mapToDrain = new HashMap<String, String>(Map.of(\"k1\", \"v1\", \"k2\", \"v2\"));\n" +
            "        int mapLoopRuns = 0;\n" +
            "        for (; mapToDrain; ) {\n" +
            "            mapLoopRuns++;\n" +
            "            var firstKey = mapToDrain.keySet().iterator().next();\n" +
            "            mapToDrain.remove(firstKey);\n" +
            "        }\n" +
            "        if (mapLoopRuns != 2) throw new RuntimeException(\"for loop with Map condition expected 2 runs, got \" + mapLoopRuns);\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("IfElseForMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.IfElseForMain");
    }

    @Test
    void testForElseStatementsAndExpressions() throws Exception {
        String code =
            "package test;\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "public class ForElseMain {\n" +
            "    public static void main(String[] args) {\n" +
            "        // 1. Enhanced for/else with non-empty list (no break)\n" +
            "        final list1 = List.of(\"a\", \"b\");\n" +
            "        boolean else1Ran = false;\n" +
            "        int count1 = 0;\n" +
            "        for (var item : list1) {\n" +
            "            count1++;\n" +
            "        } else {\n" +
            "            else1Ran = true;\n" +
            "        }\n" +
            "        if (count1 != 2) throw new RuntimeException(\"Expected count1 == 2, got \" + count1);\n" +
            "        if (!else1Ran) throw new RuntimeException(\"For/else without break should execute else branch\");\n" +
            "\n" +
            "        // 2. Enhanced for/else with non-empty list (with break)\n" +
            "        boolean else2Ran = false;\n" +
            "        int count2 = 0;\n" +
            "        for (var item : list1) {\n" +
            "            count2++;\n" +
            "            if (item.equals(\"a\")) break;\n" +
            "        } else {\n" +
            "            else2Ran = true;\n" +
            "        }\n" +
            "        if (count2 != 1) throw new RuntimeException(\"Expected count2 == 1, got \" + count2);\n" +
            "        if (else2Ran) throw new RuntimeException(\"For/else with break should NOT execute else branch\");\n" +
            "\n" +
            "        // 3. Enhanced for/else with empty list\n" +
            "        final emptyList = List.of();\n" +
            "        boolean else3Ran = false;\n" +
            "        int count3 = 0;\n" +
            "        for (var item : emptyList) {\n" +
            "            count3++;\n" +
            "        } else {\n" +
            "            else3Ran = true;\n" +
            "        }\n" +
            "        if (count3 != 0) throw new RuntimeException(\"Expected count3 == 0, got \" + count3);\n" +
            "        if (!else3Ran) throw new RuntimeException(\"For/else with empty list should execute else branch\");\n" +
            "\n" +
            "        // 4. Enhanced for/else with null iterable\n" +
            "        final List nullList = null;\n" +
            "        boolean else4Ran = false;\n" +
            "        for (var item : nullList) {\n" +
            "            throw new RuntimeException(\"Should not run loop body for null\");\n" +
            "        } else {\n" +
            "            else4Ran = true;\n" +
            "        }\n" +
            "        if (!else4Ran) throw new RuntimeException(\"For/else with null iterable should execute else branch\");\n" +
            "\n" +
            "        // 5. Standard for/else loop\n" +
            "        boolean else5Ran = false;\n" +
            "        int sum5 = 0;\n" +
            "        for (var i = 0; i < 3; i++) {\n" +
            "            sum5 = sum5 + i;\n" +
            "        } else {\n" +
            "            else5Ran = true;\n" +
            "        }\n" +
            "        if (sum5 != 3 || !else5Ran) throw new RuntimeException(\"Standard for/else without break failed\");\n" +
            "\n" +
            "        boolean else6Ran = false;\n" +
            "        for (var i = 0; i < 3; i++) {\n" +
            "            if (i == 1) break;\n" +
            "        } else {\n" +
            "            else6Ran = true;\n" +
            "        }\n" +
            "        if (else6Ran) throw new RuntimeException(\"Standard for/else with break should NOT execute else branch\");\n" +
            "\n" +
            "        // 6. for/else loop expression yielding values\n" +
            "        final res1 = for (var item : emptyList) { yield item; } else { yield \"fallback\"; };\n" +
            "        if (!res1.equals(List.of(\"fallback\"))) throw new RuntimeException(\"Loop expression for empty list expected List.of('fallback'), got \" + res1);\n" +
            "    }\n" +
            "}\n";

        Path tempDir = createTempDir();
        Path srcDir = tempDir.resolve("src");
        Path binDir = tempDir.resolve("bin");
        Path genDir = tempDir.resolve("gen");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("ForElseMain.accent"), code, StandardCharsets.UTF_8);

        CompilationResult result = compile(srcDir, binDir, genDir);
        assertTrue(result.successful(), "Compilation failed: " + result.diagnostics());
        runClass(binDir, "test.ForElseMain");
    }
}
