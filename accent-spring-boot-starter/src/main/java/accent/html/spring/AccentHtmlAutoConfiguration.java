package accent.html.spring;

import java.util.ArrayList;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;

@AutoConfiguration(after = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, RequestMappingHandlerAdapter.class})
public class AccentHtmlAutoConfiguration {

    @Bean
    public static BeanPostProcessor accentHtmlReturnValueHandlerConfigurer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RequestMappingHandlerAdapter adapter && adapter.getReturnValueHandlers() != null) {
                    var handlers = new ArrayList<HandlerMethodReturnValueHandler>();
                    // Native HTML must be selected before Spring's @ResponseBody handler.
                    handlers.add(new HtmlReturnValueHandler());
                    for (var handler : adapter.getReturnValueHandlers()) {
                        if (!(handler instanceof HtmlReturnValueHandler)) handlers.add(handler);
                    }
                    adapter.setReturnValueHandlers(handlers);
                }
                return bean;
            }
        };
    }

    @Bean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    public AccentFileRouter accentFileRouter() {
        return new AccentFileRouter();
    }
}
