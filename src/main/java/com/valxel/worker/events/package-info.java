/**
 * Applying events to the product's data stores.
 *
 * <p>Services publish every change to Kafka first (constitution VX-16); this worker reads those
 * topics and keeps a data store up to date. For each record it:
 *
 * <ol>
 *   <li>parses the CloudEvent ({@link com.valxel.worker.events.Event}); a record that isn't one goes
 *       straight to the dead-letter topic, since retrying can't fix it;
 *   <li>in one database transaction, notes the event id in {@code applied_events} and runs the
 *       {@link com.valxel.worker.events.EventHandler} for its type, so an event delivered twice (a
 *       producer retry, a redelivery after a crash) changes the store once;
 *   <li>commits the Kafka offset only after the transaction commits. A failure is retried with
 *       backoff, then parked on {@code <topic>.dlt} so one bad record doesn't stop the partition.
 * </ol>
 *
 * <p>Events for one record share a key, so they arrive in order. Handlers also keep the record's
 * Kafka position and ignore anything older, so replaying a topic over an existing store is safe.
 * Event types this worker doesn't know are skipped, so producers can add types first.
 */
package com.valxel.worker.events;
