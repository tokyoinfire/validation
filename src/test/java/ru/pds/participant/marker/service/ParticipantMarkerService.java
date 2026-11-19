package ru.pds.participant.marker.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import ru.pds.participant.marker.dto.MarkerRequest;
import ru.pds.participant.marker.dto.MarkerResponse;
import ru.pds.participant.marker.entity.ParticipantMarker;
import ru.pds.participant.marker.exception.MarkerException;
import ru.pds.participant.marker.repository.ParticipantMarkerRepository;

/**
 * Общая логика журнала признака ФЛ.
 * <ul>
 *   <li>add, действующей записи нет (в т.ч. признак раньше снимали) — создаётся новая запись;</li>
 *   <li>add, действующая запись есть — 400;</li>
 *   <li>delete, действующая запись есть — она закрывается (правится текущая запись);</li>
 *   <li>delete, действующей записи нет — 400.</li>
 * </ul>
 */
@Slf4j
public abstract class ParticipantMarkerService<T extends ParticipantMarker> {

    private final ParticipantMarkerRepository<T> repository;
    private final Supplier<T> newRecord;
    private final String markerName;

    protected ParticipantMarkerService(ParticipantMarkerRepository<T> repository,
                                       Supplier<T> newRecord,
                                       String markerName) {
        this.repository = repository;
        this.newRecord = newRecord;
        this.markerName = markerName;
    }

    @Transactional
    public MarkerResponse apply(MarkerRequest request, String login) {
        Long participantId = request.participantId();
        LocalDateTime dt = request.dt() != null ? request.dt() : LocalDateTime.now();

        if (!repository.participantExists(participantId)) {
            throw new MarkerException(HttpStatus.NOT_FOUND, "ФЛ не найдено: participantId=" + participantId);
        }
        Optional<T> active = repository.findByParticipantIdAndActiveTrue(participantId);

        T marker = switch (request.action()) {
            case ADD -> {
                if (active.isPresent()) {
                    throw badRequest("уже установлен и не был снят", participantId);
                }
                T opened = open(participantId, dt, request);
                afterOpen(opened, request);
                yield opened;
            }
            case DELETE -> {
                T closed = close(
                        active.orElseThrow(() -> badRequest("не установлен, снимать нечего", participantId)), dt);
                afterClose(closed, request);
                yield closed;
            }
        };

        log.info("AUDIT признак=«{}» action={} participantId={} dt={} recordId={} user={}",
                markerName, request.action(), participantId, dt, marker.getId(), login);
        return MarkerResponse.from(marker);
    }

    @Transactional(readOnly = true)
    public List<MarkerResponse> journal(Long participantId) {
        return repository.findAllByParticipantIdOrderByStartDateDesc(participantId).stream()
                .map(MarkerResponse::from)
                .toList();
    }

    /** Заполнить специфичные поля новой записи до сохранения (и проверить запрос). */
    protected void beforeOpen(T marker, MarkerRequest request) {
    }

    /** Побочные действия после установки признака, в той же транзакции. */
    protected void afterOpen(T marker, MarkerRequest request) {
    }

    /** Побочные действия после снятия признака, в той же транзакции. */
    protected void afterClose(T marker, MarkerRequest request) {
    }

    private T open(Long participantId, LocalDateTime dt, MarkerRequest request) {
        T marker = newRecord.get();
        marker.setParticipantId(participantId);
        marker.setActive(true);
        marker.setStartDate(dt);
        beforeOpen(marker, request);
        try {
            return repository.saveAndFlush(marker);
        } catch (DataIntegrityViolationException e) {
            // параллельный запрос успел установить признак — сработал уникальный индекс по действующей записи
            throw badRequest("уже установлен и не был снят", participantId);
        }
    }

    private T close(T marker, LocalDateTime dt) {
        if (dt.isBefore(marker.getStartDate())) {
            throw badRequest("нельзя снять датой раньше даты установки", marker.getParticipantId());
        }
        marker.setActive(false);
        marker.setEndDate(dt);
        return repository.saveAndFlush(marker);
    }

    protected MarkerException badRequest(String reason, Long participantId) {
        return new MarkerException(HttpStatus.BAD_REQUEST,
                "Признак «%s» %s: participantId=%d".formatted(markerName, reason, participantId));
    }
}
