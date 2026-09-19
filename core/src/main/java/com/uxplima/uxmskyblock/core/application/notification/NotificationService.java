package com.uxplima.uxmskyblock.core.application.notification;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;

/**
 * Domain application service handling offline notifications and delivery (Section 2.18 & 2.42).
 */
public final class NotificationService {

    private final NotificationStoragePort storagePort;

    public NotificationService(NotificationStoragePort storagePort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
    }

    public Notification dispatchNotification(
            ProfileId recipientProfileId,
            NotificationCategory category,
            String payloadTypeId,
            int payloadSchemaVersion,
            String payloadData,
            @Nullable Instant expiresAt) {

        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        Objects.requireNonNull(payloadData, "payloadData must not be null");

        Notification notification = new Notification(
                UUID.randomUUID(),
                recipientProfileId,
                category,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                false,
                null,
                expiresAt,
                Instant.now()
        );

        storagePort.saveNotification(notification);
        return notification;
    }

    public List<Notification> drainPendingNotifications(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        List<Notification> pending = storagePort.findPendingNotifications(recipientProfileId);
        if (!pending.isEmpty()) {
            storagePort.markAllAsRead(recipientProfileId, Instant.now());
        }
        return pending;
    }

    public int getUnreadCount(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        return storagePort.countUnread(recipientProfileId);
    }
}
