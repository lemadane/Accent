package accent.html.spring;

import accent.html.Component;
import accent.html.Html;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

/** Handles native HTML even on controllers without @ResponseBody. */
public final class HtmlReturnValueHandler extends RequestResponseBodyMethodProcessor {
    public HtmlReturnValueHandler() {
        super(List.of(new HtmlHttpMessageConverter()));
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        Class<?> type = returnType.getParameterType();
        return Html.class.isAssignableFrom(type) || Component.class.isAssignableFrom(type);
    }

    @Override
    public void handleReturnValue(Object value, MethodParameter returnType,
            ModelAndViewContainer container, NativeWebRequest request)
            throws IOException, HttpMediaTypeNotAcceptableException {
        if (value != null) checkAccept(request);
        super.handleReturnValue(value, returnType, container, request);
    }

    static void checkAccept(NativeWebRequest request) throws HttpMediaTypeNotAcceptableException {
        // Spring 6.1 also considers q=0 compatible. Honor explicit HTML exclusions.
        var acceptable = new ArrayList<>(new HeaderContentNegotiationStrategy()
                .resolveMediaTypes(request));
        acceptable.sort(Comparator.comparingInt((MediaType type) ->
                type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2).reversed());
        for (MediaType type : acceptable) {
            if (type.isCompatibleWith(MediaType.TEXT_HTML)) {
                if (type.getQualityValue() == 0) {
                    throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_HTML));
                }
                return;
            }
        }
        throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_HTML));
    }
}
