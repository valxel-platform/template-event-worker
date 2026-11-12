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
 * <pre>{@code vrixel.flag_cohorts {"flag":"emr.emr-feat-3.kiosk","on":{"requests":40,"errors":0,"slow":1},"off":{"requests":760,"errors":2,"slow":9}}}</pre>
 *
 * <p>The product events an attempt records ({@code ProductEvents.record}) count on the same side,
 * so the tool can also compare how often work with a feature on does what its goal counts:
 *
 * <pre>{@code vrixel.flag_cohorts {"flag":"...","on":{"requests":40,"errors":0,"slow":1,"events":{"checkin_completed":9}},"off":{...}}}</pre>
 *
 * <p>Only counts leave the worker: no user, event or value beyond the flag's key.
 */
public class FlagCohorts implements AutoCloseable {

    public static final String MARKER = "vrixel.flag_cohorts";
    private static final Logger log = LoggerFactory.getLogger(FlagCohorts.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,200}");
    private static final ThreadLocal<Map<String, Boolean>> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> STARTED = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Integer>> EVENTS = new ThreadLocal<>();
    private static final Pattern EVENT = Pattern.compile("[a-z][a-z0-9_.]{0,99}");
    /**
     * Slower than this counts as slow: 5000 ms, the event lag budget (one attempt slower than that breaks it
     * alone), unless FLAG_COHORTS_SLOW_MS says otherwise.
     */
    static final long SLOW_MS = slowMillis(System.getenv("FLAG_COHORTS_SLOW_MS"));

    // on requests, on errors, on slow, off requests, off errors, off slow
    private final Map<String, AtomicLongArray> counts = new ConcurrentHashMap<>();
    // flag -> event -> on, off
    private final Map<String, Map<String, AtomicLongArray>> events = new ConcurrentHashMap<>();
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
        EVENTS.set(new HashMap<>());
        STARTED.set(System.nanoTime());
    }

    /** A product event happened in the current attempt (ProductEvents.record). Outside one it isn't counted here. */
    public static void event(String name) {
        Map<String, Integer> current = EVENTS.get();
        if (current != null && name != null && EVENT.matcher(name).matches()) {
            current.merge(name, 1, Integer::sum);
        }
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
        Long started = STARTED.get();
        end(failed, started == null ? 0 : (System.nanoTime() - started) / 1_000_000);
    }

    /** The attempt ended after this long: count it for each flag it evaluated, slow over {@link #SLOW_MS}. */
    public void end(boolean failed, long elapsedMillis) {
        Map<String, Boolean> current = CURRENT.get();
        Map<String, Integer> happened = EVENTS.get();
        CURRENT.remove();
        EVENTS.remove();
        STARTED.remove();
        if (current == null) {
            return;
        }
        current.forEach((key, on) -> {
            AtomicLongArray c = counts.computeIfAbsent(key, k -> new AtomicLongArray(6));
            int side = on ? 0 : 3;
            c.incrementAndGet(side);
            if (failed) {
                c.incrementAndGet(side + 1);
            }
            if (elapsedMillis > SLOW_MS) {
                c.incrementAndGet(side + 2);
            }
            if (happened != null && !happened.isEmpty()) {
                Map<String, AtomicLongArray> byEvent = events.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                happened.forEach((name, n) -> byEvent.computeIfAbsent(name, x -> new AtomicLongArray(2))
                        .addAndGet(on ? 0 : 1, n));
            }
        });
    }

    static long slowMillis(String value) {
        try {
            long v = value == null || value.isBlank() ? 5000 : Long.parseLong(value.trim());
            return v > 0 ? v : 5000;
        } catch (NumberFormatException e) {
            return 5000;
        }
    }

    /** The lines since the last call, one per flag, and the counts start again. */
    public List<String> drain() {
        List<String> lines = new ArrayList<>();
        for (String key : List.copyOf(counts.keySet())) {
            AtomicLongArray c = counts.remove(key);
            if (c == null) {
                continue;
            }
            Map<String, AtomicLongArray> ev = events.remove(key);
            lines.add(MARKER + " {\"flag\":\"" + key + "\",\"on\":{\"requests\":" + c.get(0) + ",\"errors\":" + c.get(1)
                    + ",\"slow\":" + c.get(2) + events(ev, 0) + "},\"off\":{\"requests\":" + c.get(3) + ",\"errors\":"
                    + c.get(4) + ",\"slow\":" + c.get(5) + events(ev, 1) + "}}");
        }
        return lines;
    }

    private static String events(Map<String, AtomicLongArray> ev, int side) {
        if (ev == null || ev.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        new java.util.TreeMap<>(ev).forEach((name, n) -> parts.add("\"" + name + "\":" + n.get(side)));
        return ",\"events\":{" + String.join(",", parts) + "}";
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
