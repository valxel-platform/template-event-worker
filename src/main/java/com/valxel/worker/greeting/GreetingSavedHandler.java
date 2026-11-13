package com.valxel.worker.greeting;

import com.valxel.worker.events.Event;
import com.valxel.worker.events.EventHandler;
import com.valxel.worker.events.InvalidEventException;
import com.valxel.worker.events.Position;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The example read model: valxel.greeting.saved.v1 (published by template-service's
 * PUT /api/v1/greetings/{id}) becomes a greeting in the store (Postgres or DynamoDB, see
 * {@link GreetingStore}). Replace with your entity's handlers.
 */
@Component
public class GreetingSavedHandler implements EventHandler {

    public static final String TYPE = "valxel.greeting.saved.v1";

    private final GreetingStore store;

    public GreetingSavedHandler(GreetingStore store) {
        this.store = store;
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
        store.save(new GreetingStore.Greeting(id, event.dataText("name"), event.dataText("message"),
                event.dataText("saved_by"), event.id(), time), at);
    }
}
