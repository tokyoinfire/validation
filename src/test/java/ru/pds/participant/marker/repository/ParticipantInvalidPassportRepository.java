package ru.pds.participant.marker.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.pds.participant.marker.entity.ParticipantInvalidPassport;

public interface ParticipantInvalidPassportRepository
        extends ParticipantMarkerRepository<ParticipantInvalidPassport> {

    /**
     * Меняет статус ДУЛ в блоке ДУЛ по серии и номеру.
     *
     * @return количество обновлённых строк
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update qpartparticipant.qpart_individualidentitycard "
            + "set status = :status "
            + "where identitycardseries = :series and identitycardnumber = :number",
            nativeQuery = true)
    int updateIdentityCardStatus(@Param("series") String series,
                                 @Param("number") String number,
                                 @Param("status") int status);
}
