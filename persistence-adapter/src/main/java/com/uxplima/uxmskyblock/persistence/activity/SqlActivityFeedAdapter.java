package com.uxplima.uxmskyblock.persistence.activity;

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

import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedStoragePort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * SQL persistence adapter for user-facing activity events feed (Section 2.19 & 2.42).
 */
public final class SqlActivityFeedAdapter implements ActivityFeedStoragePort {

    private final DataSource dataSource;

    public SqlActivityFeedAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void appendEvent(ActivityEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        String sql = """
                INSERT INTO activity_events (
                    event_id, instance_id, actor_profile_id, event_type,
                    visibility, payload_type_id, payload_schema_version,
                    payload_data, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, event.eventId().toString());
            stmt.setString(2, event.instanceId());
            stmt.setString(3, event.actorProfileId() != null ? event.actorProfileId().value().toString() : null);
            stmt.setString(4, event.eventType().name());
            stmt.setString(5, event.visibility().name());
            stmt.setString(6, event.payloadTypeId());
            stmt.setInt(7, event.payloadSchemaVersion());
            stmt.setString(8, event.payloadData());
            stmt.setTimestamp(9, Timestamp.from(event.createdAt()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to append activity event " + event.eventId(), e);
        }
    }

    @Override
    public List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        int maxLimit = Math.max(1, limit);

        String sql = """
                SELECT event_id, instance_id, actor_profile_id, event_type,
                       visibility, payload_type_id, payload_schema_version,
                       payload_data, created_at
                FROM activity_events
                WHERE instance_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;

        List<ActivityEvent> events = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, instanceId);
            stmt.setInt(2, maxLimit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    events.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find activity events for instance " + instanceId, e);
        }
        return events;
    }

    private static ActivityEvent mapRow(ResultSet rs) throws SQLException {
        UUID eventId = UUID.fromString(rs.getString("event_id"));
        String instanceId = rs.getString("instance_id");
        String actorProfileStr = rs.getString("actor_profile_id");
        ProfileId actorProfileId = actorProfileStr != null ? ProfileId.of(UUID.fromString(actorProfileStr)) : null;
        ActivityEventType eventType = ActivityEventType.valueOf(rs.getString("event_type"));
        ActivityVisibility visibility = ActivityVisibility.valueOf(rs.getString("visibility"));
        String payloadTypeId = rs.getString("payload_type_id");
        int payloadSchemaVersion = rs.getInt("payload_schema_version");
        String payloadData = rs.getString("payload_data");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();

        return new ActivityEvent(
                eventId,
                instanceId,
                actorProfileId,
                eventType,
                visibility,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                createdAt
        );
    }
}
