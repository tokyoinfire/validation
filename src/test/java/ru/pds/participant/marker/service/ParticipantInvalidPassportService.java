package ru.pds.participant.marker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.pds.participant.marker.dto.MarkerRequest;
import ru.pds.participant.marker.entity.ParticipantInvalidPassport;
import ru.pds.participant.marker.exception.MarkerException;
import ru.pds.participant.marker.repository.ParticipantInvalidPassportRepository;

/**
 * Признак «Недействительный паспорт».
 * add    — ДУЛ с переданными серией и номером получает статус «Не действует» (2);
 * delete — ДУЛ из действующей записи журнала возвращается в статус «Действует» (1).
 */
@Slf4j
@Service
public class ParticipantInvalidPassportService extends ParticipantMarkerService<ParticipantInvalidPassport> {

    static final int STATUS_VALID = 1;
    static final int STATUS_INVALID = 2;

    private final ParticipantInvalidPassportRepository repository;

    public ParticipantInvalidPassportService(ParticipantInvalidPassportRepository repository) {
        super(repository, ParticipantInvalidPassport::new, "Недействительный паспорт");
        this.repository = repository;
    }

    @Override
    protected void beforeOpen(ParticipantInvalidPassport marker, MarkerRequest request) {
        if (!StringUtils.hasText(request.serDul()) || !StringUtils.hasText(request.numDul())) {
            throw badRequest("для установки обязательны ser_dul и num_dul", request.participantId());
        }
        marker.setIdentityCardSeries(request.serDul());
        marker.setIdentityCardNumber(request.numDul());
    }

    @Override
    protected void afterOpen(ParticipantInvalidPassport marker, MarkerRequest request) {
        changeStatus(marker, STATUS_INVALID);
    }

    @Override
    protected void afterClose(ParticipantInvalidPassport marker, MarkerRequest request) {
        changeStatus(marker, STATUS_VALID);
    }

    private void changeStatus(ParticipantInvalidPassport marker, int status) {
        String series = marker.getIdentityCardSeries();
        String number = marker.getIdentityCardNumber();
        if (series == null || number == null) {
            // запись создана до появления серии/номера в журнале — ДУЛ не трогаем
            log.warn("В записи журнала id={} нет серии/номера ДУЛ, статус не меняется", marker.getId());
            return;
        }
        int updated = repository.updateIdentityCardStatus(series, number, status);
        if (updated == 0) {
            // исключение откатывает всю транзакцию, включая запись журнала
            throw new MarkerException(HttpStatus.NOT_FOUND,
                    "ДУЛ не найден по серии и номеру: participantId=" + marker.getParticipantId());
        }
        if (updated > 1) {
            log.warn("По серии и номеру обновлено {} ДУЛ: participantId={}", updated, marker.getParticipantId());
        }
        log.info("AUDIT ДУЛ status={} обновлено строк={} participantId={} recordId={}",
                status, updated, marker.getParticipantId(), marker.getId());
    }
}
