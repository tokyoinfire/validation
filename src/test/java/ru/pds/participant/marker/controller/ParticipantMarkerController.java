package ru.pds.participant.marker.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.pds.participant.marker.dto.MarkerRequest;
import ru.pds.participant.marker.dto.MarkerResponse;
import ru.pds.participant.marker.exception.MarkerException;
import ru.pds.participant.marker.service.ParticipantInvalidPassportService;
import ru.pds.participant.marker.service.ParticipantTerroristService;

@RestController
@RequestMapping("/api/v1/participants")
@RequiredArgsConstructor
public class ParticipantMarkerController {

    private final ParticipantTerroristService terroristService;
    private final ParticipantInvalidPassportService invalidPassportService;

    /** Установка/снятие признака «Принадлежность к Перечням Росфинмониторинга». */
    @PostMapping("/markers/terrorist")
    public MarkerResponse applyTerrorist(@Valid @RequestBody MarkerRequest request, Principal principal) {
        return terroristService.apply(request, login(principal));
    }

    /** Установка/снятие признака «Недействительный паспорт». */
    @PostMapping("/markers/invalid-passport")
    public MarkerResponse applyInvalidPassport(@Valid @RequestBody MarkerRequest request, Principal principal) {
        return invalidPassportService.apply(request, login(principal));
    }

    /** Журнал признака по ФЛ — для вкладки в карточке клиента. */
    @GetMapping("/{participantId}/markers/terrorist")
    public List<MarkerResponse> terroristJournal(@PathVariable("participantId") Long participantId) {
        return terroristService.journal(participantId);
    }

    @GetMapping("/{participantId}/markers/invalid-passport")
    public List<MarkerResponse> invalidPassportJournal(@PathVariable("participantId") Long participantId) {
        return invalidPassportService.journal(participantId);
    }

    @ExceptionHandler(MarkerException.class)
    public ProblemDetail handleMarkerException(MarkerException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
    }

    private static String login(Principal principal) {
        return principal != null ? principal.getName() : "unknown";
    }
}
