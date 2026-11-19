package ru.pds.participant.marker.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Ошибка обработки запроса на установку/снятие признака с HTTP-статусом для ответа. */
@Getter
public class MarkerException extends RuntimeException {

    private final HttpStatus status;

    public MarkerException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
