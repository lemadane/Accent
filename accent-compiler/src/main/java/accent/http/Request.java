package accent.http;

import accent.json.Json;
import accent.json.JsonException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Request-local immutable data; headers are looked up case-insensitively. */
public final class Request {
    private final String method;
    private final String path;
    private final Map<String, String> parameters;
    private final Map<String, List<String>> query;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    Request(String method, String path, Map<String, String> parameters, Map<String, List<String>> query,
            Map<String, List<String>> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.parameters = Map.copyOf(parameters);
        this.query = immutable(query);
        this.headers = immutable(headers);
        this.body = body;
    }

    private static Map<String, List<String>> immutable(Map<String, List<String>> source) {
        var copy = new LinkedHashMap<String, List<String>>();
        source.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    public String method() { return method; }
    public String path() { return path; }
    public String path(String name) { return parameters.get(name); }
    public Map<String, String> pathParameters() { return parameters; }
    public String query(String name) { return first(query.get(name)); }
    public List<String> queryValues(String name) { return query.getOrDefault(name, List.of()); }
    public Map<String, List<String>> queryParameters() { return query; }
    public String header(String name) { return first(headers.get(name.toLowerCase(Locale.ROOT))); }
    public Map<String, List<String>> headers() { return headers; }
    public String body() { return new String(body, StandardCharsets.UTF_8); }
    public byte[] bodyBytes() { return body.clone(); }
    public Object json() {
        try { return Json.parse(body()); }
        catch (JsonException exception) { throw new HttpException(400, "Invalid JSON request body"); }
    }
    public <T> T json(Class<T> type) {
        try { return Json.parse(body(), type); }
        catch (JsonException exception) { throw new HttpException(400, "Invalid JSON request body"); }
    }

    private static String first(List<String> values) { return values == null || values.isEmpty() ? null : values.getFirst(); }
}
