package accent.html.spring;

import accent.html.Component;
import accent.html.Html;
import accent.html.HtmlWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;

/** Streams Accent HTML through Spring's regular response entity processing. */
public final class HtmlHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
    public HtmlHttpMessageConverter() {
        super(StandardCharsets.UTF_8, MediaType.TEXT_HTML);
    }

    @Override
    protected boolean supports(Class<?> type) {
        return Html.class.isAssignableFrom(type) || Component.class.isAssignableFrom(type);
    }

    @Override
    public boolean canRead(Class<?> type, MediaType mediaType) {
        return false;
    }

    @Override
    protected Object readInternal(Class<?> type, HttpInputMessage input) {
        throw new UnsupportedOperationException("Accent HTML is a response type");
    }

    @Override
    protected void writeInternal(Object value, HttpOutputMessage output) throws IOException {
        Html html = value instanceof Component component ? component.render() : (Html) value;
        if (html == null) return;
        MediaType contentType = output.getHeaders().getContentType();
        var charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset() : StandardCharsets.UTF_8;
        if (contentType != null && contentType.getCharset() == null) {
            output.getHeaders().setContentType(new MediaType(contentType, charset));
        }
        HtmlWriter writer = new HtmlWriter(new OutputStreamWriter(output.getBody(), charset));
        html.writeTo(writer);
        writer.flush();
    }
}
