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

/** Журнал признака «Принадлежность к Перечням Росфинмониторинга». */
@Getter
@Setter
@Entity
@Table(name = "qpart_participantterrorist", schema = "qpartparticipant")
public class ParticipantTerrorist extends ParticipantMarker {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "participantTerroristSeq")
    @SequenceGenerator(name = "participantTerroristSeq", schema = "qpartparticipant",
            sequenceName = "qpart_participantterrorist_seq", allocationSize = 1)
    @Column(name = "participantterroristid")
    private Long id;
}
