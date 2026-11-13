package com.valxel.worker.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The worker's settings (app.worker.*).
 *
 * @param topics the topics to apply, e.g. emr.intake-api.patients.v1
 * @param deadLetterSuffix records that can't be applied go to {@code <topic><suffix>}
 * @param retries attempts after the first, with exponential backoff, before a record is parked
 * @param backoff the first retry's delay; doubles each time up to maxBackoff
 * @param maxBackoff the longest delay between retries
 * @param dedupRetention how long applied event ids are kept for spotting duplicates
 */
@ConfigurationProperties("app.worker")
public record WorkerProperties(
        List<String> topics,
        String deadLetterSuffix,
        Integer retries,
        Duration backoff,
        Duration maxBackoff,
        Duration dedupRetention) {

    public WorkerProperties {
        topics = topics == null ? List.of() : List.copyOf(topics);
        deadLetterSuffix = deadLetterSuffix == null || deadLetterSuffix.isBlank() ? ".dlt" : deadLetterSuffix.strip();
        retries = retries == null ? 4 : retries;
        backoff = backoff == null ? Duration.ofMillis(500) : backoff;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(10) : maxBackoff;
        dedupRetention = dedupRetention == null ? Duration.ofDays(7) : dedupRetention;
    }
}
