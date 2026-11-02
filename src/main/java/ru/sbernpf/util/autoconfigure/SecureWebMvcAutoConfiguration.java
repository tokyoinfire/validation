package ru.sbernpf.util.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import ru.sbernpf.util.SecureValidator;
import ru.sbernpf.util.web.ValidatingArgumentResolversPostProcessor;

/**
 * Проверяет параметры контроллеров Spring MVC.
 * Отключается свойством {@code sbernpf.secure-validation.web.enabled=false}.
 */
@AutoConfiguration(after = SecureValidationAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RequestMappingHandlerAdapter.class)
@ConditionalOnProperty(prefix = "sbernpf.secure-validation", name = {"enabled", "web.enabled"}, matchIfMissing = true)
public class SecureWebMvcAutoConfiguration {

    @Bean
    public static ValidatingArgumentResolversPostProcessor validatingArgumentResolversPostProcessor(
            ObjectProvider<SecureValidator> validator) {
        return new ValidatingArgumentResolversPostProcessor(validator::getObject);
    }
}
