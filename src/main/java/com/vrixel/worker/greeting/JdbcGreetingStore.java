package com.vrixel.worker.greeting;

import com.vrixel.worker.events.Position;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The greetings read model in Postgres (db/migration). */
public class JdbcGreetingStore implements GreetingStore {

    private final JdbcClient jdbc;

    public JdbcGreetingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Greeting g, Position at) {
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
                .param("id", g.id())
                .param("name", g.name())
                .param("message", g.message())
                .param("savedBy", g.savedBy())
                .param("eventId", g.eventId())
                .param("partition", at.partition())
                .param("offset", at.offset())
                .param("time", OffsetDateTime.ofInstant(g.updatedAt(), ZoneOffset.UTC))
                .update();
    }
}
