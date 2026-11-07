package com.vrixel.worker.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OfrepProviderTest {

    private static final String URL = "http://flipt.emr.svc:8080/ofrep/v1/evaluate/flags/";
    private static final String KEY = "emr.emr-feat-3.check-in-kiosk";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer flipt = MockRestServiceServer.bindTo(builder).build();

    private OfrepProvider provider(String environment, String namespace, String token) {
        return new OfrepProvider(builder, "http://flipt.emr.svc:8080", environment, namespace, token);
    }

    private static String answer(String value) {
        return "{\"key\":\"" + KEY + "\",\"value\":" + value + ",\"variant\":\"on\",\"reason\":\"TARGETING_MATCH\"}";
    }

    @Test
    void asksTheProductsFliptWithItsEnvironmentNamespaceAndTheCaller() {
        OfrepProvider p = provider("production", "emr", "t0ken");
        flipt.expect(requestTo(URL + KEY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-flipt-environment", "production"))
                .andExpect(header("x-flipt-namespace", "emr"))
                .andExpect(header("Authorization", "Bearer t0ken"))
                .andExpect(jsonPath("$.context.targetingKey").value("nurse-1"))
                .andExpect(jsonPath("$.context.clinic").value("north"))
                .andRespond(withSuccess(answer("true"), MediaType.APPLICATION_JSON));

        ProviderEvaluation<Boolean> r = p.getBooleanEvaluation(
                KEY, false, new ImmutableContext("nurse-1", Map.of("clinic", new Value("north"))));

        assertThat(r.getValue()).isTrue();
        assertThat(r.getVariant()).isEqualTo("on");
        assertThat(r.getReason()).isEqualTo("TARGETING_MATCH");
        assertThat(r.getErrorCode()).isNull();
        assertThat(p.getMetadata().getName()).isEqualTo("flipt-ofrep");
        flipt.verify();
    }

    @Test
    void sendsOnlyTheHeadersThatAreConfigured() {
        OfrepProvider p = provider("", "", "");
        flipt.expect(requestTo(URL + KEY))
                .andExpect(headerDoesNotExist("x-flipt-environment"))
                .andExpect(headerDoesNotExist("x-flipt-namespace"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(answer("false"), MediaType.APPLICATION_JSON));

        assertThat(p.getBooleanEvaluation(KEY, true, null).getValue()).isFalse();
        flipt.verify();
    }

    @Test
    void anUnknownFlagGivesTheDefault() {
        OfrepProvider p = provider("production", "emr", "");
        flipt.expect(requestTo(URL + KEY)).andRespond(withResourceNotFound());

        ProviderEvaluation<Boolean> r = p.getBooleanEvaluation(KEY, false, new ImmutableContext());

        assertThat(r.getValue()).isFalse();
        assertThat(r.getErrorCode()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
        assertThat(r.getReason()).isEqualTo("ERROR");
    }

    @Test
    void anUnavailableFlagServerGivesTheDefault() {
        OfrepProvider p = provider("production", "emr", "");
        flipt.expect(requestTo(URL + KEY)).andRespond(withServerError());

        ProviderEvaluation<Boolean> r = p.getBooleanEvaluation(KEY, false, new ImmutableContext());

        assertThat(r.getValue()).isFalse();
        assertThat(r.getErrorCode()).isEqualTo(ErrorCode.GENERAL);
    }

    @Test
    void aValueOfTheWrongTypeGivesTheDefault() {
        OfrepProvider p = provider("production", "emr", "");
        flipt.expect(requestTo(URL + KEY)).andRespond(withSuccess(answer("\"yes\""), MediaType.APPLICATION_JSON));

        ProviderEvaluation<Boolean> r = p.getBooleanEvaluation(KEY, false, new ImmutableContext());

        assertThat(r.getValue()).isFalse();
        assertThat(r.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
    }

    @Test
    void readsStringsAndNumbers() {
        OfrepProvider p = provider("production", "emr", "");
        flipt.expect(requestTo(URL + "s")).andRespond(withSuccess(answer("\"blue\""), MediaType.APPLICATION_JSON));
        flipt.expect(requestTo(URL + "i")).andRespond(withSuccess(answer("3"), MediaType.APPLICATION_JSON));
        flipt.expect(requestTo(URL + "d")).andRespond(withSuccess(answer("2"), MediaType.APPLICATION_JSON));

        assertThat(p.getStringEvaluation("s", "red", null).getValue()).isEqualTo("blue");
        assertThat(p.getIntegerEvaluation("i", 1, null).getValue()).isEqualTo(3);
        assertThat(p.getDoubleEvaluation("d", 1.0, null).getValue()).isEqualTo(2.0);
        flipt.verify();
    }

    @Test
    void objectFlagsAreNotSupported() {
        Value fallback = new Value("x");
        ProviderEvaluation<Value> r = provider("", "", "").getObjectEvaluation(KEY, fallback, null);

        assertThat(r.getValue()).isSameAs(fallback);
        assertThat(r.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
    }

    @Test
    void anEmptyTargetingKeyIsLeftOut() {
        assertThat(OfrepProvider.context(new ImmutableContext("", Map.of("a", new Value(1)))))
                .containsEntry("a", 1)
                .doesNotContainKey("targetingKey");
        assertThat(OfrepProvider.context(null)).isEmpty();
    }
}
