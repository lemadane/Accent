package accent.html.spring;

import accent.html.Html;
import accent.html.Component;
import accent.html.HtmlWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

public final class HtmlReturnValueHandler implements HandlerMethodReturnValueHandler {

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        Class<?> type = returnType.getParameterType();
        return Html.class.isAssignableFrom(type) || Component.class.isAssignableFrom(type);
    }

    @Override
    public void handleReturnValue(
            Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer container,
            NativeWebRequest request
    ) throws Exception {
        container.setRequestHandled(true);

        if (returnValue == null) {
            return;
        }

        HttpServletResponse response = request.getNativeResponse(HttpServletResponse.class);
        if (response == null) {
            throw new IllegalStateException("HttpServletResponse not available");
        }

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");

        Html html;
        if (returnValue instanceof Component component) {
            html = component.render();
        } else if (returnValue instanceof Html val) {
            html = val;
        } else {
            throw new IllegalStateException("Unexpected return value type: " + returnValue.getClass().getName());
        }

        if (html != null) {
            HtmlWriter writer = new HtmlWriter(response.getWriter());
            html.writeTo(writer);
            writer.flush();
        }
    }
}
