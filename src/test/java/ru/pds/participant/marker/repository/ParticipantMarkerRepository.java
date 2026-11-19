package ru.pds.participant.marker.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import ru.pds.participant.marker.entity.ParticipantMarker;

@NoRepositoryBean
public interface ParticipantMarkerRepository<T extends ParticipantMarker> extends JpaRepository<T, Long> {

    /**
     * Действующая запись по ФЛ. Строка блокируется (SELECT ... FOR UPDATE),
     * чтобы параллельные запросы на снятие обрабатывались по очереди.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<T> findByParticipantIdAndActiveTrue(Long participantId);

    /** Весь журнал по ФЛ, свежие записи сверху. */
    List<T> findAllByParticipantIdOrderByStartDateDesc(Long participantId);

    @Query(value = "select exists(select 1 from qpartparticipant.qpart_participant "
            + "where participantid = :participantId)", nativeQuery = true)
    boolean participantExists(@Param("participantId") Long participantId);
}
