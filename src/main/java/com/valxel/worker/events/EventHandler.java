package com.valxel.worker.events;

/**
 * Applies one event type to a data store. Runs inside the transaction that records the event as
 * applied, so it either fully applies or not at all. Make it safe to run on an older event than
 * the store already has (compare positions), so a replay never undoes a newer change.
 */
public interface EventHandler {

    /** The CloudEvents type it applies, e.g. valxel.greeting.saved.v1. */
    String type();

    void apply(Event event, Position at);
}
