package com.valxel.worker.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.valxel.worker.events.Position;
import com.valxel.worker.greeting.GreetingStore.Greeting;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** When Firestore fails or the worker is stopped mid-save (no emulator needed). */
class FirestoreGreetingStoreErrorsTest {

    private static final Greeting GREETING =
            new Greeting("ada", "Ada", "Hello", "u1", "e1", Instant.parse("2026-11-06T00:00:00Z"));
    private static final Position AT = new Position("g", 0, 10);

    private static Firestore firestoreWhoseTransactionReturns(Object future) {
        Firestore firestore = mock(Firestore.class);
        CollectionReference items = mock(CollectionReference.class);
        when(firestore.collection(FirestoreGreetingStore.COLLECTION)).thenReturn(items);
        when(items.document(anyString())).thenReturn(mock(DocumentReference.class));
        doReturn(future).when(firestore).runTransaction(any());
        return firestore;
    }

    @Test
    void aFailedTransactionSaysWhichGreetingAndWhy() {
        RuntimeException cause = new RuntimeException("ABORTED: too much contention");
        FirestoreGreetingStore store =
                new FirestoreGreetingStore(firestoreWhoseTransactionReturns(ApiFutures.immediateFailedFuture(cause)));

        assertThatThrownBy(() -> store.save(GREETING, AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not save greeting ada")
                .hasCause(cause);
    }

    @Test
    void anInterruptedSaveKeepsTheInterrupt() {
        // A transaction that hasn't finished when the worker's thread is interrupted (shutting down).
        FirestoreGreetingStore store =
                new FirestoreGreetingStore(firestoreWhoseTransactionReturns(SettableApiFuture.create()));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> store.save(GREETING, AT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Interrupted saving greeting ada")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear it, so the next test starts clean
        }
    }
}
