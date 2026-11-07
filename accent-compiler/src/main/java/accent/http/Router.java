package accent.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Segment-based router: literal segments take precedence over :parameter segments. */
public final class Router {
    private volatile List<Route> routes = new ArrayList<>();
    private boolean frozen;

    private record Route(String method, List<String> segments, String shape, Handler handler, int literals) {}
    record Match(Handler handler, Map<String, String> parameters) {}

    public Router get(String path, Handler handler) { return route("GET", path, handler); }
    public Router post(String path, Handler handler) { return route("POST", path, handler); }
    public Router put(String path, Handler handler) { return route("PUT", path, handler); }
    public Router patch(String path, Handler handler) { return route("PATCH", path, handler); }
    public Router delete(String path, Handler handler) { return route("DELETE", path, handler); }
    public Router head(String path, Handler handler) { return route("HEAD", path, handler); }
    public Router options(String path, Handler handler) { return route("OPTIONS", path, handler); }

    public synchronized Router route(String method, String path, Handler handler) {
        if (frozen) throw new IllegalStateException("Routes cannot change after the server starts");
        String verb = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        if (!verb.matches("[A-Z]+")) throw new IllegalArgumentException("Invalid HTTP method");
        if (path == null || !path.startsWith("/") || path.contains("?") || path.contains("#") || path.contains("*")) {
            throw new IllegalArgumentException("Route must be an absolute path without query, fragment or wildcard");
        }
        List<String> segments = segments(path);
        var names = new TreeSet<String>();
        var shape = new ArrayList<String>();
        int literals = 0;
        for (String segment : segments) {
            if (segment.startsWith(":")) {
                String name = segment.substring(1);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !names.add(name)) {
                    throw new IllegalArgumentException("Invalid or repeated route parameter");
                }
                shape.add(":");
            } else { shape.add(segment); literals++; }
        }
        String key = String.join("/", shape);
        for (Route route : routes) {
            if (route.method().equals(verb) && route.shape().equals(key)) throw new IllegalArgumentException("Duplicate route: " + verb + " " + path);
        }
        routes.add(new Route(verb, segments, key, Objects.requireNonNull(handler, "handler"), literals));
        return this;
    }

    synchronized void freeze() { routes = List.copyOf(routes); frozen = true; }

    static List<String> segments(String path) {
        String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return normalized.equals("/") ? List.of() : List.of(normalized.substring(1).split("/", -1));
    }

    static List<String> decodePath(String path) {
        try {
            return segments(path).stream().map(segment -> URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)).toList();
        } catch (IllegalArgumentException exception) { throw new HttpException(400, "Invalid URL path"); }
    }

    Match match(String method, List<String> path) {
        Route best = null;
        Map<String, String> parameters = Map.of();
        var allowed = new TreeSet<String>();
        for (Route route : routes) {
            if (route.segments().size() != path.size()) continue;
            var values = new LinkedHashMap<String, String>();
            boolean matches = true;
            for (int i = 0; i < path.size(); i++) {
                String expected = route.segments().get(i);
                if (expected.startsWith(":") && !path.get(i).isEmpty()) values.put(expected.substring(1), path.get(i));
                else if (!expected.equals(path.get(i))) { matches = false; break; }
            }
            if (!matches) continue;
            allowed.add(route.method());
            boolean exact = route.method().equals(method);
            boolean fallback = method.equals("HEAD") && route.method().equals("GET");
            if ((exact || fallback) && (best == null || route.literals() > best.literals()
                    || route.literals() == best.literals() && exact && !best.method().equals(method))) {
                best = route;
                parameters = values;
            }
        }
        if (best != null) return new Match(best.handler(), parameters);
        if (allowed.isEmpty()) return new Match(request -> Response.text("Not Found").status(404), Map.of());
        if (allowed.contains("GET")) allowed.add("HEAD");
        allowed.add("OPTIONS");
        String allow = String.join(", ", allowed);
        return new Match(request -> method.equals("OPTIONS") ? Response.empty(204).header("Allow", allow)
                : Response.text("Method Not Allowed").status(405).header("Allow", allow), Map.of());
    }
}
