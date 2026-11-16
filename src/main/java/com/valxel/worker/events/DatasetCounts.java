package com.valxel.worker.events;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Events per dataset and outcome, logged once a minute as one summary line for the platform's
 * reconciliation (the design "Datasets, contracts and reporting"):
 *
 * <pre>valxel.datasets {"emr.notes":{"published":12,"refused":0}}</pre>
 *
 * <p>In staging the SDLC tool sums these lines per component and compares what each dataset's producer
 * published with what each consumer applied, skipped or parked; a gap holds the release. Counts only:
 * never an event's content.
 */
public final class DatasetCounts {

    public static final String MARKER = "valxel.datasets";
    private static final Logger log = LoggerFactory.getLogger(DatasetCounts.class);

    private final Map<String, Map<String, LongAdder>> counts = new ConcurrentHashMap<>();

    /** Counts only (tests): nothing is logged until {@link #drain()} is called. */
    public DatasetCounts() {}

    /** The running component's: the summary line once a minute. */
    public static DatasetCounts reporting() {
        DatasetCounts c = new DatasetCounts();
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dataset-counts");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> {
            String line = c.drain();
            if (line != null) {
                log.info(line);
            }
        }, 60, 60, TimeUnit.SECONDS);
        return c;
    }

    public void count(String dataset, String outcome) {
        counts.computeIfAbsent(dataset, k -> new ConcurrentHashMap<>()).computeIfAbsent(outcome, k -> new LongAdder()).increment();
    }

    /** The summary line of what was counted since the last one, or null when nothing was. */
    public String drain() {
        Map<String, Map<String, Long>> taken = new TreeMap<>();
        counts.forEach((dataset, byOutcome) -> byOutcome.forEach((outcome, n) -> {
            long v = n.sumThenReset();
            if (v > 0) {
                taken.computeIfAbsent(dataset, k -> new TreeMap<>()).put(outcome, v);
            }
        }));
        if (taken.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(MARKER).append(" {");
        String sep = "";
        for (Map.Entry<String, Map<String, Long>> d : taken.entrySet()) {
            out.append(sep).append(quote(d.getKey())).append(":{");
            String inner = "";
            for (Map.Entry<String, Long> o : d.getValue().entrySet()) {
                out.append(inner).append(quote(o.getKey())).append(':').append(o.getValue());
                inner = ",";
            }
            out.append('}');
            sep = ",";
        }
        return out.append('}').toString();
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
