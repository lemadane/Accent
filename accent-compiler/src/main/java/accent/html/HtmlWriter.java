package accent.html;

import java.io.IOException;
import java.util.Objects;

public final class HtmlWriter {

    private final Appendable output;

    public HtmlWriter(Appendable output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    public void literal(String value) throws IOException {
        if (value != null) {
            output.append(value);
        }
    }

    public void text(Object value) throws IOException {
        if (value == null) {
            return;
        }
        if (value instanceof Html html) {
            html(html);
        } else if (value instanceof Component component) {
            component(component);
        } else {
            output.append(escapeHtml(value.toString()));
        }
    }

    /** Gives nested markup lambdas a functional-interface target type. */
    public void text(Html value) throws IOException {
        html(value);
    }

    public void attribute(Object value) throws IOException {
        if (value != null) {
            output.append(escapeHtml(value.toString()));
        }
    }

    public void urlAttribute(Object value) throws IOException {
        if (value == null) {
            return;
        }
        // Browser URL parsing removes ASCII tabs and newlines before interpreting schemes.
        String url = value.toString().replace("\t", "").replace("\r", "").replace("\n", "").trim();
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("vbscript:") || lower.startsWith("data:")) {
            throw new HtmlRenderingException("Unsafe URL scheme in dynamic URL attribute: " + url);
        }
        attribute(url);
    }

    public void html(Html html) throws IOException {
        if (html != null) {
            html.writeTo(this);
        }
    }

    public void component(Component component) throws IOException {
        if (component != null) {
            component.writeTo(this);
        }
    }

    public void flush() throws IOException {
        if (output instanceof java.io.Flushable flushable) {
            flushable.flush();
        }
    }

    private static String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
