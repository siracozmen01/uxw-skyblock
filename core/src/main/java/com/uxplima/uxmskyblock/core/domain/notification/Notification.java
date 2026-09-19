package com.uxplima.uxmskyblock.core.domain.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain entity representing a durable offline notification (Section 2.18 & 2.42).
 */
public record Notification(
        UUID notificationId,
        ProfileId recipientProfileId,
        NotificationCategory category,
        String payloadTypeId,
        int payloadSchemaVersion,
        String payloadData,
        boolean isRead,
        @Nullable Instant readAt,
        @Nullable Instant expiresAt,
        Instant createdAt) {

    public Notification {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        Objects.requireNonNull(payloadData, "payloadData must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Notification markAsRead(Instant at) {
        return new Notification(
                notificationId,
                recipientProfileId,
                category,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                true,
                at,
                expiresAt,
                createdAt);
    }
}
