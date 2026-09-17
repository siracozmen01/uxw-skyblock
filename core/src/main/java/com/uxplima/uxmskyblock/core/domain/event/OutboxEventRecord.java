package com.uxplima.uxmskyblock.core.domain.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable operational record representing an outbox event.
 */
public record OutboxEventRecord(
        EventId eventId,
        String eventType,
        String aggregateId,
        String payload,
        OutboxStatus status,
        String claimOwner,
        String claimToken,
        Instant claimExpiresAt,
        int retryCount,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant processedAt) {

    public OutboxEventRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
