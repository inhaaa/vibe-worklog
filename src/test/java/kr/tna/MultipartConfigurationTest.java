package kr.tna;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.multipart.MultipartResolver;

class MultipartConfigurationTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class));

    @Test
    void baseConfigurationDisablesMultipartParsing() {
        contextRunner.withPropertyValues("spring.profiles.active=").run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(MultipartResolver.class)
                    .doesNotHaveBean(MultipartConfigElement.class);
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(context.getEnvironment().getProperty("spring.application.name"))
                    .isEqualTo("vibe-attendance");
            assertThat(context.getEnvironment().getProperty("spring.servlet.multipart.enabled", Boolean.class))
                    .isFalse();
        });
    }

    @Test
    void localConfigurationDisablesMultipartParsing() {
        contextRunner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(MultipartResolver.class)
                    .doesNotHaveBean(MultipartConfigElement.class);
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("local");
            assertThat(context.getEnvironment().getProperty("spring.thymeleaf.cache", Boolean.class))
                    .isFalse();
            assertThat(context.getEnvironment().getProperty("spring.servlet.multipart.enabled", Boolean.class))
                    .isFalse();
        });
    }
}
