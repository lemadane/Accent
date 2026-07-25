package io.lemadane.jatot.html.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class JatotHtmlAutoConfiguration implements WebMvcConfigurer {

    @Override
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        handlers.add(new HtmlReturnValueHandler());
    }

    @Bean
    public JatotFileRouter jatotFileRouter() {
        return new JatotFileRouter();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureDb(javax.sql.DataSource dataSource) {
        if (dataSource != null) {
            io.lemadane.jatot.sql.Sql.setDataSource(dataSource);
        }
    }
}
