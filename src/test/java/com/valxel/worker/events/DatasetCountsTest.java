package com.valxel.worker.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatasetCountsTest {

    @Test
    void oneSummaryLinePerPeriodOfWhatWasCountedSinceTheLast() {
        DatasetCounts c = new DatasetCounts();
        assertThat(c.drain()).isNull();
        c.count("emr.notes", "published");
        c.count("emr.notes", "published");
        c.count("emr.notes", "refused");
        c.count("emr.forms", "published");
        assertThat(c.drain()).isEqualTo(
                """
                valxel.datasets {"emr.forms":{"published":1},"emr.notes":{"published":2,"refused":1}}""");
        assertThat(c.drain()).isNull();
        c.count("say \"hi\"", "published"); // quotes in a name are escaped
        assertThat(c.drain()).isEqualTo("valxel.datasets {\"say \\\"hi\\\"\":{\"published\":1}}");
        assertThat(DatasetCounts.reporting()).isNotNull();
    }
}
