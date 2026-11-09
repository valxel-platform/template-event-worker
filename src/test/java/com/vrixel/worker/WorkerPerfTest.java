package com.vrixel.worker;

import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

/**
 * Performance smoke test (perf.yml): a burst of events against real Kafka and Postgres, measuring
 * how long until 95% of them are in the store (lag_p95_s), events applied per second, and how many
 * were parked (error_rate). Runs only with -Dperf.out=<file>, which receives the JSON that
 * perf/compare.py judges against main's run and the budget (5 s, under 1% errors).
 */
@SpringBootTest(properties = {"app.worker.topics=" + WorkerPerfTest.TOPIC, "app.worker.retries=1",
        "app.worker.backoff=50ms", "spring.kafka.listener.concurrency=3"})
@Testcontainers
@EnabledIfSystemProperty(named = "perf.out", matches = ".+") // not part of the unit run: no containers start
class WorkerPerfTest {

    static final String TOPIC = "perf.intake-api.greetings.v1";
    static final int EVENTS = Integer.getInteger("perf.events", 3000);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic perfGreetings() {
            return new NewTopic(TOPIC, 3, (short) 1);
        }

        @Bean
        NewTopic perfDeadLetters() {
            return new NewTopic(TOPIC + ".dlt", 1, (short) 1);
        }
    }

    @Autowired KafkaTemplate<String, String> producer;
    @Autowired JdbcClient jdbc;

    @Test
    void aBurstOfEventsReachesTheStore() throws Exception {
        String out = System.getProperty("perf.out");

        // Warm-up: the first events pay for connections and compilation.
        send(0, 200);
        await().atMost(Duration.ofSeconds(60)).until(() -> count() >= 200);

        long start = System.nanoTime();
        send(200, 200 + EVENTS);
        long p95At = 0;
        while (true) {
            int done = count() - 200;
            long now = System.nanoTime();
            if (p95At == 0 && done >= EVENTS * 0.95) {
                p95At = now;
            }
            if (done >= EVENTS || Duration.ofNanos(now - start).toSeconds() > 120) {
                break;
            }
            Thread.sleep(20);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        int applied = count() - 200;
        double lagP95 = p95At == 0 ? seconds : (p95At - start) / 1e9;
        double errorRate = 1.0 - (double) applied / EVENTS;
        Files.writeString(Path.of(out), String.format(Locale.ROOT,
                "{\"lag_p95_s\": %.3f, \"events_per_s\": %.1f, \"error_rate\": %.4f, \"events\": %d}%n",
                lagP95, applied / seconds, Math.max(0, errorRate), EVENTS));
    }

    private void send(int from, int to) throws Exception {
        CompletableFuture<?>[] sent = new CompletableFuture<?>[to - from];
        for (int i = from; i < to; i++) {
            String id = "g-" + i;
            sent[i - from] = producer.send(TOPIC, id, WorkerIntegrationTest.saved("perf-" + i, id, "load"));
        }
        CompletableFuture.allOf(sent).get();
    }

    private int count() {
        return jdbc.sql("select count(*) from greetings where id like 'g-%'").query(Integer.class).single();
    }
}
