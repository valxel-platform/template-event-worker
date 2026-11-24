package com.valxel.worker.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.NoCredentials;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.valxel.worker.events.Position;
import com.valxel.worker.greeting.GreetingStore.Greeting;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.gcloud.FirestoreEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The Firestore store against the Firestore emulator (Testcontainers; needs Docker). */
@Testcontainers
class FirestoreGreetingStoreTest {

    @Container
    static final FirestoreEmulatorContainer emulator = new FirestoreEmulatorContainer(
            DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:546.0.0-emulators"));

    static Firestore firestore;

    @BeforeAll
    static void connect() {
        // The emulator takes no credentials; in the platform the pod's Google service account signs requests.
        firestore = FirestoreOptions.newBuilder()
                .setProjectId("test-project")
                // IPv4: the emulator listens on 0.0.0.0, and "localhost" may resolve to ::1 first.
                .setEmulatorHost("127.0.0.1:" + emulator.getMappedPort(8080))
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
    }

    @AfterAll
    static void close() throws Exception {
        firestore.close();
    }

    private static DocumentSnapshot doc(String id) throws Exception {
        return firestore.collection(FirestoreGreetingStore.COLLECTION).document(id).get().get();
    }

    @Test
    void savesAndOnlyMovesForward() throws Exception {
        FirestoreGreetingStore store = new FirestoreGreetingStore(firestore);
        Instant t = Instant.parse("2026-11-06T00:00:00Z");
        store.save(new Greeting("ada", "Ada", "Hello", "u1", "e1", t), new Position("g", 0, 10));
        assertThat(doc("ada").getString("message")).isEqualTo("Hello");
        assertThat(doc("ada").getLong("source_offset")).isEqualTo(10L);

        // An older event (a replay) changes nothing; the same event again changes nothing.
        store.save(new Greeting("ada", "Ada", "Old", "u1", "e0", t), new Position("g", 0, 9));
        store.save(new Greeting("ada", "Ada", "Hello", "u1", "e1", t), new Position("g", 0, 10));
        assertThat(doc("ada").getString("message")).isEqualTo("Hello");

        store.save(new Greeting("ada", "Ada", "Newer", null, "e2", t), new Position("g", 0, 11));
        assertThat(doc("ada").getString("message")).isEqualTo("Newer");
        assertThat(doc("ada").contains("saved_by")).isTrue();
        assertThat(doc("ada").getString("saved_by")).isNull();
    }
}
