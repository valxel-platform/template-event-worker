package com.valxel.worker.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Events are checked against their topic's approved data contract before a handler sees them. Synthetic data. */
class EventContractsTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final EventContracts contracts = EventContracts.load(getClass().getClassLoader(), "contracts");
    private final EventContracts.Contract patients = contracts.forTopic("emr.intake-api.patients.v1").orElseThrow();

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    @Test
    void propertiesThisCopyDoesntKnowAreDroppedAndTheRestChecked() {
        JsonNode kept = EventContracts.check(patients, json("""
                {"patient_id":"7d0f7c9e-5b3a-4f7e-9a51-0c1d2e3f4a5b","mrn":"1234","status":"active","born_on":"1970-01-01",
                 "visits":3,"score":1.5,"consented":true,"updated_at":"2026-11-16T10:00:00Z","tags":["x"],"address":{},
                 "added_later":"a newer producer's optional field","born_on_note":null}"""));
        assertThat(kept.has("added_later")).isFalse();
        assertThat(kept.path("mrn").asString()).isEqualTo("1234");
        assertThat(contracts.dataset("emr.intake-api.patients.v1")).isEqualTo("emr.patients");
        assertThat(contracts.dataset("emr.other.v1")).isEqualTo("emr.other.v1");
    }

    @Test
    void anEventThatBreaksItsContractIsInvalidNamingTheFieldNeverTheValue() {
        String id = "\"patient_id\":\"7d0f7c9e-5b3a-4f7e-9a51-0c1d2e3f4a5b\"";
        String[][] cases = {
            {"{\"mrn\":\"1\"}", "patient_id", "required"},
            {"{" + id + ",\"mrn\":null}", "mrn", "required"},
            {"{\"patient_id\":\"nope\",\"mrn\":\"1\"}", "patient_id", "format uuid"},
            {"{" + id + ",\"mrn\":\"12a\"}", "mrn", "pattern"},
            {"{" + id + ",\"mrn\":\"1234567890123\"}", "mrn", "maxLength"},
            {"{" + id + ",\"mrn\":\"1\",\"status\":\"secret-value\"}", "status", "enum"},
            {"{" + id + ",\"mrn\":\"1\",\"visits\":\"3\"}", "visits", "type integer"},
            {"{" + id + ",\"mrn\":\"1\",\"updated_at\":\"later\"}", "updated_at", "format date-time"},
            {"{" + id + ",\"mrn\":\"1\",\"consented\":\"yes\"}", "consented", "type boolean"},
        };
        for (String[] c : cases) {
            assertThatThrownBy(() -> EventContracts.check(patients, json(c[0])))
                    .as(c[1])
                    .isInstanceOf(InvalidEventException.class)
                    .hasMessage("emr.patients: " + c[1] + " breaks the data contract (" + c[2] + ")")
                    .hasMessageNotContaining("secret-value");
        }
        assertThatThrownBy(() -> EventContracts.check(patients, json("[1]"))).hasMessage("emr.patients: data is not an object");
    }

    @Test
    void theApplierCountsEachDatasetAndParksWhatBreaksItsContract() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DatasetCounts summary = new DatasetCounts();
        EventApplier applier = new EventApplier(null, List.of(), meters, contracts, summary);
        Event event = Event.parse(EventTest.event("e-1", "{\"x\":1}"), mapper);
        assertThat(applier.apply(event, new Position("emr.intake-api.patients.v1", 0, 1))).isEqualTo(EventApplier.Outcome.SKIPPED);
        assertThat(meters.counter("valxel.dataset.events", "dataset", "emr.patients", "outcome", "skipped").count()).isEqualTo(1);
        assertThat(summary.drain()).isEqualTo("valxel.datasets {\"emr.patients\":{\"skipped\":1}}");
        assertThat(new EventApplier(null, List.of(), meters, contracts)).isNotNull();
        assertThat(EventContracts.none().forTopic("emr.intake-api.patients.v1")).isEmpty();
        assertThat(EventContracts.load(getClass().getClassLoader(), "no-such-dir").forTopic("x")).isEmpty();
        assertThatThrownBy(() -> EventContracts.load(getClass().getClassLoader(), "broken-contracts"))
                .hasMessageContaining("emr.ghost.json is missing");
    }
}
