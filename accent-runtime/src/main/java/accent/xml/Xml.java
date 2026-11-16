package accent.xml;

import accent.json.JsonOptions;
import accent.json.internal.JsonMapper;
import accent.runtime.AccentModel;
import java.lang.reflect.*;
import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Parses Accent XML conventions into maps, lists, and string scalar values. */
public final class Xml {
    private Xml() {}

    public static Object parse(String text) {
        if (text == null || text.isBlank()) throw new XmlParseException("Empty XML input");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            Element root = builder.parse(new InputSource(new StringReader(text))).getDocumentElement();
            Object value = read(root);
            // At the document root an empty named data element represents an empty object.
            return value.equals("") && !root.getTagName().equals("List") && !root.getTagName().equals("Map")
                    ? new LinkedHashMap<String, Object>() : value;
        } catch (XmlParseException e) {
            throw e;
        } catch (Exception e) {
            throw new XmlParseException("Invalid XML input", e);
        }
    }

    public static <T> T parse(String text, Class<T> type) {
        Object value = parse(text);
        if (type == Object.class || type == List.class || type == Map.class) {
            if (!type.isInstance(value)) throw new XmlParseException("Parsed XML is not a " + type.getSimpleName());
            return type.cast(value);
        }
        return new JsonMapper(JsonOptions.DEFAULT).mapToRecord(normalize(value, type), type);
    }

    public static <T> List<T> parseList(String text, Class<T> elementType) {
        Object value = parse(text);
        if (!(value instanceof List<?> items)) throw new XmlParseException("Expected XML List");
        List<T> result = new ArrayList<>();
        var mapper = new JsonMapper(JsonOptions.DEFAULT);
        for (Object item : items) result.add(mapper.mapToRecord(normalize(item, elementType), elementType));
        return result;
    }

    public static <T> Map<String, T> parseMap(String text, Class<T> valueType) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?> entries)) throw new XmlParseException("Expected XML Map");
        Map<String, T> result = new LinkedHashMap<>();
        var mapper = new JsonMapper(JsonOptions.DEFAULT);
        for (var entry : entries.entrySet()) result.put((String) entry.getKey(),
                mapper.mapToRecord(normalize(entry.getValue(), valueType), valueType));
        return result;
    }

    private static Object normalize(Object value, Type type) {
        if (value == null) return null;
        Class<?> raw = type instanceof Class<?> clazz ? clazz
                : type instanceof ParameterizedType parameterized ? (Class<?>) parameterized.getRawType() : Object.class;
        Type[] arguments = type instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments() : new Type[0];
        if (raw.isArray() || Collection.class.isAssignableFrom(raw)) {
            Type elementType = raw.isArray() ? raw.getComponentType() : arguments.length > 0 ? arguments[0] : Object.class;
            if (value.equals("")) return List.of();
            if (value instanceof List<?> items) return items.stream().map(item -> item == null ? null : normalize(item, elementType)).toList();
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (value.equals("")) return Map.of();
            if (value instanceof Map<?, ?> entries) {
                Map<Object, Object> result = new LinkedHashMap<>();
                Type valueType = arguments.length > 1 ? arguments[1] : Object.class;
                entries.forEach((key, item) -> result.put(key, item == null ? null : normalize(item, valueType)));
                return result;
            }
        }
        if (raw.isRecord() || AccentModel.class.isAssignableFrom(raw)) {
            if (value.equals("")) return Map.of();
            if (value instanceof Map<?, ?> fields) {
                Map<String, Object> result = new LinkedHashMap<>();
                fields.forEach((key, item) -> result.put((String) key, item));
                Map<String, Type> types = new LinkedHashMap<>();
                if (raw.isRecord()) {
                    for (var component : raw.getRecordComponents()) types.put(component.getName(), component.getGenericType());
                } else {
                    for (var field : raw.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) types.put(field.getName(), field.getGenericType());
                    }
                }
                types.forEach((key, fieldType) -> {
                    if (result.get(key) != null) result.put(key, normalize(result.get(key), fieldType));
                });
                return result;
            }
        }
        return value;
    }

    private static Object read(Element element) {
        if (element.hasAttributes()) throw new XmlParseException("XML attributes are not supported");
        List<Element> children = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) children.add(child);
            else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) text.append(node.getNodeValue());
        }
        boolean list = element.getTagName().equals("List") || !children.isEmpty() && children.stream().allMatch(c -> c.getTagName().equals("item"));
        boolean map = element.getTagName().equals("Map") || !children.isEmpty() && children.stream().allMatch(c -> c.getTagName().equals("entry"));
        if (children.isEmpty() && !list && !map) return text.toString();
        if (!text.toString().isBlank()) throw new XmlParseException("Mixed XML text and elements are not supported");
        if (list) {
            List<Object> values = new ArrayList<>();
            for (Element child : children) {
                if (!child.getTagName().equals("item")) throw new XmlParseException("Expected item in List");
                values.add(read(child));
            }
            return values;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Element child : children) {
            String key = child.getTagName();
            Object value;
            if (map) {
                if (!key.equals("entry")) throw new XmlParseException("Expected entry in Map");
                Object entry = read(child);
                if (!(entry instanceof Map<?, ?> fields) || !(fields.get("key") instanceof String name)
                        || fields.size() > 2 || fields.keySet().stream().anyMatch(k -> !k.equals("key") && !k.equals("value"))) {
                    throw new XmlParseException("Expected key and optional value in map entry");
                }
                key = name;
                value = fields.get("value");
            } else value = read(child);
            if (values.containsKey(key)) throw new XmlParseException("Duplicate XML property or map key: " + key);
            values.put(key, value);
        }
        return values;
    }
}
