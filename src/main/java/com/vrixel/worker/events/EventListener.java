package com.vrixel.worker.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the topics in app.worker.topics. One record at a time: the offset is committed after the
 * record is applied (ack mode record), and a failure goes to the error handler (KafkaConfig).
 * With observations on, the event's traceparent header continues the producer's trace.
 */
@Component
public class EventListener {

    private final EventApplier applier;
    private final ObjectMapper mapper;

    public EventListener(EventApplier applier, ObjectMapper mapper) {
        this.applier = applier;
        this.mapper = mapper;
    }

    @KafkaListener(id = "events", topics = "#{'${app.worker.topics}'.split('\\s*,\\s*')}")
    public void onRecord(ConsumerRecord<String, String> record) {
        Event event = Event.parse(record.value(), mapper);
        applier.apply(event, new Position(record.topic(), record.partition(), record.offset()));
    }
}
