package com.vrixel.worker.greeting;

import com.vrixel.worker.events.Event;
import com.vrixel.worker.events.EventHandler;
import com.vrixel.worker.events.InvalidEventException;
import com.vrixel.worker.events.Position;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The example read model: vrixel.greeting.saved.v1 (published by template-service's
 * PUT /api/v1/greetings/{id}) becomes a row in greetings. Replace with your entity's handlers.
 *
 * <p>The upsert only moves forward: a row keeps the Kafka position it came from and ignores any
 * event from an earlier position, so replaying the topic over an existing table is safe.
 */
@Component
public class GreetingSavedHandler implements EventHandler {

    public static final String TYPE = "vrixel.greeting.saved.v1";

    private final JdbcClient jdbc;

    public GreetingSavedHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void apply(Event event, Position at) {
        String id = event.dataText("id");
        if (event.subject() != null && !event.subject().isBlank() && !event.subject().equals("greeting/" + id)) {
            throw new InvalidEventException(TYPE + " subject doesn't match data.id");
        }
        Instant time = event.time() == null ? Instant.now() : event.time();
        jdbc.sql("""
                        insert into greetings (id, name, message, saved_by, event_id, source_partition, source_offset, updated_at)
                        values (:id, :name, :message, :savedBy, :eventId, :partition, :offset, :time)
                        on conflict (id) do update set
                            name = excluded.name,
                            message = excluded.message,
                            saved_by = excluded.saved_by,
                            event_id = excluded.event_id,
                            source_partition = excluded.source_partition,
                            source_offset = excluded.source_offset,
                            updated_at = excluded.updated_at
                        where greetings.source_offset < excluded.source_offset""")
                .param("id", id)
                .param("name", event.dataText("name"))
                .param("message", event.dataText("message"))
                .param("savedBy", event.dataText("saved_by"))
                .param("eventId", event.id())
                .param("partition", at.partition())
                .param("offset", at.offset())
                .param("time", OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .update();
    }
}
