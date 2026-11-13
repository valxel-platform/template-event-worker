package com.valxel.worker.events;

import com.valxel.worker.config.WorkerProperties;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Forgets applied event ids older than app.worker.dedup-retention. Duplicates come from producer
 * retries and redeliveries, which arrive within minutes; the positions in each table guard the rest.
 */
@Component
public class AppliedEventsPruner {

    private static final Logger log = LoggerFactory.getLogger(AppliedEventsPruner.class);

    private final JdbcClient jdbc;
    private final WorkerProperties props;

    public AppliedEventsPruner(JdbcClient jdbc, WorkerProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public int prune() {
        Instant cutoff = Instant.now().minus(props.dedupRetention());
        int removed = jdbc.sql("delete from applied_events where applied_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
        if (removed > 0) {
            log.info("forgot {} applied event ids older than {}", removed, props.dedupRetention());
        }
        return removed;
    }
}
