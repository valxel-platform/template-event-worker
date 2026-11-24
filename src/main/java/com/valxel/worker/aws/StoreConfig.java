package com.valxel.worker.aws;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.valxel.worker.gcp.FirestoreGreetingStore;
import com.valxel.worker.greeting.GreetingStore;
import com.valxel.worker.greeting.JdbcGreetingStore;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * Picks the read model's store (app.store). For DynamoDB the table name comes from the
 * environment variable OpenChoreo binds from the resource (workload.yaml), and credentials from
 * the SDK's default chain: in the platform, the pod's own IAM role through EKS Pod Identity, never
 * keys (VX-19). {@code app.dynamodb.endpoint} points at DynamoDB Local in tests only.
 *
 * <p>For Firestore (a {@code key-value-table} on Google Cloud) the database id comes from the same
 * variable, the project from the environment (GOOGLE_CLOUD_PROJECT or the metadata server), and
 * credentials from Application Default Credentials: in the platform, the pod's Google service
 * account through GKE Workload Identity, never keys. {@code app.firestore.emulator} points at the
 * Firestore emulator in tests only.
 *
 * <p>Every AWS call is a span of this component (OpenTelemetry's AWS SDK instrumentation), so
 * OpenChoreo's traces show its DynamoDB calls, their latency and their errors and throttling.
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfig {

    @Bean
    GreetingStore greetingStore(
            @Value("${app.store:postgres}") String store,
            @Value("${app.dynamodb.table:}") String table,
            @Value("${app.dynamodb.endpoint:}") String endpoint,
            @Value("${app.firestore.project:}") String project,
            @Value("${app.firestore.emulator:}") String emulator,
            JdbcClient jdbc,
            ObjectProvider<OpenTelemetry> openTelemetry) {
        return switch (store) {
            case "postgres" -> new JdbcGreetingStore(jdbc);
            case "dynamodb" -> {
                if (table.isBlank()) {
                    throw new IllegalStateException(
                            "app.store=dynamodb needs app.dynamodb.table (GREETINGS_TABLE_NAME, bound from the resource)");
                }
                yield new DynamoGreetingStore(dynamo(endpoint, openTelemetry.getIfAvailable(OpenTelemetry::noop)), table);
            }
            case "firestore" -> {
                if (table.isBlank()) {
                    throw new IllegalStateException(
                            "app.store=firestore needs app.dynamodb.table (GREETINGS_TABLE_NAME, the database bound from the resource)");
                }
                yield new FirestoreGreetingStore(firestore(project, table, emulator));
            }
            default -> throw new IllegalStateException(
                    "Unknown app.store " + store + ": use postgres, dynamodb or firestore");
        };
    }

    static DynamoDbClient dynamo(String endpoint, OpenTelemetry openTelemetry) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.addExecutionInterceptor(
                        AwsSdkTelemetry.create(openTelemetry).createExecutionInterceptor()));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    static Firestore firestore(String project, String database, String emulator) {
        FirestoreOptions.Builder builder = FirestoreOptions.newBuilder().setDatabaseId(database);
        if (project != null && !project.isBlank()) {
            builder.setProjectId(project);
        }
        if (emulator != null && !emulator.isBlank()) {
            builder.setEmulatorHost(emulator);
        }
        return builder.build().getService();
    }
}
