package ru.pds.participant.marker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Общая часть записи журнала признака ФЛ: одна запись = один период действия признака.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class ParticipantMarker {

    /** Ссылка на карточку ФЛ (qpart_participant.participantid). */
    @Column(name = "participantid", nullable = false, updatable = false)
    private Long participantId;

    /** Признак установлен (галочка стоит). */
    @Column(name = "isactive", nullable = false)
    private boolean active;

    /** Дата начала действия признака. */
    @Column(name = "startdate", nullable = false)
    private LocalDateTime startDate;

    /** Дата окончания действия признака; null, пока признак не снят. */
    @Column(name = "enddate")
    private LocalDateTime endDate;

    @CreationTimestamp
    @Column(name = "createddate", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @UpdateTimestamp
    @Column(name = "updateddate", nullable = false)
    private LocalDateTime updatedDate;

    public abstract Long getId();
}
