package com.vrixel.worker.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class EventTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    static String event(String id, String data) {
        return """
                {"specversion":"1.0","id":"%s","source":"emr/intake-api","type":"vrixel.greeting.saved.v1",
                 "time":"2026-11-04T12:00:00Z","subject":"greeting/g-1","datacontenttype":"application/json",
                 "data":%s}""".formatted(id, data);
    }

    @Test
    void parsesACloudEvent() {
        Event e = Event.parse(event("e-1", "{\"id\":\"g-1\",\"name\":\"Ada\"}"), mapper);
        assertThat(e.id()).isEqualTo("e-1");
        assertThat(e.type()).isEqualTo("vrixel.greeting.saved.v1");
        assertThat(e.source()).isEqualTo("emr/intake-api");
        assertThat(e.subject()).isEqualTo("greeting/g-1");
        assertThat(e.time()).isEqualTo(Instant.parse("2026-11-04T12:00:00Z"));
        assertThat(e.dataText("name")).isEqualTo("Ada");
    }

    @Test
    void whatIsntAnEventCantBeRetriedIntoOne() {
        assertThatThrownBy(() -> Event.parse(null, mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> Event.parse("not json", mapper)).hasMessage("not JSON");
        assertThatThrownBy(() -> Event.parse("[1]", mapper)).hasMessage("not a JSON object");
        assertThatThrownBy(() -> Event.parse("{\"id\":\"x\"}", mapper)).hasMessage("not a CloudEvents 1.0 event");
        assertThatThrownBy(() -> Event.parse(event("", "{}"), mapper)).hasMessage("missing id");
        assertThatThrownBy(() -> Event.parse(event("e", "{}").replace("2026-11-04T12:00:00Z", "yesterday"), mapper))
                .hasMessage("time is not an RFC 3339 timestamp");
    }

    @Test
    void missingDataIsNamedNeverEchoed() {
        Event e = Event.parse(event("e-1", "{\"name\":\"\"}"), mapper);
        assertThatThrownBy(() -> e.dataText("name")).hasMessage("vrixel.greeting.saved.v1 needs data.name");
        assertThatThrownBy(() -> e.dataText("id")).hasMessage("vrixel.greeting.saved.v1 needs data.id");
    }
}
