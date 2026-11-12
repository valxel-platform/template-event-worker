package com.vrixel.worker.outcomes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vrixel.worker.flags.FlagCohorts;
import com.vrixel.worker.flags.Flags;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProductEventsTest {

    private static final String KEY = "emr.emr-feat-3.consent-sync";
    private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    @AfterEach
    void reset() {
        api.setProviderAndWait(new NoOpProvider());
        ProductEvents.counting();
    }

    @Test
    void eventsAreCountedIntoOneSummaryLineAMinute() {
        ProductEvents events = ProductEvents.counting();
        ProductEvents.record("consent_synced");
        ProductEvents.record("consent_synced", Map.of("source", "kiosk"));
        ProductEvents.record("consent.rejected");
        assertThat(events.drain()).containsExactly(
                ProductEvents.MARKER + " {\"consent.rejected\":1,\"consent_synced\":2}");
        assertThat(events.drain()).isEmpty();
    }

    @Test
    void theCounterCarriesTheEventAndItsCategories() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (ProductEvents ignored = ProductEvents.reporting(meters)) {
            ProductEvents.record("consent_synced", Map.of("source", "kiosk"));
            ProductEvents.record("consent_synced", Map.of("source", "kiosk"));
            assertThat(meters.get(ProductEvents.METER).tag("event", "consent_synced").tag("source", "kiosk")
                    .counter().count()).isEqualTo(2.0);
        }
    }

    @Test
    void namesAreLowercaseWordsAndAttributesNeverReachAPerson() {
        assertThatThrownBy(() -> ProductEvents.record("ConsentSynced")).hasMessageContaining("lowercase");
        for (String attribute : new String[] {"email", "patient_id", "member_id", "last_name", "phone"}) {
            assertThatThrownBy(() -> ProductEvents.record("consent_synced", Map.of(attribute, "x")))
                    .as(attribute).hasMessageContaining("person");
        }
        ProductEvents.record("consent_synced", Map.of("source", "kiosk", "consent_type", "treatment"));
    }

    private Flags flags(boolean on) {
        Flag<Boolean> flag = Flag.<Boolean>builder().variant("on", true).variant("off", false)
                .defaultVariant(on ? "on" : "off").build();
        api.setProviderAndWait(new InMemoryProvider(Map.of(KEY, flag)));
        return new Flags(api.getClient());
    }

    @Test
    void anAttemptsEventsCountOnTheSideOfEachFlagItEvaluated() {
        ProductEvents.counting();
        FlagCohorts cohorts = new FlagCohorts();
        Flags on = flags(true);
        cohorts.count(() -> {
            on.enabled(KEY, "clinic-1");
            ProductEvents.record("consent_synced");
        });
        Flags off = flags(false);
        cohorts.count(() -> off.enabled(KEY, "clinic-2"));
        assertThat(cohorts.drain()).containsExactly(FlagCohorts.MARKER + " {\"flag\":\"" + KEY
                + "\",\"on\":{\"requests\":1,\"errors\":0,\"slow\":0,\"events\":{\"consent_synced\":1}},"
                + "\"off\":{\"requests\":1,\"errors\":0,\"slow\":0,\"events\":{\"consent_synced\":0}}}");
    }
}
