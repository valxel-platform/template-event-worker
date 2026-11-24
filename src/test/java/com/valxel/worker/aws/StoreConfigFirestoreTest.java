package com.valxel.worker.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.firestore.Firestore;
import com.valxel.worker.events.Position;
import com.valxel.worker.gcp.FirestoreGreetingStore;
import com.valxel.worker.greeting.GreetingStore;
import com.valxel.worker.greeting.GreetingStore.Greeting;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.testcontainers.gcloud.FirestoreEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** app.store=firestore as the app builds it, against the Firestore emulator (Testcontainers; needs Docker). */
@Testcontainers
class StoreConfigFirestoreTest {

    @Container
    static final FirestoreEmulatorContainer emulator = new FirestoreEmulatorContainer(
            DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:546.0.0-emulators"));

    @Test
    void theConfiguredStoreWritesToTheBoundDatabase() throws Exception {
        // The project and the emulator from configuration; the database id from the bound table
        // variable (the emulator's default database here).
        GreetingStore store = new StoreConfig().greetingStore("firestore", "(default)", "", "test-project",
                emulator.getEmulatorEndpoint(), null, null);
        assertThat(store).isInstanceOf(FirestoreGreetingStore.class);

        store.save(new Greeting("grace", "Grace", "Hi", "u2", "e1", Instant.parse("2026-11-06T00:00:00Z")),
                new Position("g", 0, 1));

        Firestore firestore = StoreConfig.firestore("test-project", "(default)", emulator.getEmulatorEndpoint());
        try {
            var doc = firestore.collection(FirestoreGreetingStore.COLLECTION).document("grace").get().get();
            assertThat(doc.getString("message")).isEqualTo("Hi");
            assertThat(doc.getLong("source_offset")).isEqualTo(1L);
        } finally {
            firestore.close();
        }
    }
}
