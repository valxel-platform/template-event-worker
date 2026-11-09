package com.vrixel.worker.flags;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FlagsTest {

    private static final String KEY = "emr.emr-feat-3.check-in-kiosk";
    private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    @AfterEach
    void noFlags() {
        api.setProviderAndWait(new NoOpProvider());
    }

    private Flags flags(boolean on) {
        Flag<Boolean> flag = Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant(on ? "on" : "off")
                .build();
        api.setProviderAndWait(new InMemoryProvider(Map.of(KEY, flag)));
        return new Flags(api.getClient());
    }

    @Test
    void aFeatureIsOnWhenItsFlagIs() {
        assertThat(flags(true).enabled(KEY, "nurse-1")).isTrue();
        assertThat(flags(false).enabled(KEY)).isFalse();
    }

    @Test
    void anUnknownFlagIsOff() {
        assertThat(flags(true).enabled("emr.unknown", null)).isFalse();
    }

    @Test
    void answersAreReusedPerFlagAndUser() {
        Flags cached = new Flags(flags(true).client(), java.time.Duration.ofMinutes(1));
        assertThat(cached.enabled(KEY, "nurse-1")).isTrue();
        flags(false); // the flag is turned off at the server
        assertThat(cached.enabled(KEY, "nurse-1")).isTrue(); // still the cached answer
        assertThat(cached.enabled(KEY, "nurse-2")).isFalse(); // another user is asked for
    }

    @Test
    void withoutAFlagServerEveryFlagIsOff() {
        assertThat(new Flags(api.getClient()).enabled(KEY)).isFalse();
    }
}
