package com.valxel.worker.events;

import com.valxel.worker.flags.FlagCohorts;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the topics in app.worker.topics. One record at a time: the offset is committed after the
 * record is applied (ack mode record), and a failure goes to the error handler (KafkaConfig).
 * With observations on, the event's traceparent header continues the producer's trace. Each
 * attempt counts toward the feature flags it evaluated ({@link FlagCohorts}).
 */
@Component
public class EventListener {

    private final EventApplier applier;
    private final ObjectMapper mapper;
    private final FlagCohorts cohorts;

    public EventListener(EventApplier applier, ObjectMapper mapper, FlagCohorts cohorts) {
        this.applier = applier;
        this.mapper = mapper;
        this.cohorts = cohorts;
    }

    @KafkaListener(id = "events", topics = "#{'${app.worker.topics}'.split('\\s*,\\s*')}")
    public void onRecord(ConsumerRecord<String, String> record) {
        Event event = Event.parse(record.value(), mapper);
        cohorts.count(() -> applier.apply(event, new Position(record.topic(), record.partition(), record.offset())));
    }
}
