package com.valxel.worker.gcp;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.valxel.worker.events.Position;
import com.valxel.worker.greeting.GreetingStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * The greetings read model in Firestore (a {@code key-value-table} resource with
 * {@code partition_key=id} on Google Cloud: a database of its own, items in the collection
 * {@code items}, the document id the partition key). One transaction per event: it writes only if
 * the document is new or came from an earlier Kafka offset, so a duplicate or an older event
 * changes nothing.
 *
 * <p>The worker records each applied event in Postgres in the same step; if that fails after this
 * write, the retry finds the document already at this offset and moves on, so the result is the same.
 */
public class FirestoreGreetingStore implements GreetingStore {

    /** The collection the platform's key-value-table Composition indexes. */
    public static final String COLLECTION = "items";

    private final Firestore firestore;

    public FirestoreGreetingStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(Greeting g, Position at) {
        DocumentReference doc = firestore.collection(COLLECTION).document(g.id());
        Map<String, Object> values = new HashMap<>();
        values.put("id", g.id());
        values.put("name", g.name());
        values.put("message", g.message());
        values.put("saved_by", g.savedBy());
        values.put("event_id", g.eventId());
        values.put("source_partition", at.partition());
        values.put("source_offset", at.offset());
        values.put("updated_at", g.updatedAt().toString());
        try {
            firestore.runTransaction(tx -> {
                DocumentSnapshot now = tx.get(doc).get();
                Long offset = now.exists() ? now.getLong("source_offset") : null;
                if (offset == null || offset < at.offset()) {
                    tx.set(doc, values);
                }
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted saving greeting " + g.id(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not save greeting " + g.id(), e.getCause());
        }
    }
}
