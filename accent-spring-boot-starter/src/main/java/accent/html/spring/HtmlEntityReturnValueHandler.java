package accent.html.spring;

import accent.html.Component;
import accent.html.Html;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpEntity;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor;

/** Keeps native HTML entities from falling through to JSON converters. */
public final class HtmlEntityReturnValueHandler extends HttpEntityMethodProcessor {
    public HtmlEntityReturnValueHandler() {
        super(List.of(new HtmlHttpMessageConverter()));
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        if (!super.supportsReturnType(returnType)) return false;
        Class<?> body = ResolvableType.forMethodParameter(returnType).getGeneric(0).resolve();
        return body != null && (Html.class.isAssignableFrom(body) || Component.class.isAssignableFrom(body));
    }

    @Override
    public void handleReturnValue(Object value, MethodParameter returnType,
            ModelAndViewContainer container, NativeWebRequest request)
            throws Exception {
        if (value instanceof HttpEntity<?> entity && entity.getBody() != null) {
            HtmlReturnValueHandler.checkAccept(request);
        }
        super.handleReturnValue(value, returnType, container, request);
    }
}
