package ru.pds.participant.marker.dto;

import java.time.LocalDateTime;
import ru.pds.participant.marker.entity.ParticipantMarker;

/** Запись журнала признака. */
public record MarkerResponse(
        Long id,
        Long participantId,
        boolean active,
        LocalDateTime startDate,
        LocalDateTime endDate,
        LocalDateTime createdDate,
        LocalDateTime updatedDate) {

    public static MarkerResponse from(ParticipantMarker m) {
        return new MarkerResponse(m.getId(), m.getParticipantId(), m.isActive(),
                m.getStartDate(), m.getEndDate(), m.getCreatedDate(), m.getUpdatedDate());
    }
}
