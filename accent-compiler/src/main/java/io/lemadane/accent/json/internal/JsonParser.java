package io.lemadane.accent.json.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.lemadane.accent.json.JsonParseException;

public class JsonParser {
    private final String json;
    private int pos = 0;

    private JsonParser(String json) {
        this.json = json;
    }

    public static Object parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new JsonParseException("Empty JSON string");
        }
        JsonParser parser = new JsonParser(json);
        Object result = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos < parser.json.length()) {
            throw new JsonParseException("Unexpected extra data after JSON value at pos " + parser.pos);
        }
        return result;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= json.length()) throw new JsonParseException("Unexpected end of JSON");

        char c = json.charAt(pos);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't') return parseLiteral("true", true);
        if (c == 'f') return parseLiteral("false", false);
        if (c == 'n') return parseLiteral("null", null);
        if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();

        throw new JsonParseException("Unexpected character '" + c + "' at pos " + pos);
    }

    private Map<String, Object> parseObject() {
        pos++; // skip '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (pos < json.length() && json.charAt(pos) == '}') {
            pos++;
            return map;
        }

        while (pos < json.length()) {
            skipWhitespace();
            if (json.charAt(pos) != '"') throw new JsonParseException("Expected string key in object at pos " + pos);
            String key = parseString();

            skipWhitespace();
            if (pos >= json.length() || json.charAt(pos) != ':') throw new JsonParseException("Expected ':' in object at pos " + pos);
            pos++; // skip ':'

            Object value = parseValue();
            map.put(key, value);

            skipWhitespace();
            if (pos >= json.length()) throw new JsonParseException("Unterminated object");
            char c = json.charAt(pos);
            if (c == '}') {
                pos++;
                return map;
            } else if (c == ',') {
                pos++;
            } else {
                throw new JsonParseException("Expected ',' or '}' in object at pos " + pos);
            }
        }
        throw new JsonParseException("Unterminated object");
    }

    private List<Object> parseArray() {
        pos++; // skip '['
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (pos < json.length() && json.charAt(pos) == ']') {
            pos++;
            return list;
        }

        while (pos < json.length()) {
            list.add(parseValue());

            skipWhitespace();
            if (pos >= json.length()) throw new JsonParseException("Unterminated array");
            char c = json.charAt(pos);
            if (c == ']') {
                pos++;
                return list;
            } else if (c == ',') {
                pos++;
            } else {
                throw new JsonParseException("Expected ',' or ']' in array at pos " + pos);
            }
        }
        throw new JsonParseException("Unterminated array");
    }

    private String parseString() {
        pos++; // skip '"'
        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            } else if (c == '\\') {
                if (pos >= json.length()) throw new JsonParseException("Unterminated escape sequence");
                char e = json.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > json.length()) throw new JsonParseException("Unterminated unicode escape");
                        String hex = json.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default: throw new JsonParseException("Invalid escape sequence \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new JsonParseException("Unterminated string");
    }

    private Number parseNumber() {
        int start = pos;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        String numStr = json.substring(start, pos);
        try {
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            } else {
                long l = Long.parseLong(numStr);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            }
        } catch (NumberFormatException e) {
            throw new JsonParseException("Invalid number format: " + numStr);
        }
    }

    private Object parseLiteral(String literal, Object value) {
        if (pos + literal.length() <= json.length() && json.substring(pos, pos + literal.length()).equals(literal)) {
            pos += literal.length();
            return value;
        }
        throw new JsonParseException("Expected literal '" + literal + "' at pos " + pos);
    }

    private void skipWhitespace() {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }
}
