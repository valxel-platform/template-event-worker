package com.valxel.worker.outcomes;

import com.valxel.worker.flags.FlagCohorts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The product events that show whether a feature moves its goal (the PRD's Instrumentation
 * section names them; the SDLC tool reads them).
 *
 * <pre>{@code ProductEvents.record("checkin_completed");
 * ProductEvents.record("checkin_completed", Map.of("visit_type", "follow_up"));}</pre>
 *
 * <p>Each event goes three places:
 * <ul>
 *   <li>the counter {@code valxel.events} with attribute {@code event} (and any attributes given),
 *       exported like every other metric: a goal's metric can read it (Prometheus
 *       {@code sum(increase(valxel_events_total{event="checkin_completed"}[$window]))});</li>
 *   <li>a summary line once a minute, which the tool counts in staging to prove every event fires
 *       before release: {@code valxel.events {"checkin_completed":12}};</li>
 *   <li>the current delivery attempt's flag cohorts ({@link FlagCohorts}), so a gradual release can
 *       compare how often work with a feature on does it against work with it off.</li>
 * </ul>
 *
 * <p>Events carry counts and categories, never people: a name that isn't lowercase words, or an
 * attribute that names or identifies a person (a name, contact details, an id), throws. Attributes
 * are low-cardinality categories (a visit type, a plan), since each value is a separate series.
 */
public final class ProductEvents implements AutoCloseable {

    public static final String MARKER = "valxel.events";
    public static final String METER = "valxel.events";
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*");
    private static final Pattern ATTRIBUTE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int MAX_NAME = 100;
    private static final int MAX_VALUE = 64;
    private static final Set<String> PERSONAL = Set.of("name", "first_name", "last_name", "full_name", "display_name",
            "username", "user_name", "email", "email_address", "phone", "phone_number", "mobile", "dob",
            "date_of_birth", "birth_date", "birthdate", "ssn", "social_security_number", "mrn", "address", "street",
            "street_address", "zip", "zipcode", "zip_code", "postcode", "postal_code", "ip", "ip_address", "card",
            "card_number", "pan", "cvv", "cvc", "iban", "account_number", "routing_number", "passport",
            "passport_number", "license_number", "drivers_license", "lat", "lng", "latitude", "longitude", "geo",
            "location");
    private static final List<String> PERSON_IDS = List.of("patient", "member", "beneficiary", "subscriber",
            "cardholder", "user", "customer", "guest", "client", "person", "account");
    private static final Logger log = LoggerFactory.getLogger(ProductEvents.class);
    private static volatile ProductEvents current = new ProductEvents(null, 0);

    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final ScheduledExecutorService reporter;

    private ProductEvents(MeterRegistry meters, long periodSeconds) {
        this.meters = meters;
        if (periodSeconds <= 0) {
            this.reporter = null;
            return;
        }
        this.reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "product-events");
            t.setDaemon(true);
            return t;
        });
        this.reporter.scheduleAtFixedRate(this::report, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /** Counts without a meter or a reporter (tests), and becomes the one {@link #record} uses. */
    public static ProductEvents counting() {
        ProductEvents e = new ProductEvents(null, 0);
        current = e;
        return e;
    }

    /** The running service's: the meter, and the summary line once a minute. */
    public static ProductEvents reporting(MeterRegistry meters) {
        ProductEvents e = new ProductEvents(meters, 60);
        current = e;
        return e;
    }

    /** Something happened that the feature's goal counts. */
    public static void record(String event) {
        record(event, Map.of());
    }

    /** Something happened that the feature's goal counts, with categories to break it down by. */
    public static void record(String event, Map<String, String> attributes) {
        check(event, attributes);
        current.count(event, attributes);
        FlagCohorts.event(event);
    }

    static void check(String event, Map<String, String> attributes) {
        if (event == null || event.length() > MAX_NAME || !NAME.matcher(event).matches()) {
            throw new IllegalArgumentException("Event names are lowercase words joined by _ or . (checkin_completed), "
                    + "as the PRD's Instrumentation section names them: " + event);
        }
        for (Map.Entry<String, String> a : attributes.entrySet()) {
            String key = a.getKey() == null ? "" : a.getKey().toLowerCase(Locale.ROOT);
            if (!ATTRIBUTE.matcher(key).matches() || "event".equals(key)) {
                throw new IllegalArgumentException(event + ": attribute " + a.getKey() + " must be a plain name");
            }
            if (personal(key)) {
                throw new IllegalArgumentException(event + ": attribute " + key + " names or identifies a person; "
                        + "events carry counts and categories, never people");
            }
            if (a.getValue() == null || a.getValue().length() > MAX_VALUE) {
                throw new IllegalArgumentException(event + ": attribute " + key + " needs a short category value");
            }
        }
    }

    static boolean personal(String key) {
        if (PERSONAL.contains(key)) {
            return true;
        }
        for (String suffix : List.of("_email", "_phone", "_dob", "_ssn", "_address", "_card_number")) {
            if (key.endsWith(suffix)) {
                return true;
            }
        }
        if (key.endsWith("_name") && (key.startsWith("first") || key.startsWith("last") || key.startsWith("full")
                || PERSON_IDS.stream().anyMatch(w -> key.startsWith(w + "_")))) {
            return true;
        }
        return PERSON_IDS.stream().anyMatch(w -> key.equals(w) || key.equals(w + "_id") || key.equals(w + "_ids")
                || key.equals(w + "_number"));
    }

    private void count(String event, Map<String, String> attributes) {
        counts.computeIfAbsent(event, e -> new LongAdder()).increment();
        if (meters != null) {
            Counter.Builder b = Counter.builder(METER).description("Product events the feature goals count")
                    .tag("event", event);
            new TreeMap<>(attributes).forEach(b::tag);
            b.register(meters).increment();
        }
    }

    /** The summary line since the last call (empty when nothing happened), and the counts start again. */
    public List<String> drain() {
        TreeMap<String, Long> got = new TreeMap<>();
        for (String event : List.copyOf(counts.keySet())) {
            LongAdder n = counts.remove(event);
            if (n != null && n.sum() > 0) {
                got.put(event, n.sum());
            }
        }
        if (got.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        got.forEach((k, v) -> parts.add("\"" + k + "\":" + v));
        return List.of(MARKER + " {" + String.join(",", parts) + "}");
    }

    void report() {
        try {
            drain().forEach(log::info);
        } catch (RuntimeException e) {
            log.warn("product events not reported: {}", e.toString());
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
