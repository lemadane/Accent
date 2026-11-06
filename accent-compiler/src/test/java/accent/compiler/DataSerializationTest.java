package accent.compiler;

import accent.json.Json;
import accent.xml.Xml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DataSerializationTest {
    @TempDir Path tempDir;

    @Test
    void generatesMethodsForRecordsAndModelsAndSupportsDirectCalls() throws Exception {
        Path sources = Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(sources.resolve("Address.accent"), """
                public record Address(String city) {}
                """);
        Files.writeString(sources.resolve("User.accent"), """
                public model User(String name, Address address, String missing, int[] scores) {}
                """);
        Files.writeString(sources.resolve("Calls.accent"), """
                public class Calls {
                    public static String json(User! user) { return user.toJson(); }
                    public static String xml(User! user) { return user.toXml(); }
                    public static String recordJson(Address! address) { return address.toJson(); }
                    public static String recordXml(Address! address) { return address.toXml(); }
                }
                """);
        Files.writeString(sources.resolve("Custom.accent"), """
                public record Custom(String name) {
                    public String toJson() { return "custom json"; }
                    public String toXml() { return "custom xml"; }
                }
                """);
        Path output = tempDir.resolve("classes");
        var result = new AccentCompiler().compile(List.of(sources), output,
                List.of(Path.of("build/classes/java/main").toAbsolutePath().toString()),
                tempDir.resolve("java"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
            Class<?> addressClass = loader.loadClass("Address");
            Object address = addressClass.getConstructor(String.class).newInstance("A&B <City>");
            Class<?> userClass = loader.loadClass("User");
            Object user = userClass.getConstructor(String.class, addressClass, String.class, int[].class)
                    .newInstance("Lem\"\n", address, null, new int[]{1, 2});
            String json = (String) userClass.getMethod("toJson").invoke(user);
            assertEquals("{\"name\":\"Lem\\\"\\n\",\"address\":{\"city\":\"A&B <City>\"},\"scores\":[1,2]}", json);
            assertTrue(Json.isValid(json));
            String xml = (String) userClass.getMethod("toXml").invoke(user);
            assertEquals("<User><name>Lem&quot;\n</name><address><city>A&amp;B &lt;City&gt;</city></address><scores><item>1</item><item>2</item></scores></User>", xml);
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            assertEquals("A&B <City>", document.getElementsByTagName("city").item(0).getTextContent());
            Class<?> calls = loader.loadClass("Calls");
            assertEquals(json, calls.getMethod("json", userClass).invoke(null, user));
            assertEquals(xml, calls.getMethod("xml", userClass).invoke(null, user));
            assertEquals("{\"city\":\"A&B <City>\"}", calls.getMethod("recordJson", addressClass).invoke(null, address));
            assertEquals("<Address><city>A&amp;B &lt;City&gt;</city></Address>", calls.getMethod("recordXml", addressClass).invoke(null, address));
            Class<?> custom = loader.loadClass("Custom");
            Object customValue = custom.getConstructor(String.class).newInstance("name");
            assertEquals("custom json", custom.getMethod("toJson").invoke(customValue));
            assertEquals("custom xml", custom.getMethod("toXml").invoke(customValue));
            userClass.getMethod("setName", String.class).invoke(user, "Updated");
            assertTrue(((String) userClass.getMethod("toJson").invoke(user)).contains("Updated"));
        }
    }

    @Test
    void serializesListsOfRecordsAndMapsOfModels() throws Exception {
        Path sources = Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(sources.resolve("Item.accent"), "public record Item(String name) {}");
        Files.writeString(sources.resolve("Entry.accent"), "public model Entry(String name) {}");
        Files.writeString(sources.resolve("Containers.accent"), """
                import java.util.*;
                public class Containers {
                    public static String listJson(List<Item> values) { return values.toJson(); }
                    public static String listXml(List<Item> values) { return values.toXml(); }
                    public static String mapJson(Map<String, Entry> values) { return values.toJson(); }
                    public static String mapXml(Map<String, Entry> values) { return values.toXml(); }
                    public static String modelList(List<Entry> values) { return values.toJson(); }
                    public static String recordMap(Map<Integer, Item> values) { return values.toXml(); }
                    public static String concreteList(ArrayList<Item> values) { return values.toJson(); }
                    public static String concreteMap(LinkedHashMap<String, Entry> values) { return values.toXml(); }
                    public static String optionalList(List<Item> values) { return values?.toJson(); }
                    public static String optionalMap(Map<String, Entry> values) { return values?.toXml(); }
                    public static int chained(List<Item> values) { return values.toJson().length(); }
                    public static String fallback(List<Item> values) { return values?.toJson() ?? "absent"; }
                }
                """);
        Path output = tempDir.resolve("classes");
        var result = new AccentCompiler().compile(List.of(sources), output,
                List.of(Path.of("build/classes/java/main").toAbsolutePath().toString()),
                tempDir.resolve("java"), true);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
            Class<?> type = loader.loadClass("Containers");
            Object record = loader.loadClass("Item").getConstructor(String.class).newInstance("<&>");
            Object model = loader.loadClass("Entry").getConstructor(String.class).newInstance("Lem");
            List<Object> items = List.of(record);
            assertEquals(accent.runtime.DataSerialization.toJson(items), type.getMethod("listJson", List.class).invoke(null, items));
            assertEquals("<List><item><name>&lt;&amp;&gt;</name></item></List>",
                    type.getMethod("listXml", List.class).invoke(null, items));
            Map<String, Object> map = Map.of("user", model);
            assertEquals(accent.runtime.DataSerialization.toJson(map), type.getMethod("mapJson", Map.class).invoke(null, map));
            assertEquals(accent.runtime.DataSerialization.toXml(map), type.getMethod("mapXml", Map.class).invoke(null, map));
            assertEquals(accent.runtime.DataSerialization.toJson(List.of(model)), type.getMethod("modelList", List.class).invoke(null, List.of(model)));
            assertEquals(accent.runtime.DataSerialization.toXml(Map.of(1, record)), type.getMethod("recordMap", Map.class).invoke(null, Map.of(1, record)));
            assertEquals("[]", type.getMethod("concreteList", ArrayList.class).invoke(null, new ArrayList<>()));
            assertEquals("<Map></Map>", type.getMethod("concreteMap", java.util.LinkedHashMap.class)
                    .invoke(null, new java.util.LinkedHashMap<>()));
            assertNull(type.getMethod("optionalList", List.class).invoke(null, new Object[]{null}));
            assertNull(type.getMethod("optionalMap", Map.class).invoke(null, new Object[]{null}));
            assertEquals(accent.runtime.DataSerialization.toJson(items), type.getMethod("optionalList", List.class).invoke(null, items));
            assertEquals("absent", type.getMethod("fallback", List.class).invoke(null, new Object[]{null}));
            assertEquals(accent.runtime.DataSerialization.toJson(items).length(), type.getMethod("chained", List.class).invoke(null, items));
            assertEquals("[]", type.getMethod("listJson", List.class).invoke(null, List.of()));
            assertEquals("{}", type.getMethod("mapJson", Map.class).invoke(null, Map.of()));
        }
    }

    @Test
    void rejectsScalarRawAndUnknownContainerTypes() {
        for (String container : List.of("List<String>", "List<Integer>", "List<Object>", "List",
                "Map<String, String>", "Map<String, Integer>", "Map<String, Object>", "Map",
                "List<List<Item>>", "Map<String, List<Item>>")) {
            for (String method : List.of("toJson", "toXml")) {
                var source = new accent.source.SourceFile(Path.of("Invalid.accent"),
                        "import java.util.*; record Item(String name) {} public class Invalid { public String run("
                                + container + " values) { return values." + method + "(); } }");
                assertFalse(new AccentCompiler().check(source).successful(), container + "." + method);
            }
        }
    }

    @Test
    void rejectsSerializationArgumentsAndUnrelatedReceivers() {
        for (String expression : List.of("values.toJson(1)", "values.toXml(1)", "text.toJson()")) {
            var source = new accent.source.SourceFile(Path.of("Invalid.accent"),
                    "import java.util.List; public class Invalid { public String run(List<String> values, String text) { return "
                            + expression + "; } }");
            assertFalse(new AccentCompiler().check(source).successful(), expression);
        }
    }

    record Values(List<Object> items, Map<String, Object> metadata) {}

    @Test
    void supportsContainersSharedValuesAndRejectsCycles() {
        Object shared = List.of("<&>", 'x');
        var value = new Values(List.of(shared, shared), Map.of("unsafe key", new int[]{3, 4}));
        assertTrue(Json.isValid(accent.runtime.DataSerialization.toJson(value)));
        assertTrue(accent.runtime.DataSerialization.toXml(value).contains("<key>unsafe key</key>"));
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> accent.runtime.DataSerialization.toJson(cycle));
        assertThrows(IllegalArgumentException.class, () -> accent.runtime.DataSerialization.toXml(cycle));
        assertThrows(IllegalArgumentException.class, () -> accent.runtime.DataSerialization.toXml("\u0000"));
        assertThrows(IllegalArgumentException.class, () -> accent.runtime.DataSerialization.toJson(Double.NaN));
        assertEquals("[]", accent.runtime.DataSerialization.toJson(List.of()));
        assertEquals("null", accent.runtime.DataSerialization.toJson(null));
    }
}
