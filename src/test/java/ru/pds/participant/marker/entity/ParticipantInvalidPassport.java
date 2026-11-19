package ru.pds.participant.marker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Журнал признака «Недействительный паспорт». */
@Getter
@Setter
@Entity
@Table(name = "qpart_participantinvalidpassport", schema = "qpartparticipant")
public class ParticipantInvalidPassport extends ParticipantMarker {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "participantInvalidPassportSeq")
    @SequenceGenerator(name = "participantInvalidPassportSeq", schema = "qpartparticipant",
            sequenceName = "qpart_participantinvalidpassport_seq", allocationSize = 1)
    @Column(name = "participantinvalidpassportid")
    private Long id;

    /** Серия ДУЛ, который признан недействительным. */
    @Column(name = "identitycardseries", length = 4)
    private String identityCardSeries;

    /** Номер ДУЛ, который признан недействительным. */
    @Column(name = "identitycardnumber", length = 6)
    private String identityCardNumber;
}
