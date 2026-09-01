package io.lemadane.accent.html;

import java.io.IOException;
import java.util.Objects;

public final class TrustedHtml implements Html {
    private final String value;

    public TrustedHtml(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    public void writeTo(HtmlWriter writer) throws IOException {
        writer.literal(value);
    }

    public String getValue() {
        return value;
    }
}
