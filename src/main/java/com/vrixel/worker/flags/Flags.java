package com.vrixel.worker.flags;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feature flags. The Vrixel SDLC tool gives each feature a flag (a key like
 * {@code emr.emr-feat-3.check-in-kiosk}) in the product's Flipt when its design is approved: on in
 * dev and staging, off in production until the feature is released. Put the new behavior behind it,
 * keyed by the user the event is about or who caused it (the same key the service used, so a
 * person gets the same answer in both):
 *
 * <pre>{@code
 * if (flags.enabled("emr.emr-feat-3.check-in-kiosk", event.dataText("saved_by"))) {
 *     // the new behavior
 * } else {
 *     // today's behavior
 * }
 * }</pre>
 */
@Component
public class Flags {

    /** At most this many answers are kept; past it, expired ones go, then all of them. */
    static final int MAX_CACHED = 10_000;

    private final Client client;
    private final long ttlNanos;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    private record Answer(boolean on, long until) {}

    /** Asks the flag server every time (tests). */
    public Flags(Client client) {
        this(client, Duration.ZERO);
    }

    /**
     * Reuses each answer, per flag and user, for {@code app.flags.cache-seconds} (30 s): most
     * events then make no call to Flipt, and a turned-off flag takes effect within that time.
     */
    @Autowired
    public Flags(Client client, @Value("${app.flags.cache-seconds:30}") long cacheSeconds) {
        this(client, Duration.ofSeconds(cacheSeconds));
    }

    public Flags(Client client, Duration ttl) {
        this.client = client;
        this.ttlNanos = ttl.toNanos();
    }

    /**
     * Whether the feature is on for this caller (their user id: a gradual release turns it on for a
     * share of users, and each keeps the same answer). The request counts toward the flag's on or
     * off side ({@link FlagCohorts}).
     */
    public boolean enabled(String key, String user) {
        String who = user == null ? "" : user;
        boolean on = ttlNanos <= 0 ? ask(key, who) : cached(key, who);
        FlagCohorts.seen(key, on);
        return on;
    }

    public boolean enabled(String key) {
        return enabled(key, "");
    }

    Client client() {
        return client;
    }

    private boolean ask(String key, String user) {
        return Boolean.TRUE.equals(client.getBooleanValue(key, false, new ImmutableContext(user)));
    }

    private boolean cached(String key, String user) {
        long now = System.nanoTime();
        String id = key + "\n" + user;
        Answer hit = answers.get(id);
        if (hit != null && now - hit.until() < 0) {
            return hit.on();
        }
        boolean on = ask(key, user);
        if (answers.size() >= MAX_CACHED) {
            answers.values().removeIf(a -> now - a.until() >= 0);
            if (answers.size() >= MAX_CACHED) {
                answers.clear();
            }
        }
        answers.put(id, new Answer(on, now + ttlNanos));
        return on;
    }
}
