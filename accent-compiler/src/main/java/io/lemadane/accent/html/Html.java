package io.lemadane.accent.html;

import java.io.IOException;

@FunctionalInterface
public interface Html {

    void writeTo(HtmlWriter writer) throws IOException;

    default String renderToString() {
        StringBuilder output = new StringBuilder();
        try {
            writeTo(new HtmlWriter(output));
        } catch (IOException exception) {
            throw new HtmlRenderingException("Unable to render HTML", exception);
        }
        return output.toString();
    }

    static Html empty() {
        return writer -> {};
    }

    static Html text(Object value) {
        return writer -> writer.text(value);
    }

    static Html fragment(Html... children) {
        return writer -> {
            for (Html child : children) {
                if (child != null) {
                    child.writeTo(writer);
                }
            }
        };
    }

    static TrustedHtml trusted(String value) {
        return new TrustedHtml(value);
    }
}
