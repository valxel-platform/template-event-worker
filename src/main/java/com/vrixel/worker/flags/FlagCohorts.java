package com.vrixel.worker.flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Users with a feature on against users with it off, while the SDLC tool releases it gradually.
 *
 * <p>Every delivery attempt that evaluates a flag counts once for that flag, on the side the event
 * got, and as failed when its handler throws (a retry is another attempt; the last failure parks
 * the record on the dead-letter topic). Once a minute each flag's counts are logged as one line,
 * which the tool sums across pods from OpenChoreo's logs and compares: work that fails more with
 * the feature on stops its release. The line says "requests" for every template alike.
 *
 * <pre>{@code vrixel.flag_cohorts {"flag":"emr.emr-feat-3.kiosk","on":{"requests":40,"errors":0},"off":{"requests":760,"errors":2}}}</pre>
 *
 * <p>Only counts leave the worker: no user, event or value beyond the flag's key.
 */
public class FlagCohorts implements AutoCloseable {

    public static final String MARKER = "vrixel.flag_cohorts";
    private static final Logger log = LoggerFactory.getLogger(FlagCohorts.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,200}");
    private static final ThreadLocal<Map<String, Boolean>> CURRENT = new ThreadLocal<>();

    // on requests, on errors, off requests, off errors
    private final Map<String, AtomicLongArray> counts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reporter;

    /** Counts without reporting (tests); {@link #reporting()} logs once a minute. */
    public FlagCohorts() {
        this.reporter = null;
    }

    private FlagCohorts(long periodSeconds) {
        this.reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flag-cohorts");
            t.setDaemon(true);
            return t;
        });
        this.reporter.scheduleAtFixedRate(this::report, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public static FlagCohorts reporting() {
        return new FlagCohorts(60);
    }

    /** Runs one delivery attempt and counts it for the flags it evaluated, failed if it throws. */
    public void count(Runnable attempt) {
        begin();
        boolean failed = true;
        try {
            attempt.run();
            failed = false;
        } finally {
            end(failed);
        }
    }

    /** An attempt starts: the flags it evaluates are noted until {@link #end}. */
    public static void begin() {
        CURRENT.set(new HashMap<>());
    }

    /** A flag was evaluated for the current attempt. Outside one it isn't counted. */
    public static void seen(String key, boolean on) {
        Map<String, Boolean> current = CURRENT.get();
        if (current != null && key != null && KEY.matcher(key).matches()) {
            current.putIfAbsent(key, on);
        }
    }

    /** The attempt ended: count it for each flag it evaluated. */
    public void end(boolean failed) {
        Map<String, Boolean> current = CURRENT.get();
        CURRENT.remove();
        if (current == null) {
            return;
        }
        current.forEach((key, on) -> {
            AtomicLongArray c = counts.computeIfAbsent(key, k -> new AtomicLongArray(4));
            int side = on ? 0 : 2;
            c.incrementAndGet(side);
            if (failed) {
                c.incrementAndGet(side + 1);
            }
        });
    }

    /** The lines since the last call, one per flag, and the counts start again. */
    public List<String> drain() {
        List<String> lines = new ArrayList<>();
        for (String key : List.copyOf(counts.keySet())) {
            AtomicLongArray c = counts.remove(key);
            if (c == null) {
                continue;
            }
            lines.add(MARKER + " {\"flag\":\"" + key + "\",\"on\":{\"requests\":" + c.get(0) + ",\"errors\":" + c.get(1)
                    + "},\"off\":{\"requests\":" + c.get(2) + ",\"errors\":" + c.get(3) + "}}");
        }
        return lines;
    }

    void report() {
        try {
            drain().forEach(log::info);
        } catch (RuntimeException e) {
            log.warn("flag cohorts not reported: {}", e.toString());
        }
    }

    @Override
    public void close() {
        if (reporter != null) {
            reporter.shutdown();
            report();
        }
    }
}
