package com.valxel.worker.events;

/**
 * The record can't be applied however often it is retried (not an event, or missing what its
 * handler needs). It goes straight to the dead-letter topic. Messages name fields, never values.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
