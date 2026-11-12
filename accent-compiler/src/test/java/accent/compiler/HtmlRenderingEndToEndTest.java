package accent.compiler;

import accent.html.Html;
import accent.html.HtmlRenderingException;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles Accent markup to bytecode and checks actual rendered output. */
class HtmlRenderingEndToEndTest {
    @TempDir Path tempDir;

    private record Compiled(Class<?> type, URLClassLoader loader) implements AutoCloseable {
        Html render(Class<?>[] types, Object... args) throws Exception {
            return (Html) type.getMethod("render", types).invoke(null, args);
        }
        @Override public void close() throws IOException { loader.close(); }
    }

    private Compiled compile(String code) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve(UUID.randomUUID().toString()));
        Path sources = Files.createDirectory(root.resolve("src"));
        Files.writeString(sources.resolve("Demo.accent"), "import accent.html.*; import java.util.*;\n" + code);
        Files.writeString(sources.resolve("Box.accent"), """
                import accent.html.Component;
                import accent.html.Html;
                import accent.html.HtmlChildren;
                record Box(HtmlChildren children) implements Component {
                    Html render() { return (<section>{this.children}</section>); }
                }
                """);
        String runtime = Path.of(Html.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path output = root.resolve("classes");
        var result = new AccentCompiler().compile(List.of(sources), output, List.of(runtime), root.resolve("java"), true);
        assertTrue(result.successful(), () -> "Markup compilation failed: " + result.diagnostics());
        var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()});
        return new Compiled(loader.loadClass("Demo"), loader);
    }

    @Test
    void compiledLinksRejectObfuscatedExecutableSchemes() throws Exception {
        try (var compiled = compile("class Demo { static Html render(String url) { return (<a href={url}>link</a>); } }")) {
            for (String url : List.of("javascript:alert(1)", "java\tscript:alert(1)", "java\nscript:alert(1)",
                    "java\rscript:alert(1)", " \tJaVaScRiPt:alert(1)\r\n", "vb\tscript:msgbox(1)", "da\nta:text/html,payload")) {
                Html html = compiled.render(new Class<?>[]{String.class}, url);
                assertThrows(HtmlRenderingException.class, html::renderToString, url);
            }
        }
    }

    @Test
    void urlSchemeChecksAreIndependentOfSystemLocale() throws Exception {
        try (var compiled = compile("class Demo { static Html render(String url) { return (<img src={url}/>); } }")) {
            Locale saved = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                Html html = compiled.render(new Class<?>[]{String.class}, "JAVASCRIPT:alert(1)");
                assertThrows(HtmlRenderingException.class, html::renderToString);
            } finally { Locale.setDefault(saved); }
        }
    }

    @Test
    void safeUrlsAndDynamicTextRemainEscaped() throws Exception {
        try (var compiled = compile("class Demo { static Html render(String url, String value) { return (<a href={url} title={value}>{value}</a>); } }")) {
            for (String url : List.of("https://example.com/?a=1&b=2", "http://example.com", "/users", "#section", "mailto:a@example.com")) {
                String actual = compiled.render(new Class<?>[]{String.class, String.class}, url, "<tag>\"'&").renderToString();
                assertEquals("<a href=\"" + url.replace("&", "&amp;") + "\" title=\"&lt;tag&gt;&quot;&#39;&amp;\">&lt;tag&gt;&quot;&#39;&amp;</a>", actual);
            }
        }
    }

    @Test
    void falseAndNullBooleanConstantsAreOmitted() throws Exception {
        try (var compiled = compile("class Demo { static Html render() { return (<input disabled={false} checked={false} required={null} title={true}/>); } }")) {
            assertEquals("<input title=\"true\">", compiled.render(new Class<?>[]{}).renderToString());
        }
    }

    @Test
    void trueAndDynamicBooleanAttributesPreservePresenceSemantics() throws Exception {
        try (var compiled = compile("class Demo { static Html render(boolean disabled, Boolean checked?) { return (<input required={true} disabled={disabled} checked={checked?}/>); } }")) {
            assertEquals("<input required>", compiled.render(new Class<?>[]{boolean.class, Boolean.class}, false, null).renderToString());
            assertEquals("<input required disabled checked>", compiled.render(new Class<?>[]{boolean.class, Boolean.class}, true, true).renderToString());
            assertEquals("<input required>", compiled.render(new Class<?>[]{boolean.class, Boolean.class}, false, false).renderToString());
        }
    }

    @Test
    void nestedComponentChildrenCompileAndRender() throws Exception {
        try (var compiled = compile("class Demo { static Html render() { return (<Box><Box><Box><span>ok</span></Box></Box></Box>); } }")) {
            assertEquals("<section><section><section><span>ok</span></section></section></section>", compiled.render(new Class<?>[]{}).renderToString());
        }
    }

    @Test
    void ifWithoutElseDoesNotRenderSyntaxBraces() throws Exception {
        try (var compiled = compile("class Demo { static Html render(boolean show) { return (<div>{if (show) {<b>yes</b>}}</div>); } }")) {
            assertEquals("<div><b>yes</b></div>", compiled.render(new Class<?>[]{boolean.class}, true).renderToString());
            assertEquals("<div></div>", compiled.render(new Class<?>[]{boolean.class}, false).renderToString());
        }
    }

    @Test
    void ifElseIfChainsRenderExactlyOneBranch() throws Exception {
        try (var compiled = compile("class Demo { static Html render(boolean first, boolean second) { return (<div>{if (first) {<b>first</b>} else if (second) {<i>second</i>} else {<u>last</u>}}</div>); } }")) {
            assertEquals("<div><b>first</b></div>", compiled.render(new Class<?>[]{boolean.class, boolean.class}, true, true).renderToString());
            assertEquals("<div><i>second</i></div>", compiled.render(new Class<?>[]{boolean.class, boolean.class}, false, true).renderToString());
            assertEquals("<div><u>last</u></div>", compiled.render(new Class<?>[]{boolean.class, boolean.class}, false, false).renderToString());
        }
    }

    @Test
    void forInMarkupInfersBindingsAndRendersElse() throws Exception {
        try (var compiled = compile("class Demo { static Html render(List<String> items) { return (<ul>{for (item in items) {<li>{item.length()}</li>} else {<li>done</li>}}</ul>); } }")) {
            assertEquals("<ul><li>3</li><li>done</li></ul>",
                    compiled.render(new Class<?>[]{List.class}, List.of("one")).renderToString());
            assertEquals("<ul><li>done</li></ul>",
                    compiled.render(new Class<?>[]{List.class}, List.of()).renderToString());
        }
    }

    @Test
    void markupLoopsKeepLiftedExpressionsWithinEachIteration() throws Exception {
        try (var compiled = compile("class Demo { static Html render(List<String> items??) { return (<ul>{for (String item? : items?) {<li>{item? else \"none\"}</li>} else {<li>done</li>}}</ul>); } }")) {
            assertEquals("<ul><li>one</li><li>none</li><li>two</li><li>done</li></ul>",
                    compiled.render(new Class<?>[]{List.class}, Arrays.asList("one", null, "two")).renderToString());
            assertEquals("<ul><li>done</li></ul>", compiled.render(new Class<?>[]{List.class}, List.of()).renderToString());
            assertEquals("<ul><li>done</li></ul>", compiled.render(new Class<?>[]{List.class}, (Object) null).renderToString());
        }
    }

    @Test
    void loopWithoutElseDoesNotLeakItsClosingBrace() throws Exception {
        try (var compiled = compile("class Demo { static Html render(List<String> items??) { return (<ul>{for (String item? : items?) {<li>{item?}</li>}}</ul>); } }")) {
            assertEquals("<ul><li>one</li><li>two</li></ul>",
                    compiled.render(new Class<?>[]{List.class}, List.of("one", "two")).renderToString());
            assertEquals("<ul></ul>", compiled.render(new Class<?>[]{List.class}, List.of()).renderToString());
        }
    }

    @Test
    void nestedMarkupExpressionsHaveUniqueWritersAndHtmlTargetTypes() throws Exception {
        try (var compiled = compile("class Demo { static Html render(boolean show) { return (<div>{show then (<b>yes</b>) else (<i>no</i>)}</div>); } }")) {
            assertEquals("<div><b>yes</b></div>", compiled.render(new Class<?>[]{boolean.class}, true).renderToString());
            assertEquals("<div><i>no</i></div>", compiled.render(new Class<?>[]{boolean.class}, false).renderToString());
        }
    }

    @Test
    void nestedLoopsAndChildrenRetainTheirVariableScopes() throws Exception {
        try (var compiled = compile("class Demo { static Html render(List<String> items??) { return (<Box>{for (String item? : items?) {<Box>{if (item? != null) {<span>{item? else \"none\"}</span>} else {<i>missing</i>}}</Box>}}</Box>); } }")) {
            assertEquals("<section><section><span>one</span></section><section><i>missing</i></section></section>",
                    compiled.render(new Class<?>[]{List.class}, Arrays.asList("one", null)).renderToString());
        }
    }

    @Test
    void liftedConditionalExpressionsStayLazyUntilRenderingAndSelectedBranch() throws Exception {
        try (var compiled = compile("""
                class Demo {
                    static int calls = 0;
                    static String value?() { static.calls++; return null; }
                    static Html render(boolean show) {
                        return (<div>{if (show) {<b title={static.value()? else "tip"}>{static.value()? else "none"}</b>}}</div>);
                    }
                }
                """)) {
            Html hidden = compiled.render(new Class<?>[]{boolean.class}, false);
            assertEquals(0, compiled.type().getField("calls").getInt(null));
            assertEquals("<div></div>", hidden.renderToString());
            assertEquals(0, compiled.type().getField("calls").getInt(null));
            Html visible = compiled.render(new Class<?>[]{boolean.class}, true);
            assertEquals(0, compiled.type().getField("calls").getInt(null));
            assertEquals("<div><b title=\"tip\">none</b></div>", visible.renderToString());
            assertEquals(2, compiled.type().getField("calls").getInt(null));
            assertEquals("<div><b title=\"tip\">none</b></div>", visible.renderToString());
            assertEquals(4, compiled.type().getField("calls").getInt(null));
        }
    }
}
