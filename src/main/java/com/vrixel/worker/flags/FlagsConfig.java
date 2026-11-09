package com.vrixel.worker.flags;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Points OpenFeature at the product's Flipt, or leaves every flag at its default. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlagsProperties.class)
public class FlagsConfig {

    private static final Logger log = LoggerFactory.getLogger(FlagsConfig.class);

    @Bean
    Client featureFlagClient(FlagsProperties props) {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        FeatureProvider provider = provider(props);
        if (provider == null) {
            log.info("app.flags.url is not set: every feature flag reads as its default (off)");
        } else {
            api.setProvider(provider);
        }
        return api.getClient();
    }

    /** Users with each feature on against off, logged once a minute for the SDLC tool's gradual releases. */
    @Bean(destroyMethod = "close")
    FlagCohorts flagCohorts() {
        return FlagCohorts.reporting();
    }

    static FeatureProvider provider(FlagsProperties props) {
        if (props.url().isEmpty()) {
            return null;
        }
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        // A slow Flipt costs an event at most a quarter of a second (the flag then reads as off);
        // with answers cached in Flags, most events don't call Flipt at all.
        http.setConnectTimeout(Duration.ofMillis(250));
        http.setReadTimeout(Duration.ofMillis(250));
        return new OfrepProvider(RestClient.builder().requestFactory(http), props.url(), props.environment(),
                props.namespace(), props.token());
    }
}
