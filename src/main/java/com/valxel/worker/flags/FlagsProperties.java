package com.valxel.worker.flags;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The product's Flipt (app.flags.*, or FLAGS_URL and friends in application.yaml). Without a URL
 * every feature flag reads as its default: off.
 *
 * @param url the product's Flipt, e.g. http://flipt.emr.svc:8080
 * @param environment the Flipt environment, e.g. production
 * @param namespace the product's Flipt namespace, e.g. emr
 * @param token a Flipt client token, if the product's Flipt requires one for evaluation
 */
@ConfigurationProperties("app.flags")
public record FlagsProperties(String url, String environment, String namespace, String token) {

    public FlagsProperties {
        url = url == null ? "" : url.strip();
        environment = environment == null ? "" : environment.strip();
        namespace = namespace == null ? "" : namespace.strip();
        token = token == null ? "" : token.strip();
    }
}
