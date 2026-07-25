package io.lemadane.jatot.logging.spring.boot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

public class JatotLoggingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JatotLoggingAutoConfiguration.class));

    @Test
    public void testContextLoads() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JatotLoggingProperties.class);
            assertThat(io.lemadane.jatot.logging.LogManager.configuration()).isNotNull();
        });
    }
}
