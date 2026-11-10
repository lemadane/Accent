package accent.compiler;

import accent.json.Json;
import accent.json.JsonParseException;
import accent.xml.Xml;
import accent.xml.XmlParseException;
import accent.runtime.AccentModel;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DataParsingEndToEndTest {
    @TempDir static Path tempDir;
    private static URLClassLoader loader;
    private static Class<?> calls;
    private static Path output;
    private static String runtimeClasspath;

    @BeforeAll
    static void compileProgram() throws Exception {
        Path sources = Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(sources.resolve("Item.accent"), "record Item(String name?)");
        Files.writeString(sources.resolve("Person.accent"), "model Person(String name?)");
        Files.writeString(sources.resolve("Envelope.accent"), """
                import java.util.*;
                record Envelope(List<Item> items, Map<String, Person> people, int[] numbers, char initial)
                """);
        Files.writeString(sources.resolve("Parsing.accent"), """
                import accent.json.Json;
                import accent.xml.Xml;
                import java.util.*;
                class Parsing {
                    static Object json?(String text?) { return Json.parse(text?); }
                    static Object xml?(String text?) { return Xml.parse(text?); }
                    static Object jsonObject?(String text?) { return Json.parse(text?, Object.class); }
                    static Object xmlObject?(String text?) { return Xml.parse(text?, Object.class); }
                    static List jsonList?(String text?) { return Json.parse(text?, List.class); }
                    static List xmlList?(String text?) { return Xml.parse(text?, List.class); }
                    static Map jsonMap?(String text?) { return Json.parse(text?, Map.class); }
                    static Map xmlMap?(String text?) { return Xml.parse(text?, Map.class); }
                    static Item jsonRecord?(String text?) { return Json.parse(text?, Item.class); }
                    static Item xmlRecord?(String text?) { return Xml.parse(text?, Item.class); }
                    static Person jsonModel?(String text?) { return Json.parse(text?, Person.class); }
                    static Person xmlModel?(String text?) { return Xml.parse(text?, Person.class); }
                    static List<Item?> jsonRecords?(String text?) { return Json.parseList(text?, Item.class); }
                    static List<Item?> xmlRecords?(String text?) { return Xml.parseList(text?, Item.class); }
                    static List<Person?> jsonModels?(String text?) { return Json.parseList(text?, Person.class); }
                    static List<Person?> xmlModels?(String text?) { return Xml.parseList(text?, Person.class); }
                    static Map<String, Item> jsonRecordMap?(String text?) { return Json.parseMap(text?, Item.class); }
                    static Map<String, Item> xmlRecordMap?(String text?) { return Xml.parseMap(text?, Item.class); }
                    static Map<String, Person> jsonModelMap?(String text?) { return Json.parseMap(text?, Person.class); }
                    static Map<String, Person> xmlModelMap?(String text?) { return Xml.parseMap(text?, Person.class); }
                    static Envelope jsonEnvelope?(String text?) { return Json.parse(text?, Envelope.class); }
                    static Envelope xmlEnvelope?(String text?) { return Xml.parse(text?, Envelope.class); }
                    static String jsonRoundTrip?(List<Item?> items) {
                        List<Item?> parsed? = Json.parseList(items.toJson(), Item.class);
                        return parsed?.toJson();
                    }
                    static String xmlRoundTrip?(Map<String, Person> people) {
                        Map<String, Person> parsed? = Xml.parseMap(people.toXml(), Person.class);
                        return parsed?.toXml();
                    }
                    static void main(String[] args) {
                        List<Item?> items = List.of(new Item("Lem"));
                        Map<String, Person> people? = Map.of("U1", new Person("Ana"));
                        System.out.println(static.jsonRoundTrip(items)?);
                        System.out.println(static.xmlRoundTrip(people?)?);
                    }
                }
                """);
        runtimeClasspath = Path.of(AccentModel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        output = tempDir.resolve("classes");
        var result = new AccentCompiler().compile(List.of(sources), output, List.of(runtimeClasspath), tempDir.resolve("java"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()});
        calls = loader.loadClass("Parsing");
    }

    @AfterAll
    static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    private static Object parse(String method, String text) throws Exception {
        return calls.getMethod(method, String.class).invoke(null, text);
    }

    @TestFactory
    Stream<DynamicTest> untypedJsonValues() {
        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("{\"name\":\"Lem\",\"active\":true,\"items\":[1,null]}",
                Map.of("name", "Lem", "active", true, "items", Arrays.asList(1, null)));
        cases.put("[{\"name\":\"Ana\"},2,false]", List.of(Map.of("name", "Ana"), 2, false));
        cases.put("{}", Map.of()); cases.put("[]", List.of());
        cases.put("null", null); cases.put("true", true); cases.put("false", false);
        cases.put("42", 42); cases.put("-12", -12); cases.put("2147483648", 2147483648L);
        cases.put("1.25", 1.25); cases.put("1e2", 100.0);
        cases.put("\"Manila 日本 😀\\n\\t\\\"\\\\\"", "Manila 日本 😀\n\t\"\\");
        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            assertEquals(entry.getValue(), parse("json", entry.getKey()));
            assertEquals(entry.getValue(), parse("jsonObject", entry.getKey()));
        }));
    }

    @TestFactory
    Stream<DynamicTest> untypedXmlShapes() {
        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("<Person><name>Lem &amp; Ana</name><age>42</age><active>true</active></Person>",
                Map.of("name", "Lem & Ana", "age", "42", "active", "true"));
        cases.put("<List><item><name>Lem</name></item></List>", List.of(Map.of("name", "Lem")));
        cases.put("<Map><entry><key>U1</key><value><name>Ana</name></value></entry></Map>", Map.of("U1", Map.of("name", "Ana")));
        cases.put("<List/>", List.of()); cases.put("<Map/>", Map.of()); cases.put("<Person/>", Map.of());
        cases.put("<Person><name/></Person>", Map.of("name", ""));
        cases.put("<Person><name><![CDATA[<raw> 日本 😀]]></name></Person>", Map.of("name", "<raw> 日本 😀"));
        cases.put("<Person>\n  <name>Lem</name>\n</Person>", Map.of("name", "Lem"));
        cases.put("<Person><name>  Lem  </name></Person>", Map.of("name", "  Lem  "));
        return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            assertEquals(entry.getValue(), parse("xml", entry.getKey()));
            assertEquals(entry.getValue(), parse("xmlObject", entry.getKey()));
        }));
    }

    @Test
    void supportsExplicitObjectListAndMapTargetsAndRejectsShapeMismatch() throws Exception {
        assertEquals(List.of(Map.of("name", "Lem")), parse("jsonList", "[{\"name\":\"Lem\"}]"));
        assertEquals(Map.of("name", "Lem"), parse("jsonMap", "{\"name\":\"Lem\"}"));
        assertEquals(List.of(Map.of("name", "Lem")), parse("xmlList", "<List><item><name>Lem</name></item></List>"));
        assertEquals(Map.of("name", "Lem"), parse("xmlMap", "<Person><name>Lem</name></Person>"));
        for (String method : List.of("jsonList", "jsonRecords", "jsonModels")) assertThrows(InvocationTargetException.class, () -> parse(method, "{}"));
        assertThrows(InvocationTargetException.class, () -> parse("jsonMap", "[]"));
        for (String method : List.of("xmlList", "xmlRecords", "xmlModels")) assertThrows(InvocationTargetException.class, () -> parse(method, "<Map/>"));
        assertThrows(InvocationTargetException.class, () -> parse("xmlMap", "<List/>"));
    }

    @TestFactory
    Stream<DynamicTest> typedRecordAndModelRoundTrips() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String format : List.of("json", "xml")) {
            for (String kind : List.of("Record", "Model")) {
                tests.add(DynamicTest.dynamicTest(format + " " + kind + " round trips", () -> {
                    Class<?> type = loader.loadClass(kind.equals("Record") ? "Item" : "Person");
                    Object original = type.getConstructor(String.class).newInstance("Lem <&> 日本 😀");
                    String serializer = format.equals("json") ? "toJson" : "toXml";
                    String text = (String) type.getMethod(serializer).invoke(original);
                    Object parsed = parse(format + kind, text);
                    assertEquals(type, parsed.getClass());
                    assertEquals("Lem <&> 日本 😀", type.getMethod("name").invoke(parsed));
                    assertEquals(text, type.getMethod(serializer).invoke(parsed));
                    String listText = format.equals("json") ? "[" + text + "]" : "<List><item><name>Lem &lt;&amp;&gt; 日本 😀</name></item></List>";
                    List<?> list = (List<?>) parse(format + kind + "s", listText);
                    assertEquals(1, list.size()); assertEquals(parsed, list.get(0));
                    String mapText = format.equals("json") ? "{\"U1\":" + text + "}" : "<Map><entry><key>U1</key><value><name>Lem &lt;&amp;&gt; 日本 😀</name></value></entry></Map>";
                    Map<?, ?> map = (Map<?, ?>) parse(format + kind + "Map", mapText);
                    assertEquals(parsed, map.get("U1"));
                    assertEquals(List.of(), parse(format + kind + "s", format.equals("json") ? "[]" : "<List/>"));
                    assertEquals(Map.of(), parse(format + kind + "Map", format.equals("json") ? "{}" : "<Map/>"));
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void parsesNestedTypedRecordsModelsArraysAndEmptyContainers() throws Exception {
        String json = "{\"items\":[{\"name\":\"Lem\"}],\"people\":{\"U1\":{\"name\":\"Ana\"}},\"numbers\":[1,-2],\"initial\":\"Z\"}";
        String xml = "<Envelope><items><item><name>Lem</name></item></items><people><entry><key>U1</key><value><name>Ana</name></value></entry></people><numbers><item>1</item><item>-2</item></numbers><initial>Z</initial></Envelope>";
        for (String format : List.of("json", "xml")) {
            Object envelope = parse(format + "Envelope", format.equals("json") ? json : xml);
            Class<?> type = envelope.getClass();
            assertArrayEquals(new int[]{1, -2}, (int[]) type.getMethod("numbers").invoke(envelope));
            assertEquals('Z', type.getMethod("initial").invoke(envelope));
            assertEquals(loader.loadClass("Item"), ((List<?>) type.getMethod("items").invoke(envelope)).get(0).getClass());
            assertEquals(loader.loadClass("Person"), ((Map<?, ?>) type.getMethod("people").invoke(envelope)).get("U1").getClass());
        }
        Object empty = parse("xmlEnvelope", "<Envelope><items/><people/><numbers/><initial>Z</initial></Envelope>");
        assertEquals(List.of(), empty.getClass().getMethod("items").invoke(empty));
        assertEquals(Map.of(), empty.getClass().getMethod("people").invoke(empty));
        assertArrayEquals(new int[0], (int[]) empty.getClass().getMethod("numbers").invoke(empty));
    }

    @TestFactory
    Stream<DynamicTest> rejectsMalformedJson() {
        return Arrays.asList(null, "", " ", "{", "{ ", "[", "{\"a\":}", "[1,]", "{\"a\":1,}", "true false", "01", "1.", "1e999", "\"raw\nline\"", "\"\\uZZZZ\"")
                .stream().map(text -> DynamicTest.dynamicTest("invalid JSON " + text, () -> {
                    var error = assertThrows(InvocationTargetException.class, () -> parse("json", text));
                    assertInstanceOf(JsonParseException.class, error.getCause());
                }));
    }

    @TestFactory
    Stream<DynamicTest> rejectsMalformedOrUnsafeXml() {
        return Arrays.asList(null, "", " ", "<", "<Person>", "<a></b>", "<a/><b/>", "<a>&unknown;</a>",
                "<!DOCTYPE a [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><a>&xxe;</a>",
                "<!DOCTYPE a SYSTEM 'https://example.invalid/a.dtd'><a/>", "<a x='1'/>", "<a>mixed<b>x</b></a>",
                "<Map><entry><value>x</value></entry></Map>", "<Map><entry><key>x</key><extra>v</extra></entry></Map>",
                "<List><unexpected/></List>", "<Person><name>a</name><name>b</name></Person>")
                .stream().map(text -> DynamicTest.dynamicTest("invalid XML " + text, () -> {
                    var error = assertThrows(InvocationTargetException.class, () -> parse("xml", text));
                    assertInstanceOf(XmlParseException.class, error.getCause());
                }));
    }

    @Test
    void rejectsTypedDataWithWrongShapeAndPreservesNullJsonValues() throws Exception {
        for (String method : List.of("jsonRecord", "jsonModel", "jsonRecordMap", "jsonModelMap")) {
            assertThrows(InvocationTargetException.class, () -> parse(method, "[]"));
        }
        assertNull(parse("jsonRecord", "null"));
        assertNull(parse("jsonModel", "null"));
        assertEquals(Arrays.asList((Object) null), parse("jsonRecords", "[null]"));
        Map<?, ?> jsonMap = (Map<?, ?>) parse("jsonModelMap", "{\"missing\":null}");
        assertTrue(jsonMap.containsKey("missing"));
        assertNull(jsonMap.get("missing"));
        Map<?, ?> xmlMap = (Map<?, ?>) parse("xmlModelMap", "<Map><entry><key>missing</key></entry></Map>");
        assertTrue(xmlMap.containsKey("missing"));
        assertNull(xmlMap.get("missing"));
        for (String format : List.of("json", "xml")) {
            String expression = (format.equals("json") ? "Json" : "Xml") + ".stringify(value)";
            var source = new accent.source.SourceFile(Path.of("Invalid.accent"),
                    "import accent.json.Json; import accent.xml.Xml; class Invalid { String run(Object value) { return "
                            + expression + "; } }");
            var result = new AccentCompiler().check(source);
            assertFalse(result.successful());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Cannot resolve method 'stringify'")),
                    () -> result.diagnostics().toString());
        }
    }

    @Test
    void publicStringifyMethodsAreRemoved() {
        assertThrows(NoSuchMethodException.class, () -> Json.class.getMethod("stringify", Object.class));
        assertThrows(NoSuchMethodException.class, () -> Xml.class.getMethod("stringify", Object.class));
    }

    @Test
    void executesNativeSerializationParsingRoundTripInSeparateJvm() throws Exception {
        Path log = tempDir.resolve("output.txt");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", output + java.io.File.pathSeparator + runtimeClasspath, "Parsing")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> log.toString());
            assertEquals(List.of("[{\"name\":\"Lem\"}]", "<Map><entry><key>U1</key><value><name>Ana</name></value></entry></Map>"), Files.readAllLines(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
