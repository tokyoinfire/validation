package ru.sbernpf.util.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import ru.sbernpf.util.SecurityValidationException;
import ru.sbernpf.util.Violation;

import java.util.List;

/**
 * Бросается, если параметр контроллера не прошёл проверки безопасности:
 * метод контроллера не вызывается, клиент получает HTTP 400.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class SecureParameterException extends RuntimeException {

    private final transient List<Violation> violations;

    public SecureParameterException(SecurityValidationException cause) {
        super(cause.getMessage(), cause);
        this.violations = cause.getViolations();
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
