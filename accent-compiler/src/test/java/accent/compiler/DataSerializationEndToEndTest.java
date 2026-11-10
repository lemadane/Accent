package accent.compiler;

import accent.json.internal.JsonParser;
import accent.runtime.AccentModel;
import accent.source.SourceFile;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles real Accent sources, then executes their native serialization calls. */
class DataSerializationEndToEndTest {
    @TempDir static Path tempDir;
    private static URLClassLoader loader;
    private static Class<?> harness;
    private static Path output;
    private static String runtimeClasspath;

    @BeforeAll
    static void compileFixtures() throws Exception {
        Path sources = Files.createDirectory(tempDir.resolve("src"));
        runtimeClasspath = Path.of(AccentModel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("PersonRecord", "record PersonRecord(String name) {}");
        fixtures.put("PersonModel", "model PersonModel(String name) {}");
        fixtures.put("Payload", """
                import java.util.*;
                record Payload(String text, boolean active, int count, double ratio,
                        char initial, int[] numbers, String missing, PersonModel owner,
                        List<PersonRecord> people, Map<String, PersonModel> indexed) {}
                """);
        fixtures.put("Node", "model Node(Object value) {}");
        fixtures.put("Box", "record Box(Object value) {}");
        fixtures.put("EmptyRecord", "record EmptyRecord() {}");
        fixtures.put("GenericRecord", "record GenericRecord<T>(T value) {}");
        fixtures.put("GenericModel", "model GenericModel<T>(T value) {}");
        fixtures.put("OverloadedModel", """
                model OverloadedModel(String name) {
                    String toJson(String prefix) { return prefix; }
                    String toXml(String prefix) { return prefix; }
                }
                """);
        fixtures.put("CustomRecord", """
                record CustomRecord(String name) {
                    String toJson() { return "record custom"; }
                }
                """);
        fixtures.put("CustomModel", """
                model CustomModel(String name) {
                    String toXml() { return "model custom"; }
                }
                """);
        StringBuilder calls = new StringBuilder("import java.util.*; class Harness {\n");
        for (String kind : List.of("Record", "Model")) {
            for (String format : List.of("Json", "Xml")) {
                String type = "Person" + kind;
                String method = "to" + format;
                calls.append("static String direct").append(kind).append(format)
                        .append('(').append(type).append(" value) { return value.").append(method).append("!(); }\n");
                for (String container : List.of("List", "Map")) {
                    String generic = container.equals("List") ? type : "String, " + type;
                    for (String optional : List.of("", "Optional")) {
                        calls.append("static String ").append(container.toLowerCase(Locale.ROOT))
                                .append(kind).append(format).append(optional).append('(')
                                .append(container).append('<').append(generic).append("> value) { return value")
                                .append(optional.isEmpty() ? "." : "?.").append(method).append("!(); }\n");
                    }
                }
            }
        }
        calls.append("""
                static String arrayList(ArrayList<PersonRecord> value) { return value.toJson!(); }
                static String linkedList(LinkedList<PersonModel> value) { return value.toXml!(); }
                static String hashMap(HashMap<String, PersonRecord> value) { return value.toJson!(); }
                static String treeMap(TreeMap<String, PersonModel> value) { return value.toXml!(); }
                static String fallback(List<PersonRecord> value) { return value?.toJson!() ?? "absent"; }
                static int chained(Map<String, PersonModel> value) { return value.toJson!().length(); }
                static int calls = 0;
                static List<PersonRecord> next() {
                    Harness.calls++;
                    List<PersonRecord> result = List.of(new PersonRecord("once"));
                    return result;
                }
                static String once() { return Harness.next().toJson!(); }
                static String payload(Payload value) { return value.toJson!(); }
                static String payloadXml(Payload value) { return value.toXml!(); }
                static String nodeJson(Node value) { return value.toJson!(); }
                static String nodeXml(Node value) { return value.toXml!(); }
                static String boxJson(Box value) { return value.toJson!(); }
                static String boxXml(Box value) { return value.toXml!(); }
                static String nodeList(List<Node> value) { return value.toJson!(); }
                static String nodeMap(Map<String, Node> value) { return value.toXml!(); }
                static void main(String[] args) {
                    List<PersonRecord> people = List.of(new PersonRecord("Lem"));
                    Map<String, PersonModel> indexed = Map.of("U1", new PersonModel("Ana"));
                    System.out.println(people.toJson!());
                    System.out.println(indexed.toXml!());
                }
                }
                """);
        fixtures.put("Harness", calls.toString());
        for (var fixture : fixtures.entrySet()) {
            Files.writeString(sources.resolve(fixture.getKey() + ".accent"), fixture.getValue());
        }
        output = tempDir.resolve("classes");
        var result = new AccentCompiler().compile(List.of(sources), output, List.of(runtimeClasspath),
                tempDir.resolve("generated"), true);
        assertTrue(result.successful(), () -> "Fixture compilation failed: " + result.diagnostics());
        loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()});
        harness = loader.loadClass("Harness");
    }

    @AfterAll
    static void closeLoader() throws Exception {
        if (loader != null) loader.close();
    }

    private static Object person(String kind, String name) throws Exception {
        return loader.loadClass("Person" + kind).getConstructor(String.class).newInstance(name);
    }

    private static Object call(String name, Class<?> parameter, Object value) throws Exception {
        return harness.getMethod(name, parameter).invoke(null, value);
    }

    private static Document xml(String text) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @TestFactory
    Stream<DynamicTest> recordAndModelContainerMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String kind : List.of("Record", "Model")) {
            for (String format : List.of("Json", "Xml")) {
                for (String container : List.of("List", "Map")) {
                    for (String scenario : List.of("populated", "empty", "nullable entries", "optional null", "optional populated")) {
                        tests.add(DynamicTest.dynamicTest(kind + " " + container + " " + format + " " + scenario, () -> {
                            Object first = person(kind, "Lem");
                            Object second = person(kind, "Ana");
                            boolean list = container.equals("List");
                            Object value;
                            if (scenario.equals("empty")) value = list ? List.of() : Map.of();
                            else if (scenario.equals("optional null")) value = null;
                            else if (list) value = scenario.equals("nullable entries")
                                    ? Arrays.asList(first, null, second) : List.of(first, second);
                            else {
                                Map<String, Object> map = new LinkedHashMap<>();
                                map.put("U1", first);
                                if (scenario.equals("nullable entries")) map.put("missing", null);
                                map.put("U2", second);
                                value = map;
                            }
                            String method = container.toLowerCase(Locale.ROOT) + kind + format
                                    + (scenario.startsWith("optional") ? "Optional" : "");
                            String actual = (String) call(method, list ? List.class : Map.class, value);
                            if (value == null) { assertNull(actual); return; }
                            boolean empty = scenario.equals("empty");
                            boolean nullable = scenario.equals("nullable entries");
                            if (format.equals("Json")) {
                                String expected = list
                                        ? empty ? "[]" : "[{\"name\":\"Lem\"}," + (nullable ? "null," : "") + "{\"name\":\"Ana\"}]"
                                        : empty ? "{}" : "{\"U1\":{\"name\":\"Lem\"}," + (nullable ? "\"missing\":null," : "") + "\"U2\":{\"name\":\"Ana\"}}";
                                assertEquals(expected, actual);
                                assertNotNull(JsonParser.parse(actual));
                            } else {
                                String body = list ? "<item><name>Lem</name></item><item><name>Ana</name></item>"
                                        : "<entry><key>U1</key><value><name>Lem</name></value></entry>"
                                        + (nullable ? "<entry><key>missing</key></entry>" : "")
                                        + "<entry><key>U2</key><value><name>Ana</name></value></entry>";
                                assertEquals("<" + container + ">" + (empty ? "" : body) + "</" + container + ">", actual);
                                assertEquals(container, xml(actual).getDocumentElement().getTagName());
                            }
                        }));
                    }
                }
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> directMethodsEscapeAndRoundTripText() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String kind : List.of("Record", "Model")) {
            for (String text : List.of("", "normal", "<&>\"'\\", "line\nnext\ttab", "Manila 日本 😀")) {
                tests.add(DynamicTest.dynamicTest(kind + " text " + text, () -> {
                    Object value = person(kind, text);
                    Class<?> type = value.getClass();
                    String json = (String) call("direct" + kind + "Json", type, value);
                    assertEquals(Map.of("name", text), JsonParser.parse(json));
                    String serializedXml = (String) call("direct" + kind + "Xml", type, value);
                    assertEquals(text, xml(serializedXml).getElementsByTagName("name").item(0).getTextContent());
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void nestedDataPreservesScalarTypesAndOmitsNullProperties() throws Exception {
        Object owner = person("Model", "Ana");
        Object record = person("Record", "Lem");
        Class<?> payloadClass = loader.loadClass("Payload");
        Object payload = payloadClass.getConstructor(String.class, boolean.class, int.class, double.class,
                char.class, int[].class, String.class, owner.getClass(), List.class, Map.class)
                .newInstance("<&>", false, 0, 1.25, 'Z', new int[]{0, -2}, null, owner,
                        List.of(record), Map.of("owner", owner));
        String json = (String) call("payload", payloadClass, payload);
        Map<?, ?> parsed = (Map<?, ?>) JsonParser.parse(json);
        assertEquals(false, parsed.get("active"));
        assertEquals("0", parsed.get("count").toString());
        assertEquals("1.25", parsed.get("ratio").toString());
        assertEquals("Z", parsed.get("initial"));
        assertFalse(parsed.containsKey("missing"));
        assertEquals(Map.of("name", "Ana"), parsed.get("owner"));
        assertEquals(List.of(Map.of("name", "Lem")), parsed.get("people"));
        assertEquals(Map.of("owner", Map.of("name", "Ana")), parsed.get("indexed"));
        Document document = xml((String) call("payloadXml", payloadClass, payload));
        assertEquals(0, document.getElementsByTagName("missing").getLength());
        assertEquals("false", document.getElementsByTagName("active").item(0).getTextContent());
        assertEquals("0", document.getElementsByTagName("count").item(0).getTextContent());
        assertEquals(3, document.getElementsByTagName("name").getLength());
        owner.getClass().getMethod("name", String.class).invoke(owner, "Updated");
        Map<?, ?> updated = (Map<?, ?>) JsonParser.parse((String) call("payload", payloadClass, payload));
        assertEquals(Map.of("name", "Updated"), updated.get("owner"));
    }

    @Test
    void concreteContainersChainingFallbackAndSingleEvaluation() throws Exception {
        Object record = person("Record", "Lem");
        Object model = person("Model", "Ana");
        assertEquals("[{\"name\":\"Lem\"}]", call("arrayList", ArrayList.class, new ArrayList<>(List.of(record))));
        assertEquals("<List><item><name>Ana</name></item></List>", call("linkedList", LinkedList.class, new LinkedList<>(List.of(model))));
        assertEquals("{\"U1\":{\"name\":\"Lem\"}}", call("hashMap", HashMap.class, new HashMap<>(Map.of("U1", record))));
        assertEquals("<Map><entry><key>U1</key><value><name>Ana</name></value></entry></Map>",
                call("treeMap", TreeMap.class, new TreeMap<>(Map.of("U1", model))));
        assertEquals("absent", call("fallback", List.class, null));
        assertEquals("[]", call("fallback", List.class, List.of()));
        assertEquals("{\"U1\":{\"name\":\"Ana\"}}".length(), call("chained", Map.class, Map.of("U1", model)));
        harness.getField("calls").setInt(null, 0);
        assertEquals("[{\"name\":\"once\"}]", harness.getMethod("once").invoke(null));
        assertEquals(1, harness.getField("calls").getInt(null));
    }

    @Test
    void genericTypesEmptyRecordsAndCustomMethods() throws Exception {
        for (String typeName : List.of("GenericRecord", "GenericModel")) {
            Class<?> type = loader.loadClass(typeName);
            Object value = type.getConstructor(Object.class).newInstance(person("Record", "Lem"));
            assertEquals("{\"value\":{\"name\":\"Lem\"}}", type.getMethod("toJson").invoke(value));
            assertEquals(typeName, xml((String) type.getMethod("toXml").invoke(value)).getDocumentElement().getTagName());
        }
        Class<?> overloaded = loader.loadClass("OverloadedModel");
        Object overloadedValue = overloaded.getConstructor(String.class).newInstance("Lem");
        assertEquals("{\"name\":\"Lem\"}", overloaded.getMethod("toJson").invoke(overloadedValue));
        assertEquals("<OverloadedModel><name>Lem</name></OverloadedModel>", overloaded.getMethod("toXml").invoke(overloadedValue));
        assertEquals("prefix", overloaded.getMethod("toJson", String.class).invoke(overloadedValue, "prefix"));
        assertEquals("prefix", overloaded.getMethod("toXml", String.class).invoke(overloadedValue, "prefix"));
        Class<?> empty = loader.loadClass("EmptyRecord");
        Object emptyValue = empty.getConstructor().newInstance();
        assertEquals("{}", empty.getMethod("toJson").invoke(emptyValue));
        assertEquals("<EmptyRecord></EmptyRecord>", empty.getMethod("toXml").invoke(emptyValue));
        Class<?> customRecord = loader.loadClass("CustomRecord");
        Object record = customRecord.getConstructor(String.class).newInstance("Lem");
        assertEquals("record custom", customRecord.getMethod("toJson").invoke(record));
        assertEquals("<CustomRecord><name>Lem</name></CustomRecord>", customRecord.getMethod("toXml").invoke(record));
        Class<?> customModel = loader.loadClass("CustomModel");
        Object model = customModel.getConstructor(String.class).newInstance("Ana");
        assertEquals("model custom", customModel.getMethod("toXml").invoke(model));
        assertEquals("{\"name\":\"Ana\"}", customModel.getMethod("toJson").invoke(model));
    }

    @Test
    void nullFieldsAreOmittedAndNullOnlyDataRemainsValid() throws Exception {
        for (String kind : List.of("Record", "Model")) {
            Object value = person(kind, null);
            assertEquals("{}", call("direct" + kind + "Json", value.getClass(), value));
            assertEquals("<Person" + kind + "></Person" + kind + ">",
                    call("direct" + kind + "Xml", value.getClass(), value));
        }
    }

    @TestFactory
    Stream<DynamicTest> rejectsArgumentsTypeArgumentsAndUnrelatedReceivers() {
        return List.of("items.toJson!(1)", "items.toXml!(1)", "items.<String>toJson()", "items.<String>toXml()",
                "indexed.toJson!(1)", "indexed.toXml!(1)", "text.toJson!()", "text.toXml!()", "List.toJson!()", "Map.toXml!()")
                .stream().map(expression -> DynamicTest.dynamicTest("reject " + expression, () -> {
                    var source = new SourceFile(Path.of("Invalid.accent"),
                            "import java.util.*; class Invalid { "
                                    + "String run(List<Person> items, Map<String, Person> indexed, String text) { return "
                                    + expression + "; } }");
                    var result = new AccentCompiler().check(List.of(
                            new SourceFile(Path.of("Person.accent"), "record Person(String name) {}"), source));
                    assertFalse(result.successful(), expression);
                    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Cannot resolve method")),
                            () -> result.diagnostics().toString());
                }));
    }

    @Test
    void generatedMethodsRejectCyclesAndRecoverOnLaterCalls() throws Exception {
        Class<?> nodeType = loader.loadClass("Node");
        Object node = nodeType.getConstructor(Object.class).newInstance(new Object[]{null});
        nodeType.getMethod("value", Object.class).invoke(node, node);
        for (String format : List.of("Json", "Xml")) {
            var error = assertThrows(InvocationTargetException.class, () -> call("node" + format, nodeType, node));
            assertTrue(hasMessage(error, "Circular reference"));
        }
        var listError = assertThrows(InvocationTargetException.class, () -> call("nodeList", List.class, List.of(node)));
        assertTrue(hasMessage(listError, "Circular reference"));
        var mapError = assertThrows(InvocationTargetException.class, () -> call("nodeMap", Map.class, Map.of("cycle", node)));
        assertTrue(hasMessage(mapError, "Circular reference"));
        nodeType.getMethod("value", Object.class).invoke(node, "recovered");
        assertEquals("{\"value\":\"recovered\"}", call("nodeJson", nodeType, node));
        assertEquals("<Node><value>recovered</value></Node>", call("nodeXml", nodeType, node));
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        Class<?> boxType = loader.loadClass("Box");
        Object box = boxType.getConstructor(Object.class).newInstance(cycle);
        for (String format : List.of("Json", "Xml")) {
            var error = assertThrows(InvocationTargetException.class, () -> call("box" + format, boxType, box));
            assertTrue(hasMessage(error, "Circular reference"));
        }
    }

    private static boolean hasMessage(Throwable error, String message) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(message)) return true;
        }
        return false;
    }

    @Test
    void generatedMethodsRejectInvalidXmlAndNonFiniteJson() throws Exception {
        Class<?> boxType = loader.loadClass("Box");
        for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            Object box = boxType.getConstructor(Object.class).newInstance(invalid);
            var error = assertThrows(InvocationTargetException.class, () -> call("boxJson", boxType, box));
            assertTrue(hasMessage(error, "Non-finite"));
        }
        Object invalidXml = boxType.getConstructor(Object.class).newInstance("\u0000");
        var error = assertThrows(InvocationTargetException.class, () -> call("boxXml", boxType, invalidXml));
        assertTrue(hasMessage(error, "Invalid XML character"));
        Object control = boxType.getConstructor(Object.class).newInstance("\b\f\r\t");
        assertEquals(Map.of("value", "\b\f\r\t"), JsonParser.parse((String) call("boxJson", boxType, control)));
    }

    @TestFactory
    Stream<DynamicTest> rejectsUnsupportedTypesAtCompilation() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String container : List.of("List<String>", "List<Integer>", "List<Object>", "List",
                "Map<String, String>", "Map<String, Integer>", "Map<String, Object>", "Map",
                "List<List<PersonRecord>>", "Map<String, List<PersonModel>>", "List<Plain>", "Map<String, Plain>",
                "Set<PersonRecord>", "PersonRecord[]", "List<PersonRecord[]>")) {
            for (String format : List.of("Json", "Xml")) {
                tests.add(DynamicTest.dynamicTest("reject " + container + ".to" + format, () -> {
                    String source = "import java.util.*; "
                            + "class Invalid { String run(" + container + " value) { return value.to" + format + "(); } }";
                    var result = new AccentCompiler().check(List.of(
                            new SourceFile(Path.of("PersonRecord.accent"), "record PersonRecord(String name) {}"),
                            new SourceFile(Path.of("PersonModel.accent"), "model PersonModel(String name) {}"),
                            new SourceFile(Path.of("Plain.accent"), "class Plain {}"),
                            new SourceFile(Path.of("Invalid.accent"), source)));
                    assertFalse(result.successful());
                    assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Cannot resolve method 'to" + format + "'")),
                            () -> "Expected a method resolution error, got " + result.diagnostics());
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void serializesRecordAndModelTypesLoadedFromDependencyClasspath() throws Exception {
        Path source = tempDir.resolve("DependencyCalls.accent");
        Files.writeString(source, """
                import java.util.*;
                class DependencyCalls {
                    static String recordValue(PersonRecord value) { return value.toJson!(); }
                    static String modelValue(PersonModel value) { return value.toXml!(); }
                    static String records(List<PersonRecord> values) { return values.toJson!(); }
                    static String models(Map<String, PersonModel> values) { return values.toXml!(); }
                }
                """);
        Path dependencyOutput = tempDir.resolve("dependency-consumer");
        var result = new AccentCompiler().compile(List.of(source), dependencyOutput,
                List.of(runtimeClasspath, output.toString()), tempDir.resolve("dependency-generated"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var consumerLoader = new URLClassLoader(new java.net.URL[]{dependencyOutput.toUri().toURL()}, loader)) {
            Class<?> consumer = consumerLoader.loadClass("DependencyCalls");
            Object record = person("Record", "Lem");
            Object model = person("Model", "Ana");
            assertEquals("{\"name\":\"Lem\"}", consumer.getMethod("recordValue", record.getClass()).invoke(null, record));
            assertEquals("<PersonModel><name>Ana</name></PersonModel>", consumer.getMethod("modelValue", model.getClass()).invoke(null, model));
            assertEquals("[{\"name\":\"Lem\"}]", consumer.getMethod("records", List.class).invoke(null, List.of(record)));
            assertEquals("<Map><entry><key>U1</key><value><name>Ana</name></value></entry></Map>",
                    consumer.getMethod("models", Map.class).invoke(null, Map.of("U1", model)));
        }
    }

    @Test
    void compiledAccentProgramRunsInSeparateJvm() throws Exception {
        Path log = tempDir.resolve("program-output.txt");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", output + java.io.File.pathSeparator + runtimeClasspath, "Harness")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Compiled Accent program timed out");
            assertEquals(0, process.exitValue(), () -> readLog(log));
            assertEquals(List.of("[{\"name\":\"Lem\"}]", "<Map><entry><key>U1</key><value><name>Ana</name></value></entry></Map>"),
                    Files.readAllLines(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readLog(Path log) {
        try { return Files.readString(log); }
        catch (java.io.IOException e) { return e.toString(); }
    }
}
