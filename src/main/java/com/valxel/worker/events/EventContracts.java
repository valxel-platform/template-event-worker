package com.valxel.worker.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The approved data contracts of the topics this worker reads (the design "Datasets, contracts and
 * reporting"). The SDLC tool writes {@code contracts/<dataset>.json} (the JSON Schema generated from
 * each ODCS contract) and {@code contracts/lock.yaml} (dataset: version, sha, topic); the build puts them
 * on the classpath, so nothing reads GitHub at run time.
 *
 * <p>Reading an event: drop the properties this copy doesn't have (a newer, compatible producer may add
 * optional ones), then check the rest. An event that breaks its contract is an
 * {@link InvalidEventException} naming the field, never its value: it goes straight to the dead-letter
 * topic. This checks the subset of JSON Schema the tool generates: type, format (date, date-time, time,
 * uuid), enum, required, minLength, maxLength and pattern.
 */
public final class EventContracts {

    /** One dataset: its contract's schema and the topic it comes on. */
    public record Contract(String dataset, String topic, String version, Map<String, Object> schema) {}

    private static final EventContracts NONE = new EventContracts(Map.of());
    private final Map<String, Contract> byTopic;

    public EventContracts(Map<String, Contract> byTopic) {
        this.byTopic = Map.copyOf(byTopic);
    }

    public static EventContracts none() {
        return NONE;
    }

    /** The contracts under {@code dir} on the classpath; none when there is no lock.yaml. */
    @SuppressWarnings("unchecked")
    public static EventContracts load(ClassLoader loader, String dir) {
        Map<String, Object> lock = (Map<String, Object>) read(loader, dir + "/lock.yaml");
        if (lock == null) {
            return NONE;
        }
        Map<String, Contract> out = new LinkedHashMap<>();
        lock.forEach((dataset, row) -> {
            Map<String, Object> r = (Map<String, Object>) row;
            Map<String, Object> schema = (Map<String, Object>) read(loader, dir + "/" + dataset + ".json");
            if (schema == null) {
                throw new IllegalStateException(dir + "/lock.yaml lists " + dataset + " but " + dataset + ".json is missing");
            }
            String topic = String.valueOf(r.get("topic"));
            out.put(topic, new Contract(dataset, topic, String.valueOf(r.get("version")), schema));
        });
        return new EventContracts(out);
    }

    private static Object read(ClassLoader loader, String path) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            return in == null ? null : new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("can't read " + path, e);
        }
    }

    public Optional<Contract> forTopic(String topic) {
        return Optional.ofNullable(byTopic.get(topic));
    }

    /** The dataset a topic carries: its contract's id, or the topic itself. */
    public String dataset(String topic) {
        Contract c = byTopic.get(topic);
        return c == null ? topic : c.dataset();
    }

    /** The event's data as this copy knows it, or an {@link InvalidEventException} naming the field. */
    @SuppressWarnings("unchecked")
    public static JsonNode check(Contract c, JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new InvalidEventException(c.dataset() + ": data is not an object");
        }
        Map<String, Object> props = (Map<String, Object>) c.schema().getOrDefault("properties", Map.of());
        ObjectNode kept = (ObjectNode) data.deepCopy();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : data.properties()) {
            if (!props.containsKey(e.getKey())) {
                unknown.add(e.getKey());
            }
        }
        unknown.forEach(kept::remove);
        for (Object req : (List<Object>) c.schema().getOrDefault("required", List.of())) {
            JsonNode v = kept.path(String.valueOf(req));
            if (v.isMissingNode() || v.isNull()) {
                throw new InvalidEventException(c.dataset() + ": " + req + " breaks the data contract (required)");
            }
        }
        for (Map.Entry<String, JsonNode> e : kept.properties()) {
            String rule = violation((Map<String, Object>) props.get(e.getKey()), e.getValue());
            if (rule != null) {
                throw new InvalidEventException(c.dataset() + ": " + e.getKey() + " breaks the data contract (" + rule + ")");
            }
        }
        return kept;
    }

    @SuppressWarnings("unchecked")
    private static String violation(Map<String, Object> s, JsonNode v) {
        if (v.isNull()) {
            return null; // an optional property left empty
        }
        String type = String.valueOf(s.get("type"));
        boolean ok = switch (type) {
            case "string" -> v.isString();
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            default -> true;
        };
        if (!ok) {
            return "type " + type;
        }
        if (v.isString()) {
            String text = v.asString();
            Object format = s.get("format");
            if (format != null && !matchesFormat(String.valueOf(format), text)) {
                return "format " + format;
            }
            if (s.get("minLength") instanceof Number n && text.length() < n.intValue()) {
                return "minLength";
            }
            if (s.get("maxLength") instanceof Number n && text.length() > n.intValue()) {
                return "maxLength";
            }
            if (s.get("pattern") instanceof String p && !Pattern.compile(p).matcher(text).find()) {
                return "pattern";
            }
        }
        List<Object> allowed = (List<Object>) s.get("enum");
        String value = v.isString() ? v.asString() : v.toString();
        if (allowed != null && allowed.stream().noneMatch(a -> String.valueOf(a).equals(value))) {
            return "enum";
        }
        return null;
    }

    private static boolean matchesFormat(String format, String text) {
        try {
            switch (format) {
                case "date" -> LocalDate.parse(text);
                case "date-time" -> OffsetDateTime.parse(text);
                case "time" -> OffsetTime.parse(text);
                case "uuid" -> UUID.fromString(text);
                default -> {
                    // Other formats (email, uri...) aren't checked here.
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
