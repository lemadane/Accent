package io.lemadane.accent.logging.spring.boot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

public class AccentLoggingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AccentLoggingAutoConfiguration.class));

    @Test
    public void testContextLoads() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AccentLoggingProperties.class);
            assertThat(io.lemadane.accent.logging.LogManager.configuration()).isNotNull();
        });
    }
}
