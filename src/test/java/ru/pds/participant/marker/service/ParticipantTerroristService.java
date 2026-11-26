package ru.pds.participant.marker.service;

import org.springframework.stereotype.Service;
import ru.pds.participant.marker.entity.ParticipantTerrorist;
import ru.pds.participant.marker.repository.ParticipantTerroristRepository;

@Service
public class ParticipantTerroristService extends ParticipantMarkerService<ParticipantTerrorist> {

    public ParticipantTerroristService(ParticipantTerroristRepository repository) {
        super(repository, ParticipantTerrorist::new, "Принадлежность к Перечням Росфинмониторинга");
    }
}



{
  "size": 1000,
  "track_total_hits": true,
  "_source": ["message", "traceId", "tessaId", "idTessa"],
  "query": {
    "bool": {
      "filter": [
        { "range": { "@timestamp": { "gte": "now-15m", "lte": "now" } } }
      ],
      "minimum_should_match": 1,
      "should": [
        {
          "bool": {
            "_name": "sent",
            "filter": [
              { "term": { "service.name.keyword": "qppnpftessaexchangemessage" } },
              { "match_phrase": { "message": "Send pension application message to topic" } }
            ]
          }
        },
        {
          "bool": {
            "_name": "received",
            "filter": [
              { "term": { "service.name.keyword": "qppnpfpensionappassignpayments" } },
              { "exists": { "field": "traceId" } }
            ],
            "minimum_should_match": 1,
            "should": [
              { "exists": { "field": "tessaId" } },
              { "exists": { "field": "idTessa" } },
              { "match": { "message": "tessaId idTessa" } }
            ]
          }
        },
        {
          "bool": {
            "_name": "failed",
            "filter": [
              { "term": { "service.name.keyword": "qppnpfpensionappassignpayments" } },
              { "term": { "log.level.keyword": "ERROR" } },
              { "exists": { "field": "traceId" } }
            ]
          }
        }
      ]
    }
  }
}
