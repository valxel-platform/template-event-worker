package com.vrixel.worker.greeting;

import com.vrixel.worker.events.Event;
import com.vrixel.worker.events.EventHandler;
import com.vrixel.worker.events.InvalidEventException;
import com.vrixel.worker.events.Position;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The example read model: vrixel.greeting.saved.v1 (published by template-service's
 * PUT /api/v1/greetings/{id}) becomes a greeting in the store (Postgres or DynamoDB, see
 * {@link GreetingStore}). Replace with your entity's handlers.
 */
@Component
public class GreetingSavedHandler implements EventHandler {

    public static final String TYPE = "vrixel.greeting.saved.v1";

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
