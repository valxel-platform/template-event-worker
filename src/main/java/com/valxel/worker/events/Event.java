package com.valxel.worker.events;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One CloudEvents 1.0 event (structured JSON, as the service template publishes them). */
public record Event(String id, String type, String source, String subject, Instant time, JsonNode data) {

    public static Event parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("empty record");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidEventException("not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidEventException("not a JSON object");
        }
        if (!"1.0".equals(text(root, "specversion"))) {
            throw new InvalidEventException("not a CloudEvents 1.0 event");
        }
        String id = required(root, "id");
        String type = required(root, "type");
        String source = required(root, "source");
        Instant time;
        try {
            String t = text(root, "time");
            time = t.isEmpty() ? null : Instant.parse(t);
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("time is not an RFC 3339 timestamp");
        }
        return new Event(id, type, source, text(root, "subject"), time, root.path("data"));
    }

    /** A string field of the event's data, or an InvalidEventException naming it (never its value). */
    public String dataText(String field) {
        JsonNode v = data.path(field);
        if (!v.isString() || v.asString().isBlank()) {
            throw new InvalidEventException(type + " needs data." + field);
        }
        return v.asString();
    }

    private static String required(JsonNode root, String field) {
        String v = text(root, field);
        if (v.isBlank()) {
            throw new InvalidEventException("missing " + field);
        }
        return v;
    }

    private static String text(JsonNode root, String field) {
        JsonNode v = root.path(field);
        return v.isString() ? v.asString() : "";
    }
}
