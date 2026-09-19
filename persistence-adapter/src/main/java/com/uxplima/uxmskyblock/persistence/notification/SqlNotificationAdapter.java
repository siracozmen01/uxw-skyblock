package com.uxplima.uxmskyblock.persistence.notification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.notification.NotificationStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;

/**
 * SQL persistence adapter for durable offline notifications (Section 2.18 & 2.42).
 */
public final class SqlNotificationAdapter implements NotificationStoragePort {

    private final DataSource dataSource;

    public SqlNotificationAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void saveNotification(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");

        String sql = """
                INSERT INTO notifications (
                    notification_id, recipient_profile_id, category,
                    payload_type_id, payload_schema_version, payload_data,
                    is_read, read_at, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, notification.notificationId().toString());
            stmt.setString(2, notification.recipientProfileId().value().toString());
            stmt.setString(3, notification.category().name());
            stmt.setString(4, notification.payloadTypeId());
            stmt.setInt(5, notification.payloadSchemaVersion());
            stmt.setString(6, notification.payloadData());
            stmt.setBoolean(7, notification.isRead());
            stmt.setTimestamp(8, notification.readAt() != null ? Timestamp.from(notification.readAt()) : null);
            stmt.setTimestamp(9, notification.expiresAt() != null ? Timestamp.from(notification.expiresAt()) : null);
            stmt.setTimestamp(10, Timestamp.from(notification.createdAt()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save notification " + notification.notificationId(), e);
        }
    }

    @Override
    public List<Notification> findPendingNotifications(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");

        String sql = """
                SELECT notification_id, recipient_profile_id, category,
                       payload_type_id, payload_schema_version, payload_data,
                       is_read, read_at, expires_at, created_at
                FROM notifications
                WHERE recipient_profile_id = ? AND is_read = 0 AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY created_at ASC
                """;

        List<Notification> notifications = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, recipientProfileId.value().toString());
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notifications.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find pending notifications for profile " + recipientProfileId, e);
        }
        return notifications;
    }

    @Override
    public void markAsRead(UUID notificationId, Instant readAt) {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(readAt, "readAt must not be null");

        String sql = "UPDATE notifications SET is_read = 1, read_at = ? WHERE notification_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(readAt));
            stmt.setString(2, notificationId.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark notification as read: " + notificationId, e);
        }
    }

    @Override
    public void markAllAsRead(ProfileId recipientProfileId, Instant readAt) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        Objects.requireNonNull(readAt, "readAt must not be null");

        String sql = "UPDATE notifications SET is_read = 1, read_at = ? WHERE recipient_profile_id = ? AND is_read = 0";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(readAt));
            stmt.setString(2, recipientProfileId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark all notifications as read for profile " + recipientProfileId, e);
        }
    }

    @Override
    public int countUnread(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");

        String sql = "SELECT COUNT(*) FROM notifications WHERE recipient_profile_id = ? AND is_read = 0";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, recipientProfileId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count unread notifications for profile " + recipientProfileId, e);
        }
    }

    private static Notification mapRow(ResultSet rs) throws SQLException {
        UUID notificationId = UUID.fromString(rs.getString("notification_id"));
        UUID recipientProfileId = UUID.fromString(rs.getString("recipient_profile_id"));
        NotificationCategory category = NotificationCategory.valueOf(rs.getString("category"));
        String payloadTypeId = rs.getString("payload_type_id");
        int payloadSchemaVersion = rs.getInt("payload_schema_version");
        String payloadData = rs.getString("payload_data");
        boolean isRead = rs.getBoolean("is_read");
        Timestamp readAtTs = rs.getTimestamp("read_at");
        Instant readAt = readAtTs != null ? readAtTs.toInstant() : null;
        Timestamp expiresAtTs = rs.getTimestamp("expires_at");
        Instant expiresAt = expiresAtTs != null ? expiresAtTs.toInstant() : null;
        Instant createdAt = rs.getTimestamp("created_at").toInstant();

        return new Notification(
                notificationId,
                ProfileId.of(recipientProfileId),
                category,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                isRead,
                readAt,
                expiresAt,
                createdAt
        );
    }
}
