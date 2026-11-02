package ru.sbernpf.util;

import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.Closeable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Бросается, если DTO с {@link SecureDto} не прошёл проверки безопасности.
 * <p>
 * Наследует {@link JsonMappingException}, поэтому Spring MVC при десериализации
 * тела запроса вернёт клиенту HTTP 400 (через {@code HttpMessageNotReadableException}).
 */
public class SecurityValidationException extends JsonMappingException {

    private final transient List<Violation> violations;

    public SecurityValidationException(Class<?> dtoClass, List<Violation> violations) {
        super((Closeable) null, buildMessage(dtoClass, violations));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() {
        return violations;
    }

    private static String buildMessage(Class<?> dtoClass, List<Violation> violations) {
        return "Security validation failed for " + dtoClass.getSimpleName() + ": "
                + violations.stream().map(Violation::toString).collect(Collectors.joining("; "));
    }
}
