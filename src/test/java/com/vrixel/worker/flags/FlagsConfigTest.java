package com.vrixel.worker.flags;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FlagsConfigTest {

    @AfterEach
    void noFlags() {
        OpenFeatureAPI.getInstance().setProviderAndWait(new NoOpProvider());
    }

    @Test
    void withoutAUrlFlagsStayAtTheirDefaults() {
        FlagsProperties props = new FlagsProperties(null, null, null, null);
        assertThat(props.url()).isEmpty();
        assertThat(FlagsConfig.provider(props)).isNull();
        assertThat(new FlagsConfig().featureFlagClient(props).getBooleanValue("emr.x", false)).isFalse();
    }

    @Test
    void withAUrlFlagsComeFromTheProductsFlipt() {
        FlagsProperties props = new FlagsProperties(" http://flipt.emr.svc:8080 ", "production", "emr", "");
        assertThat(FlagsConfig.provider(props)).isInstanceOf(OfrepProvider.class);
        new FlagsConfig().featureFlagClient(props);
        assertThat(OpenFeatureAPI.getInstance().getProviderMetadata().getName()).isEqualTo("flipt-ofrep");
    }
}
