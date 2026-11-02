package ru.sbernpf.util.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import ru.sbernpf.util.SecureValidator;
import ru.sbernpf.util.kafka.ValidatingListenerPostProcessor;

/**
 * Проверяет аргументы слушателей Kafka.
 * Отключается свойством {@code sbernpf.secure-validation.kafka.enabled=false}.
 */
@AutoConfiguration(after = SecureValidationAutoConfiguration.class)
@ConditionalOnClass(KafkaListener.class)
@ConditionalOnProperty(prefix = "sbernpf.secure-validation", name = {"enabled", "kafka.enabled"}, matchIfMissing = true)
public class SecureKafkaAutoConfiguration {

    @Bean
    public static ValidatingListenerPostProcessor validatingListenerPostProcessor(
            ObjectProvider<SecureValidator> validator) {
        return new ValidatingListenerPostProcessor(validator::getObject);
    }
}
