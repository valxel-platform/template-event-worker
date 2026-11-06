package com.vrixel.worker.aws;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The store's DynamoDB client makes a span for every call, which is how a component's use of AWS
 * services shows in OpenChoreo's traces (DynamoDB Local in Testcontainers; needs Docker).
 */
@Testcontainers
class AwsTracingTest {

    @Container
    static final GenericContainer<?> local = new GenericContainer<>("amazon/dynamodb-local:2.5.2").withExposedPorts(8000);

    @Test
    void everyCallIsASpan() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .build();
        // The SDK's default chain reads these; in the platform the pod's identity and region apply.
        System.setProperty("aws.region", "us-east-1");
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        try (DynamoDbClient dynamo = StoreConfig.dynamo("http://" + local.getHost() + ":" + local.getMappedPort(8000), otel)) {
            dynamo.listTables();
        } finally {
            System.clearProperty("aws.accessKeyId");
            System.clearProperty("aws.secretAccessKey");
            System.clearProperty("aws.region");
        }
        assertThat(spans.getFinishedSpanItems())
                .extracting(SpanData::getName)
                .containsExactly("DynamoDb.ListTables");
        SpanData span = spans.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("rpc.service"))).isEqualTo("DynamoDb");
        otel.close();
    }
}
