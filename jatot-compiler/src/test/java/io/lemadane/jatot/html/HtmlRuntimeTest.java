package io.lemadane.jatot.html;

import org.junit.jupiter.api.Test;

import io.lemadane.jatot.html.Html;
import io.lemadane.jatot.html.HtmlRenderingException;
import io.lemadane.jatot.html.HtmlWriter;

import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class HtmlRuntimeTest {

    @Test
    void testTextEscaping() {
        Html html = Html.text("<script>alert('x')</script>");
        String result = html.renderToString();
        assertEquals("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;", result);
    }

    @Test
    void testAttributeEscaping() throws IOException {
        StringBuilder sb = new StringBuilder();
        HtmlWriter writer = new HtmlWriter(sb);
        writer.attribute("hello \"world' & <tag>");
        assertEquals("hello &quot;world&#39; &amp; &lt;tag&gt;", sb.toString());
    }

    @Test
    void testUrlValidationSafe() {
        Html html = writer -> {
            writer.urlAttribute("https://example.com/profile?id=123");
        };
        String result = html.renderToString();
        assertEquals("https://example.com/profile?id=123", result);
    }

    @Test
    void testUrlValidationUnsafeJavascript() {
        Html html = writer -> {
            writer.urlAttribute("javascript:alert(1)");
        };
        assertThrows(HtmlRenderingException.class, html::renderToString);
    }

    @Test
    void testUrlValidationUnsafeData() {
        Html html = writer -> {
            writer.urlAttribute("data:text/html,<script>alert(1)</script>");
        };
        assertThrows(HtmlRenderingException.class, html::renderToString);
    }

    @Test
    void testFragments() {
        Html f = Html.fragment(
            Html.text("First"),
            Html.text("Second")
        );
        assertEquals("FirstSecond", f.renderToString());
    }

    @Test
    void testTrustedHtml() {
        Html html = Html.fragment(
            Html.text("Safe"),
            Html.trusted("<div>Unsafe</div>")
        );
        assertEquals("Safe<div>Unsafe</div>", html.renderToString());
    }

    @Test
    void testNullValues() {
        Html html = Html.fragment(
            Html.text(null),
            writer -> writer.attribute(null),
            writer -> writer.urlAttribute(null)
        );
        assertEquals("", html.renderToString());
    }
}
