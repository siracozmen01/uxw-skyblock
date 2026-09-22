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

    /**
     * Deletes the notifications already read whose read moment is older than {@code before}.
     *
     * <p>Nothing ever deleted one, so the table would have held every notice a server had ever
     * sent, for as long as the server ran.
     *
     * @return how many were deleted
     */
    int purgeReadBefore(Instant before);
}
