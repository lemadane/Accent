package io.lemadane.jatot.html;

import java.io.IOException;

public interface Component {

    Html render();

    default void writeTo(HtmlWriter writer) throws IOException {
        Html rendered = render();
        if (rendered != null) {
            rendered.writeTo(writer);
        }
    }

    default String renderToString() {
        Html rendered = render();
        return rendered != null ? rendered.renderToString() : "";
    }
}
