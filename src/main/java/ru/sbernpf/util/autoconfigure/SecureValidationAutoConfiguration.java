package ru.sbernpf.util.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.sbernpf.util.SecureValidator;
import ru.sbernpf.util.jackson.SecureValidationModule;

import java.time.Clock;

/**
 * Регистрирует {@link SecureValidationModule} в ObjectMapper Spring Boot.
 * Отключается свойством {@code sbernpf.secure-validation.enabled=false}.
 */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnProperty(prefix = "sbernpf.secure-validation", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SecureValidationProperties.class)
public class SecureValidationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecureValidator secureValidator(SecureValidationProperties properties) {
        return new SecureValidator(Clock.systemUTC(), properties.getForbiddenPatterns());
    }

    @Bean
    @ConditionalOnMissingBean
    public SecureValidationModule secureValidationModule(SecureValidator secureValidator) {
        return new SecureValidationModule(secureValidator);
    }
}
