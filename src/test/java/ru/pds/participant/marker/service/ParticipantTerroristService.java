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


source = `pds-dslog-*`
| where match_phrase(service, 'qppnpftessaexchangemessage')
    AND match_phrase(message, 'Message received from TESSA')
    AND match_phrase(message, 'paymentsAssignment = UniversalApplicationsDTO')
| parse message "(?s).*?ID_Tessa[ ]*=[ ]*'(?<uuid>[0-9A-Fa-f-]{36})'.*"
| where isnotnull(uuid) AND uuid != ''
| eval tessaId = lower(uuid), received_at = `@timestamp`
| eval deadline = timestampadd(MINUTE, 15, received_at)
| fields tessaId, received_at, deadline
| join type=left max=0 left=l right=r
    on l.tessaId = r.successUuid
    AND r.success_at >= l.received_at
    AND r.success_at <= l.deadline
    [
      source = `pds-dslog-*`
      | where match_phrase(service, 'qppnpfpensionappassignpayments')
          AND match_phrase(message, 'Сообщение успешно обработано и отправлено')
      | parse message "(?s).*?Tessa_ID[ ]*=[ ]*'(?<uuid>[0-9A-Fa-f-]{36})'.*"
      | where isnotnull(uuid) AND uuid != ''
      | eval successUuid = lower(uuid), success_at = `@timestamp`
      | fields successUuid, success_at
    ]
| stats min(r.success_at) as processed_at
    by l.tessaId as tessaId, l.received_at as received_at, l.deadline as deadline
| eval status = if(isnotnull(processed_at), 'Успешно',
                    if(now() >= deadline, 'Просрочено', 'Ожидает'))
| sort - received_at



def readUuid(String message, String key) {
    int keyAt = message.indexOf(key);
    if (keyAt < 0) return null;

    String tail = message.substring(keyAt + key.length()).trim();
    if (!tail.startsWith("=")) return null;

    tail = tail.substring(1).trim();
    if (!tail.startsWith("'")) return null;

    int end = tail.indexOf("'", 1);
    if (end != 37) return null;

    return tail.substring(1, end).toLowerCase();
}

long eventTime(def hit) {
    return Long.parseLong(hit['fields']['@timestamp'][0].toString());
}

if (ctx.results == null || ctx.results.isEmpty()) return false;

def result = ctx.results[0];
def hits = result['hits']['hits'];
if (result['timed_out'] == true || result['_shards']['failed'] > 0 ||
    result['hits']['total']['value'] > hits.size()) return false;

def successesByUuid = new HashMap();

for (def hit : hits) {
    def source = hit['_source'];
    if (source['service'] != 'qppnpfpensionappassignpayments') continue;

    def uuid = readUuid(source['message'], 'Tessa_ID');
    if (uuid == null) continue;

    if (!successesByUuid.containsKey(uuid)) {
        successesByUuid.put(uuid, new ArrayList());
    }
    successesByUuid.get(uuid).add(eventTime(hit));
}

for (def hit : hits) {
    def source = hit['_source'];
    if (source['service'] != 'qppnpftessaexchangemessage') continue;

    def uuid = readUuid(source['message'], 'ID_Tessa');
    if (uuid == null) continue;

    long startedAt = eventTime(hit);
    long deadline = startedAt + 900000L;
    boolean succeeded = false;

    def successTimes = successesByUuid.get(uuid);
    if (successTimes != null) {
        for (def successAt : successTimes) {
            if (successAt >= startedAt && successAt <= deadline) {
                succeeded = true;
                break;
            }
        }
    }

    if (!succeeded) return true;
}

return false;



{
  "size": 1000,
  "track_total_hits": true,
  "_source": ["service", "message"],
  "docvalue_fields": [
    { "field": "@timestamp", "format": "epoch_millis" }
  ],
  "query": {
    "bool": {
      "minimum_should_match": 1,
      "should": [
        {
          "bool": {
            "filter": [
              { "match_phrase": { "service": "qppnpftessaexchangemessage" } },
              { "match_phrase": { "message": "Message received from TESSA" } },
              { "match_phrase": { "message": "paymentsAssignment = UniversalApplicationsDTO" } },
              { "range": { "@timestamp": { "gte": "now-20m", "lte": "now-15m" } } }
            ]
          }
        },
        {
          "bool": {
            "filter": [
              { "match_phrase": { "service": "qppnpfpensionappassignpayments" } },
              { "match_phrase": { "message": "Сообщение успешно обработано и отправлено" } },
              { "range": { "@timestamp": { "gte": "now-20m", "lte": "now" } } }
            ]
          }
        }
      ]
    }
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
