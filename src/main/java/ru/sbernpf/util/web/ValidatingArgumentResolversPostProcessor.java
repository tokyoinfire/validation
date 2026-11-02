package ru.sbernpf.util.web;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.method.annotation.AbstractCookieValueMethodArgumentResolver;
import org.springframework.web.method.annotation.ModelAttributeMethodProcessor;
import org.springframework.web.method.annotation.RequestHeaderMethodArgumentResolver;
import org.springframework.web.method.annotation.RequestParamMapMethodArgumentResolver;
import org.springframework.web.method.annotation.RequestParamMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.MatrixVariableMapMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.MatrixVariableMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.PathVariableMapMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.PathVariableMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestPartMethodArgumentResolver;
import ru.sbernpf.util.SecureValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Оборачивает resolver'ы пользовательского ввода в {@link ValidatingArgumentResolver}.
 * <p>
 * Работает на уровне resolver'ов, а не сервлетного фильтра: в их сигнатурах нет
 * {@code javax.servlet}/{@code jakarta.servlet}, поэтому один jar подходит для Spring Boot 2 и 3.
 * {@code @RequestBody} проверяется Jackson-модулем. {@code @RequestHeader Map} и {@code HttpHeaders}
 * не проверяются: штатный {@code Accept: *}{@code /*} похож на конец SQL-комментария.
 */
public class ValidatingArgumentResolversPostProcessor implements BeanPostProcessor {

    private static final List<Class<?>> USER_INPUT_RESOLVERS = List.of(
            RequestParamMethodArgumentResolver.class,
            RequestParamMapMethodArgumentResolver.class,
            PathVariableMethodArgumentResolver.class,
            PathVariableMapMethodArgumentResolver.class,
            MatrixVariableMethodArgumentResolver.class,
            MatrixVariableMapMethodArgumentResolver.class,
            RequestHeaderMethodArgumentResolver.class,
            AbstractCookieValueMethodArgumentResolver.class,
            ModelAttributeMethodProcessor.class,
            RequestPartMethodArgumentResolver.class);

    private final Supplier<SecureValidator> validator;

    public ValidatingArgumentResolversPostProcessor(Supplier<SecureValidator> validator) {
        this.validator = validator;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RequestMappingHandlerAdapter) {
            RequestMappingHandlerAdapter adapter = (RequestMappingHandlerAdapter) bean;
            List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
            for (HandlerMethodArgumentResolver resolver : adapter.getArgumentResolvers()) {
                resolvers.add(isUserInput(resolver) ? new ValidatingArgumentResolver(resolver, validator) : resolver);
            }
            adapter.setArgumentResolvers(resolvers);
        }
        return bean;
    }

    private static boolean isUserInput(HandlerMethodArgumentResolver resolver) {
        return USER_INPUT_RESOLVERS.stream().anyMatch(type -> type.isInstance(resolver));
    }
}
