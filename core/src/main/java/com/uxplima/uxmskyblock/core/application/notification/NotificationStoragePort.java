package com.uxplima.uxmskyblock.core.application.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;

/**
 * Storage port for durable offline notifications (Section 2.18 & 2.42).
 */
public interface NotificationStoragePort {

    void saveNotification(Notification notification);

    List<Notification> findPendingNotifications(ProfileId recipientProfileId);

    void markAsRead(UUID notificationId, Instant readAt);

    void markAllAsRead(ProfileId recipientProfileId, Instant readAt);

    int countUnread(ProfileId recipientProfileId);
}
