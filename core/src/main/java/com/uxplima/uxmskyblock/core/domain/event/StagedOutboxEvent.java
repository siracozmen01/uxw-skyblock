package com.uxplima.uxmskyblock.core.domain.event;

import java.util.Objects;

/**
 * Encapsulates an outbox event to be atomically staged within the same database transaction
 * as the domain entity mutation.
 */
public record StagedOutboxEvent(EventId eventId, String eventType, String aggregateId, String payload) {

    public StagedOutboxEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static StagedOutboxEvent of(String eventType, String aggregateId, String payload) {
        return new StagedOutboxEvent(EventId.random(), eventType, aggregateId, payload);
    }

    public EventId id() {
        return eventId;
    }
}
