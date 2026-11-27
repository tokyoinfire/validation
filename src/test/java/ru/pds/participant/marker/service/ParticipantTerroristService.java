
BEGIN;

WITH bank_mapping (old_bic, new_bic, new_corr_account, new_bank_name) AS (
    VALUES
        -- Excel
        ('045655635', '043807606', '30101810300000000606', 'КУРСКОЕ ОТДЕЛЕНИЕ N8596'),
        ('048952615', '047308602', '30101810000000000602', 'УЛЬЯНОВСКОЕ ОТДЕЛЕНИЕ N8588'),
        ('041708602', '047308602', '30101810000000000602', 'УЛЬЯНОВСКОЕ ОТДЕЛЕНИЕ N8588'),
        ('044206604', '040173604', '30101810200000000604', 'АЛТАЙСКОЕ ОТДЕЛЕНИЕ N8644'),
        ('047601637', '049514608', '30101810500000000608', 'АБАКАНСКОЕ ОТДЕЛЕНИЕ N8602'),
        ('046850649', '042007681', '30101810600000000681', 'ЦЕНТРАЛЬНО-ЧЕРНОЗЕМНЫЙ БАНК'),
        ('041909644', '044705615', '30101810300000000615', 'МУРМАНСКОЕ ОТДЕЛЕНИЕ N8627'),
        ('049401601', '048073601', '30101810300000000601', 'БАШКИРСКОЕ ОТДЕЛЕНИЕ N8598'),
        ('049706609', '043304609', '30101810500000000609', 'КИРОВСКОЕ ОТДЕЛЕНИЕ N8612'),

        -- Страница задачи
        ('042908612', '043304609', '30101810500000000609', 'КИРОВСКОЕ ОТДЕЛЕНИЕ N8612'),
        ('045805602', '044705615', '30101810300000000615', 'МУРМАНСКОЕ ОТДЕЛЕНИЕ N8627'),
        ('046126614', '045402601', '30101810300000000601', 'ОРЛОВСКОЕ ОТДЕЛЕНИЕ № 8595'),
        ('046902606', '047102651', '30101810800000000651', 'ЗАПАДНО-СИБИРСКОЕ ОТДЕЛЕНИЕ № 8647'),
        ('048602673', '044705615', '30101810300000000615', 'МУРМАНСКОЕ ОТДЕЛЕНИЕ N8627'),
        ('044442607', '042520607', '30101810900000000607', 'БАЙКАЛЬСКИЙ БАНК'),
        ('041501601', '045402601', '30101810300000000601', 'ОРЛОВСКОЕ ОТДЕЛЕНИЕ № 8595')
)
UPDATE qpartparticipant.qpart_paymentdetail AS pd
SET additionalinformation = (
    pd.additionalinformation::jsonb || jsonb_build_object(
        'bic',             m.new_bic,
        'bankCorrAccount', m.new_corr_account,
        'bankName',        m.new_bank_name
    )
)::text
FROM bank_mapping AS m
WHERE pd.status = 1
  AND pd.additionalinformation::jsonb ->> 'bic' = m.old_bic
  AND EXISTS (
      SELECT 1
      FROM qcppstatementforpayment.qcpp_pensionreestrcontents AS qp
      JOIN qcppstatementforpayment.qcpp_statementforpayment AS qs
        ON qs.pensionreestrid = qp.statementforpaymentid
      WHERE qp.statementforpaymentid IN (4304, 4302)
        AND qp.recipientid = pd.participantid
  )
RETURNING
    pd.participantid,
    m.old_bic,
    pd.additionalinformation::jsonb ->> 'bic' AS new_bic,
    pd.additionalinformation::jsonb ->> 'bankCorrAccount' AS new_corr_account,
    pd.additionalinformation::jsonb ->> 'bankName' AS new_bank_name;

ROLLBACK;

{
  "$schema": "https://vega.github.io/schema/vega/v5.json",
  "title": "Успешные и ожидающие заявки TESSA",
  "width": 730,
  "autosize": "none",
  "signals": [
    {
      "name": "height",
      "update": "max(80, 40 + length(data('rows')) * 21)"
    }
  ],
  "data": [
    {
      "name": "logs",
      "url": {
        "index": "pds-dslog-*",
        "body": {
          "size": 0,
          "query": {
            "bool": {
              "filter": [
                {
                  "range": {
                    "@timestamp": {
                      "lte": "now"
                    }
                  }
                }
              ],
              "minimum_should_match": 1,
              "should": [
                {
                  "bool": {
                    "filter": [
                      {
                        "range": {
                          "@timestamp": {
                            "%timefilter%": true
                          }
                        }
                      },
                      {
                        "match_phrase": {
                          "service": "qppnpftessaexchangemessage"
                        }
                      },
                      {
                        "match_phrase": {
                          "message": "Message received from TESSA"
                        }
                      },
                      {
                        "match_phrase": {
                          "message": "paymentsAssignment = UniversalApplicationsDTO"
                        }
                      }
                    ]
                  }
                },
                {
                  "bool": {
                    "filter": [
                      {
                        "range": {
                          "@timestamp": {
                            "gte": {
                              "%timefilter%": "min"
                            },
                            "lte": {
                              "%timefilter%": "max",
                              "shift": -15,
                              "unit": "minute"
                            }
                          }
                        }
                      },
                      {
                        "match_phrase": {
                          "service": "qppnpfpensionappassignpayments"
                        }
                      },
                      {
                        "match_phrase": {
                          "message": "Сообщение успешно обработано и отправлено"
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          "aggs": {
            "tessa_events": {
              "scripted_metric": {
                "init_script": "state.events = new ArrayList();",
                "map_script": {
                  "lang": "painless",
                  "source": "def src = params['_source'];\nif (src == null || src.message == null || doc['@timestamp'].size() == 0) return;\nboolean received = 'qppnpftessaexchangemessage'.equals(src.service);\nString message = src.message;\nString marker = received ? 'ID_Tessa' : 'Tessa_ID';\nint at = message.indexOf(marker);\nif (at < 0) return;\nString tail = message.substring(at + marker.length()).trim();\nif (!tail.startsWith('=')) return;\ntail = tail.substring(1).trim();\nif (!tail.startsWith(\"'\")) return;\nint end = tail.indexOf(\"'\", 1);\nif (end != 37) return;\nString uuid = tail.substring(1, end).toLowerCase();\nlong timestamp = doc['@timestamp'].value.toInstant().toEpochMilli();\nstate.events.add(['uuid': uuid, 'time': timestamp, 'received': received]);"
                },
                "combine_script": "return state.events;",
                "reduce_script": "def events = new ArrayList();\nfor (def shard : states) {\n  if (shard != null) events.addAll(shard);\n}\nreturn events;"
              }
            }
          }
        }
      },
      "format": {
        "property": "aggregations.tessa_events.value"
      }
    },
    {
      "name": "events",
      "source": "logs",
      "transform": [
        {
          "type": "filter",
          "expr": "test(regexp('^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'), datum.uuid)"
        },
        {
          "type": "identifier",
          "as": "eventId"
        }
      ]
    },
    {
      "name": "successes",
      "source": "events",
      "transform": [
        {
          "type": "filter",
          "expr": "!datum.received"
        },
        {
          "type": "aggregate",
          "groupby": [
            "uuid"
          ],
          "fields": [
            "time"
          ],
          "ops": [
            "values"
          ],
          "as": [
            "matches"
          ]
        }
      ]
    },
    {
      "name": "rows",
      "source": "events",
      "transform": [
        {
          "type": "filter",
          "expr": "datum.received"
        },
        {
          "type": "lookup",
          "from": "successes",
          "key": "uuid",
          "fields": [
            "uuid"
          ],
          "values": [
            "matches"
          ],
          "as": [
            "matches"
          ]
        },
        {
          "type": "formula",
          "as": "candidates",
          "expr": "datum.matches || [null]"
        },
        {
          "type": "flatten",
          "fields": [
            "candidates"
          ],
          "as": [
            "success"
          ]
        },
        {
          "type": "formula",
          "as": "onTime",
          "expr": "datum.success && datum.success.time >= datum.time && datum.success.time <= datum.time + 900000 ? datum.success.time : null"
        },
        {
          "type": "aggregate",
          "groupby": [
            "eventId",
            "uuid",
            "time"
          ],
          "fields": [
            "onTime"
          ],
          "ops": [
            "min"
          ],
          "as": [
            "processed"
          ]
        },
        {
          "type": "formula",
          "as": "priority",
          "expr": "isValid(datum.processed) ? 2 : now() >= datum.time + 900000 ? 0 : 1"
        },
        {
          "type": "formula",
          "as": "status",
          "expr": "datum.priority === 0 ? 'Просрочено' : datum.priority === 1 ? 'Ожидает' : 'Успешно'"
        },
        {
          "type": "filter",
          "expr": "datum.status === 'Успешно' || datum.status === 'Ожидает'"
        },
        {
          "type": "window",
          "sort": {
            "field": [
              "priority",
              "time"
            ],
            "order": [
              "ascending",
              "descending"
            ]
          },
          "ops": [
            "row_number"
          ],
          "as": [
            "row"
          ]
        }
      ]
    }
  ],
  "marks": [
    {
      "type": "text",
      "encode": {
        "update": {
          "x": {
            "value": 5
          },
          "y": {
            "value": 16
          },
          "fontWeight": {
            "value": "bold"
          },
          "text": {
            "signal": "length(data('rows')) ? 'Статус   ·   Время получения   ·   UUID   ·   Время успеха' : 'За выбранный период нет успешных и ожидающих заявок'"
          }
        }
      }
    },
    {
      "type": "text",
      "from": {
        "data": "rows"
      },
      "encode": {
        "update": {
          "x": {
            "value": 5
          },
          "y": {
            "signal": "18 + datum.row * 21"
          },
          "font": {
            "value": "monospace"
          },
          "fontSize": {
            "value": 12
          },
          "fill": {
            "signal": "datum.priority === 0 ? '#b42318' : '#475467'"
          },
          "text": {
            "signal": "pad(datum.status, 12) + timeFormat(datum.time, '%d.%m %H:%M:%S') + '   ' + datum.uuid + '   ' + (isValid(datum.processed) ? timeFormat(datum.processed, '%d.%m %H:%M:%S') : '—')"
          }
        }
      }
    }
  ]
}


{
  "$schema": "https://vega.github.io/schema/vega/v5.json",
  "title": "Просроченные заявки TESSA",
  "width": 730,
  "autosize": "none",
  "signals": [
    {
      "name": "height",
      "update": "max(80, 40 + length(data('rows')) * 21)"
    }
  ],
  "data": [
    {
      "name": "logs",
      "url": {
        "index": "pds-dslog-*",
        "body": {
          "size": 0,
          "query": {
            "bool": {
              "filter": [
                {
                  "range": {
                    "@timestamp": {
                      "lte": "now"
                    }
                  }
                }
              ],
              "minimum_should_match": 1,
              "should": [
                {
                  "bool": {
                    "filter": [
                      {
                        "range": {
                          "@timestamp": {
                            "%timefilter%": true
                          }
                        }
                      },
                      {
                        "match_phrase": {
                          "service": "qppnpftessaexchangemessage"
                        }
                      },
                      {
                        "match_phrase": {
                          "message": "Message received from TESSA"
                        }
                      },
                      {
                        "match_phrase": {
                          "message": "paymentsAssignment = UniversalApplicationsDTO"
                        }
                      }
                    ]
                  }
                },
                {
                  "bool": {
                    "filter": [
                      {
                        "range": {
                          "@timestamp": {
                            "gte": {
                              "%timefilter%": "min"
                            },
                            "lte": {
                              "%timefilter%": "max",
                              "shift": -15,
                              "unit": "minute"
                            }
                          }
                        }
                      },
                      {
                        "match_phrase": {
                          "service": "qppnpfpensionappassignpayments"
                        }
                      },
                      {
                        "match_phrase": {
                          "message": "Сообщение успешно обработано и отправлено"
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          "aggs": {
            "tessa_events": {
              "scripted_metric": {
                "init_script": "state.events = new ArrayList();",
                "map_script": {
                  "lang": "painless",
                  "source": "def src = params['_source'];\nif (src == null || src.message == null || doc['@timestamp'].size() == 0) return;\nboolean received = 'qppnpftessaexchangemessage'.equals(src.service);\nString message = src.message;\nString marker = received ? 'ID_Tessa' : 'Tessa_ID';\nint at = message.indexOf(marker);\nif (at < 0) return;\nString tail = message.substring(at + marker.length()).trim();\nif (!tail.startsWith('=')) return;\ntail = tail.substring(1).trim();\nif (!tail.startsWith(\"'\")) return;\nint end = tail.indexOf(\"'\", 1);\nif (end != 37) return;\nString uuid = tail.substring(1, end).toLowerCase();\nlong timestamp = doc['@timestamp'].value.toInstant().toEpochMilli();\nstate.events.add(['uuid': uuid, 'time': timestamp, 'received': received]);"
                },
                "combine_script": "return state.events;",
                "reduce_script": "def events = new ArrayList();\nfor (def shard : states) {\n  if (shard != null) events.addAll(shard);\n}\nreturn events;"
              }
            }
          }
        }
      },
      "format": {
        "property": "aggregations.tessa_events.value"
      }
    },
    {
      "name": "events",
      "source": "logs",
      "transform": [
        {
          "type": "filter",
          "expr": "test(regexp('^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'), datum.uuid)"
        },
        {
          "type": "identifier",
          "as": "eventId"
        }
      ]
    },
    {
      "name": "successes",
      "source": "events",
      "transform": [
        {
          "type": "filter",
          "expr": "!datum.received"
        },
        {
          "type": "aggregate",
          "groupby": [
            "uuid"
          ],
          "fields": [
            "time"
          ],
          "ops": [
            "values"
          ],
          "as": [
            "matches"
          ]
        }
      ]
    },
    {
      "name": "rows",
      "source": "events",
      "transform": [
        {
          "type": "filter",
          "expr": "datum.received"
        },
        {
          "type": "lookup",
          "from": "successes",
          "key": "uuid",
          "fields": [
            "uuid"
          ],
          "values": [
            "matches"
          ],
          "as": [
            "matches"
          ]
        },
        {
          "type": "formula",
          "as": "candidates",
          "expr": "datum.matches || [null]"
        },
        {
          "type": "flatten",
          "fields": [
            "candidates"
          ],
          "as": [
            "success"
          ]
        },
        {
          "type": "formula",
          "as": "onTime",
          "expr": "datum.success && datum.success.time >= datum.time && datum.success.time <= datum.time + 900000 ? datum.success.time : null"
        },
        {
          "type": "aggregate",
          "groupby": [
            "eventId",
            "uuid",
            "time"
          ],
          "fields": [
            "onTime"
          ],
          "ops": [
            "min"
          ],
          "as": [
            "processed"
          ]
        },
        {
          "type": "formula",
          "as": "priority",
          "expr": "isValid(datum.processed) ? 2 : now() >= datum.time + 900000 ? 0 : 1"
        },
        {
          "type": "formula",
          "as": "status",
          "expr": "datum.priority === 0 ? 'Просрочено' : datum.priority === 1 ? 'Ожидает' : 'Успешно'"
        },
        {
          "type": "filter",
          "expr": "datum.status === 'Просрочено'"
        },
        {
          "type": "window",
          "sort": {
            "field": [
              "priority",
              "time"
            ],
            "order": [
              "ascending",
              "descending"
            ]
          },
          "ops": [
            "row_number"
          ],
          "as": [
            "row"
          ]
        }
      ]
    }
  ],
  "marks": [
    {
      "type": "text",
      "encode": {
        "update": {
          "x": {
            "value": 5
          },
          "y": {
            "value": 16
          },
          "fontWeight": {
            "value": "bold"
          },
          "text": {
            "signal": "length(data('rows')) ? 'Статус   ·   Время получения   ·   UUID   ·   Время успеха' : 'За выбранный период нет просроченных заявок'"
          }
        }
      }
    },
    {
      "type": "text",
      "from": {
        "data": "rows"
      },
      "encode": {
        "update": {
          "x": {
            "value": 5
          },
          "y": {
            "signal": "18 + datum.row * 21"
          },
          "font": {
            "value": "monospace"
          },
          "fontSize": {
            "value": 12
          },
          "fill": {
            "signal": "datum.priority === 0 ? '#b42318' : '#475467'"
          },
          "text": {
            "signal": "pad(datum.status, 12) + timeFormat(datum.time, '%d.%m %H:%M:%S') + '   ' + datum.uuid + '   ' + (isValid(datum.processed) ? timeFormat(datum.processed, '%d.%m %H:%M:%S') : '—')"
          }
        }
      }
    }
  ]
}








{
  "$schema": "https://vega.github.io/schema/vega/v5.json",
  "title": "Обработка заявок TESSA",
  "width": 730,
  "autosize": "none",
  "signals": [
    {
      "name": "height",
      "update": "max(80, 40 + length(data('rows')) * 21)"
    }
  ],
  "data": [
    {
      "name": "logs",
      "url": {
        "index": "pds-dslog-*",
        "body": {
          "size": 0,
          "query": {
            "bool": {
              "filter": [
                {"range": {"@timestamp": {"lte": "now"}}}
              ],
              "minimum_should_match": 1,
              "should": [
                {
                  "bool": {
                    "filter": [
                      {"range": {"@timestamp": {"%timefilter%": true}}},
                      {"match_phrase": {"service": "qppnpftessaexchangemessage"}},
                      {"match_phrase": {"message": "Message received from TESSA"}},
                      {"match_phrase": {"message": "paymentsAssignment = UniversalApplicationsDTO"}}
                    ]
                  }
                },
                {
                  "bool": {
                    "filter": [
                      {
                        "range": {
                          "@timestamp": {
                            "gte": {"%timefilter%": "min"},
                            "lte": {"%timefilter%": "max", "shift": -15, "unit": "minute"}
                          }
                        }
                      },
                      {"match_phrase": {"service": "qppnpfpensionappassignpayments"}},
                      {"match_phrase": {"message": "Сообщение успешно обработано и отправлено"}}
                    ]
                  }
                }
              ]
            }
          },
          "aggs": {
            "tessa_events": {
              "scripted_metric": {
                "init_script": "state.events = new ArrayList();",
                "map_script": {
                  "lang": "painless",
                  "source": "def src = params['_source'];\nif (src == null || src.message == null || doc['@timestamp'].size() == 0) return;\nboolean received = 'qppnpftessaexchangemessage'.equals(src.service);\nString message = src.message;\nString marker = received ? 'ID_Tessa' : 'Tessa_ID';\nint at = message.indexOf(marker);\nif (at < 0) return;\nString tail = message.substring(at + marker.length()).trim();\nif (!tail.startsWith('=')) return;\ntail = tail.substring(1).trim();\nif (!tail.startsWith(\"'\")) return;\nint end = tail.indexOf(\"'\", 1);\nif (end != 37) return;\nString uuid = tail.substring(1, end).toLowerCase();\nlong timestamp = doc['@timestamp'].value.toInstant().toEpochMilli();\nstate.events.add(['uuid': uuid, 'time': timestamp, 'received': received]);"
                },
                "combine_script": "return state.events;",
                "reduce_script": "def events = new ArrayList();\nfor (def shard : states) {\n  if (shard != null) events.addAll(shard);\n}\nreturn events;"
              }
            }
          }
        }
      },
      "format": {"property": "aggregations.tessa_events.value"}
    },
    {
      "name": "events",
      "source": "logs",
      "transform": [
        {
          "type": "filter",
          "expr": "test(regexp('^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'), datum.uuid)"
        },
        {"type": "identifier", "as": "eventId"}
      ]
    },
    {
      "name": "successes",
      "source": "events",
      "transform": [
        {"type": "filter", "expr": "!datum.received"},
        {
          "type": "aggregate",
          "groupby": ["uuid"],
          "fields": ["time"],
          "ops": ["values"],
          "as": ["matches"]
        }
      ]
    },
    {
      "name": "rows",
      "source": "events",
      "transform": [
        {"type": "filter", "expr": "datum.received"},
        {
          "type": "lookup",
          "from": "successes",
          "key": "uuid",
          "fields": ["uuid"],
          "values": ["matches"],
          "as": ["matches"]
        },
        {
          "type": "formula",
          "as": "candidates",
          "expr": "datum.matches || [null]"
        },
        {"type": "flatten", "fields": ["candidates"], "as": ["success"]},
        {
          "type": "formula",
          "as": "onTime",
          "expr": "datum.success && datum.success.time >= datum.time && datum.success.time <= datum.time + 900000 ? datum.success.time : null"
        },
        {
          "type": "aggregate",
          "groupby": ["eventId", "uuid", "time"],
          "fields": ["onTime"],
          "ops": ["min"],
          "as": ["processed"]
        },
        {
          "type": "formula",
          "as": "priority",
          "expr": "isValid(datum.processed) ? 2 : now() >= datum.time + 900000 ? 0 : 1"
        },
        {
          "type": "formula",
          "as": "status",
          "expr": "datum.priority === 0 ? 'Просрочено' : datum.priority === 1 ? 'Ожидает' : 'Успешно'"
        },
        {
          "type": "window",
          "sort": {
            "field": ["priority", "time"],
            "order": ["ascending", "descending"]
          },
          "ops": ["row_number"],
          "as": ["row"]
        }
      ]
    }
  ],
  "marks": [
    {
      "type": "text",
      "encode": {
        "update": {
          "x": {"value": 5},
          "y": {"value": 16},
          "fontWeight": {"value": "bold"},
          "text": {
            "signal": "length(data('rows')) ? 'Статус   ·   Время получения   ·   UUID   ·   Время успеха' : 'За выбранный период нет подходящих входящих заявок'"
          }
        }
      }
    },
    {
      "type": "text",
      "from": {"data": "rows"},
      "encode": {
        "update": {
          "x": {"value": 5},
          "y": {"signal": "18 + datum.row * 21"},
          "font": {"value": "monospace"},
          "fontSize": {"value": 12},
          "fill": {
            "signal": "datum.priority === 0 ? '#b42318' : '#475467'"
          },
          "text": {
            "signal": "pad(datum.status, 12) + timeFormat(datum.time, '%d.%m %H:%M:%S') + '   ' + datum.uuid + '   ' + (isValid(datum.processed) ? timeFormat(datum.processed, '%d.%m %H:%M:%S') : '—')"
          }
        }
      }
    }
  ]
}




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



"query": {
  "bool": {
    "filter": [
      {"range": {"@timestamp": {"lte": "now"}}}
    ],
    "minimum_should_match": 1,
    "should": [
      {
        "bool": {
          "filter": [
            {"range": {"@timestamp": {"%timefilter%": true}}},
            {"match_phrase": {"service": "qppnpftessaexchangemessage"}},
            {"match_phrase": {"message": "Message received from TESSA"}},
            {"match_phrase": {"message": "paymentsAssignment = UniversalApplicationsDTO"}}
          ]
        }
      },
      {
        "bool": {
          "filter": [
            {
              "range": {
                "@timestamp": {
                  "gte": {"%timefilter%": "min"},
                  "lte": {"%timefilter%": "max", "shift": -15, "unit": "minute"}
                }
              }
            },
            {"match_phrase": {"service": "qppnpfpensionappassignpayments"}},
            {"match_phrase": {"message": "Сообщение успешно обработано и отправлено"}}
          ]
        }
      }
    ]
  }
}




{
  "$schema": "https://vega.github.io/schema/vega/v5.json",
  "title": "Обработка заявок TESSA",
  "width": 730,
  "height": 455,
  "autosize": "pad",
  "data": [
    {
      "name": "logs",
      "url": {
        "index": "pds-dslog-*",
        "body": {
          "size": 3000,
          "_source": ["service", "message"],
          "docvalue_fields": [
            {"field": "@timestamp", "format": "epoch_millis"}
          ],
          "query": {
            "bool": {
              "filter": [
                {"range": {"@timestamp": {"gte": "now-1h", "lte": "now"}}}
              ],
              "minimum_should_match": 1,
              "should": [
                {
                  "bool": {
                    "filter": [
                      {"match_phrase": {"service": "qppnpftessaexchangemessage"}},
                      {"match_phrase": {"message": "Message received from TESSA"}},
                      {"match_phrase": {"message": "paymentsAssignment = UniversalApplicationsDTO"}}
                    ]
                  }
                },
                {
                  "bool": {
                    "filter": [
                      {"match_phrase": {"service": "qppnpfpensionappassignpayments"}},
                      {"match_phrase": {"message": "Сообщение успешно обработано и отправлено"}}
                    ]
                  }
                }
              ]
            }
          }
        }
      },
      "format": {"property": "hits.hits"}
    },
    {
      "name": "events",
      "source": "logs",
      "transform": [
        {"type": "filter", "expr": "length(data('logs')) < 3000 && datum.fields && datum.fields['@timestamp']"},
        {"type": "formula", "as": "received", "expr": "datum._source.service === 'qppnpftessaexchangemessage'"},
        {"type": "formula", "as": "marker", "expr": "datum.received ? 'ID_Tessa' : 'Tessa_ID'"},
        {"type": "formula", "as": "tail", "expr": "split(datum._source.message, datum.marker)[1]"},
        {"type": "filter", "expr": "isValid(datum.tail)"},
        {"type": "formula", "as": "uuid", "expr": "lower(split(datum.tail, \"'\")[1] || '')"},
        {"type": "filter", "expr": "test(regexp('^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'), datum.uuid)"},
        {"type": "formula", "as": "time", "expr": "toNumber(datum.fields['@timestamp'][0])"},
        {"type": "formula", "as": "eventId", "expr": "datum._index + '/' + datum._id"}
      ]
    },
    {
      "name": "successes",
      "source": "events",
      "transform": [
        {"type": "filter", "expr": "!datum.received"},
        {"type": "aggregate", "groupby": ["uuid"], "ops": ["values"], "as": ["matches"]}
      ]
    },
    {
      "name": "rows",
      "source": "events",
      "transform": [
        {"type": "filter", "expr": "datum.received"},
        {"type": "lookup", "from": "successes", "key": "uuid", "fields": ["uuid"], "values": ["matches"], "as": ["matches"]},
        {"type": "formula", "as": "candidates", "expr": "datum.matches || [null]"},
        {"type": "flatten", "fields": ["candidates"], "as": ["success"]},
        {"type": "formula", "as": "onTime", "expr": "datum.success && datum.success.time >= datum.time && datum.success.time <= datum.time + 900000 ? datum.success.time : null"},
        {"type": "aggregate", "groupby": ["eventId", "uuid", "time"], "fields": ["onTime"], "ops": ["min"], "as": ["processed"]},
        {"type": "formula", "as": "priority", "expr": "isValid(datum.processed) ? 2 : now() >= datum.time + 900000 ? 0 : 1"},
        {"type": "formula", "as": "status", "expr": "datum.priority === 0 ? 'Просрочено' : datum.priority === 1 ? 'Ожидает' : 'Успешно'"},
        {"type": "window", "sort": {"field": ["priority", "time"], "order": ["ascending", "descending"]}, "ops": ["row_number"], "as": ["row"]},
        {"type": "filter", "expr": "datum.row <= 20"}
      ]
    }
  ],
  "marks": [
    {
      "type": "text",
      "encode": {
        "update": {
          "x": {"value": 5},
          "y": {"value": 16},
          "fontWeight": {"value": "bold"},
          "text": {"signal": "length(data('logs')) >= 3000 ? 'Достигнут лимит 3000 логов за час' : length(data('rows')) ? 'Статус   ·   Время получения   ·   UUID   ·   Время успеха' : 'За последний час нет подходящих входящих заявок'"}
        }
      }
    },
    {
      "type": "text",
      "from": {"data": "rows"},
      "encode": {
        "update": {
          "x": {"value": 5},
          "y": {"signal": "18 + datum.row * 21"},
          "font": {"value": "monospace"},
          "fontSize": {"value": 12},
          "fill": {"signal": "datum.priority === 0 ? '#b42318' : '#475467'"},
          "text": {"signal": "pad(datum.status, 12) + timeFormat(datum.time, '%H:%M:%S') + '   ' + datum.uuid + '   ' + (isValid(datum.processed) ? timeFormat(datum.processed, '%H:%M:%S') : '—')"}
        }
      }
    }
  ]
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
