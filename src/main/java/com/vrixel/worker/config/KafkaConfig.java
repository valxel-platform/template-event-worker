package com.vrixel.worker.config;

import com.vrixel.worker.events.InvalidEventException;
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
    DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> kafka, WorkerProperties props) {
        DeadLetterPublishingRecoverer park = new DeadLetterPublishingRecoverer(
                kafka, (record, ex) -> new TopicPartition(record.topic() + props.deadLetterSuffix(), -1));
        ExponentialBackOff backoff = new ExponentialBackOff(props.backoff().toMillis(), 2.0);
        backoff.setMaxInterval(props.maxBackoff().toMillis());
        backoff.setMaxAttempts(props.retries());
        DefaultErrorHandler handler = new DefaultErrorHandler(park, backoff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
