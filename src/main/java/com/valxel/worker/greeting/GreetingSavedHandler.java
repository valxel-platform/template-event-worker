package com.valxel.worker.greeting;

import com.valxel.worker.events.Event;
import com.valxel.worker.events.EventHandler;
import com.valxel.worker.events.InvalidEventException;
import com.valxel.worker.events.Position;
import com.valxel.worker.flags.Flags;
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
    private final Flags flags;

    public GreetingSavedHandler(GreetingStore store, Flags flags) {
        this.store = store;
        this.flags = flags;
    }

    /**
     * The example feature's flag. It shows the pattern every feature follows: the tool gives the
     * feature a flag in the product's Flipt (on in dev and staging, off in production until it's
     * released) and the new behaviour reads it. Replace it with your first feature's own.
     */
    public static String exampleFlag(String product) {
        return (product == null || product.isBlank() ? "example" : product) + ".example.tidy-greeting-message";
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
        String message = event.dataText("message");
        String savedBy = event.dataText("saved_by");
        // The template's example feature, behind its flag: off, the message is stored as sent; on,
        // runs of spaces in it are tidied to one. The product is the event source's first part.
        String product = event.source() == null ? "" : event.source().split("/", 2)[0];
        if (message != null && flags.enabled(exampleFlag(product), savedBy)) {
            message = message.strip().replaceAll("\\s+", " ");
        }
        store.save(new GreetingStore.Greeting(id, event.dataText("name"), message, savedBy, event.id(), time), at);
    }
}
