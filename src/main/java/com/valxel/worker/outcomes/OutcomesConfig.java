package com.valxel.worker.outcomes;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Product events: the counter, and the summary line the SDLC tool counts ({@link ProductEvents}). */
@Configuration(proxyBeanMethods = false)
public class OutcomesConfig {

    @Bean(destroyMethod = "close")
    ProductEvents productEvents(ObjectProvider<MeterRegistry> meters) {
        return ProductEvents.reporting(meters.getIfAvailable());
    }
}
