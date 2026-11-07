package accent.http;

import accent.html.Component;
import accent.html.Html;
import accent.runtime.DataSerialization;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable HTTP response. Text, JSON and HTML use UTF-8. */
public final class Response {
    private final int status;
    private final Map<String, String> headers;
    private final byte[] body;

    private Response(int status, Map<String, String> headers, byte[] body) {
        if (status < 200 || status > 599) throw new IllegalArgumentException("Final HTTP status must be 200..599");
        this.status = status;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body;
    }

    private static Response content(String type, String body) {
        return new Response(200, Map.of("content-type", type), Objects.requireNonNull(body, "body").getBytes(StandardCharsets.UTF_8));
    }

    public static Response text(String body) { return content("text/plain;charset=UTF-8", body); }
    public static Response json(Object value) { return content("application/json;charset=UTF-8", DataSerialization.toJson(value)); }
    public static Response html(String html) { return content("text/html;charset=UTF-8", html); }
    public static Response html(Html html) { return html(Objects.requireNonNull(html, "html").renderToString()); }
    public static Response html(Component component) { return html(Objects.requireNonNull(component, "component").renderToString()); }
    public static Response empty(int status) { return new Response(status, Map.of(), new byte[0]); }
    public static Response bytes(byte[] body, String contentType) {
        return new Response(200, Map.of(), Objects.requireNonNull(body, "body").clone()).header("Content-Type", contentType);
    }

    public Response status(int status) { return new Response(status, headers, body); }

    public Response header(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!name.matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+") || value.chars().anyMatch(c -> c == 127 || c < 32 && c != 9)) {
            throw new IllegalArgumentException("Invalid HTTP header");
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (key.equals("content-length") || key.equals("transfer-encoding") || key.equals("connection")) {
            throw new IllegalArgumentException("Response framing headers are managed by the server");
        }
        var copy = new LinkedHashMap<>(headers);
        copy.put(key, value);
        return new Response(status, copy, body);
    }

    public int status() { return status; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body.clone(); }
    byte[] content() { return body; }
}
