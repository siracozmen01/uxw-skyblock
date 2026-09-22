package com.uxplima.uxmskyblock.core.application.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationPayload;
import org.jspecify.annotations.Nullable;

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
                Instant.now());

        storagePort.saveNotification(notification);
        return notification;
    }

    /**
     * Writes down something that happened to a player who may not be here to read it.
     *
     * <p>The inbox, its table, its ten categories and the delivery on join have been here since the
     * notification work, and nothing ever wrote a row: dispatchNotification had no caller anywhere,
     * so "while you were away" was always empty.
     *
     * @param messageKey the message in the operator's catalogue, never a sentence
     * @param values what that message has holes for
     */
    public Notification notify(
            ProfileId recipientProfileId,
            NotificationCategory category,
            String messageKey,
            Map<String, String> values,
            @Nullable Instant expiresAt) {
        Objects.requireNonNull(messageKey, "messageKey must not be null");
        Objects.requireNonNull(values, "values must not be null");
        return dispatchNotification(
                recipientProfileId, category, messageKey, 1, NotificationPayload.pack(values), expiresAt);
    }

    /**
     * Deletes the notifications a player has already read and long since acted on.
     *
     * <p>Nothing ever deleted one. The table would have held every notice a server had ever sent,
     * for as long as the server ran, which is the reason to sweep it the moment anything starts
     * writing to it at all.
     *
     * @return how many were deleted
     */
    public int purgeRead(Instant before) {
        Objects.requireNonNull(before, "before must not be null");
        return storagePort.purgeReadBefore(before);
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
