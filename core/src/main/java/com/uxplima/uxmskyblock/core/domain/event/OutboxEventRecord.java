package com.uxplima.uxmskyblock.core.domain.event;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Immutable operational record representing an outbox event.
 */
public record OutboxEventRecord(
        EventId eventId,
        String eventType,
        String aggregateId,
        String payload,
        OutboxStatus status,
        @Nullable String claimOwner,
        @Nullable String claimToken,
        @Nullable Instant claimExpiresAt,
        int retryCount,
        @Nullable Instant nextAttemptAt,
        @Nullable String lastError,
        Instant createdAt,
        @Nullable Instant processedAt) {

    public OutboxEventRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
