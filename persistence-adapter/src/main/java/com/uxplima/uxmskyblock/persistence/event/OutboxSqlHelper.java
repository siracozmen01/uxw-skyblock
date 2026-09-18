package com.uxplima.uxmskyblock.persistence.event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import org.jspecify.annotations.Nullable;

/**
 * Shared persistence helper to stage outbox events within an existing database transaction.
 */
public final class OutboxSqlHelper {

    private static final String INSERT_OUTBOX_SQL = """
            INSERT INTO outbox_events (
                event_id, event_type, aggregate_id, payload,
                status, retry_count, created_at
            ) VALUES (?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP)
            """;

    private OutboxSqlHelper() {}

    public static void stageEvent(Connection connection, @Nullable StagedOutboxEvent event) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        if (event == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(INSERT_OUTBOX_SQL)) {
            ps.setString(1, event.eventId().value().toString());
            ps.setString(2, event.eventType());
            ps.setString(3, event.aggregateId());
            ps.setString(4, event.payload());
            ps.executeUpdate();
        }
    }
}
