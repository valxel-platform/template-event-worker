package com.vrixel.worker.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies an event once: records its id and runs its handler in one transaction. */
@Component
public class EventApplier {

    /** What happened to an event. */
    public enum Outcome { APPLIED, DUPLICATE, SKIPPED }

    private static final Logger log = LoggerFactory.getLogger(EventApplier.class);

    private final JdbcClient jdbc;
    private final Map<String, EventHandler> handlers;
    private final MeterRegistry meters;

    public EventApplier(JdbcClient jdbc, List<EventHandler> handlers, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.handlers = handlers.stream().collect(Collectors.toMap(EventHandler::type, Function.identity()));
        this.meters = meters;
    }

    @Transactional
    public Outcome apply(Event event, Position at) {
        EventHandler handler = handlers.get(event.type());
        Outcome outcome;
        if (handler == null) {
            // Not ours (yet): producers may add types before workers handle them.
            log.debug("skipping {} at {}-{}@{}: no handler", event.type(), at.topic(), at.partition(), at.offset());
            outcome = Outcome.SKIPPED;
        } else if (!firstTime(event, at)) {
            outcome = Outcome.DUPLICATE;
        } else {
            handler.apply(event, at);
            outcome = Outcome.APPLIED;
        }
        meters.counter("worker.events", "type", event.type(), "outcome", outcome.name().toLowerCase()).increment();
        return outcome;
    }

    private boolean firstTime(Event event, Position at) {
        return jdbc.sql("""
                        insert into applied_events (event_id, topic, partition, "offset")
                        values (:id, :topic, :partition, :offset)
                        on conflict (event_id) do nothing""")
                        .param("id", event.id())
                        .param("topic", at.topic())
                        .param("partition", at.partition())
                        .param("offset", at.offset())
                        .update()
                == 1;
    }
}
