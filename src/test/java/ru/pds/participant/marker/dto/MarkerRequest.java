package ru.pds.participant.marker.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

/**
 * Тело запроса на установку/снятие признака:
 * {"FaceID": "123456789", "action": "add", "dt": "2025-03-01T16:13:00.123",
 *  "ser_dul": "1234", "num_dul": "123456"}
 *
 * @param dt     дата начала (add) или окончания (delete) действия признака; если не передана — текущая
 * @param serDul серия ДУЛ — только для признака «Недействительный паспорт»
 * @param numDul номер ДУЛ — только для признака «Недействительный паспорт»
 */
public record MarkerRequest(
        @NotNull @JsonProperty("FaceID") @JsonAlias("participantID") Long participantId,
        @NotNull Action action,
        LocalDateTime dt,
        @Pattern(regexp = "\\d{4}", message = "ser_dul: 4 цифры") @JsonProperty("ser_dul") String serDul,
        @Pattern(regexp = "\\d{6}", message = "num_dul: 6 цифр") @JsonProperty("num_dul") String numDul) {

    public enum Action {
        @JsonProperty("add") ADD,
        @JsonProperty("delete") DELETE
    }
}
