package ru.sbernpf.util.web;

import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.method.support.UriComponentsContributor;
import org.springframework.web.multipart.support.MultipartResolutionDelegate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.sbernpf.util.DateTimeRange;
import ru.sbernpf.util.SecureField;
import ru.sbernpf.util.SecureValidator;
import ru.sbernpf.util.SecurityValidationException;
import ru.sbernpf.util.Violation;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Обёртка над штатным resolver'ом: проверяет значение, которое получит метод контроллера.
 * <p>
 * Параметры настраиваются теми же {@link SecureField} и {@link DateTimeRange}, что и поля DTO.
 * Загруженные файлы не проверяются. {@link UriComponentsContributor} нужен для
 * {@code MvcUriComponentsBuilder}: он ищет реализации этого интерфейса в списке resolver'ов.
 */
class ValidatingArgumentResolver implements HandlerMethodArgumentResolver, UriComponentsContributor {

    private final HandlerMethodArgumentResolver delegate;
    private final Supplier<SecureValidator> validator;

    ValidatingArgumentResolver(HandlerMethodArgumentResolver delegate, Supplier<SecureValidator> validator) {
        this.delegate = delegate;
        this.validator = validator;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return delegate.supportsParameter(parameter);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        Object value = delegate.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
        if (value != null && !MultipartResolutionDelegate.isMultipartArgument(parameter.nestedIfOptional())) {
            String name = parameter.getParameterName() != null
                    ? parameter.getParameterName() : "arg" + parameter.getParameterIndex();
            List<Violation> violations = validator.get().validateParameter(value, name,
                    parameter.getParameterAnnotation(SecureField.class),
                    parameter.getParameterAnnotation(DateTimeRange.class));
            if (!violations.isEmpty()) {
                throw new SecureParameterException(new SecurityValidationException(value.getClass(), violations));
            }
        }
        return value;
    }

    @Override
    public void contributeMethodArgument(MethodParameter parameter, Object value, UriComponentsBuilder builder,
                                         Map<String, Object> uriVariables, ConversionService conversionService) {
        if (delegate instanceof UriComponentsContributor) {
            ((UriComponentsContributor) delegate)
                    .contributeMethodArgument(parameter, value, builder, uriVariables, conversionService);
        }
    }
}
