package com.valxel.worker.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.valxel.worker.events.Position;
import com.valxel.worker.greeting.GreetingStore.Greeting;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** The DynamoDB store against DynamoDB Local (Testcontainers; needs Docker). */
@Testcontainers
class DynamoGreetingStoreTest {

    @Container
    static final GenericContainer<?> local = new GenericContainer<>("amazon/dynamodb-local:2.5.2").withExposedPorts(8000);

    static DynamoDbClient dynamo;

    @BeforeAll
    static void table() {
        // DynamoDB Local takes any credentials; in the platform the pod's identity signs requests.
        dynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + local.getHost() + ":" + local.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        dynamo.createTable(b -> b.tableName("greetings")
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id")
                        .attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
    }

    private static Map<String, AttributeValue> item(String id) {
        return dynamo.getItem(b -> b.tableName("greetings").key(Map.of("id", AttributeValue.fromS(id)))).item();
    }

    @Test
    void savesAndOnlyMovesForward() {
        DynamoGreetingStore store = new DynamoGreetingStore(dynamo, "greetings");
        Instant t = Instant.parse("2026-11-06T00:00:00Z");
        store.save(new Greeting("ada", "Ada", "Hello", "u1", "e1", t), new Position("g", 0, 10));
        assertThat(item("ada").get("message").s()).isEqualTo("Hello");
        assertThat(item("ada").get("source_offset").n()).isEqualTo("10");

        // An older event (a replay) changes nothing; the same event again changes nothing.
        store.save(new Greeting("ada", "Ada", "Old", "u1", "e0", t), new Position("g", 0, 9));
        store.save(new Greeting("ada", "Ada", "Hello", "u1", "e1", t), new Position("g", 0, 10));
        assertThat(item("ada").get("message").s()).isEqualTo("Hello");

        store.save(new Greeting("ada", "Ada", "Newer", null, "e2", t), new Position("g", 0, 11));
        assertThat(item("ada").get("message").s()).isEqualTo("Newer");
        assertThat(item("ada").get("saved_by").nul()).isTrue();
    }

    @Test
    void theStoreIsChosenByConfiguration() {
        assertThat(new StoreConfig().greetingStore("postgres", "", "", null, null)).isInstanceOf(
                com.valxel.worker.greeting.JdbcGreetingStore.class);
        assertThatThrownBy(() -> new StoreConfig().greetingStore("dynamodb", " ", "", null, null))
                .hasMessageContaining("GREETINGS_TABLE_NAME");
        assertThatThrownBy(() -> new StoreConfig().greetingStore("cassandra", "", "", null, null))
                .hasMessageContaining("use postgres or dynamodb");
    }
}
