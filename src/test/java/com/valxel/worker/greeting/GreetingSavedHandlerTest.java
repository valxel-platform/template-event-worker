package com.valxel.worker.greeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.valxel.worker.events.Event;
import com.valxel.worker.events.Position;
import com.valxel.worker.flags.Flags;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GreetingSavedHandlerTest {

    private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    private final List<GreetingStore.Greeting> saved = new ArrayList<>();
    private final GreetingStore store = (greeting, at) -> saved.add(greeting);

    @AfterEach
    void noFlags() {
        api.setProviderAndWait(new NoOpProvider());
    }

    private static Event event(String message) {
        String json = """
                {"specversion":"1.0","id":"e-1","source":"emr/intake-api","type":"valxel.greeting.saved.v1",
                 "time":"2026-11-04T12:00:00Z","subject":"greeting/g-1",
                 "data":{"id":"g-1","name":"Ada","message":"%s","saved_by":"user-1"}}
                """.formatted(message);
        return Event.parse(json, JsonMapper.builder().build());
    }

    /** The pattern every feature follows: its new behaviour reads its flag, and off is today's. */
    @Test
    void theExampleFeatureIsBehindItsFlag() {
        assertThat(GreetingSavedHandler.exampleFlag("emr")).isEqualTo("emr.example.tidy-greeting-message");
        assertThat(GreetingSavedHandler.exampleFlag(null)).isEqualTo("example.example.tidy-greeting-message");
        Position at = new Position("emr.intake-api.greetings.v1", 0, 1);

        api.setProviderAndWait(new InMemoryProvider(Map.of()));
        new GreetingSavedHandler(store, new Flags(api.getClient())).apply(event(" Hello   there "), at);
        assertThat(saved.get(0).message()).isEqualTo(" Hello   there ");

        Flag<Boolean> on = Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant("on")
                .build();
        api.setProviderAndWait(new InMemoryProvider(Map.of("emr.example.tidy-greeting-message", on)));
        new GreetingSavedHandler(store, new Flags(api.getClient())).apply(event(" Hello   there "), at);
        assertThat(saved.get(1).message()).isEqualTo("Hello there");
        assertThat(saved.get(1).savedBy()).isEqualTo("user-1");
    }
}
