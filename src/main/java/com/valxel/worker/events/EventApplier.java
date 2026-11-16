package com.valxel.worker.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final EventContracts contracts;
    private final DatasetCounts summary;

    @Autowired
    public EventApplier(JdbcClient jdbc, List<EventHandler> handlers, MeterRegistry meters) {
        this(jdbc, handlers, meters, EventContracts.load(EventApplier.class.getClassLoader(), "contracts"),
                DatasetCounts.reporting());
    }

    public EventApplier(JdbcClient jdbc, List<EventHandler> handlers, MeterRegistry meters, EventContracts contracts) {
        this(jdbc, handlers, meters, contracts, new DatasetCounts());
    }

    public EventApplier(JdbcClient jdbc, List<EventHandler> handlers, MeterRegistry meters, EventContracts contracts,
            DatasetCounts summary) {
        this.jdbc = jdbc;
        this.handlers = handlers.stream().collect(Collectors.toMap(EventHandler::type, Function.identity()));
        this.meters = meters;
        this.contracts = contracts;
        this.summary = summary;
    }

    @Transactional
    public Outcome apply(Event event, Position at) {
        String dataset = contracts.dataset(at.topic());
        try {
            Outcome outcome = applyChecked(event, at);
            count(dataset, outcome.name().toLowerCase());
            return outcome;
        } catch (InvalidEventException e) {
            count(dataset, "parked"); // straight to the dead-letter topic (KafkaConfig)
            throw e;
        }
    }

    // Per dataset, for the platform's reconciliation: what the producer published against what this
    // worker applied, skipped or parked.
    private void count(String dataset, String outcome) {
        summary.count(dataset, outcome); // the line the platform reconciles in staging
        meters.counter("valxel.dataset.events", "dataset", dataset, "outcome", outcome).increment();
    }

    private Outcome applyChecked(Event received, Position at) {
        EventHandler handler = handlers.get(received.type());
        Event event = received;
        if (handler != null) {
            // The contract's view of the data: properties this copy doesn't know are dropped, the rest checked.
            event = contracts.forTopic(at.topic()).map(c -> received.withData(EventContracts.check(c, received.data())))
                    .orElse(received);
        }
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
