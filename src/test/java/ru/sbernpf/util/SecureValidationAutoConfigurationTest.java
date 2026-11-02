package ru.sbernpf.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.sbernpf.util.autoconfigure.SecureValidationAutoConfiguration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureValidationAutoConfigurationTest {

    @SecureDto
    public static class Dto {
        public String name;
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecureValidationAutoConfiguration.class,
                    JacksonAutoConfiguration.class));

    @Test
    void registersModuleInBootObjectMapper() {
        runner.run(ctx -> {
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            assertThrows(SecurityValidationException.class,
                    () -> mapper.readValue("{\"name\":\"' OR 1=1 --\"}", Dto.class));
        });
    }

    @Test
    void appliesForbiddenPatternsFromProperties() {
        runner.withPropertyValues("sbernpf.secure-validation.forbidden-patterns[0]=\\bПАРОЛЬ\\b",
                "sbernpf.secure-validation.forbidden-patterns[1]=x{2,3}y").run(ctx -> {
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            assertThrows(SecurityValidationException.class,
                    () -> mapper.readValue("{\"name\":\"мой пароль\"}", Dto.class));
            assertThrows(SecurityValidationException.class,
                    () -> mapper.readValue("{\"name\":\"xxy\"}", Dto.class));
            assertDoesNotThrow(() -> mapper.readValue("{\"name\":\"Иванов\"}", Dto.class));
        });
    }

    @Test
    void invalidForbiddenPatternFailsStartup() {
        runner.withPropertyValues("sbernpf.secure-validation.forbidden-patterns[0]=[")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("sbernpf.secure-validation.enabled=false").run(ctx -> {
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            assertDoesNotThrow(() -> mapper.readValue("{\"name\":\"' OR 1=1 --\"}", Dto.class));
        });
    }
}
