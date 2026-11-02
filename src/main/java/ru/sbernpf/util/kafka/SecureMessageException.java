package ru.sbernpf.util.kafka;

import org.springframework.messaging.converter.MessageConversionException;
import ru.sbernpf.util.SecurityValidationException;
import ru.sbernpf.util.Violation;

import java.util.List;

/**
 * Бросается, если сообщение Kafka не прошло проверки безопасности: слушатель не вызывается.
 * <p>
 * Наследует {@link MessageConversionException}: обработчики ошибок spring-kafka не повторяют
 * такие сообщения, а сразу передают их recoverer'у (по умолчанию пишут в лог и пропускают).
 */
public class SecureMessageException extends MessageConversionException {

    private final transient List<Violation> violations;

    public SecureMessageException(SecurityValidationException cause) {
        super(cause.getMessage(), cause);
        this.violations = cause.getViolations();
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
