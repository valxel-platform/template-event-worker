package com.valxel.worker.flags;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads feature flags from the product's Flipt over OFREP, the OpenFeature Remote Evaluation
 * Protocol: {@code POST /ofrep/v1/evaluate/flags/{key}} with the Flipt environment and namespace
 * as headers. Any failure (Flipt down, unknown flag, wrong type) gives the caller's default, so a
 * new feature stays off.
 */
public final class OfrepProvider implements FeatureProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    private final RestClient http;

    public OfrepProvider(RestClient.Builder builder, String baseUrl, String environment, String namespace, String token) {
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeaders(h -> {
                    if (!environment.isBlank()) {
                        h.set("x-flipt-environment", environment);
                    }
                    if (!namespace.isBlank()) {
                        h.set("x-flipt-namespace", namespace);
                    }
                    if (!token.isBlank()) {
                        h.setBearerAuth(token);
                    }
                })
                .build();
    }

    @Override
    public Metadata getMetadata() {
        return () -> "flipt-ofrep";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        return evaluate(key, defaultValue, Boolean.class, ctx);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return evaluate(key, defaultValue, String.class, ctx);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        return evaluate(key, defaultValue, Integer.class, ctx);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        return evaluate(key, defaultValue, Double.class, ctx);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        return failed(defaultValue, ErrorCode.TYPE_MISMATCH, "object flags are not supported; use a boolean");
    }

    private <T> ProviderEvaluation<T> evaluate(String key, T fallback, Class<T> type, EvaluationContext ctx) {
        Map<String, Object> result;
        try {
            result = http.post()
                    .uri("/ofrep/v1/evaluate/flags/{key}", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("context", context(ctx)))
                    .retrieve()
                    .body(JSON);
        } catch (HttpClientErrorException.NotFound e) {
            return failed(fallback, ErrorCode.FLAG_NOT_FOUND, "flag " + key + " was not found");
        } catch (RestClientException e) {
            return failed(fallback, ErrorCode.GENERAL, "flag server unavailable: " + e.getClass().getSimpleName());
        }
        T value = result == null ? null : coerce(result.get("value"), type);
        if (value == null) {
            return failed(fallback, ErrorCode.TYPE_MISMATCH, "flag " + key + " is not a " + type.getSimpleName());
        }
        return ProviderEvaluation.<T>builder()
                .value(value)
                .variant(text(result.get("variant")))
                .reason(text(result.get("reason")))
                .build();
    }

    static Map<String, Object> context(EvaluationContext ctx) {
        Map<String, Object> out = new HashMap<>();
        if (ctx == null) {
            return out;
        }
        out.putAll(ctx.asObjectMap());
        String targetingKey = ctx.getTargetingKey();
        if (targetingKey != null && !targetingKey.isBlank()) {
            out.put("targetingKey", targetingKey);
        } else {
            out.remove("targetingKey");
        }
        return out;
    }

    static <T> T coerce(Object value, Class<T> type) {
        if (value instanceof Number n && type == Integer.class) {
            return type.cast(n.intValue());
        }
        if (value instanceof Number n && type == Double.class) {
            return type.cast(n.doubleValue());
        }
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static <T> ProviderEvaluation<T> failed(T fallback, ErrorCode code, String message) {
        return ProviderEvaluation.<T>builder()
                .value(fallback)
                .reason(Reason.ERROR.toString())
                .errorCode(code)
                .errorMessage(message)
                .build();
    }
}
