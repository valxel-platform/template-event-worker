package com.valxel.worker.greeting;

import com.valxel.worker.events.Position;
import java.time.Instant;

/**
 * Where the greetings read model lives: Postgres (the default) or DynamoDB, chosen by
 * {@code app.store} (VX-19). Each store only moves forward: a record keeps the Kafka position it
 * came from and ignores an event from an earlier one, so a replay never undoes a newer change.
 */
public interface GreetingStore {

    record Greeting(String id, String name, String message, String savedBy, String eventId, Instant updatedAt) {}

    void save(Greeting greeting, Position at);
}
