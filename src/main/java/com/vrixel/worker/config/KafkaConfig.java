package com.vrixel.worker.config;

import com.vrixel.worker.events.InvalidEventException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when a record can't be applied: retry with exponential backoff (the database may
 * be restarting), then park it on the dead-letter topic and move on, so one bad record never
 * stops its partition. Records that aren't valid events skip the retries. Parked records keep
 * their headers plus the exception (kafka_dlt-*), so they can be fixed and replayed.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    @Bean
    DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> kafka, WorkerProperties props, MeterRegistry meters) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(
                kafka, (record, ex) -> new TopicPartition(record.topic() + props.deadLetterSuffix(), -1));
        // Every parked record counts (worker.dead_lettered, by topic and exception type), so an alert
        // can fire on the first one instead of someone finding the dead-letter topic later.
        ConsumerRecordRecoverer park = (record, ex) -> {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            meters.counter("worker.dead_lettered", "topic", record.topic(), "exception", cause.getClass().getSimpleName())
                    .increment();
            dlt.accept(record, ex);
        };
        ExponentialBackOff backoff = new ExponentialBackOff(props.backoff().toMillis(), 2.0);
        backoff.setMaxInterval(props.maxBackoff().toMillis());
        backoff.setMaxAttempts(props.retries());
        DefaultErrorHandler handler = new DefaultErrorHandler(park, backoff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    /** Data classes whose Kafka traffic must be encrypted: it names people and their records. */
    static final Set<String> ENCRYPTED_CLASSES = Set.of("phi", "payments");

    /** PHI and payments products never read Kafka in plain text: the worker refuses to start. */
    @Bean
    Object encryptedKafka(
            @Value("${app.data-class:general}") String dataClass,
            @Value("${spring.kafka.security.protocol:${spring.kafka.properties.security.protocol:PLAINTEXT}}") String protocol) {
        requireEncryption(dataClass, protocol);
        return Boolean.TRUE;
    }

    static void requireEncryption(String dataClass, String protocol) {
        String p = protocol == null ? "PLAINTEXT" : protocol.toUpperCase(Locale.ROOT);
        if (ENCRYPTED_CLASSES.contains(dataClass) && !Set.of("SSL", "SASL_SSL").contains(p)) {
            throw new IllegalStateException("The Kafka connection of a " + dataClass
                    + " product must use TLS: set spring.kafka.security.protocol to SSL or SASL_SSL (it is " + p + ")");
        }
    }
}
