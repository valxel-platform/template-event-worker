package com.valxel.worker.events;

/** Where an event sits in Kafka. Within a partition, a larger offset is a later change. */
public record Position(String topic, int partition, long offset) {}
