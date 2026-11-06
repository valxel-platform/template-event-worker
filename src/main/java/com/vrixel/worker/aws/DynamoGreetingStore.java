package com.vrixel.worker.aws;

import com.vrixel.worker.events.Position;
import com.vrixel.worker.greeting.GreetingStore;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * The greetings read model in a DynamoDB table (a {@code dynamodb-table} resource with
 * {@code partition_key=id}). One conditional update per event: it applies only if the item is new
 * or came from an earlier Kafka offset, so a duplicate or an older event changes nothing.
 *
 * <p>The worker records each applied event in Postgres in the same step; if that fails after this
 * write, the retry finds the item already at this offset and moves on, so the result is the same.
 */
public class DynamoGreetingStore implements GreetingStore {

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoGreetingStore(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public void save(Greeting g, Position at) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":name", s(g.name()));
        values.put(":message", s(g.message()));
        values.put(":savedBy", s(g.savedBy()));
        values.put(":eventId", s(g.eventId()));
        values.put(":partition", n(at.partition()));
        values.put(":offset", n(at.offset()));
        values.put(":time", s(g.updatedAt().toString()));
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("id", s(g.id())))
                    .updateExpression("SET #name = :name, message = :message, saved_by = :savedBy, event_id = :eventId,"
                            + " source_partition = :partition, source_offset = :offset, updated_at = :time")
                    .conditionExpression("attribute_not_exists(id) OR source_offset < :offset")
                    .expressionAttributeNames(Map.of("#name", "name"))
                    .expressionAttributeValues(values)
                    .build());
        } catch (ConditionalCheckFailedException older) {
            // Already at this offset or a later one: nothing to do.
        }
    }

    private static AttributeValue s(String value) {
        return value == null ? AttributeValue.fromNul(true) : AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
