package accent.html.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class AccentHtmlAutoConfiguration implements WebMvcConfigurer {

    @Override
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        handlers.add(new HtmlReturnValueHandler());
    }

    @Bean
    public AccentFileRouter accentFileRouter() {
        return new AccentFileRouter();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureDb(javax.sql.DataSource dataSource) {
        if (dataSource != null) {
            accent.sql.Sql.setDataSource(dataSource);
        }
    }
}
