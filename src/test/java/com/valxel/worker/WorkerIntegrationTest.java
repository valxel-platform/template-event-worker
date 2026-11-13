package com.valxel.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.valxel.worker.events.AppliedEventsPruner;
import com.valxel.worker.events.Event;
import com.valxel.worker.events.EventApplier;
import com.valxel.worker.events.Position;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** The worker against real Postgres and Kafka (Testcontainers; needs Docker). */
@SpringBootTest(properties = {"app.worker.topics=" + WorkerIntegrationTest.TOPIC, "app.worker.retries=1", "app.worker.backoff=50ms"})
@Testcontainers
class WorkerIntegrationTest {

    static final String TOPIC = "emr.intake-api.greetings.v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic greetings() {
            return new NewTopic(TOPIC, 3, (short) 1);
        }

        @Bean
        NewTopic deadLetters() {
            return new NewTopic(TOPIC + ".dlt", 1, (short) 1);
        }
    }

    @Autowired KafkaTemplate<String, String> producer;
    @Autowired JdbcClient jdbc;
    @Autowired EventApplier applier;
    @Autowired AppliedEventsPruner pruner;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate greetings, applied_events").update();
    }

    static String saved(String eventId, String id, String message) {
        return """
                {"specversion":"1.0","id":"%s","source":"emr/intake-api","type":"valxel.greeting.saved.v1",
                 "time":"2026-11-04T12:00:00Z","subject":"greeting/%s",
                 "data":{"id":"%s","name":"Ada","message":"%s","saved_by":"user-1"}}"""
                .formatted(eventId, id, id, message);
    }

    private String message(String id) {
        return jdbc.sql("select message from greetings where id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private int applied(String eventId) {
        return jdbc.sql("select count(*) from applied_events where event_id = :id").param("id", eventId)
                .query(Integer.class).single();
    }

    @Test
    void anEventBecomesARowAndLaterEventsUpdateIt() throws Exception {
        producer.send(TOPIC, "g-1", saved("e-1", "g-1", "Hello")).get();
        producer.send(TOPIC, "g-1", saved("e-2", "g-1", "Welcome back")).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> "Welcome back".equals(message("g-1")));
        assertThat(applied("e-1")).isEqualTo(1);
    }

    @Test
    void aDuplicateDeliveryChangesNothing() throws Exception {
        producer.send(TOPIC, "g-2", saved("e-3", "g-2", "First")).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> "First".equals(message("g-2")));
        jdbc.sql("update greetings set message = 'edited by hand' where id = 'g-2'").update();
        double before = duplicates();
        // The producer retried: same event id, new offset. Applied once, so the hand edit stays.
        producer.send(TOPIC, "g-2", saved("e-3", "g-2", "First")).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> duplicates() > before);
        assertThat(message("g-2")).isEqualTo("edited by hand");
    }

    private double duplicates() {
        var c = meters.find("worker.events").tag("outcome", "duplicate").counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void anOlderPositionNeverOverwritesANewerOne() {
        applier.apply(Event.parse(saved("e-5", "g-3", "New"), mapper), new Position(TOPIC, 0, 10));
        // A replay of the topic over the existing table: the older change is ignored.
        applier.apply(Event.parse(saved("e-6", "g-3", "Old"), mapper), new Position(TOPIC, 0, 9));
        assertThat(message("g-3")).isEqualTo("New");
    }

    @Test
    void unknownTypesAreSkipped() {
        String other = saved("e-7", "g-4", "x").replace("valxel.greeting.saved.v1", "valxel.greeting.archived.v1");
        assertThat(applier.apply(Event.parse(other, mapper), new Position(TOPIC, 0, 1)))
                .isEqualTo(EventApplier.Outcome.SKIPPED);
        assertThat(applied("e-7")).isZero(); // applied later, once a handler exists
    }

    @Test
    void whatCantBeAppliedIsParkedAndThePartitionMovesOn() throws Exception {
        producer.send(TOPIC, "g-5", "not an event").get();
        producer.send(TOPIC, "g-5", saved("e-8", "g-5", "Hi").replace("\"name\":\"Ada\",", "")).get(); // no name
        producer.send(TOPIC, "g-5", saved("e-9", "g-5", "Still moving")).get();
        await().atMost(Duration.ofSeconds(60)).until(() -> "Still moving".equals(message("g-5")));

        List<ConsumerRecord<String, String>> parked = new ArrayList<>();
        Properties p = new Properties();
        p.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            dlt.subscribe(List.of(TOPIC + ".dlt"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                dlt.poll(Duration.ofMillis(500)).forEach(parked::add);
                return parked.size() >= 2;
            });
        }
        assertThat(parked).extracting(ConsumerRecord::value).first().isEqualTo("not an event");
        String reason = new String(parked.get(1).headers().lastHeader("kafka_dlt-exception-message").value(), StandardCharsets.UTF_8);
        assertThat(reason).contains("needs data.name").doesNotContain("Ada");
    }

    @Test
    void oldEventIdsAreForgotten() {
        jdbc.sql("insert into applied_events (event_id, topic, partition, \"offset\", applied_at) "
                        + "values ('old', 't', 0, 1, now() - interval '30 days'), ('new', 't', 0, 2, now())")
                .update();
        assertThat(pruner.prune()).isEqualTo(1);
        assertThat(applied("new")).isEqualTo(1);
    }
}
