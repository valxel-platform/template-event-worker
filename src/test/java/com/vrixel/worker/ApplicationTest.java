package com.vrixel.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.vrixel.worker.config.WorkerProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationTest {

    @Test
    void settingsHaveSafeDefaults() {
        WorkerProperties p = new WorkerProperties(null, " ", null, null, null, null);
        assertThat(p.topics()).isEmpty();
        assertThat(p.deadLetterSuffix()).isEqualTo(".dlt");
        assertThat(p.retries()).isEqualTo(4);
        assertThat(p.backoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.maxBackoff()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.dedupRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(new WorkerProperties(List.of("a"), ".dead", 1, null, null, null).deadLetterSuffix()).isEqualTo(".dead");
    }
}
