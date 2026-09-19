package com.uxplima.uxmskyblock.core.domain.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain entity representing a user-facing activity feed event (Section 2.19 & 2.42).
 */
public record ActivityEvent(
        UUID eventId,
        String instanceId,
        @Nullable ProfileId actorProfileId,
        ActivityEventType eventType,
        ActivityVisibility visibility,
        String payloadTypeId,
        int payloadSchemaVersion,
        String payloadData,
        Instant createdAt) {

    public ActivityEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        Objects.requireNonNull(payloadData, "payloadData must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
